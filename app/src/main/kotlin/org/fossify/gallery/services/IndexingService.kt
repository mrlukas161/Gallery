package org.fossify.gallery.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.gallery.R
import org.fossify.gallery.clip.ClipIndexer
import org.fossify.gallery.clip.ClipSearch
import org.fossify.gallery.faces.FaceIndexer
import org.fossify.gallery.faces.GeoIndexer
import org.fossify.gallery.faces.OcrIndexer
import org.fossify.gallery.faces.PhashIndexer
import org.fossify.gallery.faces.QrIndexer
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.IndexPerf
import org.fossify.gallery.helpers.PendingCounter
import org.fossify.gallery.helpers.ProcessingPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Foreground služba pre indexovanie. V režime Max/nabíjanie bežia funkcie PARALELNE (všetko naraz),
// v obmedzenom režime postupne. Notifikácia ukazuje priebeh KAŽDEJ bežiacej funkcie. Ref-counted:
// viac spustení (aj z rôznych tlačidiel) beží súčasne; služba skončí až keď dobehnú všetky.
class IndexingService : Service() {
    private val progressMap = ConcurrentHashMap<String, String>()
    private val active = AtomicInteger(0)

    // bežal v tejto službe auto-sken? (po dobehnutí sa zapíše čas pre autoScanIfStale)
    @Volatile
    private var autoRan = false

    // úlohy, ktoré táto služba práve spustila (pri časovom limite systému sa zastavia práve tie)
    private val launched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // po finish() už neposielať notifikáciu priebehu — oneskorený callback indexera by inak
    // vyvesil „trvalú" notifikáciu, ktorú už nikto nezruší
    @Volatile
    private var stopped = false

    // systém vyčerpal časový limit služby (Android 15+) — ďalšie úlohy v poradí už nespúšťať
    @Volatile
    private var timedOut = false

    // F5: automaticky spustené úlohy pod dohľadom ProcessingPolicy (úloha -> veľkosť dávky pri štarte);
    // pri zmene batérie/nabíjania sa rozhodnutie zopakuje a úloha sa prípadne zastaví
    private val watched = ConcurrentHashMap<String, Int>()

    // úlohy zastavené pravidlami spracovania (slabá batéria, odpojená nabíjačka) — nie sú to chyby
    private val policyStopped: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // niečo z automatického spracovania počká na nabíjanie (autoScanIfStale to potom dobehne)
    @Volatile
    private var deferred = false

