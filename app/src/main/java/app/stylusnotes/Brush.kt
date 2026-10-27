package app.stylusnotes

import android.graphics.*
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * A stamp brush: the dab [shape] is repeated every [spacing]×size along the stroke.
 * Pressure scales size by [sizeP] and opacity by [opP]; [flow] is the opacity of a single dab.
 */
class Brush(
    val name: String,
    val shape: Int,
    val spacing: Float,
    val sizeP: Float,
    val opP: Float,
    val flow: Float,
    val scatter: Float = 0f,
    val spin: Boolean = false,
    val angle: Float = Float.NaN,
    val minSize: Float = 0.08f,
)

object Brushes {
    const val HARD = 0; const val SOFT = 1; const val GRAIN = 2; const val CHARCOAL = 3; const val AIR = 4; const val FLAT = 5

    val all = listOf(
        Brush("Studio Pen", HARD, 0.06f, 0.85f, 0f, 1f),
        Brush("Technical Pen", HARD, 0.06f, 0.15f, 0f, 1f),
        Brush("Ink Brush", HARD, 0.05f, 1f, 0f, 1f, minSize = 0.02f),
        Brush("6B Pencil", GRAIN, 0.12f, 0.35f, 0.8f, 0.55f, spin = true),
        Brush("HB Pencil", GRAIN, 0.12f, 0.2f, 0.9f, 0.35f, spin = true),
        Brush("Charcoal", CHARCOAL, 0.15f, 0.4f, 0.6f, 0.6f, scatter = 0.1f, spin = true),
        Brush("Soft Airbrush", AIR, 0.08f, 0f, 1f, 0.07f),
        Brush("Hard Airbrush", SOFT, 0.08f, 0f, 0.8f, 0.18f),
        Brush("Round Brush", SOFT, 0.05f, 0.7f, 0.3f, 0.45f),
        Brush("Marker", HARD, 0.05f, 0.1f, 0f, 0.3f),
        Brush("Calligraphy", FLAT, 0.03f, 0.6f, 0f, 1f, angle = 45f),
    )

    val groups = listOf(
        "Inking" to listOf(0, 1, 2), "Sketching" to listOf(3, 4, 5), "Airbrushing" to listOf(6, 7),
        "Painting" to listOf(8, 9), "Calligraphy" to listOf(10),
    )

    private val masks = arrayOfNulls<Bitmap>(6)

    /** 128px ALPHA_8 dab mask: drawn with a paint, it takes the paint's colour. */
    @Synchronized fun mask(shape: Int): Bitmap = masks[shape] ?: make(shape).also { masks[shape] = it }

    private fun make(shape: Int): Bitmap {
        val s = 128; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val r = Random(shape * 31 + 7)
        when (shape) {
            HARD -> c.drawCircle(64f, 64f, 62f, p)
            SOFT -> { p.shader = RadialGradient(64f, 64f, 63f, intArrayOf(0xFF000000.toInt(), 0xC0000000.toInt(), 0), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP); c.drawCircle(64f, 64f, 63f, p) }
            AIR -> { p.shader = RadialGradient(64f, 64f, 63f, intArrayOf(0x90000000.toInt(), 0x50000000, 0), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP); c.drawCircle(64f, 64f, 63f, p) }
            FLAT -> c.drawOval(RectF(4f, 50f, 124f, 78f), p)
            GRAIN -> {
                c.drawCircle(64f, 64f, 62f, p)
                val px = IntArray(s * s); b.getPixels(px, 0, s, 0, 0, s, s)
                for (i in px.indices) {
                    val a = px[i] ushr 24
                    if (a != 0) px[i] = (if (r.nextFloat() < 0.3f) 0 else (a * (0.35f + 0.65f * r.nextFloat())).toInt()) shl 24
                }
                b.setPixels(px, 0, s, 0, 0, s, s)
            }
            CHARCOAL -> repeat(260) {
                val a = r.nextFloat() * 2 * PI; val d = 60f * kotlin.math.sqrt(r.nextFloat())
                p.alpha = 60 + r.nextInt(110)
                c.drawCircle(64f + d * kotlin.math.cos(a).toFloat(), 64f + d * sin(a).toFloat(), 2f + r.nextFloat() * 5f, p)
            }
        }
        val m = b.extractAlpha(); b.recycle()
        return m
    }

