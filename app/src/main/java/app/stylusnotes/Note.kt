package app.stylusnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import java.io.*
import kotlin.math.max

/** Anything that lives on a note page. */
abstract class Item {
    var layer = 0
    abstract val bounds: RectF
    abstract fun move(dx: Float, dy: Float)
    abstract fun scale(f: Float, ox: Float, oy: Float)
    abstract fun copy(): Item
}

/** pts = packed x,y,pressure triples in page coordinates. */
class Stroke(val color: Int, val kind: Int, width: Float, val pts: FloatArray, layer: Int = 0) : Item() {
    var width = width; private set
    override val bounds = RectF()
    init { this.layer = layer; measure() }

    val n get() = pts.size / 3

    private fun measure() {
        bounds.set(pts[0], pts[1], pts[0], pts[1])
        for (i in pts.indices step 3) bounds.union(pts[i], pts[i + 1])
        val pad = width * mul(kind) / 2 + 1
        bounds.inset(-pad, -pad)
    }

    override fun move(dx: Float, dy: Float) {
        for (i in pts.indices step 3) { pts[i] += dx; pts[i + 1] += dy }
        bounds.offset(dx, dy)
    }

    override fun scale(f: Float, ox: Float, oy: Float) {
        for (i in pts.indices step 3) { pts[i] = ox + (pts[i] - ox) * f; pts[i + 1] = oy + (pts[i + 1] - oy) * f }
        width *= f; measure()
    }

    override fun copy() = Stroke(color, kind, width, pts.copyOf(), layer)

    companion object {
        const val PEN = 0; const val HI = 1; const val FOUNTAIN = 2
        const val CALLI = 3; const val MARKER = 4; const val PENCIL = 5

        /** Widest the brush gets, relative to its size setting. */
        fun mul(kind: Int) = when (kind) { HI -> 4f; MARKER -> 1.5f; FOUNTAIN -> 1.2f; else -> 1f }

        /** Brushes drawn as one translucent/rough path instead of pressure segments. */
        fun isPath(kind: Int) = kind == HI || kind == MARKER || kind == PENCIL
    }
}

class Label(var text: String, var x: Float, var y: Float, val color: Int, size: Float, layer: Int = 0) : Item() {
    var size = size; private set
    override val bounds = RectF()
    init { this.layer = layer; measure() }

    val lineHeight get() = size * 1.25f

    fun measure() {
        val p = Paint().apply { textSize = size }
        val lines = text.split('\n')
        bounds.set(x, y, x + max(lines.maxOf { p.measureText(it) }, size), y + lines.size * lineHeight)
    }

    override fun move(dx: Float, dy: Float) { x += dx; y += dy; bounds.offset(dx, dy) }
    override fun scale(f: Float, ox: Float, oy: Float) { x = ox + (x - ox) * f; y = oy + (y - oy) * f; size *= f; measure() }
    override fun copy() = Label(text, x, y, color, size, layer)
}

/** Encoded image bytes (already downsized on import), decoded once and shared by copies. */
class PicSrc(val bytes: ByteArray) {
    val bitmap: Bitmap? by lazy { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
}

class Pic(val src: PicSrc, var x: Float, var y: Float, var w: Float, var h: Float, layer: Int = 0) : Item() {
    override val bounds = RectF()
    init { this.layer = layer; measure() }

    private fun measure() = bounds.set(x, y, x + w, y + h)
    override fun move(dx: Float, dy: Float) { x += dx; y += dy; measure() }
    override fun scale(f: Float, ox: Float, oy: Float) { x = ox + (x - ox) * f; y = oy + (y - oy) * f; w *= f; h *= f; measure() }
    override fun copy() = Pic(src, x, y, w, h, layer)
}

/** A typeset LaTeX formula. */
class MathItem(latex: String, var x: Float, var y: Float, val color: Int, size: Float, layer: Int = 0) : Item() {
    var latex = latex; private set
    var size = size; private set
    @Volatile private var dr: Drawable? = null
    override val bounds = RectF()
    init { this.layer = layer; measure() }

    fun drawable(): Drawable = dr ?: Tex.drawable(latex, size, color).also { dr = it }
    fun set(l: String) { latex = l; dr = null; measure() }

    private fun measure() {
        val d = drawable()
        bounds.set(x, y, x + d.intrinsicWidth, y + d.intrinsicHeight)
    }

    override fun move(dx: Float, dy: Float) { x += dx; y += dy; bounds.offset(dx, dy) }
    override fun scale(f: Float, ox: Float, oy: Float) { x = ox + (x - ox) * f; y = oy + (y - oy) * f; size *= f; dr = null; measure() }
    override fun copy() = MathItem(latex, x, y, color, size, layer)
}

class Layer(val id: Int, var name: String, var visible: Boolean = true)

class Note(
    val id: String,
    var title: String = "",
    var bg: Int = 1,
    var paper: Int = 0,
    var modified: Long = 0,
    val layers: MutableList<Layer> = arrayListOf(Layer(0, "Layer 1")),
    val items: MutableList<Item> = ArrayList(),
)

/** One file per note, written to a temp file then renamed so a crash never leaves a half-written note. */
object NoteIO {
    private const val V1 = 0x494E4B31 // strokes only
    private const val V2 = 0x494E4B32 // + layers, paper, text
    private const val V3 = 0x494E4B33 // + pictures, equations

    private fun dir(c: Context) = File(c.filesDir, "notes").apply { mkdirs() }
    private fun file(c: Context, id: String) = File(dir(c), "$id.ink")
    fun thumb(c: Context, id: String) = File(dir(c), "$id.png")

