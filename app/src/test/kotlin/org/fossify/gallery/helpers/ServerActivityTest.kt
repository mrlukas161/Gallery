package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerActivityTest {
    private var now = 1_000_000L
    private val activity = ServerActivity { now }

    @Test
    fun beznaPrevadzkaBezPoziadaviekNicNebrzdi() {
        assertFalse(activity.activeWithin(ServerActivity.AUTO_QUIET_MS))
        assertEquals(Long.MAX_VALUE, activity.idleMs())
        assertTrue(ServerActivity.autoStartAllowed(false, activity))
    }

    @Test
    fun automatickeUlohyCakajuMinutuPoPoslednejPoziadavke() {
        activity.begin()
        now += 200
        activity.end()
        now += 59_000
        assertFalse(ServerActivity.autoStartAllowed(false, activity))
        // ručné spustenie (výslovná voľba používateľa) beží vždy
        assertTrue(ServerActivity.autoStartAllowed(true, activity))
        now += 1_001
        assertTrue(ServerActivity.autoStartAllowed(false, activity))
    }

    @Test
    fun beziacaPoziadavkaJeAktivnaAjPoDlhomPrenose() {
        activity.begin()
        now += 5 * 60_000L // veľký originál sa posiela 5 minút
        assertTrue(activity.activeWithin(ServerActivity.RESUME_QUIET_MS))
        assertEquals(0L, activity.idleMs())
        assertEquals(1, activity.inFlight)
    }

    @Test
    fun zabudnutaPoziadavkaNezastaviSpracovanieNavzdy() {
        activity.begin() // koniec sa nikdy nezapísal
        now += ServerActivity.STUCK_MS + 1
        assertFalse(activity.activeWithin(ServerActivity.AUTO_QUIET_MS))
    }

    @Test
    fun zastavenieServeraVsetkoUvolni() {
        activity.begin()
        activity.reset()
        assertEquals(0, activity.inFlight)
        assertFalse(activity.activeWithin(ServerActivity.AUTO_QUIET_MS))
        // oneskorený koniec požiadavky po zastavení nespraví záporné počítadlo
        activity.end()
        assertEquals(0, activity.inFlight)
    }

    @Test
    fun pingANastroveStatistikyNieSuPracou() {
        assertFalse(ServerActivity.countsAsWork("/api/ping"))
        assertFalse(ServerActivity.countsAsWork("/api/stats"))
        assertTrue(ServerActivity.countsAsWork("/api/thumb"))
        assertTrue(ServerActivity.countsAsWork("/api/file"))
        assertTrue(ServerActivity.countsAsWork("/"))
    }

    // ---------- ServerYield ----------

    private class Events : ServerYield.Listener {
        val log = ArrayList<String>()

        override fun onPause(task: String) {
            log.add("pause:$task")
        }

        override fun onResume(task: String) {
            log.add("resume:$task")
        }
    }

    @Test
    fun indexerBezAktivityServeraNecaka() {
        val y = ServerYield(activity, { false }) { error("nemá spať") }
        assertFalse(y.pause("faces", { true }))
    }

    @Test
    fun indexerCakaKymJeServerAktivnyAPotomPokracuje() {
        var sleeps = 0
        val y = ServerYield(activity, { false }) { ms ->
            sleeps++
            now += ms
        }
        val events = Events()
        y.addListener(events)
        activity.begin()
        activity.end()
        assertTrue(y.pause("geo", { true }))
        // čakal, kým od poslednej požiadavky neubehlo RESUME_QUIET_MS
        assertEquals((ServerActivity.RESUME_QUIET_MS / ServerYield.POLL_MS).toInt(), sleeps)
        assertEquals(listOf("pause:geo", "resume:geo"), events.log)
        assertTrue(y.pausedTasks().isEmpty())
    }

    @Test
    fun zastavenaUlohaPrestaneCakatHned() {
        var running = true
        val y = ServerYield(activity, { false }) { now += it }
        activity.begin()
        var polls = 0
        val waited = y.pause("ocr", {
            polls++
            if (polls > 3) running = false
            running
        })
        assertTrue(waited)
        assertFalse(running)
        assertEquals(1, activity.inFlight)
    }

    @Test
    fun naliehavejUloheIndexerUvolniModelRaz() {
        var urgent = false
        var releases = 0
        var sleeps = 0
        val y = ServerYield(activity, { urgent }) {
            sleeps++
            if (sleeps == 2) urgent = true // /api/quality práve žiada o model
            if (sleeps == 10) activity.end()
            now += it
        }
        activity.begin()
        y.pause("faces", { true }) { releases++ }
        assertEquals(1, releases)
    }

    @Test
    fun chybaVPoslucachoviIndexerNezhodi() {
        val y = ServerYield(activity, { false }) { now += it }
        y.addListener(object : ServerYield.Listener {
            override fun onPause(task: String) = error("notifikácia")

            override fun onResume(task: String) = error("notifikácia")
        })
        activity.begin()
        activity.end()
        assertTrue(y.pause("qr", { true }))
    }
}
