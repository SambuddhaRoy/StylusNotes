package app.stylusnotes

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Raster canvas. Each layer is a grid of 256px tiles; dabs are stamped straight into the tiles they touch,
 * so a frame re-uploads only those tiles. The view matrix (pan/zoom/rotate) is applied at draw time.
 */
class PaintView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs), InputRouter.Callbacks {
    companion object {
        const val PAINT = 0; const val SMUDGE = 1; const val ERASE = 2; const val SELECT = 3; const val TRANSFORM = 4; const val PICK = 5
        const val FREE = 0; const val RECT = 1; const val OVAL = 2
        private const val HOLD_MS = 550L
        private const val UNDO_TILES = 160 // ~40 MB of tile copies
    }

    // ---- undo records ----
    sealed class AOp
    class TileOp(val l: ALayer) : AOp() { val before = HashMap<Int, Bitmap?>(); var after: HashMap<Int, Bitmap?>? = null }
    data class LP(val name: String, val visible: Boolean, val opacity: Float, val blend: Int, val lock: Boolean)
    class Snap(val layers: List<ALayer>, val props: List<LP>, val bg: Int, val bgVis: Boolean, val active: Int)
    class Struct(val before: Snap, val after: Snap) : AOp()
    class Multi(val ops: List<AOp>) : AOp()

    lateinit var art: Art
        private set
    lateinit var active: ALayer
    var tool = PAINT
        set(v) { field = v; invalidate() }
    /** Brush, size (0..1) and opacity per tool, like Procreate: paint, smudge and erase each remember their own. */
    val brush = intArrayOf(0, 8, 7)
    val sizes = floatArrayOf(0.12f, 0.3f, 0.25f)
    val opac = floatArrayOf(1f, 0.6f, 1f)
    var color = 0xFF1C1B1F.toInt()
    var stab = 0.15f
    var quickShape = true
    var symmetry = false
        set(v) { field = v; invalidate() }
    var gamma = 1f
    var fingerDraw = false
    var selMode = FREE
    /** Live adjustment preview on the active layer. */
    var preview: ColorFilter? = null
        set(v) { field = v; invalidate() }
    var maxLayers = 16

    var onChange: (() -> Unit)? = null
    var onPick: ((Int) -> Unit)? = null
    var onTap: ((Int) -> Unit)? = null
    var onSnap: (() -> Unit)? = null
    var onHidden: (() -> Unit)? = null
    var onSelection: ((Boolean) -> Unit)? = null
    var onFit: (() -> Unit)? = null

    val router = InputRouter(this, this).also { it.fingerDraws = { fingerDraw || tool == PICK || tool == SELECT || tool == TRANSFORM } }
    val canUndo get() = undoS.isNotEmpty() || floating != null
    val canRedo get() = redoS.isNotEmpty()
    val hasSelection get() = selPath != null
    var floating: Bitmap? = null; private set

