package com.pianoorchestra.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.pianoorchestra.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Les mises à jour : GitHub compile chaque nouvelle version et la publie ;
 * l'application regarde s'il y en a une plus récente et l'installe en un toucher.
 */
class Updater(private val context: Context) {

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data object Offline : State()
        data class Available(val version: String, val code: Int, val url: String) : State()
        data class Downloading(val percent: Int) : State()
        data object NeedsPermission : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    val currentVersion: String = BuildConfig.VERSION_NAME

    fun check(quiet: Boolean = false) {
        if (_state.value is State.Downloading) return
        if (!quiet) _state.value = State.Checking
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val code = json.getString("tag_name").trimStart('b', 'v').toIntOrNull() ?: 0
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                _state.value = if (code > BuildConfig.VERSION_CODE && url != null)
                    State.Available(json.optString("name", "0.1.$code").substringAfterLast(' '), code, url)
                else if (quiet) State.Idle else State.UpToDate
            } catch (e: Exception) {
                _state.value = if (quiet) State.Idle else State.Offline
            }
        }.start()
    }

    fun install(available: State.Available) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        _state.value = State.Downloading(0)
        Thread {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "PianoOrchestra.apk")
                var url = URL(available.url)
                var conn: HttpURLConnection
                var redirects = 0
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    val code = conn.responseCode
                    if (code in 300..399 && redirects < 5) {
                        url = URL(url, conn.getHeaderField("Location"))
                        redirects++
                        continue
                    }
                    break
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            done += r
                            if (total > 0) _state.value = State.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                _state.value = available
            } catch (e: Exception) {
                _state.value = State.Offline
            }
        }.start()
    }
}
