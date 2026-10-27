package app.stylusnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** Getting pictures in from the gallery, the clipboard and the web. All blocking: call off the UI thread. */
object Images {
    private const val MAX_SIDE = 1600
    private const val MAX_BYTES = 20 shl 20

    fun fromUri(c: Context, uri: Uri): ByteArray? =
        c.contentResolver.openInputStream(uri)?.use { normalize(it.readBytes()) }

    fun fromUrl(url: String): ByteArray? {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return null
            return normalize(Base64.decode(url.substring(comma + 1), Base64.DEFAULT))
        }
        var u = URL(url)
        repeat(5) { // follow redirects, including http→https which HttpURLConnection won't
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 20000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) StylusNotes")
            try {
                val code = conn.responseCode
                if (code in 300..399) { u = URL(u, conn.getHeaderField("Location") ?: return null); return@repeat }
                if (code != 200) return null
                val out = ByteArrayOutputStream()
                conn.inputStream.use { inp ->
                    val buf = ByteArray(32 shl 10)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null
                    }
                }
                return normalize(out.toByteArray())
            } finally { conn.disconnect() }
        }
        return null
    }

    fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** Downsamples to at most [MAX_SIDE] and re-encodes (PNG if it has transparency, else JPEG). */
    fun normalize(bytes: ByteArray): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= MAX_SIDE) s *= 2
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val m = max(b.width, b.height)
        if (m > MAX_SIDE) b = Bitmap.createScaledBitmap(b, b.width * MAX_SIDE / m, b.height * MAX_SIDE / m, true)
        val out = ByteArrayOutputStream()
        if (b.hasAlpha()) b.compress(Bitmap.CompressFormat.PNG, 100, out) else b.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }
}
