package app.stylusnotes

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A library entry: a note or a drawing. */
class Doc(val id: String, val art: Boolean, val title: String, val modified: Long, val thumb: File)

object Store {
    /** Single writer thread; reads that must see pending writes go through it too. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36)

    fun list(c: Context): List<Doc> = (NoteIO.list(c) + ArtIO.list(c)).sortedByDescending { it.modified }

    fun delete(c: Context, d: Doc) = if (d.art) ArtIO.delete(c, d.id) else NoteIO.delete(c, d.id)

    /** All three below run on the io thread. */
    fun duplicate(c: Context, d: Doc) = if (d.art) ArtIO.duplicate(c, d.id, newId()) else NoteIO.duplicate(c, d.id, newId())

    fun rename(c: Context, d: Doc, title: String) = if (d.art) ArtIO.rename(c, d.id, title) else NoteIO.rename(c, d.id, title)
}
