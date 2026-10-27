package app.stylusnotes

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import java.io.*
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Tile edge. Layers are grids of these, allocated only where something has been painted. */
const val TS = 256

class ALayer(val id: Int, var name: String, n: Int) {
    val tiles = arrayOfNulls<Bitmap>(n)
    var visible = true
    var opacity = 1f
    var blend = 0
    var alphaLock = false
    /** Tiles changed since the last save. */
    val dirty = HashSet<Int>()
    fun markAllDirty() { for (i in tiles.indices) if (tiles[i] != null) dirty += i }
}

object Blend {
    val names = arrayOf("Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "Add", "Color Burn", "Color Dodge", "Difference")
    val psdKeys = arrayOf("norm", "mul ", "scrn", "over", "dark", "lite", "lddg", "idiv", "div ", "diff")

    fun apply(p: Paint, i: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            p.blendMode = if (i == 0) null else arrayOf(
                BlendMode.SRC_OVER, BlendMode.MULTIPLY, BlendMode.SCREEN, BlendMode.OVERLAY, BlendMode.DARKEN,
                BlendMode.LIGHTEN, BlendMode.PLUS, BlendMode.COLOR_BURN, BlendMode.COLOR_DODGE, BlendMode.DIFFERENCE,
            )[i]
        } else {
            val m = arrayOf(
                PorterDuff.Mode.SRC_OVER, PorterDuff.Mode.MULTIPLY, PorterDuff.Mode.SCREEN, PorterDuff.Mode.OVERLAY, PorterDuff.Mode.DARKEN,
                PorterDuff.Mode.LIGHTEN, PorterDuff.Mode.ADD, PorterDuff.Mode.MULTIPLY, PorterDuff.Mode.SCREEN, PorterDuff.Mode.XOR,
            )[i]
            p.xfermode = if (i == 0) null else PorterDuffXfermode(m)
        }
    }
}

/** A raster drawing: a background colour plus a stack of tiled layers. */
class Art(val id: String, val w: Int, val h: Int) {
    var title = ""
    var modified = 0L
    var bg = Color.WHITE
    var bgVisible = true
    val tw = (w + TS - 1) / TS
    val th = (h + TS - 1) / TS
    val n = tw * th
    val layers = ArrayList<ALayer>()
    var nextId = 0
    val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

    fun newLayer(name: String = "Layer ${nextId + 1}") = ALayer(nextId++, name, n)
    fun tx(i: Int) = (i % tw) * TS
    fun ty(i: Int) = (i / tw) * TS
    fun ensure(l: ALayer, i: Int) = l.tiles[i] ?: Bitmap.createBitmap(TS, TS, Bitmap.Config.ARGB_8888).also { l.tiles[i] = it }

    /** Calls [f] with every tile index overlapping [r] (canvas coords). */
    inline fun forTiles(r: RectF, f: (Int) -> Unit) {
        val x0 = max(0, floor(r.left / TS).toInt()); val x1 = min(tw - 1, floor(r.right / TS).toInt())
        val y0 = max(0, floor(r.top / TS).toInt()); val y1 = min(th - 1, floor(r.bottom / TS).toInt())
        for (y in y0..y1) for (x in x0..x1) f(y * tw + x)
    }

    fun drawLayer(c: Canvas, l: ALayer, p: Paint?, r: RectF = rect) = forTiles(r) { i ->
        l.tiles[i]?.let { c.drawBitmap(it, tx(i).toFloat(), ty(i).toFloat(), p) }
    }

    fun layerPaint(l: ALayer, p: Paint = Paint(Paint.FILTER_BITMAP_FLAG)) = p.apply {
        alpha = (l.opacity * 255).toInt(); Blend.apply(this, l.blend)
    }

    private val bgPaint = Paint()

