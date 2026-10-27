package app.stylusnotes

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Infinite note page. Committed items live in a screen-sized bitmap; pen strokes are appended to it
 * segment by segment, so a frame costs one blit no matter how much is on the page.
 * Pan/zoom just transforms the stale bitmap and re-renders once the fingers lift.
 *
 * tool < 10 is a brush (a Stroke.kind); ERASE, LASSO and TEXT are the others.
 */
class InkView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs), InputRouter.Callbacks {
    companion object {
        const val ERASE = 10; const val LASSO = 11; const val TEXT = 12
        private const val HOLD_MS = 550L
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 8f
    }

    private sealed interface Op
    private class Add(val items: List<Item>) : Op
    private class Del(val entries: List<Pair<Int, Item>>) : Op // (index at removal time, item), in removal order
    private class Move(val items: List<Item>, val dx: Float, val dy: Float) : Op
    private class Scale(val items: List<Item>, val f: Float, val ox: Float, val oy: Float) : Op
    private class Edit(val l: Label, val old: String, val next: String) : Op
    private class EditMath(val m: MathItem, val old: String, val next: String) : Op
    private class Multi(val ops: List<Op>) : Op
    private enum class Mode { NONE, DRAW, ERASE, LASSO, DRAG, SCALE, TEXT }