    private val d = resources.displayMetrics.density
    private val accent = themeColor(Attr.primary)
    private val vm = Matrix(); private val im = Matrix()
    private var fitted = false
    private val tmp = FloatArray(2)
    private val undoS = ArrayList<AOp>(); private val redoS = ArrayList<AOp>()
    private val canvases = WeakHashMap<Bitmap, Canvas>()
    private val lp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dab = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clearP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val bgP = Paint()
    private val checker = Paint().apply {
        val b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(0xFFFFFFFF.toInt()); val q = Paint().apply { color = 0xFFE0E0E0.toInt() }; drawRect(0f, 0f, 16f, 16f, q); drawRect(16f, 16f, 32f, 32f, q) }
        shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x33000000 }
    private val antsW = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE }
    private val antsB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.BLACK }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = accent; pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f) }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = accent }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }

    // stroke state
    private var stroking = false
    private var mode = PAINT
    private var op: TileOp? = null
    private var smx = 0f; private var smy = 0f; private var smp = 0f
    private var lx = 0f; private var ly = 0f; private var lpr = 0f; private var nextD = 0f
    private var rawX = 0f; private var rawY = 0f; private var rawP = 0f
    private var pts = FloatArray(600); private var pn = 0
    private var frozen = false
    private var ax = 0f; private var ay = 0f
    private val snapRun = Runnable { snapShape() }
    private val rnd = Random(11)
    private var smudgeBuf: Bitmap? = null; private var smudgeC: Canvas? = null
    private val smudgeM = Matrix()

    // selection
    private var selPath: Path? = null
    var selInvert = false; private set
    private var selDraft = ArrayList<Float>()
    private var selX0 = 0f; private var selY0 = 0f; private var selX1 = 0f; private var selY1 = 0f
    private var selecting = false

    // transform
    val floatM = Matrix()
    private var floatOp: TileOp? = null
    private var tfMode = 0 // 1 move, 2 scale, 3 rotate
    private var tfX = 0f; private var tfY = 0f

    // gestures / hover
    private var gestureT0 = 0L; private var gestureScale = 1f; private var inGesture = false
    private var hx = 0f; private var hy = 0f; private var hover = false

    init { setBackgroundColor(themeColor(Attr.surfaceHighest)) }

    override fun onTouchEvent(e: MotionEvent) = router.onTouch(e)
    override fun onHoverEvent(e: MotionEvent) = router.onHover(e) || super.onHoverEvent(e)

    fun open(a: Art) { art = a; active = a.layers.last(); fitted = false; if (width > 0) fit(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (::art.isInitialized && (!fitted || ow == 0)) fit() else if (::art.isInitialized) { vm.postTranslate((w - ow) / 2f, (h - oh) / 2f); vm.invert(im) } }

    private fun fitMatrix(m: Matrix) {
        val s = min((width - 200 * d) / art.w, (height - 160 * d) / art.h)
        m.setScale(s, s); m.postTranslate((width - art.w * s) / 2, (height - art.h * s) / 2)
    }

    fun fit() { fitMatrix(vm); vm.invert(im); fitted = true; invalidate() }

    /** Animated return to the fitted view (quick pinch, Procreate-style). */
    fun fitAnimated() {
        val from = FloatArray(9).also { vm.getValues(it) }
        val to = FloatArray(9).also { Matrix().apply { fitMatrix(this) }.getValues(it) }
        val cur = FloatArray(9)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { a -> val t = a.animatedValue as Float; for (k in 0 until 9) cur[k] = from[k] + (to[k] - from[k]) * t; vm.setValues(cur); vm.invert(im); invalidate() }
        }.start()
        onFit?.invoke()
    }

    private fun toCanvas(x: Float, y: Float): FloatArray { tmp[0] = x; tmp[1] = y; im.mapPoints(tmp); return tmp }
    private val viewScale get() = vm.mapRadius(1f)
    internal val zoomForTest get() = viewScale
    private fun t(): Int = if (tool <= ERASE) tool else PAINT
    val brushNow get() = Brushes.all[brush[mode.coerceAtMost(ERASE)]]
    fun sizePx(tl: Int = t()): Float { val s = sizes[tl]; return 1f + s * s * max(art.w, art.h) * 0.25f }

    // ---- drawing ----

    override fun onDraw(c: Canvas) {
        if (!::art.isInitialized) return
        val vis = RectF(0f, 0f, width.toFloat(), height.toFloat()); im.mapRect(vis)
        c.save(); c.concat(vm)
        if (art.bgVisible) { bgP.color = art.bg; c.drawRect(art.rect, bgP) } else c.drawRect(art.rect, checker)
        for (l in art.layers) {
            if (!l.visible) continue
            art.layerPaint(l, lp); lp.colorFilter = if (l === active) preview else null
            art.drawLayer(c, l, lp, vis)
            if (l === active) floating?.let { c.save(); c.concat(floatM); c.drawBitmap(it, 0f, 0f, lp); c.restore() }
        }
        lp.colorFilter = null
        edge.strokeWidth = 1f / viewScale; c.drawRect(art.rect, edge)
        c.restore()

        if (symmetry) {
            val p = floatArrayOf(art.w / 2f, 0f, art.w / 2f, art.h.toFloat()); vm.mapPoints(p)
            c.drawLine(p[0], p[1], p[2], p[3], guide)
        }
        selPath?.let { s ->
            val sp = Path(s); sp.transform(vm)
            // ponytail: static ants; animating them keeps the GPU busy every frame on old tablets
            antsB.pathEffect = DashPathEffect(floatArrayOf(6 * d, 6 * d), 0f)
            c.drawPath(sp, antsW); c.drawPath(sp, antsB)
        }
        if (selecting) draftPath()?.let { p -> p.transform(vm); c.drawPath(p, antsW); c.drawPath(p, guide) }
        floating?.let { drawTransformBox(c, it) }
        if (hover && !stroking && tool <= ERASE) {
            ring.color = if (tool == ERASE) 0xFF8E8E93.toInt() else color
            c.drawCircle(hx, hy, max(2 * d, sizePx() * viewScale / 2), ring)
        }
    }

    private fun corners(f: Bitmap): FloatArray {
        val p = floatArrayOf(0f, 0f, f.width.toFloat(), 0f, f.width.toFloat(), f.height.toFloat(), 0f, f.height.toFloat())
        floatM.mapPoints(p); vm.mapPoints(p); return p
    }

    private fun knobPos(p: FloatArray): FloatArray {
        val cx = (p[0] + p[2] + p[4] + p[6]) / 4; val cy = (p[1] + p[3] + p[5] + p[7]) / 4
        val mx = (p[0] + p[2]) / 2; val my = (p[1] + p[3]) / 2
        val len = max(1f, hypot(mx - cx, my - cy))
        return floatArrayOf(mx + (mx - cx) / len * 36 * d, my + (my - cy) / len * 36 * d, mx, my)
    }

    private fun drawTransformBox(c: Canvas, f: Bitmap) {
        val p = corners(f)
        val path = Path().apply { moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); lineTo(p[6], p[7]); close() }
        c.drawPath(path, box)
        val k = knobPos(p)
        c.drawLine(k[2], k[3], k[0], k[1], box)
        for (i in 0 until 4) { c.drawCircle(p[i * 2], p[i * 2 + 1], 9 * d, knob); c.drawCircle(p[i * 2], p[i * 2 + 1], 9 * d, knobRing) }
        c.drawCircle(k[0], k[1], 10 * d, knob); c.drawCircle(k[0], k[1], 10 * d, knobRing)
    }

    // ---- input ----

    override fun hover(x: Float, y: Float, inRange: Boolean) { hover = inRange; hx = x; hy = y; invalidate() }

    override fun gesture(px: Float, py: Float, dx: Float, dy: Float, scale: Float, rot: Float) {
        if (!inGesture) { inGesture = true; gestureT0 = System.currentTimeMillis(); gestureScale = 1f }
        gestureScale *= scale
        val deg = Math.toDegrees(rot.toDouble()).toFloat()
        val f = floating
        if (f != null && tool == TRANSFORM) { // pinch transforms the floating selection
            val c = toCanvas(px, py); val cx = c[0]; val cy = c[1]
            val v = floatArrayOf(dx, dy); im.mapVectors(v)
            floatM.postScale(scale, scale, cx, cy); floatM.postRotate(deg, cx, cy); floatM.postTranslate(v[0], v[1])
        } else {
            val ns = (viewScale * scale).coerceIn(0.05f, 40f)
            val s = ns / viewScale
            vm.postScale(s, s, px, py); vm.postRotate(deg, px, py); vm.postTranslate(dx, dy); vm.invert(im)
        }
        invalidate()
    }

    override fun gestureEnd() {
        if (inGesture && floating == null && System.currentTimeMillis() - gestureT0 < 350 && gestureScale < 0.7f) fitAnimated()
        inGesture = false
    }

    override fun tap(fingers: Int) {
        when (fingers) { 2 -> undo(); 3 -> redo() }
        onTap?.invoke(fingers)
    }

    override fun penDown(x: Float, y: Float, p: Float, eraser: Boolean, finger: Boolean) {
        hover = false
        val q = toCanvas(x, y); val cx = q[0]; val cy = q[1]
        when {
            eraser && tool != SELECT && tool != TRANSFORM -> strokeBegin(cx, cy, p, ERASE)
            tool == PICK -> onPick?.invoke(colorAt(cx, cy))
            tool == SELECT -> { selecting = true; selDraft.clear(); selDraft += cx; selDraft += cy; selX0 = cx; selY0 = cy; selX1 = cx; selY1 = cy }
            tool == TRANSFORM -> transformDown(x, y, cx, cy)
            else -> strokeBegin(cx, cy, p, tool)
        }
    }

    override fun penMove(x: Float, y: Float, p: Float) {
        val q = toCanvas(x, y); val cx = q[0]; val cy = q[1]
        when {
            stroking -> strokeTo(cx, cy, p)
            selecting -> { selX1 = cx; selY1 = cy; if (selMode == FREE) { selDraft += cx; selDraft += cy }; invalidate() }
            tfMode != 0 -> transformMove(cx, cy)
        }
    }

    override fun penUp(keep: Boolean) {
        when {
            stroking -> strokeEnd(keep)
            selecting -> selectEnd(keep)
            tfMode != 0 -> { tfMode = 0; onChange?.invoke() }
        }
    }

    // ---- painting ----

    private fun strokeBegin(x: Float, y: Float, p: Float, m: Int) {
        if (!active.visible) { onHidden?.invoke(); return }
        mode = m; stroking = true; frozen = false
        op = TileOp(active)
        smx = x; smy = y; smp = p; lx = x; ly = y; lpr = p; rawX = x; rawY = y; rawP = p; pn = 0
        addPt(x, y, p)
        if (m == SMUDGE) smudgeStart(x, y)
        dabAt(x, y, p); nextD = spacing(p)
        if (quickShape && m != SMUDGE) { ax = x; ay = y; removeCallbacks(snapRun); postDelayed(snapRun, HOLD_MS) }
        invalidate()
    }

    private fun addPt(x: Float, y: Float, p: Float) {
        if (pn + 3 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[pn] = x; pts[pn + 1] = y; pts[pn + 2] = p; pn += 3
    }

    private fun curve(p: Float) = if (gamma == 1f) p else p.pow(gamma)
    private fun spacing(p: Float) = max(0.5f, Brushes.size(brushNow, sizePx(mode.coerceAtMost(ERASE)), curve(p)) * brushNow.spacing)

    private fun strokeTo(x: Float, y: Float, p: Float) {
        rawX = x; rawY = y; rawP = p
        if (frozen) return
        val k = 1 - stab * 0.85f // StreamLine: follow the pen only part of the way each sample
        smx += (x - smx) * k; smy += (y - smy) * k; smp += (p - smp) * max(k, 0.5f)
        segmentTo(smx, smy, smp)
        addPt(smx, smy, smp)
        if (quickShape && mode != SMUDGE && hypot(smx - ax, smy - ay) * viewScale > 4 * d) {
            ax = smx; ay = smy; removeCallbacks(snapRun); postDelayed(snapRun, HOLD_MS)
        }
        invalidate()
    }

    private fun segmentTo(x: Float, y: Float, p: Float) {
        val dx = x - lx; val dy = y - ly; val len = hypot(dx, dy)
        if (len < 0.01f) return
        var dd = nextD
        while (dd <= len) {
            val f = dd / len; val pp = lpr + (p - lpr) * f
            dabAt(lx + dx * f, ly + dy * f, pp)
            dd += spacing(pp)
        }
        nextD = dd - len; lx = x; ly = y; lpr = p
    }

    private fun strokeEnd(keep: Boolean) {
        removeCallbacks(snapRun)
        stroking = false
        val o = op ?: return
        op = null
        if (keep && !frozen) segmentTo(rawX, rawY, rawP) // catch up with the pen after StreamLine lag
        if (!keep) { restore(o, copy = false); invalidate(); return }
        if (o.before.isNotEmpty()) push(o)
        onChange?.invoke(); invalidate()
    }

    private fun dabAt(x: Float, y: Float, p: Float) {
        val b = brushNow
        val pc = curve(p)
        val sz = Brushes.size(b, sizePx(mode.coerceAtMost(ERASE)), pc)
        val a = Brushes.alpha(b, opac[mode.coerceAtMost(ERASE)], pc)
        val ang = if (!b.angle.isNaN()) b.angle else if (b.spin) rnd.nextFloat() * 360 else 0f
        var px = x; var py = y
        if (b.scatter > 0) { px += (rnd.nextFloat() - 0.5f) * sz * b.scatter * 2; py += (rnd.nextFloat() - 0.5f) * sz * b.scatter * 2 }
        stamp(px, py, sz, a, ang)
        if (symmetry) stamp(art.w - px, py, sz, a, -ang)
    }

    private fun canvasFor(b: Bitmap) = canvases.getOrPut(b) { Canvas(b) }

    private fun touch(o: TileOp, i: Int) { if (!o.before.containsKey(i)) o.before[i] = o.l.tiles[i]?.copy(Bitmap.Config.ARGB_8888, true) }

    private fun clip(c: Canvas) {
        val s = selPath ?: return
        if (selInvert) c.clipOutPath(s) else c.clipPath(s)
    }

    private fun stamp(x: Float, y: Float, sz: Float, a: Float, ang: Float) {
        val o = op ?: return
        val l = o.l
        val r = sz / 2; val rr = r * 1.42f
        val dst = RectF(x - r, y - r, x + r, y + r)
        val mask = Brushes.mask(brushNow.shape)
        when (mode) {
            ERASE -> { dab.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); dab.shader = null; dab.color = Color.BLACK; dab.alpha = (a * 255).toInt() }
            SMUDGE -> {
                val sb = smudgeBuf ?: return
                smudgeM.setScale(sz / sb.width, sz / sb.width); smudgeM.postTranslate(x - r, y - r)
                dab.shader = BitmapShader(sb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(smudgeM) }
                dab.xfermode = if (l.alphaLock) PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) else null
                dab.color = Color.BLACK; dab.alpha = (a * 255).toInt()
            }
            else -> {
                dab.shader = null; dab.xfermode = if (l.alphaLock) PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) else null
                dab.color = color; dab.alpha = (a * 255).toInt()
            }
        }
        art.forTiles(RectF(x - rr, y - rr, x + rr, y + rr)) { i ->
            if ((mode == ERASE || l.alphaLock) && l.tiles[i] == null) return@forTiles
            touch(o, i)
            val tc = canvasFor(art.ensure(l, i))
            tc.save(); tc.translate(-art.tx(i).toFloat(), -art.ty(i).toFloat()); clip(tc)
            if (ang != 0f) tc.rotate(ang, x, y)
            tc.drawBitmap(mask, null, dst, dab)
            tc.restore()
            l.dirty += i
        }
        if (mode == SMUDGE) smudgePickup(x, y)
    }

    private fun smudgeStart(x: Float, y: Float) {
        val s = max(8, min(512, sizePx(SMUDGE).toInt()))
        smudgeBuf = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { smudgeC = Canvas(it) }
        smudgeC!!.drawColor(0, PorterDuff.Mode.CLEAR)
        grab(x, y, 255)
    }

    /** Blends what's under the brush into the smudge buffer, so colour is carried and gradually replaced. */
    private fun smudgePickup(x: Float, y: Float) = grab(x, y, 70)

    private fun grab(x: Float, y: Float, alpha: Int) {
        val c = smudgeC ?: return; val s = smudgeBuf!!.width
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
        val r = RectF(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f)
        art.forTiles(r) { i -> active.tiles[i]?.let { c.drawBitmap(it, art.tx(i) - r.left, art.ty(i) - r.top, p) } }
    }

    /** Pen held still: redraw the stroke as a straight line, or an ellipse if it closed on itself. */
    private fun snapShape() {
        val o = op ?: return
        if (!stroking || frozen || pn < 9) return
        val m = pn / 3
        var l = Float.MAX_VALUE; var tp = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var len = 0f; var pr = 0f
        for (k in 0 until m) {
            l = min(l, pts[k * 3]); r = max(r, pts[k * 3]); tp = min(tp, pts[k * 3 + 1]); b = max(b, pts[k * 3 + 1])
            pr += pts[k * 3 + 2]
            if (k > 0) len += hypot(pts[k * 3] - pts[k * 3 - 3], pts[k * 3 + 1] - pts[k * 3 - 2])
        }
        pr /= m
        val diag = hypot(r - l, b - tp)
        if (diag * viewScale < 30 * d) return
        restore(o, copy = true)
        frozen = true
        val fx = pts[0]; val fy = pts[1]; val ex = pts[pn - 3]; val ey = pts[pn - 2]
        if (hypot(ex - fx, ey - fy) < 0.3f * diag && len > 2.2f * diag) {
            val cx = (l + r) / 2; val cy = (tp + b) / 2; val rx = (r - l) / 2; val ry = (b - tp) / 2
            lx = cx + rx; ly = cy; lpr = pr; dabAt(lx, ly, pr); nextD = spacing(pr)
            for (k in 1..96) { val a = 2 * PI * k / 96; segmentTo((cx + rx * cos(a)).toFloat(), (cy + ry * sin(a)).toFloat(), pr) }
        } else {
            lx = fx; ly = fy; lpr = pr; dabAt(fx, fy, pr); nextD = spacing(pr)
            segmentTo(ex, ey, pr)
        }
        invalidate(); onSnap?.invoke()
    }

    /** Puts the tiles back as they were before [o]; [copy] keeps the record usable for later undo. */
    private fun restore(o: TileOp, copy: Boolean) {
        for ((i, b) in o.before) { o.l.tiles[i] = if (copy) b?.copy(Bitmap.Config.ARGB_8888, true) else b; o.l.dirty += i }
    }

    fun colorAt(x: Float, y: Float): Int {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.translate(-x, -y)
        art.composite(c, RectF(x, y, x + 1, y + 1))
        val px = b.getPixel(0, 0); b.recycle()
        return px or 0xFF000000.toInt()
    }

    // ---- ColorDrop fill ----

    /** Flood-fills the area under the screen point on the active layer, looking at all visible layers for edges. */
    fun fillAt(sx: Float, sy: Float, done: (Boolean) -> Unit) {
        val q = toCanvas(sx, sy); val x0 = q[0].toInt(); val y0 = q[1].toInt()
        if (x0 !in 0 until art.w || y0 !in 0 until art.h || !active.visible) { done(false); return }
        val comp = art.flatten()
        val w = art.w; val h = art.h; val fillColor = color
        bg.execute {
            val px = IntArray(w * h); comp.getPixels(px, 0, w, 0, 0, w, h); comp.recycle()
            val seed = px[y0 * w + x0]
            fun near(c: Int): Boolean {
                var s = 0
                for (sh in intArrayOf(24, 16, 8, 0)) s += abs(((c ushr sh) and 0xFF) - ((seed ushr sh) and 0xFF))
                return s <= 48
            }
            val mask = BooleanArray(w * h)
            val stack = IntArray(w * h); var sp = 0
            stack[sp++] = y0 * w + x0; mask[y0 * w + x0] = true
            var minX = x0; var maxX = x0; var minY = y0; var maxY = y0
            while (sp > 0) {
                val i = stack[--sp]; val x = i % w; val y = i / w
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && !mask[i - 1] && near(px[i - 1])) { mask[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && !mask[i + 1] && near(px[i + 1])) { mask[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && !mask[i - w] && near(px[i - w])) { mask[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && !mask[i + w] && near(px[i + w])) { mask[i + w] = true; stack[sp++] = i + w }
            }
            // grow one pixel so the fill tucks under anti-aliased line edges
            val bw = maxX - minX + 3; val bh = maxY - minY + 3; val ox = minX - 1; val oy = minY - 1
            val out = IntArray(bw * bh)
            for (y in minY..maxY) for (x in minX..maxX) if (mask[y * w + x]) {
                for (yy in -1..1) for (xx in -1..1) out[(y - oy + yy) * bw + (x - ox + xx)] = fillColor
            }
            val fb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); fb.setPixels(out, 0, bw, 0, 0, bw, bh)
            post {
                val o = TileOp(active)
                val p = Paint().apply { if (active.alphaLock) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
                art.forTiles(RectF(ox.toFloat(), oy.toFloat(), (ox + bw).toFloat(), (oy + bh).toFloat())) { i ->
                    if (active.alphaLock && active.tiles[i] == null) return@forTiles
                    touch(o, i)
                    val tc = canvasFor(art.ensure(active, i))
                    tc.save(); tc.translate(-art.tx(i).toFloat(), -art.ty(i).toFloat()); clip(tc)
                    tc.drawBitmap(fb, ox.toFloat(), oy.toFloat(), p); tc.restore()
                    active.dirty += i
                }
                fb.recycle(); push(o); onChange?.invoke(); invalidate(); done(true)
            }
        }
    }

    // ---- selection ----

    private fun draftPath(): Path? = when (selMode) {
        FREE -> if (selDraft.size < 6) null else Path().apply { moveTo(selDraft[0], selDraft[1]); for (k in 2 until selDraft.size step 2) lineTo(selDraft[k], selDraft[k + 1]); close() }
        RECT -> Path().apply { addRect(min(selX0, selX1), min(selY0, selY1), max(selX0, selX1), max(selY0, selY1), Path.Direction.CW) }
        else -> Path().apply { addOval(min(selX0, selX1), min(selY0, selY1), max(selX0, selX1), max(selY0, selY1), Path.Direction.CW) }
    }

    private fun selectEnd(keep: Boolean) {
        selecting = false
        val moved = hypot(selX1 - selX0, selY1 - selY0) * viewScale > 6 * d
        if (keep && moved) { selPath = draftPath(); selInvert = false } else selPath = null
        onSelection?.invoke(selPath != null); invalidate()
    }

    fun invertSelection() { if (selPath != null) { selInvert = !selInvert; invalidate() } }
    fun clearSelection() { if (selPath != null) { selPath = null; selInvert = false; onSelection?.invoke(false); invalidate() } }
    fun selectAll() { selPath = Path().apply { addRect(art.rect, Path.Direction.CW) }; selInvert = false; onSelection?.invoke(true); invalidate() }

    private fun selBounds(): RectF? = selPath?.let { s ->
        if (selInvert) RectF(art.rect) else RectF().also { s.computeBounds(it, true); if (!it.intersect(art.rect)) return null }
    }

    /** Pixels of [l] inside [r] (and the selection, if any) as a bitmap. */
    private fun extract(l: ALayer, r: Rect): Bitmap {
        val b = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.translate(-r.left.toFloat(), -r.top.toFloat()); clip(c)
        art.drawLayer(c, l, null, RectF(r))
        return b
    }

    fun clearSelectedPixels() {
        val r = selBounds() ?: return
        val o = TileOp(active)
        art.forTiles(r) { i ->
            val t = active.tiles[i] ?: return@forTiles
            touch(o, i); val tc = canvasFor(t)
            tc.save(); tc.translate(-art.tx(i).toFloat(), -art.ty(i).toFloat()); clip(tc); tc.drawPaint(clearP); tc.restore()
            active.dirty += i
        }
        push(o); onChange?.invoke(); invalidate()
    }

    /** Copies the selection (or the whole layer) into a new layer above. Returns null when it worked, else why not. */
    fun copyToNewLayer(): String? {
        if (art.layers.size >= maxLayers) return "Layer limit reached for this canvas size"
        val r = (selBounds() ?: RectF(art.rect)).let { Rect(it.left.toInt(), it.top.toInt(), kotlin.math.ceil(it.right).toInt(), kotlin.math.ceil(it.bottom).toInt()) }
        if (r.isEmpty || art.tightBounds(active)?.let { Rect.intersects(it, r) } != true) return "Nothing selected on this layer"
        val bmp = extract(active, r)
        val b = snap()
        val nl = art.newLayer(active.name + " copy")
        val pnt = Paint()
        art.forTiles(RectF(r)) { i -> canvasFor(art.ensure(nl, i)).drawBitmap(bmp, (r.left - art.tx(i)).toFloat(), (r.top - art.ty(i)).toFloat(), pnt) }
        bmp.recycle(); nl.markAllDirty()
        art.layers.add(art.layers.indexOf(active) + 1, nl); active = nl
        clearSelection(); commit(b)
        return null
    }

    // ---- transform ----

    /** Lifts the selection (or the whole layer) off the canvas so it can be moved, scaled and rotated. */
    fun beginTransform(): Boolean {
        if (floating != null) return true
        val r = selBounds()?.let { Rect(it.left.toInt(), it.top.toInt(), kotlin.math.ceil(it.right).toInt(), kotlin.math.ceil(it.bottom).toInt()) } ?: art.tightBounds(active) ?: return false
        if (r.isEmpty) return false
        val bmp = extract(active, r)
        val o = TileOp(active)
        art.forTiles(RectF(r)) { i ->
            val t = active.tiles[i] ?: return@forTiles
            touch(o, i); val tc = canvasFor(t)
            tc.save(); tc.translate(-art.tx(i).toFloat(), -art.ty(i).toFloat()); tc.clipRect(r); clip(tc); tc.drawPaint(clearP); tc.restore()
            active.dirty += i
        }
        floatOp = o; floating = bmp
        floatM.setTranslate(r.left.toFloat(), r.top.toFloat())
        selPath = null; selInvert = false; onSelection?.invoke(false)
        invalidate(); onChange?.invoke()
        return true
    }

    /** Places [bmp] floating on the active layer (inserted photos). */
    fun beginTransformWith(bmp: Bitmap) {
        floatOp = TileOp(active); floating = bmp
        val s = min(1f, min(art.w * 0.7f / bmp.width, art.h * 0.7f / bmp.height))
        floatM.setScale(s, s); floatM.postTranslate((art.w - bmp.width * s) / 2, (art.h - bmp.height * s) / 2)
        invalidate(); onChange?.invoke()
    }

    fun applyTransform() {
        val f = floating ?: return; val o = floatOp ?: return
        val r = RectF(0f, 0f, f.width.toFloat(), f.height.toFloat()); floatM.mapRect(r)
        if (r.intersect(art.rect)) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            art.forTiles(r) { i ->
                touch(o, i); val tc = canvasFor(art.ensure(active, i))
                tc.save(); tc.translate(-art.tx(i).toFloat(), -art.ty(i).toFloat()); tc.concat(floatM); tc.drawBitmap(f, 0f, 0f, p); tc.restore()
                active.dirty += i
            }
        }
        f.recycle(); floating = null; floatOp = null
        if (o.before.isNotEmpty()) push(o)
        onChange?.invoke(); invalidate()
    }

    fun cancelTransform() {
        floatOp?.let { restore(it, copy = false) }
        floating?.recycle(); floating = null; floatOp = null
        onChange?.invoke(); invalidate()
    }

    private fun floatCenter(): FloatArray { val f = floating!!; val c = floatArrayOf(f.width / 2f, f.height / 2f); floatM.mapPoints(c); return c }
    fun flipFloat(horizontal: Boolean) { if (floating == null) return; val c = floatCenter(); floatM.postScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f, c[0], c[1]); invalidate() }
    fun rotateFloat(deg: Float) { if (floating == null) return; val c = floatCenter(); floatM.postRotate(deg, c[0], c[1]); invalidate() }

    private fun transformDown(sx: Float, sy: Float, cx: Float, cy: Float) {
        val f = floating ?: return
        val p = corners(f); val k = knobPos(p)
        tfMode = when {
            hypot(sx - k[0], sy - k[1]) < 28 * d -> 3
            (0 until 4).any { hypot(sx - p[it * 2], sy - p[it * 2 + 1]) < 28 * d } -> 2
            else -> {
                val inv = Matrix(); floatM.invert(inv); val q = floatArrayOf(cx, cy); inv.mapPoints(q)
                if (q[0] in 0f..f.width.toFloat() && q[1] in 0f..f.height.toFloat()) 1 else 0
            }
        }
        tfX = cx; tfY = cy
    }

    private fun transformMove(cx: Float, cy: Float) {
        val c = floatCenter()
        when (tfMode) {
            1 -> floatM.postTranslate(cx - tfX, cy - tfY)
            2 -> { val s = hypot(cx - c[0], cy - c[1]) / max(1f, hypot(tfX - c[0], tfY - c[1])); floatM.postScale(s, s, c[0], c[1]) }
            3 -> floatM.postRotate(Math.toDegrees((atan2(cy - c[1], cx - c[0]) - atan2(tfY - c[1], tfX - c[0])).toDouble()).toFloat(), c[0], c[1])
        }
        tfX = cx; tfY = cy; invalidate()
    }

    // ---- adjustments ----

    /** Bakes [cf] into every painted tile of the active layer. */
    fun bakeFilter(cf: ColorFilter) {
        val o = TileOp(active); val p = Paint().apply { colorFilter = cf }
        for (i in 0 until art.n) {
            val t = active.tiles[i] ?: continue
            val nb = Bitmap.createBitmap(TS, TS, Bitmap.Config.ARGB_8888)
            Canvas(nb).drawBitmap(t, 0f, 0f, p)
            o.before[i] = t; active.tiles[i] = nb; active.dirty += i // the old tile moves into the undo record, no copy
        }
        preview = null
        if (o.before.isNotEmpty()) push(o)
        onChange?.invoke(); invalidate()
    }

    /** Replaces the active layer's pixels with [full] (canvas-sized), recording undo. */
    fun setLayerPixels(l: ALayer, full: Bitmap, o: TileOp = TileOp(l), pushIt: Boolean = true): TileOp {
        val px = IntArray(TS * TS)
        for (i in 0 until art.n) {
            val nb = Bitmap.createBitmap(TS, TS, Bitmap.Config.ARGB_8888)
            Canvas(nb).drawBitmap(full, -art.tx(i).toFloat(), -art.ty(i).toFloat(), null)
            nb.getPixels(px, 0, TS, 0, 0, TS, TS)
            val empty = px.all { it ushr 24 == 0 }
            if (empty && l.tiles[i] == null) { nb.recycle(); continue }
            if (!o.before.containsKey(i)) o.before[i] = l.tiles[i]
            l.tiles[i] = if (empty) null.also { nb.recycle() } else nb
            l.dirty += i
        }
        if (pushIt && o.before.isNotEmpty()) push(o)
        return o
    }

    fun blur(radius: Int, done: () -> Unit) {
        val l = active; val full = art.layerBitmap(l)
        bg.execute {
            val out = Blur.apply(full, radius); full.recycle()
            post { setLayerPixels(l, out); out.recycle(); onChange?.invoke(); invalidate(); done() }
        }
    }

    fun flipCanvas(horizontal: Boolean) {
        val ops = ArrayList<AOp>()
        val m = Matrix().apply { setScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f, art.w / 2f, art.h / 2f) }
        for (l in art.layers) {
            val src = art.layerBitmap(l)
            val dst = Bitmap.createBitmap(art.w, art.h, Bitmap.Config.ARGB_8888)
            Canvas(dst).drawBitmap(src, m, null); src.recycle()
            ops += setLayerPixels(l, dst, pushIt = false); dst.recycle()
        }
        push(Multi(ops)); onChange?.invoke(); invalidate()
    }

    // ---- layers ----

    fun snap() = Snap(art.layers.toList(), art.layers.map { LP(it.name, it.visible, it.opacity, it.blend, it.alphaLock) }, art.bg, art.bgVisible, active.id)

    private fun applySnap(s: Snap) {
        val had = art.layers.toSet()
        art.layers.clear(); art.layers.addAll(s.layers)
        s.layers.forEachIndexed { k, l ->
            val p = s.props[k]; l.name = p.name; l.visible = p.visible; l.opacity = p.opacity; l.blend = p.blend; l.alphaLock = p.lock
            if (l !in had) l.markAllDirty() // its tile files may have been cleaned up while it was deleted
        }
        art.bg = s.bg; art.bgVisible = s.bgVis
        active = art.layers.firstOrNull { it.id == s.active } ?: art.layers.last()
    }

    /** Records a structural change made since [before] as one undo step. */
    fun commit(before: Snap) { push(Struct(before, snap())); onChange?.invoke(); invalidate() }

    fun addLayer(): Boolean {
        if (art.layers.size >= maxLayers) return false
        val b = snap(); val l = art.newLayer()
        art.layers.add(art.layers.indexOf(active) + 1, l); active = l
        commit(b); return true
    }

    fun deleteLayer(l: ALayer) {
        if (art.layers.size <= 1) return
        val b = snap(); val idx = art.layers.indexOf(l)
        art.layers.remove(l)
        if (active === l) active = art.layers[max(0, idx - 1)]
        commit(b)
    }

    fun duplicateLayer(l: ALayer): Boolean {
        if (art.layers.size >= maxLayers) return false
        val b = snap(); val nl = art.newLayer(l.name + " copy")
        for (i in 0 until art.n) nl.tiles[i] = l.tiles[i]?.copy(Bitmap.Config.ARGB_8888, true)
        nl.visible = l.visible; nl.opacity = l.opacity; nl.blend = l.blend; nl.alphaLock = l.alphaLock; nl.markAllDirty()
        art.layers.add(art.layers.indexOf(l) + 1, nl); active = nl
        commit(b); return true
    }

    fun mergeDown(l: ALayer): Boolean {
        val idx = art.layers.indexOf(l)
        if (idx <= 0) return false
        val lower = art.layers[idx - 1]
        val b = snap(); val o = TileOp(lower)
        val p = art.layerPaint(l)
        for (i in 0 until art.n) {
            val t = l.tiles[i] ?: continue
            touch(o, i); canvasFor(art.ensure(lower, i)).drawBitmap(t, 0f, 0f, p); lower.dirty += i
        }
        art.layers.remove(l); active = lower
        push(Multi(listOf(o, Struct(b, snap())))); onChange?.invoke(); invalidate()
        return true
    }

    fun moveLayer(l: ALayer, dir: Int) {
        val i = art.layers.indexOf(l); val j = i + dir
        if (j !in art.layers.indices) return
        val b = snap(); art.layers.add(j, art.layers.removeAt(i)); commit(b)
    }

    fun clearLayer(l: ALayer) {
        val o = TileOp(l)
        for (i in 0 until art.n) { if (l.tiles[i] == null) continue; o.before[i] = l.tiles[i]; l.tiles[i] = null; l.dirty += i }
        if (o.before.isNotEmpty()) push(o)
        onChange?.invoke(); invalidate()
    }

    fun fillLayer(l: ALayer, c: Int) {
        val o = TileOp(l)
        val p = Paint().apply { color = c; if (l.alphaLock) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
        for (i in 0 until art.n) {
            if (l.alphaLock && l.tiles[i] == null) continue
            touch(o, i); val tc = canvasFor(art.ensure(l, i))
            tc.save(); tc.translate(-art.tx(i).toFloat(), -art.ty(i).toFloat()); clip(tc); tc.drawRect(art.rect, p); tc.restore()
            l.dirty += i
        }
        push(o); onChange?.invoke(); invalidate()
    }

    // ---- history ----

    private fun push(o: AOp) {
        undoS += o; redoS.clear()
        fun tiles(a: AOp): Int = when (a) { is TileOp -> a.before.size; is Multi -> a.ops.sumOf { tiles(it) }; else -> 0 }
        while (undoS.size > 1 && (undoS.size > 50 || undoS.sumOf { tiles(it) } > UNDO_TILES)) undoS.removeAt(0)
    }

    fun undo() {
        if (floating != null) { cancelTransform(); return }
        val o = undoS.removeLastOrNull() ?: return
        revert(o); redoS += o; onChange?.invoke(); invalidate()
    }

    fun redo() {
        val o = redoS.removeLastOrNull() ?: return
        reapply(o); undoS += o; onChange?.invoke(); invalidate()
    }

    private fun revert(o: AOp) {
        when (o) {
            is TileOp -> { val a = HashMap<Int, Bitmap?>(); for ((i, b) in o.before) { a[i] = o.l.tiles[i]; o.l.tiles[i] = b; o.l.dirty += i }; o.after = a }
            is Struct -> applySnap(o.before)
            is Multi -> o.ops.asReversed().forEach { revert(it) }
        }
    }

    private fun reapply(o: AOp) {
        when (o) {
            is TileOp -> o.after?.forEach { (i, a) -> o.l.tiles[i] = a; o.l.dirty += i }
            is Struct -> applySnap(o.after)
            is Multi -> o.ops.forEach { reapply(it) }
        }
    }
}
