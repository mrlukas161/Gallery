package org.fossify.gallery.helpers

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.extensions.config
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jednoduchý in-app updater pre Galéria+.
 * Číta najnovší GitHub Release nášho repozitára, porovná versionCode (z názvu APK assetu)
 * s nainštalovanou verziou a ponúkne stiahnutie + inštaláciu. Kontaktuje len api.github.com
 * a download URL z GitHubu, nič iné.
 */
object AppUpdater {
    private const val RELEASES_API = "https://api.github.com/repos/mrlukas161/Gallery/releases/latest"
    private const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L // raz za deň
    private const val USER_AGENT = "GaleriaPlus-Updater"

    data class Update(val versionName: String, val versionCode: Int, val apkUrl: String)

    fun checkForUpdate(activity: Activity, force: Boolean, onResult: (Update?) -> Unit) {
        val config = activity.config
        if (!force) {
            if (!config.checkForAppUpdates) {
                onResult(null)
                return
            }
            if (System.currentTimeMillis() - config.lastAppUpdateCheckTS < CHECK_INTERVAL) {
                onResult(null)
                return
            }
        }

        ensureBackgroundThread {
            val update = try {
                fetchLatest()
            } catch (e: Exception) {
                null
            }
            config.lastAppUpdateCheckTS = System.currentTimeMillis()
            activity.runOnUiThread {
                if (update != null && update.versionCode > BuildConfig.VERSION_CODE) {
                    onResult(update)
                } else {
                    onResult(null)
                }
            }
        }
    }

    private fun fetchLatest(): Update? {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }

        connection.inputStream.bufferedReader().use { reader ->
            val json = JSONObject(reader.readText())
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                val match = Regex("gallery-(\\d+)-foss-release\\.apk").find(name)
                if (match != null) {
                    val versionCode = match.groupValues[1].toInt()
                    val apkUrl = asset.optString("browser_download_url")
                    val versionName = tag.removePrefix("v").substringBefore("-")
                    if (apkUrl.isNotEmpty()) {
                        return Update(versionName, versionCode, apkUrl)
                    }
                }
            }
        }
        return null
    }

    fun downloadAndInstall(activity: Activity, update: Update, onError: (String) -> Unit) {
        ensureBackgroundThread {
            try {
                val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val apkFile = File(activity.externalCacheDir ?: activity.cacheDir, "galeria-update.apk")
                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                activity.runOnUiThread { installApk(activity, apkFile) }
            } catch (e: Exception) {
                activity.runOnUiThread { onError(e.message ?: "error") }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity.startActivity(intent)
    }
}
