package app.stylusnotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Callable
import kotlin.math.max
import kotlin.math.min

class NoteActivity : EditorBase() {
    private lateinit var ink: InkView
    private lateinit var note: Note
    private lateinit var root: View
    private lateinit var titleTv: TextView
    private lateinit var undoBtn: View
    private lateinit var redoBtn: View
    private lateinit var tools: MaterialButtonToggleGroup
    private lateinit var colorBtn: Swatch
    private lateinit var selBar: View
    private lateinit var layersPanel: View
    private lateinit var layersBox: LinearLayout

    private val brushes = listOf(
        Stroke.PEN to "Pen", Stroke.FOUNTAIN to "Fountain", Stroke.CALLI to "Calligraphy",
        Stroke.MARKER to "Marker", Stroke.PENCIL to "Pencil", Stroke.HI to "Highlighter",
    )
    private var brush = Stroke.PEN

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage { Images.fromUri(this, uri) }
    }
    private val webImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("url")?.let { u -> loadImage { Images.fromUrl(u) } }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getStringExtra("id")!!
        // a corrupt file must never be opened empty: the next autosave would overwrite it
        note = try {
            Store.io.submit(Callable { NoteIO.load(this, id) }).get()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open this note", Toast.LENGTH_LONG).show(); finish(); return
        }
        setContentView(R.layout.activity_note)
        root = findViewById(R.id.root)
        ink = findViewById(R.id.ink)
        titleTv = findViewById(R.id.title)
        undoBtn = findViewById(R.id.undo); redoBtn = findViewById(R.id.redo)
        tools = findViewById(R.id.tools)
        colorBtn = findViewById(R.id.color)
        selBar = findViewById(R.id.selBar)
        layersPanel = findViewById(R.id.layersPanel); layersBox = findViewById(R.id.layersBox)

        ink.items = note.items; ink.layers = note.layers; ink.activeLayer = note.layers.last().id
        ink.bg = note.bg; ink.paper = note.paper
        ink.onChange = { markDirty(); history() }
        ink.onSelect = { selBar.reveal(it > 0) }
        ink.onText = { item, x, y -> textTap(item, x, y) }
        ink.onGestureTap = { ink.hConfirm() }
        ink.onSnap = { ink.hSnap() }
        statusBarIcons()
        applySettings()

        findViewById<View>(R.id.back).setOnClickListener { finishAfterTransition() }
        titleTv.setOnClickListener { rename() }
        showTitle()
        undoBtn.setOnClickListener { it.hTick(); ink.undo() }
        redoBtn.setOnClickListener { it.hTick(); ink.redo() }
        findViewById<View>(R.id.ai).setOnClickListener { it.hTick(); aiSheet() }
        findViewById<View>(R.id.layers).setOnClickListener { it.hTick(); rebuildLayers(); layersPanel.reveal(layersPanel.visibility != View.VISIBLE) }
        findViewById<View>(R.id.more).setOnClickListener { menu(it) }

        tools.check(R.id.pen)
        tools.addOnButtonCheckedListener { g, btn, on ->
            if (!on) return@addOnButtonCheckedListener
            g.hTick()
            ink.tool = when (btn) { R.id.pen -> brush; R.id.eraser -> InkView.ERASE; R.id.lasso -> InkView.LASSO; else -> InkView.TEXT }
        }
        // tapping the pen while it's already the tool opens the pen picker
        findViewById<View>(R.id.pen).setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP && ink.tool < 10) v.post { penPicker(v) }
            false
        }
        findViewById<View>(R.id.insert).setOnClickListener { insertMenu(it) }
        colorBtn.col = ink.color
        colorBtn.setOnClickListener { v ->
            popover(v, colorPanel(this, prefs, ink.color) { c -> ink.color = c; colorBtn.col = c }, below = false)
                .setOnDismissListener { Recent.push(prefs, ink.color) }
        }
        findViewById<Slider>(R.id.size).addOnChangeListener { s, v, user -> ink.size = v; if (user && v.toInt() % 4 == 0) s.hTick() }

        findViewById<View>(R.id.selCopy).setOnClickListener { it.hConfirm(); ink.duplicateSelection() }
        findViewById<View>(R.id.selDelete).setOnClickListener { it.hReject(); ink.deleteSelection() }
        findViewById<View>(R.id.selDone).setOnClickListener { it.hTick(); ink.deselect() }

        for (id in intArrayOf(R.id.back, R.id.undo, R.id.redo, R.id.ai, R.id.layers, R.id.more, R.id.insert, R.id.color, R.id.selCopy, R.id.selDelete, R.id.selDone))
            findViewById<View>(id).bouncy()
        applyInsets(root, listOf(findViewById(R.id.top), layersPanel), listOf(findViewById(R.id.dock), selBar, findViewById(R.id.debug)))
        attachDebug(ink.router, findViewById(R.id.debug))
        history()
        findViewById<View>(R.id.dock).apply { translationY = dp(120f); post { springY(0f) } }
    }

    private fun statusBarIcons() {
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = note.paper != 2 && !isNight()
    }

    private fun isNight() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun applySettings() {
        ink.stabilize = prefs.getInt("stab", 2) * 0.07f
        ink.snapShapes = prefs.getBoolean("snap", true)
        ink.fingerDraw = prefs.getBoolean("finger", false)
        attachDebug(ink.router, findViewById(R.id.debug))
    }

    private fun showTitle() { titleTv.text = note.title.ifEmpty { "Untitled" } }

    private fun history() {
        undoBtn.isEnabled = ink.canUndo; undoBtn.alpha = if (ink.canUndo) 1f else 0.38f
        redoBtn.isEnabled = ink.canRedo; redoBtn.alpha = if (ink.canRedo) 1f else 0.38f
    }

    private fun rename() = ask("Rename", "Title", note.title) { note.title = it; showTitle(); markDirty() }

    // ---- pickers & menus ----

    private fun penPicker(anchor: View) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(8), dpi(8), dpi(8), dpi(8)) }
        lateinit var pw: android.widget.PopupWindow
        for ((k, name) in brushes) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(8), dpi(4), dpi(16), dpi(4)); minimumWidth = dpi(240)
                if (k == brush) background = roundRect(themeColor(Attr.secondaryContainer), 20f)
                addView(StrokePreview(context, k, labelColor()))
                addView(TextView(context).apply { text = name; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
                setOnClickListener { it.hTick(); brush = k; ink.tool = k; pw.dismiss() }
                bouncy()
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dpi(52)))
        }
        pw = popover(anchor, list, below = false)
    }

    private fun labelColor() = themeColor(Attr.onSurface)

    private fun roundRect(color: Int, r: Float) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(r); setColor(color) }

    private fun menuRow(icon: Int, text: String, act: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(16), 0, dpi(24), 0); minimumWidth = dpi(240)
        val tv = TypedValueHelper.selectable(context); if (tv != 0) setBackgroundResource(tv)
        addView(android.widget.ImageView(context).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(themeColor(Attr.onSurfaceVariant)) },
            LinearLayout.LayoutParams(dpi(24), dpi(24)))
        addView(TextView(context).apply { this.text = text; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge); setPadding(dpi(16), 0, 0, 0) })
        setOnClickListener { it.hTick(); act() }
    }

    private fun insertMenu(anchor: View) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(8), 0, dpi(8)) }
        lateinit var pw: android.widget.PopupWindow
        fun add(icon: Int, text: String, act: () -> Unit) = list.addView(menuRow(icon, text) { pw.dismiss(); act() }, LinearLayout.LayoutParams(-1, dpi(56)))
        add(R.drawable.ic_image, "Photo") { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        add(R.drawable.ic_search, "Search the web") { webImage.launch(Intent(this, WebImageActivity::class.java)) }
        add(R.drawable.ic_paste, "Paste") { paste() }
        add(R.drawable.ic_link, "Picture from link") { ask("Picture from link", "Image URL", "", "Insert") { u -> if (u.isNotBlank()) loadImage { Images.fromUrl(u) } } }
        add(R.drawable.ic_function, "Equation") { val c = ink.viewCenter(); Tex.editor(this, "", ink.color) { l -> placeMath(l, c.x, c.y) } }
        add(R.drawable.ic_text, "Text") { val c = ink.viewCenter(); textDialog(null) { t -> ink.addLabel(t, c.x - ink.viewWidth() * 0.25f, c.y) } }
        pw = popover(anchor, list, below = false)
    }

    private fun menu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Page style"); menu.add(0, 2, 0, "Paper"); menu.add(0, 3, 0, "Fit to content")
            menu.add(0, 4, 0, "Settings"); menu.add(0, 5, 0, "Share as image"); menu.add(0, 6, 0, "Share as PDF")
            menu.add(0, 7, 0, "Clear layer"); menu.add(0, 8, 0, "Delete note")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> choose("Page style", arrayOf("Blank", "Lined", "Grid", "Dots"), note.bg) { w -> note.bg = w; ink.bg = w; markDirty() }
                    2 -> choose("Paper", arrayOf("Cream", "White", "Dark"), note.paper) { w -> note.paper = w; ink.paper = w; statusBarIcons(); markDirty() }
                    3 -> ink.fitContent()
                    4 -> settings({ box -> keyRow(box) }) { applySettings() }
                    5 -> exportPng()
                    6 -> exportPdf()
                    7 -> ink.clearLayer()
                    8 -> MaterialAlertDialogBuilder(this@NoteActivity).setTitle("Delete this note?").setMessage("This can't be undone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            root.hReject(); dirty = false
                            Store.io.execute { NoteIO.delete(applicationContext, note.id) }
                            finish()
                        }.show()
                }
                true
            }
        }.show()
        anchor.hTick()
    }

    private fun choose(title: String, opts: Array<String>, cur: Int, done: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setSingleChoiceItems(opts, cur) { d, w -> root.hTick(); done(w); d.dismiss() }.show()
    }

    // ---- text, equations, pictures ----

    private fun textTap(item: Item?, x: Float, y: Float) {
        when (item) {
            is Label -> textDialog(item.text) { ink.editLabel(item, it) }
            is MathItem -> Tex.editor(this, item.latex, item.color) { ink.editMath(item, it) }
            else -> textDialog(null) { ink.addLabel(it, x, y) }
        }
    }

    private fun textDialog(initial: String?, done: (String) -> Unit) {
        val (l, e) = field(if (initial == null) "Text" else "Text (empty deletes)", initial ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        e.minLines = 2; e.gravity = Gravity.TOP
        MaterialAlertDialogBuilder(this).setTitle(if (initial == null) "Add text" else "Edit text").setView(padded(l))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> val t = e.text.toString().trimEnd(); if (t.isNotEmpty() || initial != null) done(t) }.show()
        e.requestFocus()
    }

    private fun mathSize() = max(28f, ink.size * 7f)

    private fun placeMath(latex: String, cx: Float, cy: Float) {
        val m = MathItem(latex, cx, cy, ink.color, mathSize())
        m.move(-m.bounds.width() / 2, -m.bounds.height() / 2)
        ink.addItem(m); tools.check(R.id.lasso); root.hConfirm()
    }

    private fun paste() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri
        val text = item?.coerceToText(this)?.toString()?.trim().orEmpty()
        when {
            uri != null && (contentResolver.getType(uri)?.startsWith("image/") == true) -> loadImage { Images.fromUri(this, uri) }
            text.startsWith("http://") || text.startsWith("https://") || text.startsWith("data:image") -> loadImage { Images.fromUrl(text) }
            text.isNotEmpty() -> { val c = ink.viewCenter(); ink.addLabel(text, c.x - ink.viewWidth() * 0.25f, c.y); tools.check(R.id.lasso) }
            else -> Snackbar.make(root, "Nothing to paste", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun loadImage(fetch: () -> ByteArray?) {
        val snack = Snackbar.make(root, "Adding picture…", Snackbar.LENGTH_INDEFINITE).also { it.show() }
        bg.execute {
            val bytes = runCatching(fetch).getOrNull()
            val src = bytes?.let { PicSrc(it).takeIf { s -> s.bitmap != null } }
            runOnUiThread {
                snack.dismiss()
                if (src == null) { root.hReject(); Snackbar.make(root, "Couldn't load that picture", Snackbar.LENGTH_LONG).show(); return@runOnUiThread }
                val b = src.bitmap!!
                val w = min(b.width.toFloat(), ink.viewWidth() * 0.6f); val h = w * b.height / b.width
                val c = ink.viewCenter()
                ink.addItem(Pic(src, c.x - w / 2, c.y - h / 2, w, h))
                tools.check(R.id.lasso); root.hConfirm()
            }
        }
    }

    // ---- AI ----

    private fun keyRow(box: LinearLayout) {
        val (l, e) = field("Anthropic API key (for AI)", prefs.getString("ai_key", "")!!, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        l.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        l.helperText = "Stored only on this device. Get one at console.anthropic.com."
        e.setOnFocusChangeListener { _, f -> if (!f) prefs.edit().putString("ai_key", e.text.toString().trim()).apply() }
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { prefs.edit().putString("ai_key", s.toString().trim()).apply() }
        })
        box.addView(l, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(16) })
    }

    private fun aiSheet() {
        val key = prefs.getString("ai_key", "")!!
        if (key.isBlank()) {
            MaterialAlertDialogBuilder(this).setTitle("Connect Claude")
                .setMessage("AI features use Claude through your own Anthropic API key. Add it in Settings.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings") { _, _ -> settings({ box -> keyRow(box) }) { applySettings() } }.show()
            return
        }
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_ai, null)
        val sheet = BottomSheetDialog(this).apply { setContentView(v); behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED; behavior.skipCollapsed = true }
        val sel = ink.selection.toList()
        v.findViewById<TextView>(R.id.scope).text = if (sel.isNotEmpty()) "Selection" else "Visible page"
        val loading = v.findViewById<LoadingIndicator>(R.id.loading)
        val card = v.findViewById<MaterialCardView>(R.id.resultCard)
        val result = v.findViewById<TextView>(R.id.result).apply { movementMethod = android.text.method.ScrollingMovementMethod() }
        val actions = v.findViewById<View>(R.id.resultActions)
        val insertMath = v.findViewById<Button>(R.id.insertMath)
        val chips = v.findViewById<ChipGroup>(R.id.actions)
        val ask = v.findViewById<TextInputEditText>(R.id.ask)
        var task: Ai.Task? = null
        var busy = false

        fun go(instruction: String, quick: Boolean, t: Ai.Task?) {
            if (busy) return
            val img = ink.aiImage()
            if (img == null && t != null) { result.text = "There's nothing on the page for Claude to read yet."; card.reveal(true); return }
            busy = true; task = t
            card.visibility = View.GONE; actions.visibility = View.GONE; loading.reveal(true)
            val png = img?.let { Ai.png(it) }; img?.recycle()
            bg.execute {
                val r = runCatching { Ai.run(key, instruction, png, quick) }
                runOnUiThread {
                    busy = false; loading.visibility = View.GONE
                    result.text = r.getOrElse { it.message ?: "Something went wrong." }
                    card.reveal(true)
                    if (r.isSuccess) {
                        actions.reveal(true); v.hConfirm()
                        insertMath.visibility = if (t?.latex == true) View.VISIBLE else View.GONE
                    } else v.hReject()
                }
            }
        }
        for (t in Ai.Task.values()) chips.addView(Chip(this).apply {
            text = t.label
            setOnClickListener { it.hTick(); go(t.prompt, t.quick, t) }
        })
        fun sendAsk() { val q = ask.text.toString().trim(); if (q.isNotEmpty()) go(q, false, null) }
        v.findViewById<TextInputLayout>(R.id.askBox).setEndIconOnClickListener { it.hTick(); sendAsk() }
        ask.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { sendAsk(); true } else false }

        v.findViewById<View>(R.id.copy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Claude", result.text))
            it.hConfirm(); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        insertMath.setOnClickListener {
            val at = anchorFor(sel)
            ink.replaceSelectionOrAdd(MathItem(Ai.cleanLatex(result.text.toString()), at.left, at.top, ink.color, mathSize()), sel.isNotEmpty())
            tools.check(R.id.lasso); root.hConfirm(); sheet.dismiss()
        }
        v.findViewById<View>(R.id.insertText).setOnClickListener {
            val at = anchorFor(sel)
            val text = result.text.toString()
            val label = Label(text, at.left, at.top, ink.color, max(16f, ink.size * 6f))
            // transcriptions replace the ink they came from; other answers go next to it
            if (task == Ai.Task.TRANSCRIBE && sel.isNotEmpty()) ink.replaceSelectionOrAdd(label, true)
            else { if (sel.isNotEmpty()) label.move(0f, at.height() + 24f); ink.replaceSelectionOrAdd(label, false) }
            tools.check(R.id.lasso); root.hConfirm(); sheet.dismiss()
        }
        sheet.show()
        v.popIn()
    }

    /** Where AI output lands: over the selection, else left of the screen centre. */
    private fun anchorFor(sel: List<Item>): RectF {
        if (sel.isNotEmpty()) { val r = RectF(sel[0].bounds); sel.forEach { r.union(it.bounds) }; return r }
        val c = ink.viewCenter(); val x = c.x - ink.viewWidth() * 0.3f
        return RectF(x, c.y, x, c.y)
    }

    private fun InkView.replaceSelectionOrAdd(item: Item, replace: Boolean) {
        if (replace) { select(selection.toList()); replaceSelection(item) } else { deselect(); addItem(item) }
    }

    // ---- layers ----

    private fun rebuildLayers() {
        layersBox.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(12), 0, 0, dpi(4)) }
        head.addView(TextView(this).apply { text = "Layers"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconBtn(R.drawable.ic_add, "Add layer") { ink.addLayer(); rebuildLayers() })
        layersBox.addView(head)
        for (l in ink.layers.asReversed()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(4), 0, dpi(12), 0)
                if (l.id == ink.activeLayer) background = roundRect(themeColor(Attr.secondaryContainer), 20f)
                setOnClickListener { it.hTick(); ink.activeLayer = l.id; rebuildLayers() }
            }
            row.addView(iconBtn(if (l.visible) R.drawable.ic_eye else R.drawable.ic_eye_off, "Show or hide ${l.name}") {
                l.visible = !l.visible; ink.refresh(); markDirty(); rebuildLayers()
            }.apply { alpha = if (l.visible) 1f else 0.5f })
            row.addView(TextView(this).apply { text = l.name; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) }, LinearLayout.LayoutParams(0, -2, 1f))
            layersBox.addView(row, LinearLayout.LayoutParams(-1, dpi(52)).apply { topMargin = dpi(2) })
        }
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dpi(8), 0, 0) }
        foot.addView(iconBtn(R.drawable.ic_up, "Move layer up") { ink.moveLayer(ink.activeLayer, 1); rebuildLayers() })
        foot.addView(iconBtn(R.drawable.ic_down, "Move layer down") { ink.moveLayer(ink.activeLayer, -1); rebuildLayers() })
        foot.addView(iconBtn(R.drawable.ic_delete, "Delete layer") {
            if (ink.layers.size <= 1) { Toast.makeText(this, "Can't delete the only layer", Toast.LENGTH_SHORT).show(); return@iconBtn }
            MaterialAlertDialogBuilder(this).setTitle("Delete this layer?").setMessage("Everything on it is removed and undo history is cleared.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> root.hReject(); ink.removeLayer(ink.activeLayer); rebuildLayers() }.show()
        })
        layersBox.addView(foot)
    }

    private fun iconBtn(icon: Int, desc: String, act: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(icon); contentDescription = desc
            layoutParams = LinearLayout.LayoutParams(dpi(48), dpi(48))
            setOnClickListener { it.hTick(); act() }
        }

    // ---- export & save ----

    private fun exportPng() {
        val bmp = Painter().export(note) ?: return Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show()
        val f = exportFile(note.title, "png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle(); shareFile(f, "image/png")
    }

    private fun exportPdf() {
        val doc = Painter().pdf(note) ?: return Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show()
        val f = exportFile(note.title, "pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close(); shareFile(f, "application/pdf")
    }

    override fun save() {
        if (!dirty || !::note.isInitialized) return
        dirty = false
        NoteIO.save(this, note)
    }
}

object TypedValueHelper {
    fun selectable(c: android.content.Context): Int {
        val tv = android.util.TypedValue()
        return if (c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) tv.resourceId else 0
    }
}
