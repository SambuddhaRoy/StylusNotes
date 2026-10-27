package app.stylusnotes

import android.graphics.*
import android.graphics.pdf.PdfDocument
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Note item renderer. One instance per thread (it owns Paints). */
class Painter {
    companion object {
        val PAPERS = intArrayOf(0xFFFBF8F0.toInt(), 0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt())
        private val LINES = intArrayOf(0xFFDAD3C2.toInt(), 0xFFE3E3E8.toInt(), 0xFF3A3A3C.toInt())
        const val STEP = 40f
        private const val NIB = -0.7f // calligraphy nib angle, radians
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rough = DiscretePathEffect(3f, 0.8f)

    /** Line width for the pressure brushes; (dx,dy) is the travel direction, used by the angled nib. */
    fun width(kind: Int, size: Float, pr: Float, dx: Float = 0f, dy: Float = 0f): Float = when (kind) {
        Stroke.FOUNTAIN -> size * (0.1f + 1.1f * pr * pr)
        Stroke.CALLI -> size * (0.12f + 0.88f * abs(sin(atan2(dy, dx) - NIB))) * (0.7f + 0.3f * pr)
        else -> size * (0.25f + 0.75f * pr)
    }

    private fun seg(c: Canvas, col: Int, w: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
        p.color = col; p.strokeWidth = w; c.drawLine(x0, y0, x1, y1, p)
    }

    fun dot(c: Canvas, kind: Int, col: Int, size: Float, x: Float, y: Float, pr: Float) {
        p.color = col; p.strokeWidth = width(kind, size, pr); c.drawPoint(x, y, p)
    }

    /**
     * Highlighter, marker and pencil: one path, one alpha, so overlaps inside a stroke don't darken.
     * Smoothed with quadratic curves through the midpoints.
     */
    fun pathStroke(c: Canvas, kind: Int, col: Int, size: Float, a: FloatArray, n: Int) {
        path.rewind(); path.moveTo(a[0], a[1])
        if (n == 1) path.lineTo(a[0], a[1])
        else {
            for (i in 1 until n - 1)
                path.quadTo(a[i * 3], a[i * 3 + 1], (a[i * 3] + a[i * 3 + 3]) / 2, (a[i * 3 + 1] + a[i * 3 + 4]) / 2)
            path.lineTo(a[n * 3 - 3], a[n * 3 - 2])
        }
        var alpha = 0xFF; var w = size * 1.5f
        when (kind) {
            Stroke.HI -> { alpha = 0x70; w = size * 4 }
            Stroke.PENCIL -> { // lighter touch = fainter line
                var s = 0f; for (i in 0 until n) s += a[i * 3 + 2]
                alpha = (80 + 120 * s / n).toInt().coerceIn(0, 255); w = max(1f, size * 0.6f); p.pathEffect = rough
            }
        }
        p.color = (col and 0xFFFFFF) or (alpha shl 24); p.strokeWidth = w
        c.drawPath(path, p)
        p.pathEffect = null
    }

    /**
     * Pressure-brush smoothing: sample i (1..n-2) is the control point of a curve from the midpoint before it
     * to the midpoint after it, so corners round off without the line lagging behind the pen.
     * A stroke is pieces 1..n-2 followed by [tail].
     */
    fun piece(c: Canvas, kind: Int, col: Int, size: Float, a: FloatArray, i: Int) {
        val j = i * 3
        val sx = if (i == 1) a[0] else (a[j - 3] + a[j]) / 2
        val sy = if (i == 1) a[1] else (a[j - 2] + a[j + 1]) / 2
        val ex = (a[j] + a[j + 3]) / 2; val ey = (a[j + 1] + a[j + 4]) / 2
        path.rewind(); path.moveTo(sx, sy); path.quadTo(a[j], a[j + 1], ex, ey)
        p.color = col; p.strokeWidth = width(kind, size, a[j + 2], ex - sx, ey - sy)
        c.drawPath(path, p)
    }

    /** Last stretch, from the final midpoint to the last sample. */
    fun tail(c: Canvas, kind: Int, col: Int, size: Float, a: FloatArray, n: Int) {
        val j = n * 3 - 3
        if (n == 2) seg(c, col, width(kind, size, (a[2] + a[5]) / 2, a[3] - a[0], a[4] - a[1]), a[0], a[1], a[3], a[4])
        else {
            val sx = (a[j - 3] + a[j]) / 2; val sy = (a[j - 2] + a[j + 1]) / 2
            seg(c, col, width(kind, size, a[j + 2], a[j] - sx, a[j + 1] - sy), sx, sy, a[j], a[j + 1])
        }
    }

    fun drawStroke(c: Canvas, s: Stroke) {
        val a = s.pts
        when {
            Stroke.isPath(s.kind) -> pathStroke(c, s.kind, s.color, s.width, a, s.n)
            s.n == 1 -> dot(c, s.kind, s.color, s.width, a[0], a[1], a[2])
            else -> {
                for (i in 1..s.n - 2) piece(c, s.kind, s.color, s.width, a, i)
                tail(c, s.kind, s.color, s.width, a, s.n)
            }
        }
    }

    fun item(c: Canvas, it: Item) {
        when (it) {
            is Stroke -> drawStroke(c, it)
            is Label -> {
                tp.color = it.color; tp.textSize = it.size
                it.text.split('\n').forEachIndexed { i, line -> c.drawText(line, it.x, it.y + it.size * 0.85f + i * it.lineHeight, tp) }
            }
            is Pic -> it.src.bitmap?.let { b -> c.drawBitmap(b, null, it.bounds, bp) }
            is MathItem -> {
                val d = it.drawable()
                c.save(); c.translate(it.x, it.y)
                d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight); d.draw(c)
                c.restore()
            }
        }
    }