    @Volatile
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // chybu z minulého behu NEMAZAŤ pri každom štarte (auto-štart pri otvorení appky by ju
        // zmazal skôr, než ju používateľ stihne vidieť) — maže sa až pri štarte rovnakého typu
        // úlohy v runOne(); tu sa len obnoví prípadná chyba uložená pred reštartom appky
        lastErrorPersisted(this)
        stopped = false
        ensureChannel()
        startForegroundCompat(buildInitial())
        when (val task = intent?.getStringExtra(EXTRA_TASK)) {
            null, TASK_AUTO -> {
                // výber funkcií (autoTasks) aj pravidlá spracovania (applyPolicy) bežia mimo hlavného
                // vlákna (Room, MediaStore)
                autoRan = true
                // rezervácia HNEĎ (synchrónne): kým sa na pozadí rozhodne, čo pobeží, počítadlo nesmie
                // klesnúť na 0 — iné spustenie by medzitým zavolalo finish() a indexovanie by bežalo
                // bez foreground ochrany; rezerváciu uvoľní finally až po launch()
                active.incrementAndGet()
                org.fossify.commons.helpers.ensureBackgroundThread {
                    try {
                        val tasks = applyPolicy(autoTasks(), ProcessingPolicy.Trigger.AUTO)
                        launch(tasks, sequential = !IndexPerf.parallel(this))
                    } finally {
                        taskFinished()
                    }
                }
            }

            TASK_ALL -> {
                // jedným ťukom rozbehni VŠETKO naraz (podľa perf režimu paralelne/postupne);
                // ľahký profil z toho nechá len tváre a polohu
                val allowMetered = intent?.getBooleanExtra(EXTRA_ALLOW_METERED, false) == true
                val tasks = applyPolicy(allTasks(allowMetered), ProcessingPolicy.Trigger.MANUAL)
                launch(tasks, sequential = !IndexPerf.parallel(this))
            }

            // jedna konkrétna funkcia spustená ručne z Nastavení beží vždy (výslovná voľba používateľa)
            else -> launch(listOf(task), sequential = false)
        }
        return START_NOT_STICKY
    }

    // Nové fotky sa automaticky zaradia do VŠETKÉHO, čo už používaš: tváre/poloha/QR vždy,
    // a navyše tie funkcie, ktoré už majú svoj index (OCR, duplikáty, CLIP) — aby nové
    // fotky nezaostávali za zvyškom knižnice. Volá sa mimo hlavného vlákna (Room).
    private fun autoTasks(): List<String> {
        val list = arrayListOf(TASK_FACES, TASK_GEO, TASK_QR)
        // jednorazový prepočet odtlačkov tvárí na v2 (len pri nabíjaní, ProcessingPolicy) — pred tvárami
        if (org.fossify.gallery.faces.FaceEmbedVersion.isPending(this)) list.add(0, TASK_FACE_V2)
        if (autoOcrEnabled() || hasData { org.fossify.gallery.faces.OcrDatabase.getInstance(this).OcrDao().count() }) {
            list.add(TASK_OCR)
        }
        if (hasData { org.fossify.gallery.faces.PhashDatabase.getInstance(this).PhashDao().count() }) {
            list.add(TASK_PHASH)
        }
        if (org.fossify.gallery.clip.ClipModels.bothPresent(this) &&
            hasData { org.fossify.gallery.clip.ClipDatabase.getInstance(this).ClipDao().count() }
        ) {
            list.add(TASK_CLIP)
        }
        return list
    }

    private fun allTasks(allowMetered: Boolean): List<String> {
        val list = arrayListOf(TASK_FACES, TASK_GEO, TASK_QR, TASK_OCR, TASK_PHASH)
        // ľahký profil CLIP nespúšťa — netreba ani sťahovať model, ani hlásiť chybu na mobilných dátach
        if (currentProfile() == ProcessingPolicy.Profile.LIGHT) return list
        // CLIP vždy — ak model chýba, stiahne sa (inak „hľadanie predmetov" nikdy nefunguje
        // a používateľ nevie prečo). Na mobilných dátach sa ale model (~150 MB) bez
        // výslovného potvrdenia NESŤAHUJE — namiesto tichého míňania dát sa zapíše
        // viditeľná chyba; Nastavenia posielajú potvrdenie cez EXTRA_ALLOW_METERED.
        if (org.fossify.gallery.clip.ClipModels.bothPresent(this) ||
            allowMetered ||
            !org.fossify.gallery.clip.ClipModels.isMeteredNetwork(this)
        ) {
            list.add(TASK_CLIP)
        } else {
            fail(TASK_CLIP, getString(R.string.a3_clip_metered_skipped))
        }
        return list
    }

    // --- pravidlá spracovania (F5: profil, „Nové fotky hneď, zvyšok pri nabíjaní“, batéria) ---

    private fun currentProfile(): ProcessingPolicy.Profile = ProcessingPolicy.profileOf(config.processingProfile)

    // Vráti úlohy, ktoré smú bežať teraz. Automatické úlohy pri ľahkom profile alebo zapnutej voľbe
    // „Nové fotky hneď…“ sa počas behu sledujú (batéria, nabíjanie).
    // Pri AUTO volať mimo hlavného vlákna (PendingCounter číta MediaStore a Room).
    private fun applyPolicy(tasks: List<String>, trigger: ProcessingPolicy.Trigger): List<String> {
        val profile = currentProfile()
        val newFirst = config.processNewPhotosFirst
        val battery = IndexPerf.battery(this)
        val pending = if (ProcessingPolicy.needsBatchSize(profile, newFirst, battery.charging, trigger)) {
            PendingCounter.count(this, tasks)
        } else {
            emptyMap()
        }
        val run = ArrayList<String>()
        for (task in tasks) {
            // už bežiacu úlohu preskočí launch(); nesledovať ju (mohla ju spustiť ručne iná cesta)
            if (isTaskRunning(task)) continue
            val batch = pending[task] ?: ProcessingPolicy.UNKNOWN
            val decision = ProcessingPolicy.decide(
                profile, newFirst, battery.charging, battery.percent, batch, kindOf(task), trigger,
            )
            when (decision) {
                ProcessingPolicy.Decision.RUN -> {
                    run.add(task)
                    val watch = trigger == ProcessingPolicy.Trigger.AUTO &&
                        ProcessingPolicy.needsWatching(profile, newFirst, kindOf(task))
                    if (watch) watched[task] = batch
                }

                ProcessingPolicy.Decision.SKIP_PROFILE -> Unit
                else -> deferred = true
            }
        }
        if (watched.isNotEmpty()) registerBatteryWatch()
        return run
    }

    // smie sledovaná úloha bežať aj teraz? (pred štartom ďalšej úlohy v postupnom poradí)
    private fun allowedNow(task: String): Boolean {
        val batch = watched[task] ?: return true
        val battery = IndexPerf.battery(this)
        val decision = ProcessingPolicy.decide(
            currentProfile(), config.processNewPhotosFirst, battery.charging, battery.percent, batch, kindOf(task),
        )
        val ok = decision == ProcessingPolicy.Decision.RUN
        if (!ok) deferred = true
        return ok
    }

    private fun onBatteryChanged(battery: IndexPerf.Battery) {
        val profile = currentProfile()
        val newFirst = config.processNewPhotosFirst
        for ((task, batch) in watched) {
            if (task !in launched || task in policyStopped) continue
            val decision = ProcessingPolicy.decide(
                profile, newFirst, battery.charging, battery.percent, batch, kindOf(task),
            )
            if (decision != ProcessingPolicy.Decision.RUN) {
                policyStopped.add(task)
                deferred = true
                stopTask(task)
            }
        }
    }

    @Synchronized
    private fun registerBatteryWatch() {
        if (batteryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                onBatteryChanged(IndexPerf.batteryFrom(intent))
            }
        }
        try {
            ContextCompat.registerReceiver(
                this, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            batteryReceiver = receiver
        } catch (ignored: Throwable) {
        }
    }

    @Synchronized
    private fun unregisterBatteryWatch() {
        val receiver = batteryReceiver ?: return
        batteryReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (ignored: Throwable) {
        }
    }

    override fun onDestroy() {
        unregisterBatteryWatch()
        super.onDestroy()
    }

    // --- orchestrácia (ref-counted, paralelne alebo postupne) ---

    private fun launch(tasks: List<String>, sequential: Boolean) {
        val toRun = tasks.filter { !isTaskRunning(it) }
        if (toRun.isEmpty()) {
            maybeFinish()
            return
        }
        active.addAndGet(toRun.size)
        if (sequential) {
            chain(toRun, 0)
        } else {
            toRun.forEach { t -> runOne(t) { taskFinished() } }
        }
    }

    private fun chain(tasks: List<String>, i: Int) {
        if (i >= tasks.size || timedOut) return
        if (!allowedNow(tasks[i])) {
            // pravidlá spracovania ju teraz nepustia (batéria / nabíjanie) — dobehne neskôr
            taskFinished()
            chain(tasks, i + 1)
            return
        }
        runOne(tasks[i]) {
            taskFinished()
            chain(tasks, i + 1)
        }
    }

    private fun taskFinished() {
        active.decrementAndGet()
        maybeFinish()
    }

    private fun maybeFinish() {
        if (active.get() <= 0) finish()
    }

    private fun isTaskRunning(task: String): Boolean = when (task) {
        TASK_FACES -> FaceIndexer.isRunning
        TASK_GEO -> GeoIndexer.isRunning
        TASK_QR -> QrIndexer.isRunning
        TASK_OCR -> OcrIndexer.isRunning
        TASK_PHASH -> PhashIndexer.isRunning
        TASK_CLIP -> ClipIndexer.isRunning
        TASK_CLIP_ML -> org.fossify.gallery.clip.ClipMlModels.isRunning
        TASK_REEMBED -> org.fossify.gallery.faces.RefreshFaces.isRunning
        TASK_FACE_V2 -> org.fossify.gallery.faces.FaceReembedV2.isRunning
        else -> false
    }

    private fun runOne(task: String, finished: () -> Unit) {
        // chyba z minula sa maže až keď sa ROVNAKÝ typ úlohy reálne spúšťa znova — ak zlyhá
        // opäť, fail() ju nastaví nanovo; iné úlohy ju nechávajú viditeľnú
        if (task == lastErrorTask) clearLastError()
        launched.add(task)
        val done = {
            launched.remove(task)
            finished()
        }
        when (task) {
            TASK_FACES -> runFaces(done)
            TASK_GEO -> runGeo(done)
            TASK_QR -> runQr(done)
            TASK_OCR -> runOcr(done)
            TASK_PHASH -> runPhash(done)
            TASK_CLIP -> runClip(done)
            TASK_CLIP_ML -> runClipMl(done)
            TASK_REEMBED -> runReembed(done)
            TASK_FACE_V2 -> runFaceV2(done)
            else -> done()
        }
    }

    // --- jednotlivé indexery (bežia na vlastnom vlákne, hlásia progres kľúčom) ---

    private fun runFaces(next: () -> Unit) {
        if (FaceIndexer.isRunning) {
            next(); return
        }
        FaceIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> prog("faces", getString(R.string.q1_prog_faces, d, t)) },
            onDone = { _, _ -> clearProg("faces"); next() },
            onError = { fail("faces", it); next() },
        )
    }

    private fun runGeo(next: () -> Unit) {
        if (GeoIndexer.isRunning) {
            next(); return
        }
        GeoIndexer.index(
            applicationContext,
            onProgress = { d, t -> prog("geo", getString(R.string.q1_prog_geo, d, t)) },
            onDone = { _, _ -> clearProg("geo"); next() },
            onError = { fail("geo", it); next() },
        )
    }

    private fun runQr(next: () -> Unit) {
        if (QrIndexer.isRunning) {
            next(); return
        }
        QrIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> prog("qr", getString(R.string.q1_prog_qr, d, t)) },
            onDone = { _, _ -> clearProg("qr"); next() },
            onError = { fail("qr", it); next() },
        )
    }

    private fun runOcr(next: () -> Unit) {
        if (OcrIndexer.isRunning) {
            next(); return
        }
        OcrIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> prog("ocr", getString(R.string.q1_prog_ocr, d, t)) },
            onDone = { _, _ -> clearProg("ocr"); next() },
            onError = { fail("ocr", it); next() },
        )
    }

    private fun runPhash(next: () -> Unit) {
        if (PhashIndexer.isRunning) {
            next(); return
        }
        PhashIndexer.index(
            applicationContext, notify = false,
            onProgress = { d, t -> prog("phash", getString(R.string.q1_prog_phash, d, t)) },
            onDone = { _ -> clearProg("phash"); next() },
            onError = { fail("phash", it); next() },
        )
    }

    private fun runClip(next: () -> Unit) {
        if (ClipIndexer.isRunning) {
            next(); return
        }
        ClipIndexer.index(
            applicationContext, notify = false,
            onProgress = { phase, d, t -> prog("clip", "$phase $d/$t") },
            onDone = { _ -> ClipSearch.invalidate(); clearProg("clip"); next() },
            onError = { fail("clip", it); next() },
        )
    }

    private fun runClipMl(next: () -> Unit) {
        if (org.fossify.gallery.clip.ClipMlModels.isRunning) {
            next(); return
        }
        org.fossify.gallery.clip.ClipMlModels.download(
            applicationContext,
            onProgress = { phase, d, t -> prog("clipml", getString(R.string.q1_prog_clip_ml, phase, d, t)) },
            onDone = { org.fossify.gallery.clip.ClipSearch.releaseMl(); clearProg("clipml"); next() },
            onError = { fail("clipml", it); next() },
        )
    }

    // „Prepracovať tváre" — nahrádza staré prepočítanie odtlačkov: navyše opraví otočenie (EXIF)
    // a doplní predtým nenájdené (vzdialené) tváre, pričom menovky ostávajú zachované
    private fun runReembed(next: () -> Unit) {
        if (org.fossify.gallery.faces.RefreshFaces.isRunning) {
            next(); return
        }
        org.fossify.gallery.faces.RefreshFaces.run(
            applicationContext,
            onProgress = { d, t -> prog("reembed", getString(R.string.q1_prog_reembed, d, t)) },
            onDone = { _, _ -> clearProg("reembed"); next() },
            onError = { fail("reembed", it); next() },
        )
    }

    // Jednorazový prepočet odtlačkov tvárí na v2 (FaceReembedV2): priebeh v notifikácii služby
    // a v Nastaveniach; prerušený (nabíjačka, časový limit) pokračuje pri ďalšom nabíjaní
    private fun runFaceV2(next: () -> Unit) {
        if (org.fossify.gallery.faces.FaceReembedV2.isRunning) {
            next(); return
        }
        org.fossify.gallery.faces.FaceReembedV2.run(
            applicationContext,
            onProgress = { d, t -> prog("facev2", getString(R.string.tb_prog_face_v2, d, t)) },
            onDone = { switched ->
                clearProg("facev2")
                // nedokončené (prerušenie, nepripojená karta) → počká na ďalšie nabíjanie
                if (!switched) deferred = true
                next()
            },
            onError = { fail("facev2", it); next() },
        )
    }

    // funkcia je „používaná", ak už má v indexe nejaké záznamy
    private inline fun hasData(block: () -> Int): Boolean = try {
        block() > 0
    } catch (e: Throwable) {
        false
    }

    private fun autoOcrEnabled(): Boolean =
        getSharedPreferences("galeria_faces", Context.MODE_PRIVATE).getBoolean("auto_ocr", false)

    // --- notifikácia s per-task priebehom ---

    private fun fail(key: String, msg: String) {
        // po prerušení systémom ostáva viditeľný dôvod prerušenia, nie následné „zastavené" z indexerov;
        // zastavenie pravidlami spracovania (batéria, nabíjanie) tiež nie je chyba
        if (timedOut || key in policyStopped) {
            clearProg(key)
            return
        }
        lastError ="$key: ${msg.take(160)}"
        lastErrorTask = key
        // chyba prežije aj reštart appky (Nastavenia/Domov ju vedia zobraziť aj neskôr)
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_ERROR, lastError)
                .putString(KEY_LAST_ERROR_TASK, key)
                .apply()
        } catch (ignored: Throwable) {
        }
        clearProg(key)
    }

    private fun clearLastError() {
        lastError = null
        lastErrorTask = null
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_LAST_ERROR)
                .remove(KEY_LAST_ERROR_TASK)
                .apply()
        } catch (ignored: Throwable) {
        }
    }

    private fun prog(key: String, text: String) {
        liveProgress = (progressMap + (key to text)).values.joinToString(" · ")
        progressMap[key] = text
        refreshNotification()
    }

    private fun clearProg(key: String) {
        liveProgress = (progressMap - key).values.joinToString(" · ")
        progressMap.remove(key)
        refreshNotification()
    }

    private fun refreshNotification() {
        if (stopped) return
        try {
            val lines = progressMap.values.toList()
            val big = if (lines.isEmpty()) getString(R.string.indexing_running) else lines.joinToString("\n")
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(getString(R.string.app_name_brand))
                .setContentText(lines.firstOrNull() ?: getString(R.string.indexing_running))
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
        } catch (ignored: Throwable) {
        }
    }

    private fun buildInitial(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name_brand))
            .setContentText(getString(R.string.indexing_running))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    // Android 15+: služba typu dataSync smie bežať ~6 h za 24 h. Po vyčerpaní systém zavolá
    // onTimeout a službu treba do pár sekúnd zastaviť, inak appku ukončí s chybou. Zastav bežiace
    // spracovanie, nechaj viditeľný dôvod a NEzapisuj čas auto-skenu — pri ďalšom otvorení appky
    // (to limit vynuluje) sa pokračuje, indexery sú resumovateľné.
    override fun onTimeout(startId: Int, fgsType: Int) {
        val running = launched.toList()
        fail(running.firstOrNull() ?: TASK_AUTO, getString(R.string.c1_indexing_timeout))
        timedOut = true
        running.forEach { stopTask(it) }
        autoRan = false
        finish()
    }

    private fun stopTask(task: String) {
        when (task) {
            TASK_FACES -> FaceIndexer.stop()
            TASK_GEO -> GeoIndexer.stop()
            TASK_QR -> QrIndexer.stop()
            TASK_OCR -> OcrIndexer.stop()
            TASK_PHASH -> PhashIndexer.stop()
            TASK_CLIP -> ClipIndexer.stop()
            TASK_CLIP_ML -> org.fossify.gallery.clip.ClipMlModels.stop()
            TASK_REEMBED -> org.fossify.gallery.faces.RefreshFaces.stop()
            TASK_FACE_V2 -> org.fossify.gallery.faces.FaceReembedV2.stop()
        }
    }

    private fun finish() {
        stopped = true
        unregisterBatteryWatch()
        // auto-sken si po dobehnutí zapíše čas — autoScanIfStale ho potom 6 hodín nespúšťa znova;
        // čo počká na nabíjanie, si poznačí (autoScanIfStale to pri nabíjaní spustí aj skôr)
        if (autoRan || deferred) {
            val ran = autoRan
            val wait = deferred
            autoRan = false
            deferred = false
            try {
                val edit = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                if (ran) edit.putLong(KEY_LAST_AUTO_SCAN, System.currentTimeMillis())
                edit.putBoolean(KEY_DEFERRED, wait).apply()
            } catch (ignored: Throwable) {
            }
            // odložené na nabíjanie → okno pri nabíjaní ho dobehne (FamilySyncJob)
            if (wait) {
                val app = applicationContext
                org.fossify.commons.helpers.ensureBackgroundThread {
                    try {
                        org.fossify.gallery.jobs.FamilySyncJob.refresh(app)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
        watched.clear()
        policyStopped.clear()
        progressMap.clear()
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Throwable) {
            try {
                startForeground(NOTIF_ID, n)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            // vždy (aj keď kanál existuje) — systém tak premenuje kanál vytvorený staršou verziou
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.q1_channel_indexing), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        // živý text priebehu pre Nastavenia (poll) + posledná chyba (inak boli chyby neviditeľné)
        @Volatile
        var liveProgress: String = ""

        @Volatile
        var lastError: String? = null

        // ktorá úloha chybu spôsobila — maže sa až pri jej ďalšom štarte
        @Volatile
        private var lastErrorTask: String? = null

        private const val PREFS = "galeria_faces"
        private const val KEY_LAST_ERROR = "index_last_error"
        private const val KEY_LAST_ERROR_TASK = "index_last_error_task"
        private const val KEY_LAST_AUTO_SCAN = "last_auto_scan"
        private const val KEY_DEFERRED = "auto_scan_deferred"
        private const val AUTO_SCAN_INTERVAL_MS = 6 * 60 * 60 * 1000L

        const val EXTRA_TASK = "task"
        const val EXTRA_ALLOW_METERED = "allow_metered"
        const val TASK_AUTO = "auto"
        const val TASK_ALL = "all"
        const val TASK_FACES = "faces"
        const val TASK_GEO = "geo"
        const val TASK_OCR = "ocr"
        const val TASK_QR = "qr"
        const val TASK_PHASH = "phash"
        const val TASK_CLIP = "clip"
        const val TASK_CLIP_ML = "clipml"
        const val TASK_REEMBED = "reembed"
        const val TASK_FACE_V2 = "facev2"
        private const val CHANNEL_ID = "indexing_service"
        private const val NOTIF_ID = 49240

        @Volatile
        private var autoStarted = false

        private fun kindOf(task: String): ProcessingPolicy.Indexer = when (task) {
            TASK_FACES -> ProcessingPolicy.Indexer.FACES
            TASK_GEO -> ProcessingPolicy.Indexer.GEO
            TASK_QR -> ProcessingPolicy.Indexer.QR
            TASK_OCR -> ProcessingPolicy.Indexer.OCR
            TASK_PHASH -> ProcessingPolicy.Indexer.PHASH
            TASK_CLIP -> ProcessingPolicy.Indexer.CLIP
            TASK_FACE_V2 -> ProcessingPolicy.Indexer.FACE_V2
            else -> ProcessingPolicy.Indexer.OTHER
        }

        fun startAutoOnce(context: Context) {
            if (autoStarted) return
            autoStarted = true
            start(context, TASK_AUTO)
        }

        // Auto-sken pri návrate do appky (MainActivity.onResume): spustí TASK_AUTO, keď od
        // posledného auto-skenu ubehlo viac ako 6 hodín — fotky odfotené počas dňa tak dostanú
        // tváre/OCR/CLIP aj bez zabitia a nového spustenia appky. Rešpektuje vypnuté
        // auto-indexovanie v Nastaveniach; bežiace úlohy služba sama preskočí (ref-counted).
        fun autoScanIfStale(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!prefs.getBoolean("auto_index", true)) return
                val last = prefs.getLong(KEY_LAST_AUTO_SCAN, 0L)
                val stale = System.currentTimeMillis() - last > AUTO_SCAN_INTERVAL_MS
                // F5: spracovanie odložené na nabíjanie sa spustí hneď, keď je telefón na nabíjačke
                val deferredNow = prefs.getBoolean(KEY_DEFERRED, false) && IndexPerf.battery(context).charging
                if (!stale && !deferredNow) return
                start(context, TASK_AUTO)
            } catch (ignored: Throwable) {
            }
        }

        // posledná chyba indexovania vrátane tej z minulého behu appky (prefs) — číta ju
        // prehľad v Nastaveniach; zároveň ju obnoví do lastError pre ostatných čitateľov
        fun lastErrorPersisted(context: Context): String? {
            lastError?.let { return it }
            return try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val stored = prefs.getString(KEY_LAST_ERROR, null)
                if (stored != null) {
                    lastError = stored
                    lastErrorTask = prefs.getString(KEY_LAST_ERROR_TASK, null)
                }
                stored
            } catch (e: Throwable) {
                null
            }
        }

        // false = službu sa nepodarilo spustiť (napr. z pozadia na Androide 12+)
        fun start(context: Context, task: String, allowMetered: Boolean = false): Boolean {
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, IndexingService::class.java)
                        .putExtra(EXTRA_TASK, task)
                        .putExtra(EXTRA_ALLOW_METERED, allowMetered),
                )
                true
            } catch (ignored: Throwable) {
                false
            }
        }

        // F5: čaká niečo z automatického spracovania na nabíjanie? (okno pri nabíjaní, FamilySyncJob)
        fun deferredWorkPending(context: Context): Boolean = try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getBoolean("auto_index", true) && prefs.getBoolean(KEY_DEFERRED, false)
        } catch (ignored: Throwable) {
            false
        }
    }
}
