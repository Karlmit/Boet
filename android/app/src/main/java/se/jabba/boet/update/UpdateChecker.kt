package se.jabba.boet.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// A pending update found on GitHub Releases.
data class UpdateInfo(val versionName: String, val notes: String, val apkUrl: String)

// Self-update for the sideloaded (non–Play Store) build: checks the repo's
// latest GitHub Release, and if it's newer than the installed version, downloads
// the attached APK and hands it to the system package installer.
object UpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/Karlmit/Boet/releases/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(val tag_name: String = "", val body: String = "", val assets: List<Asset> = emptyList())

    @Serializable
    private data class Asset(val name: String = "", val browser_download_url: String = "")

    // Returns an UpdateInfo when the latest release is newer than this build, else null.
    // Network/parse failures are swallowed — a missed check should never disrupt the app.
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val rel = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                json.decodeFromString(Release.serializer(), resp.body?.string().orEmpty())
            }
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@withContext null
            val remote = versionFromTag(rel.tag_name) ?: return@withContext null
            if (isNewer(remote, currentVersion(context))) {
                UpdateInfo(remote, rel.body.trim(), apk.browser_download_url)
            } else null
        }.getOrNull()
    }

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0"

    // Pull a dotted version out of a tag like "app-v1.1" or "v1.2.0" -> "1.1" / "1.2.0".
    fun versionFromTag(tag: String): String? =
        Regex("""\d+(?:\.\d+)+""").find(tag)?.value

    // Compare dotted numeric versions; missing components count as 0 ("1.10" > "1.2").
    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    // On Android O+ the app needs the user's per-source "install unknown apps"
    // permission. Returns true if we can install now; otherwise sends the user to
    // the right settings screen and returns false (they retry after enabling it).
    fun ensureCanInstall(context: Context): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        return false
    }

    // Download the release APK to cache and launch the installer. Throws on failure.
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "boet-${info.versionName}.apk")
            val req = Request.Builder().url(info.apkUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: throw RuntimeException("Empty download")
                out.outputStream().use { o -> body.byteStream().copyTo(o) }
            }
            out
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
