package app.stylusnotes

import android.os.Build
import android.view.MotionEvent
import android.view.MotionEvent.*
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns raw touch into pen strokes, two-finger gestures and multi-finger taps, and throws palms away.
 * Both canvases route their input through this, so they reject palms the same way.
 *
 * A finger contact is a palm when any of these hold, and stays one until it lifts:
 *  - the pen is down, hovering, or was near the glass in the last [PEN_RECENT_MS]
 *  - another palm is already resting on the glass
 *  - it is wider than ~11 mm (a fingertip is 7-9 mm) or grows that wide while down
 *  - it is a second contact arriving long after the first (a hand settling, not a pinch)
 *
 * When the system cancels the stream (it does that when a palm lands mid-stroke on some ROMs),
 * the pen stroke is kept, not thrown away.
 */
class InputRouter(private val view: View, private val cb: Callbacks) {
    interface Callbacks {
        fun penDown(x: Float, y: Float, p: Float, eraser: Boolean, finger: Boolean)
        fun penMove(x: Float, y: Float, p: Float)
        /** keep=false: discard the stroke (it was really the start of a gesture, or a palm). */
        fun penUp(keep: Boolean)
        /** The point (px,py) moved by (dx,dy); scale [scale] and rotate [rot] radians around it. */
        fun gesture(px: Float, py: Float, dx: Float, dy: Float, scale: Float, rot: Float)
        fun gestureEnd()
        fun tap(fingers: Int)
        fun hover(x: Float, y: Float, inRange: Boolean)
    }

    companion object {
        const val PEN_RECENT_MS = 1000L
        const val TWO_FINGER_MS = 250L
        const val TAP_MS = 300L
    }

    var fingerDraws: () -> Boolean = { false }
    var onDebug: ((String) -> Unit)? = null

    private val dm = view.resources.displayMetrics
    private val palmPx = 11f * (if (dm.xdpi > 50) dm.xdpi else dm.density * 160) / 25.4f
    private val slop = 12 * dm.density
    private var penId = -1
    private var penFinger = false
    private var lastPen = -PEN_RECENT_MS
    private val fingers = LinkedHashSet<Int>()
    private val palms = HashSet<Int>()
    private var firstDown = 0L
    private var gesturing = false
    private var gx = 0f; private var gy = 0f; private var gd = 0f; private var ga = 0.0
    private var sgx = 0f; private var sgy = 0f; private var sgd = 0f
    private var tapT0 = 0L; private var tapMax = 0; private var moved = false

    val penActive get() = penId != -1
    val isGesturing get() = gesturing

    private fun isPen(t: Int) = t == TOOL_TYPE_STYLUS || t == TOOL_TYPE_ERASER
    private fun pressure(e: MotionEvent, i: Int) = if (penFinger) 1f else e.getPressure(i).coerceIn(0.02f, 1f)
    private fun mm(e: MotionEvent, i: Int) = "%.1fmm".format(e.getTouchMajor(i) * 25.4f / (if (dm.xdpi > 50) dm.xdpi else dm.density * 160))
    private fun debug(s: String) { onDebug?.invoke(s) }