    /** Background then visible layers, bottom to top. */
    fun composite(c: Canvas, r: RectF = rect, withBg: Boolean = true) {
        if (withBg && bgVisible) { bgPaint.color = bg; c.drawRect(rect, bgPaint) }
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in layers) if (l.visible) drawLayer(c, l, layerPaint(l, p), r)
    }

    fun flatten(scale: Float = 1f, withBg: Boolean = true): Bitmap {
        val b = Bitmap.createBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.scale(scale, scale)
        if (withBg && bgVisible) { bgPaint.color = bg; c.drawRect(rect, bgPaint) }
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in layers) if (l.visible) drawLayer(c, l, layerPaint(l, p))
        return b
    }

    fun layerBitmap(l: ALayer): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawLayer(Canvas(b), l, null)
        return b
    }

    fun thumb(l: ALayer, tw: Int, th: Int): Bitmap {
        val s = min(tw.toFloat() / w, th.toFloat() / h)
        val b = Bitmap.createBitmap(max(1, (w * s).toInt()), max(1, (h * s).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.scale(s, s)
        drawLayer(c, l, Paint(Paint.FILTER_BITMAP_FLAG))
        return b
    }

    /** Pixel-tight bounds of what's painted on [l], or null when it's empty. */
    fun tightBounds(l: ALayer): Rect? {
        val px = IntArray(TS * TS)
        var r: Rect? = null
        for (i in 0 until n) {
            val t = l.tiles[i] ?: continue
            t.getPixels(px, 0, TS, 0, 0, TS, TS)
            var x0 = TS; var y0 = TS; var x1 = -1; var y1 = -1
            for (y in 0 until TS) for (x in 0 until TS) if (px[y * TS + x] ushr 24 != 0) {
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x1 < 0) continue
            val tr = Rect(tx(i) + x0, ty(i) + y0, tx(i) + x1 + 1, ty(i) + y1 + 1)
            if (r == null) r = tr else r.union(tr)
        }
        r?.intersect(0, 0, w, h)
        return r
    }
}

/** A drawing is a folder: `meta` plus one PNG per painted tile, so a save only rewrites the tiles that changed. */
object ArtIO {
    private const val MAGIC = 0x41525431

    private fun root(c: Context) = File(c.filesDir, "art").apply { mkdirs() }
    fun dir(c: Context, id: String) = File(root(c), id)

    fun list(c: Context): List<Doc> = root(c).listFiles().orEmpty().mapNotNull { d ->
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(File(d, "meta")))).use { i ->
                require(i.readInt() == MAGIC)
                Doc(d.name, true, i.readUTF(), i.readLong(), File(d, "thumb.png"))
            }
        }.getOrNull()
    }

    private fun writeMeta(o: DataOutputStream, a: Art) {
        o.writeInt(MAGIC); o.writeUTF(a.title); o.writeLong(a.modified); o.writeInt(a.w); o.writeInt(a.h)
        o.writeInt(a.bg); o.writeBoolean(a.bgVisible); o.writeInt(a.nextId); o.writeInt(a.layers.size)
        for (l in a.layers) {
            o.writeInt(l.id); o.writeUTF(l.name); o.writeBoolean(l.visible); o.writeFloat(l.opacity); o.writeInt(l.blend); o.writeBoolean(l.alphaLock)
        }
    }

    /** Structure only, no pixels. */
    private fun loadMeta(d: File): Art = DataInputStream(BufferedInputStream(FileInputStream(File(d, "meta")))).use { i ->
        require(i.readInt() == MAGIC) { "bad drawing" }
        val title = i.readUTF(); val mod = i.readLong(); val w = i.readInt(); val h = i.readInt()
        Art(d.name, w, h).apply {
            this.title = title; modified = mod; bg = i.readInt(); bgVisible = i.readBoolean(); nextId = i.readInt()
            repeat(i.readInt()) {
                layers += ALayer(i.readInt(), i.readUTF(), n).apply { visible = i.readBoolean(); opacity = i.readFloat(); blend = i.readInt(); alphaLock = i.readBoolean() }
            }
        }
    }

    fun load(c: Context, id: String): Art {
        val d = dir(c, id)
        val a = loadMeta(d)
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val byId = a.layers.associateBy { it.id }
        for (f in d.listFiles().orEmpty()) {
            val m = Regex("L(\\d+)_(\\d+)\\.png").matchEntire(f.name) ?: continue
            val l = byId[m.groupValues[1].toInt()] ?: continue
            val i = m.groupValues[2].toInt()
            if (i < a.n) l.tiles[i] = BitmapFactory.decodeFile(f.path, opts)
        }
        return a
    }

    /** Copies changed tiles on the caller's thread, writes them on the io thread. */
    fun save(c: Context, a: Art) {
        a.modified = System.currentTimeMillis()
        val meta = ByteArrayOutputStream().also { writeMeta(DataOutputStream(it), a) }.toByteArray()
        val tiles = ArrayList<Pair<String, Bitmap?>>()
        for (l in a.layers) { for (i in l.dirty) tiles += "L${l.id}_$i.png" to l.tiles[i]?.copy(Bitmap.Config.ARGB_8888, false); l.dirty.clear() }
        val ids = a.layers.map { it.id }.toSet()
        val thumb = a.flatten(min(480f / a.w, 360f / a.h))
        val d = dir(c, a.id)
        Store.io.execute {
            runCatching {
                d.mkdirs()
                for ((name, b) in tiles) {
                    val f = File(d, name)
                    if (b == null) { f.delete(); continue }
                    val tmp = File(d, "$name.tmp")
                    FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                    check(tmp.renameTo(f)) { "rename failed" }
                }
                val tmp = File(d, "meta.tmp"); tmp.writeBytes(meta); check(tmp.renameTo(File(d, "meta")))
                // tiles of layers that no longer exist
                for (f in d.listFiles().orEmpty()) {
                    val m = Regex("L(\\d+)_\\d+\\.png").matchEntire(f.name) ?: continue
                    if (m.groupValues[1].toInt() !in ids) f.delete()
                }
                FileOutputStream(File(d, "thumb.png")).use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
                thumb.recycle()
            }.onFailure { Log.e("ArtIO", "save failed", it) }
        }
    }

    fun delete(c: Context, id: String) { dir(c, id).deleteRecursively() }

    /** Runs on the io thread. */
    fun duplicate(c: Context, id: String, newId: String) {
        val src = dir(c, id); val dst = dir(c, newId)
        src.copyRecursively(dst, overwrite = true)
        val a = loadMeta(dst)
        a.title = a.title.ifEmpty { "Untitled" } + " copy"; a.modified = System.currentTimeMillis()
        DataOutputStream(FileOutputStream(File(dst, "meta"))).use { writeMeta(it, a) }
    }

    /** Runs on the io thread. */
    fun rename(c: Context, id: String, title: String) {
        val d = dir(c, id); val a = loadMeta(d)
        a.title = title; a.modified = System.currentTimeMillis()
        val tmp = File(d, "meta.tmp")
        DataOutputStream(FileOutputStream(tmp)).use { writeMeta(it, a) }
        tmp.renameTo(File(d, "meta"))
    }

    /** Layered Photoshop file: one raw RGBA layer per layer, bottom first, plus the flattened image. */
    fun psd(a: Art, out: OutputStream) {
        val o = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
        val px = a.w * a.h
        o.writeBytes("8BPS"); o.writeShort(1); o.write(ByteArray(6)); o.writeShort(4)
        o.writeInt(a.h); o.writeInt(a.w); o.writeShort(8); o.writeShort(3)
        o.writeInt(0) // colour mode data
        o.writeInt(0) // image resources
        val names = a.layers.map { it.name.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray() }
        fun nameLen(b: ByteArray) = (1 + b.size + 3) / 4 * 4
        var recs = 2L
        for (nb in names) recs += 16 + 2 + 4 * 6 + 4 + 4 + 4 + 4 + 4 + 4 + nameLen(nb)
        val chans = a.layers.size.toLong() * 4 * (2 + px)
        var info = recs + chans
        if (info % 2 != 0L) info++
        o.writeInt((4 + info + 4).toInt()) // layer & mask section
        o.writeInt(info.toInt())
        o.writeShort(a.layers.size)
        for ((k, l) in a.layers.withIndex()) {
            o.writeInt(0); o.writeInt(0); o.writeInt(a.h); o.writeInt(a.w)
            o.writeShort(4)
            for (ch in intArrayOf(-1, 0, 1, 2)) { o.writeShort(ch); o.writeInt(2 + px) }
            o.writeBytes("8BIM"); o.writeBytes(Blend.psdKeys[l.blend])
            o.write((l.opacity * 255).toInt()); o.write(0); o.write(if (l.visible) 0 else 2); o.write(0)
            val nb = names[k]
            o.writeInt(4 + 4 + nameLen(nb)); o.writeInt(0); o.writeInt(0)
            o.write(nb.size); o.write(nb); o.write(ByteArray(nameLen(nb) - 1 - nb.size))
        }
        val buf = IntArray(px)
        val plane = ByteArray(px)
        fun planes(b: Bitmap) {
            b.getPixels(buf, 0, a.w, 0, 0, a.w, a.h)
            for (shift in intArrayOf(24, 16, 8, 0)) {
                o.writeShort(0)
                for (j in 0 until px) plane[j] = (buf[j] ushr shift).toByte()
                o.write(plane)
            }
        }
        for (l in a.layers) { val b = a.layerBitmap(l); planes(b); b.recycle() }
        if ((recs + chans) % 2 != 0L) o.write(0)
        o.writeInt(0) // global layer mask
        // flattened image: RGBA planes, raw
        val flat = a.flatten()
        flat.getPixels(buf, 0, a.w, 0, 0, a.w, a.h); flat.recycle()
        o.writeShort(0)
        for (shift in intArrayOf(16, 8, 0, 24)) { for (j in 0 until px) plane[j] = (buf[j] ushr shift).toByte(); o.write(plane) }
        o.flush()
    }
}