    /** bg: 0 blank, 1 lined, 2 grid, 3 dots. [v] is the visible page rect. */
    fun background(c: Canvas, bg: Int, paper: Int, v: RectF, scale: Float) {
        if (bg == 0 || STEP * scale < 14) return
        p.color = LINES[paper]
        val x0 = floor(v.left / STEP) * STEP
        var y = floor(v.top / STEP) * STEP
        if (bg == 3) {
            p.strokeWidth = 3f / scale
            while (y < v.bottom) {
                var x = x0
                while (x < v.right) { c.drawPoint(x, y, p); x += STEP }
                y += STEP
            }
            return
        }
        p.strokeWidth = 1.2f / scale
        while (y < v.bottom) { c.drawLine(v.left, y, v.right, y, p); y += STEP }
        if (bg == 2) {
            var x = x0
            while (x < v.right) { c.drawLine(x, v.top, x, v.bottom, p); x += STEP }
        }
    }

    /** Paper, background pattern, then visible layers bottom to top. [skip] items are left out (live drag). */
    fun scene(c: Canvas, items: List<Item>, layers: List<Layer>, paper: Int, bg: Int, r: RectF, scale: Float, skip: Set<Item>?) {
        c.drawColor(PAPERS[paper])
        background(c, bg, paper, r, scale)
        for (l in layers) if (l.visible)
            for (s in items) if (s.layer == l.id && RectF.intersects(s.bounds, r) && (skip == null || s !in skip)) item(c, s)
    }

    private fun content(items: List<Item>): RectF? {
        val r = RectF()
        for (s in items) if (r.isEmpty) r.set(s.bounds) else r.union(s.bounds)
        return if (r.isEmpty) null else r
    }

    private fun render(items: List<Item>, layers: List<Layer>, paper: Int, bg: Int, r: RectF, sc: Float, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(sc, sc); c.translate(-r.left, -r.top)
        scene(c, items, layers, paper, bg, r, sc, null)
        return b
    }

    /** Thumbnail: content centred in a w×h frame, never zoomed past 1.5×. */
    fun fit(n: Note, w: Int, h: Int): Bitmap {
        val r = content(n.items) ?: RectF(0f, 0f, 640f, 480f)
        r.inset(-30f, -30f)
        val ar = w.toFloat() / h
        if (r.width() / r.height() > ar) r.inset(0f, -(r.width() / ar - r.height()) / 2)
        else r.inset(-(r.height() * ar - r.width()) / 2, 0f)
        val minW = w / 1.5f
        if (r.width() < minW) r.inset(-(minW - r.width()) / 2, -(minW / ar - r.height()) / 2)
        return render(n.items, n.layers, n.paper, n.bg, r, w / r.width(), w, h)
    }

    /** Bitmap of [items] cropped to their bounds, at most [maxSide] px; null when there's nothing. */
    fun export(items: List<Item>, layers: List<Layer>, paper: Int, bg: Int, maxSide: Float = 3000f, maxScale: Float = 2f): Bitmap? {
        val r = content(items) ?: return null
        r.inset(-40f, -40f)
        val sc = min(maxScale, maxSide / max(r.width(), r.height()))
        return render(items, layers, paper, bg, r, sc, (r.width() * sc).toInt(), (r.height() * sc).toInt())
    }

    fun export(n: Note) = export(n.items, n.layers, n.paper, n.bg)

    /** Vector PDF, one page sized to the content; null for an empty page. */
    fun pdf(n: Note): PdfDocument? {
        val r = content(n.items) ?: return null
        r.inset(-40f, -40f)
        val d = PdfDocument()
        val pg = d.startPage(PdfDocument.PageInfo.Builder(r.width().toInt(), r.height().toInt(), 1).create())
        pg.canvas.translate(-r.left, -r.top)
        scene(pg.canvas, n.items, n.layers, n.paper, n.bg, r, 1f, null)
        d.finishPage(pg)
        return d
    }
}