    fun onHover(e: MotionEvent): Boolean {
        if (!isPen(e.getToolType(0))) return false
        lastPen = e.eventTime
        if (fingers.isNotEmpty()) { debug("pen hovering → fingers become palms"); endGesture(palm = true) }
        cb.hover(e.x, e.y, e.actionMasked != ACTION_HOVER_EXIT)
        return true
    }

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            ACTION_DOWN, ACTION_POINTER_DOWN -> down(e)
            ACTION_MOVE -> move(e)
            ACTION_UP, ACTION_POINTER_UP -> {
                val i = e.actionIndex; val id = e.getPointerId(i)
                val canceled = Build.VERSION.SDK_INT >= 33 && e.flags and FLAG_CANCELED != 0
                when {
                    id == penId -> { if (!penFinger) lastPen = e.eventTime; finishPen(!(canceled && penFinger)) }
                    palms.remove(id) -> {}
                    id in fingers -> {
                        if (gesturing && !moved && !canceled && tapMax >= 2 && e.eventTime - tapT0 < TAP_MS) {
                            val n = tapMax; tapMax = 0; debug("$n-finger tap"); cb.tap(n)
                        }
                        fingers.remove(id)
                        if (fingers.size < 2) endGesture(palm = false) else measure(e)
                    }
                }
                if (e.actionMasked == ACTION_UP) reset()
            }
            ACTION_CANCEL -> {
                debug("system cancelled the touch stream")
                if (penId != -1) finishPen(!penFinger) // a pen stroke survives the cancel
                endGesture(palm = false); fingers.clear(); palms.clear()
            }
        }
        return true
    }

    private fun down(e: MotionEvent) {
        if (e.actionMasked == ACTION_DOWN) reset()
        val i = e.actionIndex; val id = e.getPointerId(i); val t = e.getToolType(i)
        if (isPen(t)) {
            if (fingers.isNotEmpty()) endGesture(palm = true)
            if (penId != -1) finishPen(!penFinger)
            penId = id; penFinger = false; lastPen = e.eventTime
            view.requestUnbufferedDispatch(e)
            val btn = e.buttonState and (BUTTON_STYLUS_PRIMARY or BUTTON_SECONDARY) != 0
            debug("pen down" + if (btn) " (button → eraser)" else "")
            cb.penDown(e.getX(i), e.getY(i), pressure(e, i), t == TOOL_TYPE_ERASER || btn, false)
            return
        }
        val why = when {
            penId != -1 && !penFinger -> "pen is down"
            e.eventTime - lastPen < PEN_RECENT_MS -> "pen nearby"
            palms.isNotEmpty() -> "hand resting"
            e.getTouchMajor(i) > palmPx -> "too wide"
            else -> null
        }
        if (why != null) { palms += id; debug("touch ${mm(e, i)} ignored: $why"); return }
        if (penId != -1 && penFinger) {
            if (e.eventTime - firstDown > TWO_FINGER_MS) { palms += id; debug("touch ignored: late second finger"); return }
            fingers += penId; finishPen(false) // the finger stroke was the start of a pinch
        } else if (fingers.size == 1 && !gesturing && e.eventTime - firstDown > TWO_FINGER_MS) {
            palms += fingers; fingers.clear(); palms += id; debug("touch ignored: resting hand"); return
        }
        if (fingers.isEmpty() && penId == -1 && fingerDraws()) {
            penId = id; penFinger = true; firstDown = e.eventTime
            debug("finger draws (${mm(e, i)})")
            cb.penDown(e.getX(i), e.getY(i), 1f, false, true)
            return
        }
        if (fingers.isEmpty()) firstDown = e.eventTime
        fingers += id
        measure(e)
        if (fingers.size == 2) { gesturing = true; tapT0 = e.eventTime; tapMax = 2; moved = false; sgx = gx; sgy = gy; sgd = gd }
        else if (gesturing && fingers.size > tapMax) tapMax = fingers.size
        debug("finger ${fingers.size} (${mm(e, i)})")
    }

    private fun move(e: MotionEvent) {
        if (penId != -1) {
            val i = e.findPointerIndex(penId)
            if (i >= 0) {
                for (h in 0 until e.historySize)
                    cb.penMove(e.getHistoricalX(i, h), e.getHistoricalY(i, h), if (penFinger) 1f else e.getHistoricalPressure(i, h).coerceIn(0.02f, 1f))
                cb.penMove(e.getX(i), e.getY(i), pressure(e, i))
                if (!penFinger) lastPen = e.eventTime
            }
        }
        if (fingers.isEmpty()) return
        for (id in fingers) {
            val i = e.findPointerIndex(id)
            if (i >= 0 && e.getTouchMajor(i) > palmPx) { debug("contact grew to ${mm(e, i)}: palm"); endGesture(palm = true); return }
        }
        if (gesturing) pan(e)
    }

    private fun pan(e: MotionEvent) {
        val ox = gx; val oy = gy; val od = gd; val oa = ga
        measure(e)
        if (!moved) {
            if (hypot(gx - sgx, gy - sgy) > slop || abs(gd - sgd) > slop) moved = true else return
        }
        val s = if (od > slop && gd > slop) gd / od else 1f
        var r = ga - oa
        if (r > PI) r -= 2 * PI
        if (r < -PI) r += 2 * PI
        cb.gesture(ox, oy, gx - ox, gy - oy, s, r.toFloat())
    }

    private fun measure(e: MotionEvent) {
        var sx = 0f; var sy = 0f; var n = 0
        var x0 = 0f; var y0 = 0f; var x1 = 0f; var y1 = 0f
        for (id in fingers) {
            val i = e.findPointerIndex(id)
            if (i < 0) continue
            val x = e.getX(i); val y = e.getY(i)
            if (n == 0) { x0 = x; y0 = y } else if (n == 1) { x1 = x; y1 = y }
            sx += x; sy += y; n++
        }
        if (n == 0) return
        gx = sx / n; gy = sy / n
        var d = 0f
        for (id in fingers) { val i = e.findPointerIndex(id); if (i >= 0) d += hypot(e.getX(i) - gx, e.getY(i) - gy) }
        gd = d / n
        if (n >= 2) ga = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
    }

    private fun finishPen(keep: Boolean) {
        penId = -1; penFinger = false
        cb.penUp(keep)
    }

    private fun endGesture(palm: Boolean) {
        if (gesturing) { gesturing = false; cb.gestureEnd() }
        tapMax = 0
        if (palm) { palms += fingers; fingers.clear() }
    }

    private fun reset() {
        if (penId != -1) finishPen(!penFinger)
        endGesture(palm = false)
        fingers.clear(); palms.clear()
    }
}
