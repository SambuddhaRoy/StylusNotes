package app.stylusnotes

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update from GitHub Releases: the newest release's .apk asset is downloaded, checked, and installed
 * through PackageInstaller. Android shows its own confirmation unless it lets this app update itself silently
 * (Android 12+, once this app is the one that installed itself).
 */
object Updater {
    const val REPO = "SambuddhaRoy/StylusNotes"
    private const val ACTION = "app.stylusnotes.INSTALL_STATUS"

    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String?)

    fun currentVersion(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    /** "3.10.0" > "3.9.2"; a leading "v" is ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Latest published release with an APK attached, or null. Blocking. */
    fun latest(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        try {
            if (conn.responseCode != 200) return null
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = j.getJSONArray("assets")
            for (k in 0 until assets.length()) {
                val a = assets.getJSONObject(k)
                if (!a.getString("name").endsWith(".apk")) continue
                val digest = a.optString("digest", "")
                return Release(
                    j.getString("tag_name").trimStart('v', 'V'), j.optString("body", ""),
                    a.getString("browser_download_url"), a.getLong("size"),
                    digest.removePrefix("sha256:").takeIf { digest.startsWith("sha256:") },
                )
            }
            return null
        } finally { conn.disconnect() }
    }

    fun apkFile(c: Context, version: String) = File(File(c.cacheDir, "updates").apply { mkdirs() }, "StylusNotes-$version.apk")

    /** Downloads (or reuses) the release APK and checks it is intact, ours, and newer. Blocking. */
    fun download(c: Context, r: Release): File {
        val f = apkFile(c, r.version)
        if (!f.exists() || f.length() != r.size) {
            f.parentFile?.listFiles()?.forEach { it.delete() } // drop older downloads
            val tmp = File(f.path + ".part")
            val conn = URL(r.apkUrl).openConnection() as HttpURLConnection // GitHub redirects https→https, which is followed
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            try {
                check(conn.responseCode == 200) { "download failed: HTTP ${conn.responseCode}" }
                conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            } finally { conn.disconnect() }
            check(tmp.renameTo(f)) { "couldn't store the update" }
        }
        try {
            check(f.length() == r.size) { "download incomplete" }
            r.sha256?.let { want -> check(sha256(f).equals(want, true)) { "checksum mismatch" } }
            val info = c.packageManager.getPackageArchiveInfo(f.path, 0) ?: error("not an APK")
            val installed = c.packageManager.getPackageInfo(c.packageName, 0)
            check(info.packageName == c.packageName) { "wrong app in the release" }
            check(PackageInfoCompat.getLongVersionCode(info) > PackageInfoCompat.getLongVersionCode(installed)) { "release isn't newer" }
        } catch (e: Exception) { f.delete(); throw e }
        return f
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    /** Hands the APK to the system installer; the running app is closed when the update lands. */
    fun install(c: Context, apk: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(c, InstallReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(c, id, intent, flags).intentSender)
        }
    }
}

/** Install session results: shows the system confirmation when Android asks for one, reports failures. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("Updater", "install failed ($status): $msg")
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) Toast.makeText(c, "Update failed: ${msg ?: status}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

