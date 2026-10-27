package app.stylusnotes

import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.*
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Replays touch sequences a hand makes on a tablet and checks what reaches the canvas. */
@RunWith(AndroidJUnit4::class)
class PalmTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = ContextThemeWrapper(inst.targetContext, R.style.Theme_Ink)

    private data class P(val id: Int, val x: Float, val y: Float, val tool: Int = TOOL_TYPE_FINGER, val major: Float = 30f)
    private val pen = TOOL_TYPE_STYLUS
    private val palm = 400f // contact width in px, far wider than a fingertip

    private class Rec : InputRouter.Callbacks {
        val log = ArrayList<String>()
        var moves = 0; var gestures = 0; var lastScale = 1f
        override fun penDown(x: Float, y: Float, p: Float, eraser: Boolean, finger: Boolean) { log += if (finger) "fingerDown" else "penDown" }
        override fun penMove(x: Float, y: Float, p: Float) { moves++ }
        override fun penUp(keep: Boolean) { log += "penUp($keep)" }
        override fun gesture(px: Float, py: Float, dx: Float, dy: Float, scale: Float, rot: Float) { gestures++; lastScale *= scale }
        override fun gestureEnd() { log += "gestureEnd" }
        override fun tap(fingers: Int) { log += "tap$fingers" }
        override fun hover(x: Float, y: Float, inRange: Boolean) {}
    }

    private var downTime = 0L
    private fun ev(t: Long, action: Int, vararg ps: P, index: Int = 0): MotionEvent {
        if (action == ACTION_DOWN) downTime = t
        val props = ps.map { PointerProperties().apply { id = it.id; toolType = it.tool } }.toTypedArray()
        val coords = ps.map {
            PointerCoords().apply { x = it.x; y = it.y; pressure = 0.6f; size = 0.1f; touchMajor = it.major; touchMinor = it.major; toolMajor = it.major; toolMinor = it.major }
        }.toTypedArray()
        val a = if (action == ACTION_POINTER_DOWN || action == ACTION_POINTER_UP) action or (index shl ACTION_POINTER_INDEX_SHIFT) else action
        val src = if (ps.any { it.tool == pen }) InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_TOUCHSCREEN
        return obtain(downTime, t, a, ps.size, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
    }

    private fun router(): Pair<InputRouter, Rec> {
        val r = Rec(); var out: InputRouter? = null
        inst.runOnMainSync { out = InputRouter(View(ctx), r) }
        return out!! to r
    }

    private fun InputRouter.feed(vararg es: MotionEvent) = inst.runOnMainSync { for (e in es) onTouch(e) }

    @Test fun palmBeforePenIsIgnored() {
        val (r, rec) = router()
        val p0 = P(0, 600f, 700f, major = palm)
        r.feed(
            ev(5000, ACTION_DOWN, p0),
            ev(5040, ACTION_MOVE, p0.copy(x = 610f)),
            ev(5100, ACTION_POINTER_DOWN, p0, P(1, 300f, 300f, pen), index = 1),
            ev(5120, ACTION_MOVE, p0.copy(x = 620f), P(1, 340f, 310f, pen)),
            ev(5140, ACTION_MOVE, p0.copy(x = 640f, y = 720f), P(1, 380f, 320f, pen)),
            ev(5160, ACTION_POINTER_UP, p0, P(1, 380f, 320f, pen), index = 1),
            ev(5200, ACTION_UP, p0),
        )
        assertEquals(listOf("penDown", "penUp(true)"), rec.log)
        assertEquals(0, rec.gestures)
        assertTrue(rec.moves >= 2)
    }

    @Test fun palmLandingMidStrokeIsIgnored() {
        val (r, rec) = router()
        val s = P(0, 100f, 100f, pen)
        r.feed(
            ev(5000, ACTION_DOWN, s),
            ev(5020, ACTION_MOVE, s.copy(x = 140f)),
            ev(5040, ACTION_POINTER_DOWN, s.copy(x = 150f), P(1, 500f, 600f), index = 1), // small contact: a knuckle
            ev(5060, ACTION_MOVE, s.copy(x = 180f), P(1, 700f, 600f)),
            ev(5080, ACTION_POINTER_DOWN, s.copy(x = 190f), P(1, 700f, 600f), P(2, 760f, 640f), index = 2),
            ev(5100, ACTION_MOVE, s.copy(x = 220f), P(1, 900f, 700f), P(2, 960f, 760f)),
            ev(5120, ACTION_POINTER_UP, s.copy(x = 220f), P(1, 900f, 700f), P(2, 960f, 760f), index = 2),
            ev(5140, ACTION_POINTER_UP, s.copy(x = 230f), P(1, 900f, 700f), index = 1),
            ev(5160, ACTION_MOVE, s.copy(x = 260f)),
            ev(5180, ACTION_UP, s.copy(x = 260f)),
        )
        assertEquals(listOf("penDown", "penUp(true)"), rec.log)
        assertEquals(0, rec.gestures)
    }

    @Test fun systemCancelKeepsTheStroke() {
        val (r, rec) = router()
        val s = P(0, 100f, 100f, pen)
        r.feed(ev(5000, ACTION_DOWN, s), ev(5020, ACTION_MOVE, s.copy(x = 150f)), ev(5030, ACTION_CANCEL, s.copy(x = 150f)))
        assertEquals(listOf("penDown", "penUp(true)"), rec.log)
    }

    @Test fun twoFingerPinchZooms() {
        val (r, rec) = router()
        r.feed(
            ev(5000, ACTION_DOWN, P(0, 400f, 400f)),
            ev(5050, ACTION_POINTER_DOWN, P(0, 400f, 400f), P(1, 600f, 400f), index = 1),
            ev(5100, ACTION_MOVE, P(0, 350f, 400f), P(1, 650f, 400f)),
            ev(5150, ACTION_MOVE, P(0, 300f, 400f), P(1, 700f, 400f)),
        )
        assertTrue("pinch should zoom", rec.gestures > 0 && rec.lastScale > 1.5f)
    }

    @Test fun fingersJustAfterPenAreIgnored() {
        val (r, rec) = router()
        val s = P(0, 100f, 100f, pen)
        r.feed(ev(5000, ACTION_DOWN, s), ev(5050, ACTION_UP, s))
        r.feed(
            ev(5400, ACTION_DOWN, P(0, 400f, 400f)),
            ev(5420, ACTION_POINTER_DOWN, P(0, 400f, 400f), P(1, 600f, 400f), index = 1),
            ev(5500, ACTION_MOVE, P(0, 300f, 500f), P(1, 700f, 500f)),
        )
        assertEquals(0, rec.gestures)
    }

    @Test fun slowSecondContactIsARestingHand() {
        val (r, rec) = router()
        r.feed(
            ev(5000, ACTION_DOWN, P(0, 400f, 400f)),
            ev(5600, ACTION_POINTER_DOWN, P(0, 400f, 400f), P(1, 600f, 420f), index = 1),
            ev(5700, ACTION_MOVE, P(0, 300f, 500f), P(1, 750f, 520f)),
        )
        assertEquals(0, rec.gestures)
    }

    @Test fun twoFingerTapUndoesThreeFingerTapRedoes() {
        val (r, rec) = router()
        r.feed(
            ev(5000, ACTION_DOWN, P(0, 400f, 400f)),
            ev(5030, ACTION_POINTER_DOWN, P(0, 400f, 400f), P(1, 600f, 400f), index = 1),
            ev(5150, ACTION_POINTER_UP, P(0, 400f, 400f), P(1, 600f, 400f), index = 1),
            ev(5160, ACTION_UP, P(0, 400f, 400f)),
            ev(7000, ACTION_DOWN, P(0, 400f, 400f)),
            ev(7020, ACTION_POINTER_DOWN, P(0, 400f, 400f), P(1, 500f, 400f), index = 1),
            ev(7040, ACTION_POINTER_DOWN, P(0, 400f, 400f), P(1, 500f, 400f), P(2, 600f, 400f), index = 2),
            ev(7150, ACTION_POINTER_UP, P(0, 400f, 400f), P(1, 500f, 400f), P(2, 600f, 400f), index = 2),
            ev(7160, ACTION_POINTER_UP, P(0, 400f, 400f), P(1, 500f, 400f), index = 1),
            ev(7170, ACTION_UP, P(0, 400f, 400f)),
        )
        assertTrue(rec.log.toString(), rec.log.contains("tap2") && rec.log.contains("tap3"))
    }

    @Test fun contactThatSwellsBecomesAPalm() {
        val (r, rec) = router()
        r.feed(
            ev(5000, ACTION_DOWN, P(0, 400f, 400f)),
            ev(5040, ACTION_POINTER_DOWN, P(0, 400f, 400f), P(1, 600f, 400f), index = 1),
            ev(5100, ACTION_MOVE, P(0, 350f, 400f), P(1, 650f, 400f)),
        )
        val before = rec.gestures
        r.feed(
            ev(5150, ACTION_MOVE, P(0, 340f, 400f), P(1, 660f, 400f, major = palm)),
            ev(5200, ACTION_MOVE, P(0, 200f, 400f), P(1, 800f, 400f, major = palm)),
        )
        assertEquals(before, rec.gestures)
        assertTrue(rec.log.contains("gestureEnd"))
    }

    @Test fun inkViewKeepsStrokeWhenPalmCancelsIt() {
        var v: InkView? = null
        inst.runOnMainSync { v = InkView(ctx).apply { measure(1000, 800); layout(0, 0, 1000, 800) } }
        val ink = v!!
        val s = P(0, 100f, 100f, pen)
        inst.runOnMainSync {
            for (e in listOf(
                ev(5000, ACTION_DOWN, s), ev(5020, ACTION_MOVE, s.copy(x = 200f)), ev(5040, ACTION_MOVE, s.copy(x = 300f, y = 150f)),
                ev(5050, ACTION_POINTER_DOWN, s.copy(x = 310f, y = 150f), P(1, 600f, 600f, major = palm), index = 1),
                ev(5060, ACTION_MOVE, s.copy(x = 320f, y = 160f), P(1, 500f, 500f, major = palm)),
                ev(5070, ACTION_CANCEL, s.copy(x = 320f, y = 160f), P(1, 500f, 500f, major = palm)),
            )) ink.dispatchTouchEvent(e)
        }
        assertEquals(1, ink.items.size)
        assertEquals(1f, ink.zoom, 0f)
    }

    @Test fun paintViewPaintsAndIgnoresPalm() {
        var v: PaintView? = null
        inst.runOnMainSync {
            v = PaintView(ctx).apply {
                open(Art("t", 512, 512).apply { layers += newLayer() })
                measure(1000, 800); layout(0, 0, 1000, 800)
            }
        }
        val pv = v!!
        val scale0 = pv.zoomForTest
        val s = P(0, 400f, 300f, pen)
        inst.runOnMainSync {
            for (e in listOf(
                ev(5000, ACTION_DOWN, s), ev(5020, ACTION_MOVE, s.copy(x = 500f)),
                ev(5030, ACTION_POINTER_DOWN, s.copy(x = 520f), P(1, 700f, 600f, major = palm), index = 1),
                ev(5040, ACTION_MOVE, s.copy(x = 560f, y = 320f), P(1, 500f, 400f, major = palm)),
                ev(5050, ACTION_POINTER_UP, s.copy(x = 560f, y = 320f), P(1, 500f, 400f, major = palm), index = 1),
                ev(5060, ACTION_UP, s.copy(x = 600f, y = 330f)),
            )) pv.dispatchTouchEvent(e)
        }
        assertTrue("stroke should paint tiles", pv.active.tiles.any { it != null })
        assertTrue(pv.canUndo)
        assertEquals(scale0, pv.zoomForTest, 0.0001f)
    }
}