    var items: MutableList<Item> = ArrayList()
    var layers: MutableList<Layer> = arrayListOf(Layer(0, "Layer 1"))
    var activeLayer = 0
    var bg = 1
        set(v) { field = v; render(null) }
    var paper = 0
        set(v) { field = v; setBackgroundColor(Painter.PAPERS[v]); render(null) }
    var tool = Stroke.PEN
        set(v) { field = v; if (v != LASSO) deselect() }
    var color = 0xFF1C1B1F.toInt()
    var size = 4f
    /** 0..0.9: how far the line lags behind the pen to even out wobble. */
    var stabilize = 0f
    /** Hold the pen still at the end of a stroke to snap it to a line or ellipse. */
    var snapShapes = true
    var fingerDraw = false
    /** Fired after every committed change. */
    var onChange: (() -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    /** Text tool tap: the label or equation under the pen (or null) and the page position. */
    var onText: ((Item?, Float, Float) -> Unit)? = null
    var onGestureTap: ((Int) -> Unit)? = null
    var onSnap: (() -> Unit)? = null
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val selection: List<Item> get() = sel
    val zoom get() = scale

    val router = InputRouter(this, this).also { it.fingerDraws = { fingerDraw || tool == TEXT } }

    private val dp = resources.displayMetrics.density
    private val accent = themeColor(Attr.primary)
    private val painter = Painter()
    private var cache: Bitmap? = null
    private var cc: Canvas? = null
    private var scale = 1f; private var tx = 0f; private var ty = 0f
    private var cs = 1f; private var cx = 0f; private var cy = 0f // transform the cache was rendered at
    private var gesturing = false
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = accent
        pathEffect = DashPathEffect(floatArrayOf(7 * dp, 5 * dp), 0f)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val lassoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0xFFFFFF) or 0x18000000 }
    private val undo = ArrayList<Op>()
    private val redo = ArrayList<Op>()
    private val lassoPath = Path()

    // active pointer
    private var penActive = false
    private var mode = Mode.NONE
    private var kind = Stroke.PEN
    private var buf = FloatArray(768)
    private var bn = 0
    private val erased = ArrayList<Pair<Int, Item>>()
    private var frozen = false // stroke was snapped to a shape; ignore further movement
    private var ax = 0f; private var ay = 0f
    private val snapRun = Runnable { snap() }
    private var tapX = 0f; private var tapY = 0f; private var tapSX = 0f; private var tapSY = 0f; private var tapFar = false

    // selection
    private val sel = ArrayList<Item>()
    private val selB = RectF()
    private var selSet: Set<Item>? = null
    private var hideSel = false
    private var dragX = 0f; private var dragY = 0f; private var ddx = 0f; private var ddy = 0f
    private var sox = 0f; private var soy = 0f; private var sd0 = 1f; private var sf = 1f

    // hover cursor
    private var hx = 0f; private var hy = 0f; private var hover = false

    init { setBackgroundColor(Painter.PAPERS[0]) }

    override fun onTouchEvent(e: MotionEvent) = router.onTouch(e)
    override fun onHoverEvent(e: MotionEvent) = router.onHover(e) || super.onHoverEvent(e)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        cache?.recycle()
        // ponytail: RGB_565 halves memory and blit bandwidth on old GPUs; the page is opaque so nothing is lost
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { cc = Canvas(it) }
        render(null)
    }

    private fun dx(x: Float) = (x - tx) / scale
    private fun dy(y: Float) = (y - ty) / scale
    private fun layerVisible() = layers.firstOrNull { it.id == activeLayer }?.visible != false

    /** Redraw the cache; [region] (page coords) limits work to what changed. */
    private fun render(region: RectF?) {
        val c = cc ?: return
        val reg = if (gesturing) null else region
        val vis = RectF(-tx / scale, -ty / scale, (width - tx) / scale, (height - ty) / scale)
        val r = RectF(reg ?: vis)
        if (reg != null) { r.inset(-2 / scale, -2 / scale); if (!r.intersect(vis)) return }
        cs = scale; cx = tx; cy = ty
        c.save(); c.translate(tx, ty); c.scale(scale, scale); c.clipRect(r)
        painter.scene(c, items, layers, paper, bg, r, scale, if (hideSel) selSet else null)
        c.restore()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val b = cache ?: return
        if (cs == scale && cx == tx && cy == ty) c.drawBitmap(b, 0f, 0f, null)
        else {
            val r = scale / cs
            c.save(); c.translate(tx - cx * r, ty - cy * r); c.scale(r, r); c.drawBitmap(b, 0f, 0f, blit); c.restore()
        }
        c.save(); c.translate(tx, ty); c.scale(scale, scale)
        if (penActive && mode == Mode.DRAW && Stroke.isPath(kind) && bn > 0)
            painter.pathStroke(c, kind, color, size, buf, bn / 3)
        if (mode == Mode.DRAG) { c.translate(ddx, ddy); for (s in sel) painter.item(c, s) }
        if (mode == Mode.SCALE) { c.translate(sox, soy); c.scale(sf, sf); c.translate(-sox, -soy); for (s in sel) painter.item(c, s) }
        c.restore()
        if (sel.isNotEmpty()) {
            val s = selScreen()
            c.drawRoundRect(s, 8 * dp, 8 * dp, dash)
            c.drawCircle(s.right, s.bottom, 9 * dp, handle); c.drawCircle(s.right, s.bottom, 9 * dp, handleRing)
        }
        if (mode == Mode.LASSO && bn >= 6) {
            lassoPath.rewind(); lassoPath.moveTo(buf[0] * scale + tx, buf[1] * scale + ty)
            for (i in 1 until bn / 3) lassoPath.lineTo(buf[i * 3] * scale + tx, buf[i * 3 + 1] * scale + ty)
            c.drawPath(lassoPath, lassoFill)
            lassoPath.close(); c.drawPath(lassoPath, dash)
        }
        if (hover && !penActive && tool != LASSO && tool != TEXT) {
            ring.color = if (tool == ERASE) 0xFF8E8E93.toInt() else color
            c.drawCircle(hx, hy, hoverRadius(), ring)
        }
    }

    /** Selection box on screen, following a live drag or resize. */
    private fun selScreen(): RectF {
        val r = RectF(selB); r.inset(-4 / scale, -4 / scale)
        if (mode == Mode.DRAG) r.offset(ddx, ddy)
        if (mode == Mode.SCALE) r.set(sox + (r.left - sox) * sf, soy + (r.top - soy) * sf, sox + (r.right - sox) * sf, soy + (r.bottom - soy) * sf)
        return RectF(r.left * scale + tx, r.top * scale + ty, r.right * scale + tx, r.bottom * scale + ty)
    }

    private fun hoverRadius() =
        if (tool == ERASE) max(10 * dp, size * scale) else max(2 * dp, size * scale * Stroke.mul(tool) / 2)

    // ---- input (via InputRouter) ----

    override fun hover(x: Float, y: Float, inRange: Boolean) {
        val m = (hoverRadius() + 4 * dp).toInt()
        invalidate((hx - m).toInt(), (hy - m).toInt(), (hx + m).toInt() + 1, (hy + m).toInt() + 1)
        hover = inRange; hx = x; hy = y
        invalidate((hx - m).toInt(), (hy - m).toInt(), (hx + m).toInt() + 1, (hy + m).toInt() + 1)
    }

    override fun gesture(px: Float, py: Float, dx: Float, dy: Float, scale: Float, rot: Float) {
        gesturing = true
        val ns = (this.scale * scale).coerceIn(MIN_SCALE, MAX_SCALE)
        val f = ns / this.scale
        tx = (px + dx) - (px - tx) * f // keep the page point under the fingers fixed
        ty = (py + dy) - (py - ty) * f
        this.scale = ns
        invalidate()
    }

    override fun gestureEnd() { gesturing = false; render(null) }

    override fun tap(fingers: Int) {
        if (fingers == 2) undo() else if (fingers == 3) redo()
        onGestureTap?.invoke(fingers)
    }

    override fun penDown(x: Float, y: Float, p: Float, eraser: Boolean, finger: Boolean) {
        penActive = true; hover = false
        kind = tool; bn = 0; erased.clear(); frozen = false
        val px = dx(x); val py = dy(y)
        mode = when {
            eraser || tool == ERASE -> Mode.ERASE
            tool == TEXT -> Mode.TEXT
            tool == LASSO && sel.isNotEmpty() && nearHandle(x, y) -> Mode.SCALE
            tool == LASSO && sel.isNotEmpty() && selB.contains(px, py) -> Mode.DRAG
            tool == LASSO -> Mode.LASSO
            else -> Mode.DRAW
        }
        if (mode == Mode.DRAW && !layerVisible()) { penActive = false; mode = Mode.NONE; return } // can't draw on a hidden layer
        when (mode) {
            Mode.TEXT -> { tapX = px; tapY = py; tapSX = x; tapSY = y; tapFar = false }
            Mode.DRAG -> {
                dragX = px; dragY = py; ddx = 0f; ddy = 0f
                selSet = sel.toHashSet(); hideSel = true; render(null) // cache without the selection; it's drawn live
            }
            Mode.SCALE -> {
                sox = selB.left; soy = selB.top; sd0 = max(1f, hypot(px - sox, py - soy)); sf = 1f
                selSet = sel.toHashSet(); hideSel = true; render(null)
            }
            Mode.LASSO -> { deselect(); point(x, y, 0f) }
            else -> point(x, y, p)
        }
    }

    override fun penMove(x: Float, y: Float, p: Float) {
        if (!penActive) return
        if (mode == Mode.TEXT && hypot(x - tapSX, y - tapSY) > 10 * dp) tapFar = true
        point(x, y, p)
    }

    override fun penUp(keep: Boolean) { if (!penActive) return; if (keep) endStroke() else cancelStroke() }

    private fun nearHandle(x: Float, y: Float): Boolean { val s = selScreen(); return hypot(x - s.right, y - s.bottom) < 28 * dp }

    private fun point(sx: Float, sy: Float, p: Float) {
        val x = dx(sx); val y = dy(sy)
        when (mode) {
            Mode.ERASE -> eraseAt(x, y)
            Mode.DRAW -> if (!frozen) extend(x, y, p)
            Mode.LASSO -> lassoPt(x, y)
            Mode.DRAG -> { ddx = x - dragX; ddy = y - dragY; invalidate() }
            Mode.SCALE -> { sf = (hypot(x - sox, y - soy) / sd0).coerceIn(0.1f, 10f); invalidate() }
            else -> {}
        }
    }

    private fun grow() { if (bn + 3 > buf.size) buf = buf.copyOf(buf.size * 2) }

    private fun extend(x0: Float, y0: Float, p0: Float) {
        val n = bn
        var x = x0; var y = y0
        if (n > 0 && stabilize > 0f) {
            x = buf[n - 3] + (x0 - buf[n - 3]) * (1 - stabilize)
            y = buf[n - 2] + (y0 - buf[n - 2]) * (1 - stabilize)
        }
        if (n > 0) {
            val ex = x - buf[n - 3]; val ey = y - buf[n - 2]
            if (ex * ex + ey * ey < 0.5f / (scale * scale)) return // sub-pixel jitter
        }
        val p = if (n > 0) buf[n - 1] * 0.6f + p0 * 0.4f else p0
        grow()
        buf[n] = x; buf[n + 1] = y; buf[n + 2] = p; bn = n + 3
        // restart the hold-to-snap timer whenever the pen actually travels
        if (snapShapes && (n == 0 || hypot((x - ax) * scale, (y - ay) * scale) > 4 * dp)) {
            ax = x; ay = y; removeCallbacks(snapRun); postDelayed(snapRun, HOLD_MS)
        }
        if (Stroke.isPath(kind)) { invalidate(); return }
        val c = cc ?: return
        val cnt = bn / 3
        c.save(); c.translate(tx, ty); c.scale(scale, scale)
        // the curve through sample k needs sample k+1, so the drawn stroke trails the pen by one sample
        if (cnt == 1) painter.dot(c, kind, color, size, x, y, p)
        else if (cnt >= 3) painter.piece(c, kind, color, size, buf, cnt - 2)
        c.restore()
        invalidatePoints(max(0, cnt - 3), cnt)
    }

    private fun invalidatePoints(from: Int, to: Int) {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in from until to) {
            l = min(l, buf[k * 3]); r = max(r, buf[k * 3]); t = min(t, buf[k * 3 + 1]); b = max(b, buf[k * 3 + 1])
        }
        val m = size * scale + 3
        invalidate((l * scale + tx - m).toInt(), (t * scale + ty - m).toInt(), (r * scale + tx + m).toInt() + 1, (b * scale + ty + m).toInt() + 1)
    }

    /** Pen held still: replace the stroke with a straight line, or an ellipse if it closed on itself. */
    private fun snap() {
        if (!penActive || mode != Mode.DRAW || frozen || bn < 9) return
        val m = bn / 3
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var len = 0f; var pr = 0f
        for (k in 0 until m) {
            l = min(l, buf[k * 3]); r = max(r, buf[k * 3]); t = min(t, buf[k * 3 + 1]); b = max(b, buf[k * 3 + 1])
            pr += buf[k * 3 + 2]
            if (k > 0) len += hypot(buf[k * 3] - buf[k * 3 - 3], buf[k * 3 + 1] - buf[k * 3 - 2])
        }
        pr /= m
        val diag = hypot(r - l, b - t)
        if (diag * scale < 30 * dp) return
        val fx = buf[0]; val fy = buf[1]; val lx = buf[bn - 3]; val ly = buf[bn - 2]
        val shape: FloatArray
        if (hypot(lx - fx, ly - fy) < 0.3f * diag && len > 2.2f * diag) {
            val steps = 48
            shape = FloatArray((steps + 1) * 3)
            for (k in 0..steps) {
                val a = 2 * PI * k / steps
                shape[k * 3] = ((l + r) / 2 + (r - l) / 2 * cos(a)).toFloat()
                shape[k * 3 + 1] = ((t + b) / 2 + (b - t) / 2 * sin(a)).toFloat()
                shape[k * 3 + 2] = pr
            }
        } else shape = floatArrayOf(fx, fy, pr, lx, ly, pr)
        buf = shape.copyOf(max(shape.size, 768)); bn = shape.size
        frozen = true
        render(null) // drops the half-drawn stroke from the cache
        if (!Stroke.isPath(kind)) cc?.let { c ->
            c.save(); c.translate(tx, ty); c.scale(scale, scale)
            painter.drawStroke(c, Stroke(color, kind, size, shape, activeLayer))
            c.restore()
        }
        invalidate()
        onSnap?.invoke()
    }

    private fun endStroke() {
        removeCallbacks(snapRun)
        when (mode) {
            Mode.ERASE -> if (erased.isNotEmpty()) commit(Del(erased.toList()))
            Mode.DRAW -> if (bn > 0) {
                val cnt = bn / 3
                if (!Stroke.isPath(kind) && !frozen && cnt >= 2) cc?.let { c ->
                    c.save(); c.translate(tx, ty); c.scale(scale, scale)
                    painter.tail(c, kind, color, size, buf, cnt)
                    c.restore()
                }
                val s = Stroke(color, kind, size, buf.copyOf(bn), activeLayer)
                items.add(s)
                if (Stroke.isPath(kind)) render(s.bounds) // pressure strokes are already in the cache
                commit(Add(listOf(s)))
            }
            Mode.LASSO -> finishLasso()
            Mode.DRAG -> {
                hideSel = false
                if (hypot(ddx, ddy) * scale > 2) {
                    for (s in sel) s.move(ddx, ddy)
                    selB.offset(ddx, ddy)
                    commit(Move(sel.toList(), ddx, ddy))
                }
                ddx = 0f; ddy = 0f; mode = Mode.NONE
                render(null)
            }
            Mode.SCALE -> {
                hideSel = false
                val f = sf; mode = Mode.NONE
                if (kotlin.math.abs(f - 1) > 0.01f) {
                    for (s in sel) s.scale(f, sox, soy)
                    setSelection()
                    commit(Scale(sel.toList(), f, sox, soy))
                }
                render(null)
            }
            Mode.TEXT -> if (!tapFar) onText?.invoke(itemAt(tapX, tapY), tapX, tapY)
            Mode.NONE -> {}
        }
        penActive = false; mode = Mode.NONE; bn = 0; invalidate()
    }

    private fun cancelStroke() {
        removeCallbacks(snapRun)
        if (mode == Mode.ERASE) { endStroke(); return } // erasures already happened; keep them undoable
        if (mode == Mode.DRAG || mode == Mode.SCALE) { hideSel = false; ddx = 0f; ddy = 0f; sf = 1f }
        penActive = false; mode = Mode.NONE; bn = 0; render(null)
    }

    // ---- erase ----

    private fun eraseAt(x: Float, y: Float) {
        if (!layerVisible()) return
        val r = max(10 * dp, size * scale) / scale
        val dirty = RectF()
        for (i in items.indices.reversed()) {
            val s = items[i]
            if (s.layer != activeLayer || !hit(s, x, y, r)) continue
            items.removeAt(i); erased += i to s
            if (dirty.isEmpty) dirty.set(s.bounds) else dirty.union(s.bounds)
        }
        if (!dirty.isEmpty) render(dirty)
    }

    private fun hit(s: Item, x: Float, y: Float, r: Float): Boolean {
        val b = s.bounds
        if (x < b.left - r || x > b.right + r || y < b.top - r || y > b.bottom + r) return false
        if (s !is Stroke) return true // text, pictures, equations: the box is good enough
        val a = s.pts
        val lim = r + s.width * Stroke.mul(s.kind) / 2
        if (s.n == 1) return (x - a[0]) * (x - a[0]) + (y - a[1]) * (y - a[1]) <= lim * lim
        for (i in 0 until (s.n - 1) * 3 step 3)
            if (segDist2(x, y, a[i], a[i + 1], a[i + 3], a[i + 4]) <= lim * lim) return true
        return false
    }

    private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay; val l = vx * vx + vy * vy
        val t = if (l == 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / l).coerceIn(0f, 1f)
        val qx = ax + t * vx - px; val qy = ay + t * vy - py
        return qx * qx + qy * qy
    }

    // ---- lasso / selection ----

    private fun lassoPt(x: Float, y: Float) {
        if (bn > 0 && hypot((x - buf[bn - 3]) * scale, (y - buf[bn - 2]) * scale) < 3) return
        grow(); buf[bn] = x; buf[bn + 1] = y; buf[bn + 2] = 0f; bn += 3
        invalidate()
    }

    private fun inPoly(px: Float, py: Float, m: Int): Boolean {
        var inside = false; var j = m - 1
        for (i in 0 until m) {
            val xi = buf[i * 3]; val yi = buf[i * 3 + 1]; val xj = buf[j * 3]; val yj = buf[j * 3 + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun finishLasso() {
        val m = bn / 3
        if (m < 3 || !layerVisible()) return
        val poly = RectF(buf[0], buf[1], buf[0], buf[1])
        for (k in 1 until m) poly.union(buf[k * 3], buf[k * 3 + 1])
        for (s in items) {
            if (s.layer != activeLayer || !RectF.intersects(s.bounds, poly)) continue
            val picked = if (s is Stroke) (0 until s.n).any { inPoly(s.pts[it * 3], s.pts[it * 3 + 1], m) }
            else inPoly(s.bounds.centerX(), s.bounds.centerY(), m)
            if (picked) sel += s
        }
        setSelection()
    }

    private fun setSelection() {
        selB.setEmpty()
        for (s in sel) if (selB.isEmpty) selB.set(s.bounds) else selB.union(s.bounds)
        onSelect?.invoke(sel.size)
        invalidate()
    }

    fun select(list: List<Item>) { sel.clear(); sel.addAll(list); setSelection() }

    fun deselect() {
        if (sel.isEmpty()) return
        sel.clear(); onSelect?.invoke(0); invalidate()
    }

    private fun removeOp(list: List<Item>): Del {
        val entries = list.map { items.indexOf(it) to it }.filter { it.first >= 0 }.sortedByDescending { it.first }
        for ((i, _) in entries) items.removeAt(i)
        return Del(entries)
    }

    fun deleteSelection() {
        if (sel.isEmpty()) return
        val r = RectF(selB); val op = removeOp(sel)
        deselect(); render(r); commit(op)
    }

    fun duplicateSelection() {
        if (sel.isEmpty()) return
        val copies = sel.map { it.copy().also { c -> c.move(24f, 24f) } }
        items.addAll(copies)
        select(copies)
        render(selB); commit(Add(copies))
    }

    /** Swaps the selection (or nothing) for [item] as one undo step, and selects the new item. */
    fun replaceSelection(item: Item) {
        item.layer = activeLayer
        val r = RectF(item.bounds); if (sel.isNotEmpty()) r.union(selB)
        val ops = ArrayList<Op>()
        if (sel.isNotEmpty()) ops += removeOp(sel)
        items.add(item); ops += Add(listOf(item))
        render(r); commit(Multi(ops)); select(listOf(item))
    }

    fun addItem(item: Item) {
        item.layer = activeLayer
        items.add(item); render(item.bounds); commit(Add(listOf(item))); select(listOf(item))
    }

    /** Page point at the middle of the screen, and how wide the screen is in page units. */
    fun viewCenter() = PointF(dx(width / 2f), dy(height / 2f))
    fun viewWidth() = width / scale

    /** What the AI should look at: the selection, else everything on screen. */
    fun aiImage(): Bitmap? {
        val vis = RectF(dx(0f), dy(0f), dx(width.toFloat()), dy(height.toFloat()))
        val src = sel.ifEmpty { items.filter { RectF.intersects(it.bounds, vis) && layers.any { l -> l.id == it.layer && l.visible } } }
        return Painter().export(src, layers, paper, bg, maxSide = 1568f, maxScale = 1.5f)
    }

    // ---- text & equations ----

    private fun itemAt(x: Float, y: Float) = items.lastOrNull {
        (it is Label || it is MathItem) && it.layer == activeLayer && it.bounds.contains(x, y)
    }

    fun addLabel(text: String, x: Float, y: Float) = addItem(Label(text, x, y, color, max(16f, size * 6f)))

    /** Empty text deletes the label. */
    fun editLabel(l: Label, text: String) {
        if (text == l.text) return
        if (text.isEmpty()) { val r = RectF(l.bounds); val op = removeOp(listOf(l)); render(r); commit(op); return }
        val r = RectF(l.bounds)
        val old = l.text; l.text = text; l.measure(); r.union(l.bounds)
        render(r); commit(Edit(l, old, text))
    }

    fun editMath(m: MathItem, latex: String) {
        if (latex == m.latex) return
        val r = RectF(m.bounds); val old = m.latex
        m.set(latex); r.union(m.bounds)
        render(r); commit(EditMath(m, old, latex))
    }

    // ---- history ----

    private fun commit(op: Op) { undo += op; redo.clear(); onChange?.invoke() }

    fun undo() { if (undo.isNotEmpty()) apply(undo.removeAt(undo.lastIndex).also { redo += it }, true) }
    fun redo() { if (redo.isNotEmpty()) apply(redo.removeAt(redo.lastIndex).also { undo += it }, false) }

    private fun apply(op: Op, reverse: Boolean) {
        deselect()
        val r = RectF()
        fun mark(b: RectF) { if (r.isEmpty) r.set(b) else r.union(b) }
        fun run(o: Op) {
            when (o) {
                is Add -> for (s in o.items) { if (reverse) items.remove(s) else items.add(s); mark(s.bounds) }
                is Del ->
                    if (reverse) for ((i, s) in o.entries.asReversed()) { items.add(i, s); mark(s.bounds) }
                    else for ((i, s) in o.entries) { items.removeAt(i); mark(s.bounds) }
                is Move -> for (s in o.items) { mark(s.bounds); if (reverse) s.move(-o.dx, -o.dy) else s.move(o.dx, o.dy); mark(s.bounds) }
                is Scale -> for (s in o.items) { mark(s.bounds); s.scale(if (reverse) 1 / o.f else o.f, o.ox, o.oy); mark(s.bounds) }
                is Edit -> { mark(o.l.bounds); o.l.text = if (reverse) o.old else o.next; o.l.measure(); mark(o.l.bounds) }
                is EditMath -> { mark(o.m.bounds); o.m.set(if (reverse) o.old else o.next); mark(o.m.bounds) }
                is Multi -> (if (reverse) o.ops.asReversed() else o.ops).forEach { run(it) }
            }
        }
        run(op)
        render(r)
        onChange?.invoke()
    }

    /** Clears the active layer (undoable). */
    fun clearLayer() {
        val entries = ArrayList<Pair<Int, Item>>()
        var i = 0 // removed front-to-back within the layer, so replaying in order stays consistent
        while (i < items.size) if (items[i].layer == activeLayer) entries += i to items.removeAt(i) else i++
        if (entries.isEmpty()) return
        deselect(); commit(Del(entries)); render(null)
    }

    // ---- layers ----

    fun addLayer() {
        val id = (layers.maxOfOrNull { it.id } ?: -1) + 1
        layers.add(layers.indexOfFirst { it.id == activeLayer } + 1, Layer(id, "Layer ${id + 1}"))
        activeLayer = id
        onChange?.invoke()
    }

    /** Deletes a layer and everything on it. Not undoable, so history is dropped. */
    fun removeLayer(id: Int): Boolean {
        if (layers.size <= 1) return false
        items.removeAll { it.layer == id }
        layers.removeAll { it.id == id }
        if (activeLayer == id) activeLayer = layers.last().id
        undo.clear(); redo.clear(); deselect(); render(null); onChange?.invoke()
        return true
    }

    fun moveLayer(id: Int, d: Int) {
        val i = layers.indexOfFirst { it.id == id }; val j = i + d
        if (i < 0 || j !in layers.indices) return
        layers.add(j, layers.removeAt(i))
        render(null); onChange?.invoke()
    }

    /** After the layer list changed outside (visibility). */
    fun refresh() = render(null)

    // ---- view ----

    fun resetView() { scale = 1f; tx = 0f; ty = 0f; render(null) }

    fun fitContent() {
        val r = RectF()
        for (s in items) if (r.isEmpty) r.set(s.bounds) else r.union(s.bounds)
        if (r.isEmpty || width == 0) { resetView(); return }
        val s = min((width - 96 * dp) / r.width(), (height - 220 * dp) / r.height()).coerceIn(MIN_SCALE, 2f)
        scale = s; tx = width / 2f - r.centerX() * s; ty = height / 2f - r.centerY() * s
        render(null)
    }
}

/** Colour dot with a selection ring that springs in. */
class Swatch @JvmOverloads constructor(c: Context, attrs: AttributeSet? = null) : View(c, attrs) {
    var col = Color.BLACK
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val ringColor = c.themeColor(Attr.primary)
    var sel = false
        set(v) { field = v; springScale(if (v) 1.12f else 1f, 0.4f, 500f); invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f
        if (sel) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = ringColor
            c.drawCircle(cx, cy, r - 2 * d, p)
        }
        val rr = r - (if (sel) 7 else 5) * d
        p.style = Paint.Style.FILL; p.color = col; c.drawCircle(cx, cy, rr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0x33808080; c.drawCircle(cx, cy, rr, p)
    }
}

fun View.themeColor(attr: Int) = context.themeColor(attr)