    fun size(b: Brush, size: Float, p: Float) = max(0.5f, size * (b.minSize + (1 - b.minSize) * (1 - b.sizeP + b.sizeP * p)))
    fun alpha(b: Brush, opacity: Float, p: Float) = (opacity * b.flow * (1 - b.opP + b.opP * p)).coerceIn(0f, 1f)

    /** A sample S-curve painted with [b], for the brush library. */
    fun preview(b: Brush, w: Int, h: Int, color: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val m = mask(b.shape)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val r = Random(3)
        val base = h * 0.32f
        var lx = 0f; var ly = 0f; var next = 0f
        for (k in 0..200) {
            val t = k / 200f
            val x = h * 0.4f + t * (w - h * 0.8f); val y = h / 2f + sin(t * 2 * PI).toFloat() * h * 0.18f
            val pr = sin(t * PI).toFloat() * 0.9f + 0.1f
            if (k > 0) {
                val len = hypot(x - lx, y - ly); var d = next
                while (d <= len) {
                    val f = d / len; val px = lx + (x - lx) * f; val py = ly + (y - ly) * f
                    val sz = size(b, base, pr)
                    paint.color = color; paint.alpha = (alpha(b, 1f, pr) * 255).toInt()
                    c.save()
                    val ang = if (!b.angle.isNaN()) b.angle else if (b.spin) r.nextFloat() * 360 else 0f
                    if (ang != 0f) c.rotate(ang, px, py)
                    c.drawBitmap(m, null, RectF(px - sz / 2, py - sz / 2, px + sz / 2, py + sz / 2), paint)
                    c.restore()
                    d += max(0.5f, sz * b.spacing)
                }
                next = d - len
            }
            lx = x; ly = y
        }
        return out
    }
}

/** Box blur ×3 (close to Gaussian) on premultiplied channels, one channel at a time to keep memory down. */
object Blur {
    fun apply(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height; val n = w * h
        val px = IntArray(n); src.getPixels(px, 0, w, 0, 0, w, h)
        val out = Array(4) { ByteArray(n) }
        val ch = IntArray(n); val tmp = IntArray(n)
        val br = max(1, radius / 2)
        for (k in 0 until 4) {
            val shift = intArrayOf(24, 16, 8, 0)[k]
            for (i in 0 until n) {
                val a = px[i] ushr 24; val v = (px[i] ushr shift) and 0xFF
                ch[i] = if (k == 0) a else v * a / 255
            }
            repeat(3) { box(ch, tmp, w, h, br, true); box(tmp, ch, w, h, br, false) }
            for (i in 0 until n) out[k][i] = ch[i].coerceIn(0, 255).toByte()
        }
        for (i in 0 until n) {
            val a = out[0][i].toInt() and 0xFF
            if (a == 0) { px[i] = 0; continue }
            fun un(c: Byte) = ((c.toInt() and 0xFF) * 255 / a).coerceAtMost(255)
            px[i] = (a shl 24) or (un(out[1][i]) shl 16) or (un(out[2][i]) shl 8) or un(out[3][i])
        }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w; val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sum = 0
            for (k in -r..r) sum += src[base + k.coerceIn(0, len - 1) * step]
            for (k in 0 until len) {
                dst[base + k * step] = sum / div
                sum += src[base + (k + r + 1).coerceAtMost(len - 1) * step] - src[base + (k - r).coerceAtLeast(0) * step]
            }
        }
    }
}