    fun list(c: Context): List<Doc> =
        dir(c).listFiles { f -> f.name.endsWith(".ink") }.orEmpty().mapNotNull { f ->
            runCatching {
                DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
                    require(i.readInt() in V1..V3)
                    val title = i.readUTF(); i.readByte()
                    Doc(f.nameWithoutExtension, false, title, i.readLong(), thumb(c, f.nameWithoutExtension))
                }
            }.getOrNull()
        }

    fun load(c: Context, id: String): Note {
        val f = file(c, id)
        if (!f.exists()) return Note(id)
        DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
            val magic = i.readInt()
            require(magic in V1..V3) { "bad note file" }
            val title = i.readUTF(); val bg = i.readUnsignedByte(); val mod = i.readLong()
            val n = Note(id, title, bg, 0, mod)
            fun stroke(layer: Int): Stroke {
                val col = i.readInt(); val kind = i.readUnsignedByte(); val w = i.readFloat()
                return Stroke(col, kind, w, FloatArray(i.readInt() * 3) { i.readFloat() }, layer)
            }
            if (magic == V1) { repeat(i.readInt()) { n.items.add(stroke(0)) }; return n }
            n.paper = i.readUnsignedByte()
            n.layers.clear()
            repeat(i.readInt()) { n.layers.add(Layer(i.readInt(), i.readUTF(), i.readBoolean())) }
            repeat(i.readInt()) {
                when (i.readUnsignedByte()) {
                    0 -> { val layer = i.readInt(); n.items.add(stroke(layer)) }
                    1 -> {
                        val col = i.readInt(); val size = i.readFloat(); val layer = i.readInt()
                        val x = i.readFloat(); val y = i.readFloat()
                        n.items.add(Label(i.readUTF(), x, y, col, size, layer))
                    }
                    2 -> {
                        val layer = i.readInt(); val x = i.readFloat(); val y = i.readFloat(); val w = i.readFloat(); val h = i.readFloat()
                        val b = ByteArray(i.readInt()); i.readFully(b)
                        n.items.add(Pic(PicSrc(b), x, y, w, h, layer))
                    }
                    else -> {
                        val layer = i.readInt(); val col = i.readInt(); val size = i.readFloat()
                        val x = i.readFloat(); val y = i.readFloat()
                        n.items.add(MathItem(i.readUTF(), x, y, col, size, layer))
                    }
                }
            }
            return n
        }
    }

    /** Deep-copies on the caller's thread (items are mutable), then writes + renders the thumbnail on the io thread. */
    fun save(c: Context, n: Note) {
        n.modified = System.currentTimeMillis()
        write(c.applicationContext, snapshot(n))
    }

    fun snapshot(n: Note, id: String = n.id, title: String = n.title) = Note(
        id, title, n.bg, n.paper, n.modified,
        n.layers.mapTo(ArrayList()) { Layer(it.id, it.name, it.visible) },
        n.items.mapTo(ArrayList()) { it.copy() },
    )

    private fun write(app: Context, n: Note) = Store.io.execute {
        runCatching {
            val f = file(app, n.id); val tmp = File(f.path + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
                o.writeInt(V3); o.writeUTF(n.title); o.writeByte(n.bg); o.writeLong(n.modified); o.writeByte(n.paper)
                o.writeInt(n.layers.size)
                for (l in n.layers) { o.writeInt(l.id); o.writeUTF(l.name); o.writeBoolean(l.visible) }
                o.writeInt(n.items.size)
                for (s in n.items) when (s) {
                    is Stroke -> {
                        o.writeByte(0); o.writeInt(s.layer)
                        o.writeInt(s.color); o.writeByte(s.kind); o.writeFloat(s.width); o.writeInt(s.n)
                        for (v in s.pts) o.writeFloat(v)
                    }
                    is Label -> {
                        o.writeByte(1); o.writeInt(s.color); o.writeFloat(s.size); o.writeInt(s.layer)
                        o.writeFloat(s.x); o.writeFloat(s.y); o.writeUTF(s.text)
                    }
                    is Pic -> {
                        o.writeByte(2); o.writeInt(s.layer); o.writeFloat(s.x); o.writeFloat(s.y); o.writeFloat(s.w); o.writeFloat(s.h)
                        o.writeInt(s.src.bytes.size); o.write(s.src.bytes)
                    }
                    is MathItem -> {
                        o.writeByte(3); o.writeInt(s.layer); o.writeInt(s.color); o.writeFloat(s.size)
                        o.writeFloat(s.x); o.writeFloat(s.y); o.writeUTF(s.latex)
                    }
                }
            }
            check(tmp.renameTo(f)) { "rename failed" }
            val tb = Painter().fit(n, 320, 240)
            FileOutputStream(thumb(app, n.id)).use { tb.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tb.recycle()
        }.onFailure { Log.e("NoteIO", "save failed", it) }
    }

    fun delete(c: Context, id: String) { file(c, id).delete(); thumb(c, id).delete() }

    /** Runs on the io thread. */
    fun duplicate(c: Context, id: String, newId: String) {
        val n = load(c, id)
        write(c.applicationContext, snapshot(n, newId, n.title.ifEmpty { "Untitled" } + " copy").also { it.modified = System.currentTimeMillis() })
    }

    /** Runs on the io thread. */
    fun rename(c: Context, id: String, title: String) {
        val n = load(c, id); n.title = title
        write(c.applicationContext, n)
    }
}
