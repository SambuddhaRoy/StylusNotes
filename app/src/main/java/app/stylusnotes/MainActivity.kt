package app.stylusnotes

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import java.util.concurrent.Callable
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private val all = ArrayList<Doc>()
    private val shown = ArrayList<Doc>()
    private val thumbs = LruCache<String, Bitmap>(48)
    private val pendingDelete = HashSet<String>()
    private val animated = HashSet<String>()
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var search: EditText
    private lateinit var filters: ChipGroup
    private lateinit var fab: ExtendedFloatingActionButton
    private var pendingApk: File? = null
    private var pendingVersion = ""
    private var askedInstallPermission = false

    override fun onCreate(b: Bundle?) {
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementsUseOverlay = false
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list); empty = findViewById(R.id.empty)
        search = findViewById(R.id.search); filters = findViewById(R.id.filters); fab = findViewById(R.id.fab)
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = dpi(24) + s.bottom
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, dpi(120) + s.bottom)
            ins
        }
        list.layoutManager = GridLayoutManager(this, max(2, resources.configuration.screenWidthDp / 240))
        list.adapter = Adapter()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        filters.setOnCheckedStateChangeListener { g, _ -> g.hTick(); filter() }
        fab.setOnClickListener { it.hTick(); newSheet() }
        fab.bouncy()
        fab.translationY = dp(160f); fab.post { fab.springY(0f, 0.5f, 300f) }
        val prefs = getSharedPreferences("p", 0)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            menu.add(0, 1, 0, "Check for updates")
            menu.add(0, 2, 0, "Automatic updates").apply { isCheckable = true; isChecked = prefs.getBoolean("autoUpdate", true) }
            menu.add(0, 3, 0, "Version ${Updater.currentVersion(this@MainActivity)}").isEnabled = false
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> checkUpdates(manual = true)
                    2 -> { it.isChecked = !it.isChecked; hToggle(it.isChecked); prefs.edit().putBoolean("autoUpdate", it.isChecked).apply() }
                }
                true
            }
        }
    }

    override fun onResume() {
        super.onResume(); refresh()
        if (pendingApk != null) installUpdate() else checkUpdates(manual = false)
    }

    // ---- self-update ----

    /** Looks for a newer GitHub release (at most every 6 h unless [manual]) and downloads it in the background. */
    private fun checkUpdates(manual: Boolean) {
        val p = getSharedPreferences("p", 0)
        val now = System.currentTimeMillis()
        if (!manual && (!p.getBoolean("autoUpdate", true) || now - p.getLong("updateChecked", 0) < 6 * 3600_000L)) return
        if (manual) Snackbar.make(list, "Checking for updates…", Snackbar.LENGTH_SHORT).show()
        bg.execute {
            val r = runCatching { Updater.latest() }
            if (r.isSuccess) p.edit().putLong("updateChecked", now).apply()
            val cur = Updater.currentVersion(this)
            val rel = r.getOrNull()
            if (rel == null || !Updater.isNewer(rel.version, cur)) {
                if (manual) runOnUiThread { Snackbar.make(list, if (r.isFailure) "Couldn't reach GitHub to check for updates" else "You're up to date (v$cur)", Snackbar.LENGTH_LONG).show() }
                return@execute
            }
            val f = runCatching { Updater.download(this, rel) }
            runOnUiThread {
                f.onSuccess { apk ->
                    pendingApk = apk; pendingVersion = rel.version
                    // only from the library: no document is open, so nothing unsaved is lost when the app restarts
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) installUpdate()
                }.onFailure { if (manual) Snackbar.make(list, "Update download failed: ${it.message}", Snackbar.LENGTH_LONG).show() }
            }
        }
    }

    private fun installUpdate() {
        val apk = pendingApk ?: return
        if (!Updater.canInstall(this)) {
            if (askedInstallPermission) return
            askedInstallPermission = true
            MaterialAlertDialogBuilder(this).setTitle("Update ready")
                .setMessage("Stylus Notes v$pendingVersion is downloaded. To install its own updates, Android needs you to allow this app to install apps. You only have to do this once.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Allow") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .show()
            return
        }
        pendingApk = null
        list.hConfirm()
        Snackbar.make(list, "Updating to v$pendingVersion…", Snackbar.LENGTH_LONG).show()
        runCatching { Updater.install(this, apk) }.onFailure { Snackbar.make(list, "Couldn't start the update: ${it.message}", Snackbar.LENGTH_LONG).show() }
    }

    private fun open(d: Doc, from: View) {
        from.hTick()
        val i = Intent(this, if (d.art) DrawActivity::class.java else NoteActivity::class.java).putExtra("id", d.id)
        startActivity(i, ActivityOptions.makeSceneTransitionAnimation(this, from, "doc").toBundle())
    }

    // goes through the io queue so a document saved a moment ago is already on disk
    private fun refresh() {
        all.clear(); all += Store.io.submit(Callable { Store.list(this) }).get()
        filter()
    }

    private fun filter() {
        val q = search.text.toString().trim()
        val kind = filters.checkedChipId
        shown.clear()
        shown += all.filter {
            it.id !in pendingDelete && (q.isEmpty() || it.title.contains(q, true)) &&
                (kind == R.id.fAll || (kind == R.id.fNotes) == !it.art)
        }
        list.adapter!!.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.emptyTitle).text = if (all.isEmpty()) "Nothing here yet" else "No matches"
        if (shown.isEmpty()) empty.popIn()
    }

    // ---- creating ----

    private fun newSheet() {
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_new, null)
        val sheet = BottomSheetDialog(this).apply { setContentView(v); behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED; behavior.skipCollapsed = true }
        val note = v.findViewById<View>(R.id.newNote); val art = v.findViewById<View>(R.id.newArt)
        val sizes = v.findViewById<View>(R.id.sizes); val chips = v.findViewById<ChipGroup>(R.id.sizeChips)
        val dm = resources.displayMetrics
        val screenW = max(dm.widthPixels, dm.heightPixels); val screenH = kotlin.math.min(dm.widthPixels, dm.heightPixels)
        val presets = listOf(
            "Screen · $screenW × $screenH" to (screenW to screenH),
            "Square · 2048 × 2048" to (2048 to 2048),
            "A4 portrait · 1654 × 2339" to (1654 to 2339),
            "HD · 1920 × 1080" to (1920 to 1080),
        )
        presets.forEachIndexed { i, (label, _) ->
            chips.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId(); text = label; isCheckable = true; isChecked = i == 0; tag = i
                setOnClickListener { it.hTick() }
            })
        }
        note.bouncy(); art.bouncy()
        note.popIn(); art.popIn(60)
        note.setOnClickListener {
            it.hConfirm(); sheet.dismiss()
            startActivity(Intent(this, NoteActivity::class.java).putExtra("id", Store.newId()),
                ActivityOptions.makeSceneTransitionAnimation(this, fab, "doc").toBundle())
        }
        art.setOnClickListener { it.hTick(); if (sizes.visibility != View.VISIBLE) { sizes.visibility = View.VISIBLE; sizes.popIn() } }
        v.findViewById<View>(R.id.create).setOnClickListener {
            it.hConfirm(); sheet.dismiss()
            val (w, h) = presets[chips.findViewById<Chip>(chips.checkedChipId).tag as Int].second
            startActivity(Intent(this, DrawActivity::class.java).putExtra("id", Store.newId()).putExtra("w", w).putExtra("h", h),
                ActivityOptions.makeSceneTransitionAnimation(this, fab, "doc").toBundle())
        }
        sheet.show()
    }

    // ---- per-document actions ----

    private fun actions(d: Doc, anchor: View) {
        anchor.hHeavy()
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Rename"); menu.add(0, 2, 0, "Duplicate"); menu.add(0, 3, 0, "Delete")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> rename(d)
                    2 -> { Store.io.submit { Store.duplicate(this@MainActivity, d) }.get(); anchor.hConfirm(); refresh() }
                    3 -> delete(d)
                }
                true
            }
        }.show()
    }

    private fun rename(d: Doc) {
        val l = com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Title" }
        val e = com.google.android.material.textfield.TextInputEditText(l.context).apply { setText(d.title); setSingleLine(); selectAll() }
        l.addView(e)
        val box = android.widget.FrameLayout(this).apply { setPadding(dpi(24), dpi(8), dpi(24), 0); addView(l) }
        MaterialAlertDialogBuilder(this).setTitle("Rename").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ -> Store.io.submit { Store.rename(this, d, e.text.toString().trim()) }.get(); refresh() }.show()
        e.requestFocus()
    }

    /** Hides the card now and deletes for real only once the Undo snackbar has gone. */
    private fun delete(d: Doc) {
        list.hReject()
        pendingDelete += d.id; filter()
        Snackbar.make(list, "Deleted “${d.title.ifEmpty { "Untitled" }}”", Snackbar.LENGTH_LONG)
            .setAction("Undo") { it.hTick(); pendingDelete -= d.id; filter() }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(s: Snackbar?, event: Int) {
                    if (d.id in pendingDelete) { pendingDelete -= d.id; Store.io.execute { Store.delete(this@MainActivity, d) } }
                }
            }).show()
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val date: TextView = v.findViewById(R.id.date)
        val kind: Chip = v.findViewById(R.id.kind)
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(layoutInflater.inflate(R.layout.item_doc, p, false))
        override fun onBindViewHolder(h: VH, i: Int) {
            val d = shown[i]
            h.title.text = d.title.ifEmpty { "Untitled" }
            h.date.text = DateUtils.getRelativeTimeSpanString(d.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.kind.text = if (d.art) "Drawing" else "Note"
            h.kind.setChipIconResource(if (d.art) R.drawable.ic_palette else R.drawable.ic_note)
            val key = "${d.id}@${d.modified}"
            h.img.setImageBitmap(thumbs[key] ?: BitmapFactory.decodeFile(d.thumb.path)?.also { thumbs.put(key, it) })
            h.itemView.transitionName = "doc_${d.id}"
            h.itemView.setOnClickListener { v -> v.transitionName = "doc"; open(d, v); v.postDelayed({ v.transitionName = "doc_${d.id}" }, 600) }
            h.itemView.setOnLongClickListener { actions(d, it); true }
            h.itemView.bouncy()
            if (animated.add(d.id)) h.itemView.popIn((i % 12) * 35L)
        }
    }
}
