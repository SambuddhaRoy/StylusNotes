package app.stylusnotes

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Hue ring around a saturation/brightness square. */
class ColorWheel(c: Context) : View(c) {
    var onColor: ((Int) -> Unit)? = null
    private val hsv = floatArrayOf(0f, 1f, 1f)
    var color: Int
        get() = Color.HSVToColor(hsv)
        set(v) { Color.colorToHSV(v, hsv); invalidate() }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sq = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
    private val knobShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; color = 0x55000000 }
    private val box = RectF()
    private var zone = 0 // 1 ring, 2 square
    private var lastTick = 0f

    override fun onMeasure(w: Int, h: Int) { val s = (264 * d).toInt(); setMeasuredDimension(s, s) }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val rw = 24 * d; val r = min(cx, cy) - rw / 2 - 4 * d
        ring.strokeWidth = rw
        ring.shader = SweepGradient(cx, cy, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
        c.drawCircle(cx, cy, r, ring)
        val half = (r - rw / 2 - 10 * d) / sqrt(2f)
        box.set(cx - half, cy - half, cx + half, cy + half)
        val hue = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        sq.shader = ComposeShader(
            LinearGradient(0f, box.top, 0f, box.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP),
            LinearGradient(box.left, 0f, box.right, 0f, Color.WHITE, hue, Shader.TileMode.CLAMP),
            PorterDuff.Mode.MULTIPLY,
        )
        c.drawRoundRect(box, 10 * d, 10 * d, sq)
        val a = Math.toRadians(hsv[0].toDouble())
        val kx = cx + r * cos(a).toFloat(); val ky = cy + r * sin(a).toFloat()
        c.drawCircle(kx, ky, rw / 2 - 2 * d, knobShadow); c.drawCircle(kx, ky, rw / 2 - 2 * d, knob)
        val sx = box.left + hsv[1] * box.width(); val sy = box.top + (1 - hsv[2]) * box.height()
        c.drawCircle(sx, sy, 10 * d, knobShadow); c.drawCircle(sx, sy, 10 * d, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f; val cy = height / 2f
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            zone = if (box.contains(e.x, e.y)) 2 else if (hypot(e.x - cx, e.y - cy) > box.width() / 2) 1 else 0
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        when (zone) {
            1 -> {
                var deg = Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat()
                if (deg < 0) deg += 360f
                hsv[0] = deg
                if (kotlin.math.abs(deg - lastTick) > 15) { lastTick = deg; hTick() }
            }
            2 -> {
                hsv[1] = ((e.x - box.left) / box.width()).coerceIn(0f, 1f)
                hsv[2] = 1 - ((e.y - box.top) / box.height()).coerceIn(0f, 1f)
            }
            else -> return true
        }
        invalidate(); onColor?.invoke(color)
        return true
    }
}

/** Recent colours, shared by both editors. */
object Recent {
    fun get(p: SharedPreferences) = p.getString("recent", "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }
    fun push(p: SharedPreferences, c: Int) {
        val l = (listOf(c) + get(p).filter { it != c }).take(12)
        p.edit().putString("recent", l.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
    }
}

val PALETTE = longArrayOf(
    0xFF1C1B1F, 0xFF5F6368, 0xFFFFFFFF, 0xFFD93025, 0xFFF29900, 0xFFFDD663,
    0xFF1E8E3E, 0xFF12B5CB, 0xFF1A73E8, 0xFF5B4FE0, 0xFF9334E6, 0xFFE52592,
).map { it.toInt() }

/** Wheel + hex + recent colours + palette. [pick] fires live while dragging and on taps. */
fun colorPanel(c: Context, prefs: SharedPreferences, current: Int, pick: (Int) -> Unit): View {
    val pad = c.dpi(16)
    val panel = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); gravity = android.view.Gravity.CENTER_HORIZONTAL }
    val wheel = ColorWheel(c).apply { color = current }
    val hex = TextView(c).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(c.themeColor(Attr.onSurfaceVariant)); text = "#%06X".format(current and 0xFFFFFF)
    }
    fun row(colors: List<Int>) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        for (k in colors) addView(Swatch(c).apply {
            col = k; sel = k == current
            setOnClickListener { hTick(); wheel.color = k; hex.text = "#%06X".format(k and 0xFFFFFF); pick(k); Recent.push(prefs, k) }
        }, LinearLayout.LayoutParams(c.dpi(40), c.dpi(40)).apply { setMargins(c.dpi(2), c.dpi(2), c.dpi(2), c.dpi(2)) })
    }
    wheel.onColor = { k -> hex.text = "#%06X".format(k and 0xFFFFFF); pick(k) }
    panel.addView(wheel)
    panel.addView(hex, LinearLayout.LayoutParams(-2, -2).apply { topMargin = c.dpi(8); bottomMargin = c.dpi(8) })
    val recent = Recent.get(prefs)
    if (recent.isNotEmpty()) panel.addView(row(recent.take(6)))
    panel.addView(row(PALETTE.take(6))); panel.addView(row(PALETTE.drop(6)))
    return panel
}
