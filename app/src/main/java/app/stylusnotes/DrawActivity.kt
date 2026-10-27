package app.stylusnotes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Callable
import kotlin.math.hypot
import kotlin.math.min

class DrawActivity : EditorBase() {
    private lateinit var pv: PaintView
    private lateinit var art: Art
    private lateinit var root: FrameLayout
    private lateinit var colorBtn: Swatch
    private lateinit var sizeSlider: Slider
    private lateinit var opSlider: Slider
    private lateinit var ctx: View
    private lateinit var ctxRow: LinearLayout
    private lateinit var toolBtns: Map<Int, MaterialButton>
    private var prevTool = PaintView.PAINT
    private var insertedLayer = false
    private var uiHidden = false
    private var openPopup: PopupWindow? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) placePhoto { Images.fromUri(this, uri) }
    }
    private val webImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("url")?.let { u -> placePhoto { Images.fromUrl(u) } }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getStringExtra("id")!!
        val w = intent.getIntExtra("w", 0); val h = intent.getIntExtra("h", 0)
        art = try {
            if (w > 0) Art(id, w, h).apply { layers += newLayer(); dirty = true }
            else Store.io.submit(Callable { ArtIO.load(this, id) }).get()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open this drawing", Toast.LENGTH_LONG).show(); finish(); return
        }
        setContentView(R.layout.activity_draw)
        root = findViewById(R.id.root)
        pv = findViewById(R.id.paint)
        pv.maxLayers = if (art.w.toLong() * art.h > 3_000_000) 10 else 16
        pv.open(art)
        colorBtn = findViewById(R.id.color)
        ctx = findViewById(R.id.ctx); ctxRow = findViewById(R.id.ctxRow)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES

        pv.onChange = { markDirty(); history() }
        pv.onTap = { n -> pv.hConfirm(); if (n == 4) toggleUi() }
        pv.onSnap = { pv.hSnap() }
        pv.onFit = { pv.hTick() }
        pv.onHidden = { pv.hReject(); Snackbar.make(root, "This layer is hidden", Snackbar.LENGTH_SHORT).show() }
        pv.onPick = { c -> setColor(c); Recent.push(prefs, c); pv.hConfirm(); selectTool(prevTool) }
        pv.onSelection = { if (pv.tool == PaintView.SELECT) showCtx(PaintView.SELECT) }
        applySettings()

        toolBtns = mapOf(
            PaintView.PAINT to findViewById(R.id.brush), PaintView.SMUDGE to findViewById(R.id.smudge), PaintView.ERASE to findViewById(R.id.erase),
            PaintView.SELECT to findViewById(R.id.select), PaintView.TRANSFORM to findViewById(R.id.transform),
        )
        for ((t, btn) in toolBtns) btn.setOnClickListener {
            // tapping the active painting tool again opens its brush library
            if (pv.tool == t && t <= PaintView.ERASE) { brushLibrary(btn); selectTool(t) } else selectTool(t)
        }
        findViewById<View>(R.id.gallery).setOnClickListener { finishAfterTransition() }
        findViewById<View>(R.id.actions).setOnClickListener { actionsMenu(it) }
        findViewById<View>(R.id.adjust).setOnClickListener { adjustMenu(it) }
        findViewById<View>(R.id.layers).setOnClickListener { layersPanel(it) }
        findViewById<View>(R.id.undo).setOnClickListener { it.hTick(); pv.undo() }
        findViewById<View>(R.id.redo).setOnClickListener { it.hTick(); pv.redo() }
        findViewById<View>(R.id.pick).setOnClickListener { it.hTick(); if (pv.tool != PaintView.PICK) { prevTool = pv.tool.coerceAtMost(PaintView.ERASE); selectTool(PaintView.PICK) } }
        setupColorButton()

        sizeSlider = vertical(findViewById(R.id.sizeBox), "Brush size") { v -> pv.sizes[cur()] = v }
        opSlider = vertical(findViewById(R.id.opBox), "Brush opacity") { v -> pv.opac[cur()] = v }

        for (id in intArrayOf(R.id.gallery, R.id.actions, R.id.adjust, R.id.select, R.id.transform, R.id.brush, R.id.smudge, R.id.erase, R.id.layers, R.id.pick, R.id.undo, R.id.redo))
            findViewById<View>(id).bouncy()
        applyInsets(root, listOf(findViewById(R.id.top)), listOf(ctx, findViewById(R.id.debug)))
        attachDebug(pv.router, findViewById(R.id.debug))
        setColor(prefs.getInt("drawColor", pv.color))
        selectTool(PaintView.PAINT)
        history()
        findViewById<View>(R.id.side).apply { translationX = -dp(96f); post { springX(0f) } }
        findViewById<View>(R.id.top).apply { translationY = -dp(96f); post { springY(0f) } }
    }

    private fun cur() = pv.tool.coerceAtMost(PaintView.ERASE)

    private fun applySettings() {
        pv.stab = prefs.getInt("stab", 2) * 0.08f
        pv.quickShape = prefs.getBoolean("snap", true)
        pv.fingerDraw = prefs.getBoolean("finger", false)
        pv.gamma = prefs.getFloat("gamma", 1f)
        pv.symmetry = prefs.getBoolean("symmetry", false)
        attachDebug(pv.router, findViewById(R.id.debug))
    }

    /** A Material slider turned on its side, Procreate-style. */
    private fun vertical(box: FrameLayout, desc: String, change: (Float) -> Unit): Slider {
        val s = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f; labelBehavior = LabelFormatter.LABEL_GONE; contentDescription = desc
            rotation = -90f
            addOnChangeListener { sl, v, user -> if (user) { change(v); if ((v * 20).toInt() != ((v - 0.05f) * 20).toInt()) sl.hTick() } }
        }
        box.post {
            box.addView(s, FrameLayout.LayoutParams(box.height, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        return s
    }

    private fun setColor(c: Int) { pv.color = c; colorBtn.col = c; prefs.edit().putInt("drawColor", c).apply() }

    private fun selectTool(t: Int) {
        val old = pv.tool
        if (old == PaintView.TRANSFORM && t != PaintView.TRANSFORM && pv.floating != null) { pv.applyTransform(); insertedLayer = false }
        if (t == PaintView.TRANSFORM && pv.floating == null && !pv.beginTransform()) {
            pv.hReject(); Snackbar.make(root, "Nothing to transform on this layer", Snackbar.LENGTH_SHORT).show()
            toolBtns[PaintView.TRANSFORM]?.isChecked = false
            return
        }
        pv.tool = t
        for ((k, b) in toolBtns) b.isChecked = k == t
        findViewById<MaterialButton>(R.id.pick).isChecked = t == PaintView.PICK
        if (t <= PaintView.ERASE) {
            if (::sizeSlider.isInitialized) { sizeSlider.value = pv.sizes[t]; opSlider.value = pv.opac[t] }
        }
        if (old != t) toolBtns[t]?.hTick()
        showCtx(t)
    }

    private fun history() {
        val u = findViewById<View>(R.id.undo); val r = findViewById<View>(R.id.redo)
        u.isEnabled = pv.canUndo; u.alpha = if (pv.canUndo) 1f else 0.38f
        r.isEnabled = pv.canRedo; r.alpha = if (pv.canRedo) 1f else 0.38f
    }

    private fun toggleUi() {
        uiHidden = !uiHidden
        val top = findViewById<View>(R.id.top); val side = findViewById<View>(R.id.side)
        top.springY(if (uiHidden) -top.height - dp(32f) else 0f)
        side.springX(if (uiHidden) -side.width - dp(32f) else 0f)
        if (uiHidden) ctx.reveal(false) else showCtx(pv.tool)
    }

    // ---- contextual bottom bar ----

    private fun ctxButton(icon: Int?, text: String?, filled: Boolean = false, act: (View) -> Unit): MaterialButton {
        val style = when {
            icon != null && text == null -> com.google.android.material.R.attr.materialIconButtonStyle
            filled -> com.google.android.material.R.attr.materialButtonStyle
            else -> androidx.appcompat.R.attr.borderlessButtonStyle
        }
        return MaterialButton(this, null, style).apply {
            if (icon != null) setIconResource(icon)
            if (text != null) this.text = text else contentDescription = when (icon) {
                R.drawable.ic_flip -> "Flip"; R.drawable.ic_rotate -> "Rotate"; else -> ""
            }
            setOnClickListener { it.hTick(); act(it) }
            layoutParams = LinearLayout.LayoutParams(if (text == null) dpi(48) else -2, dpi(48)).apply { marginStart = dpi(4) }
        }
    }

    private fun showCtx(t: Int) {
        ctxRow.removeAllViews()
        when (t) {
            PaintView.SELECT -> {
                val group = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
                for ((k, label) in listOf(PaintView.FREE to "Freehand", PaintView.RECT to "Rectangle", PaintView.OVAL to "Ellipse")) {
                    group.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = label; tag = k })
                }
                group.check(group.getChildAt(pv.selMode).id)
                group.addOnButtonCheckedListener { g, id, on -> if (on) { g.hTick(); pv.selMode = g.findViewById<View>(id).tag as Int } }
                ctxRow.addView(group)
                ctxRow.addView(ctxButton(null, "Invert") { pv.invertSelection() })
                ctxRow.addView(ctxButton(null, "Copy & paste") { v -> pv.copyToNewLayer()?.let { pv.hReject(); Snackbar.make(root, it, Snackbar.LENGTH_SHORT).show() } ?: v.hConfirm() })
                ctxRow.addView(ctxButton(null, "Clear") { pv.clearSelectedPixels() })
                ctxRow.addView(ctxButton(null, "All") { pv.selectAll() })
                ctxRow.addView(ctxButton(R.drawable.ic_close, null) { pv.clearSelection() }.apply { contentDescription = "Deselect" })
            }
            PaintView.TRANSFORM -> {
                ctxRow.addView(ctxButton(R.drawable.ic_flip, null) { pv.flipFloat(true) }.apply { contentDescription = "Flip horizontal" })
                ctxRow.addView(ctxButton(R.drawable.ic_flip, null) { pv.flipFloat(false) }.apply { rotation = 90f; contentDescription = "Flip vertical" })
                ctxRow.addView(ctxButton(R.drawable.ic_rotate, null) { pv.rotateFloat(90f) }.apply { contentDescription = "Rotate 90°" })
                ctxRow.addView(ctxButton(null, "Cancel") {
                    pv.cancelTransform()
                    if (insertedLayer) { pv.undo(); insertedLayer = false }
                    selectTool(PaintView.PAINT)
                })
                ctxRow.addView(ctxButton(null, "Done", filled = true) { it.hConfirm(); pv.applyTransform(); insertedLayer = false; selectTool(PaintView.PAINT) })
            }
            else -> { ctx.reveal(false); return }
        }
        if (!uiHidden) { ctx.visibility = View.VISIBLE; ctx.popIn() }
    }

    private fun adjustBar(title: String, sliders: List<Triple<String, ClosedFloatingPointRange<Float>, Float>>, live: ((FloatArray) -> Unit)?, apply: (FloatArray) -> Unit) {
        ctxRow.removeAllViews()
        val vals = FloatArray(sliders.size) { sliders[it].third }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, dpi(8), 0) }
        col.addView(TextView(this).apply { text = title; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall) })
        sliders.forEachIndexed { k, (label, range, v) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = label; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge); minWidth = dpi(88) })
            row.addView(Slider(this).apply {
                valueFrom = range.start; valueTo = range.endInclusive; value = v; labelBehavior = LabelFormatter.LABEL_GONE; contentDescription = label
                addOnChangeListener { _, nv, _ -> vals[k] = nv; live?.invoke(vals) }
            }, LinearLayout.LayoutParams(dpi(260), -2))
            col.addView(row)
        }
        ctxRow.addView(col)
        ctxRow.addView(ctxButton(null, "Cancel") { pv.preview = null; showCtx(pv.tool) })
        ctxRow.addView(ctxButton(null, "Apply", filled = true) { it.hConfirm(); apply(vals); showCtx(pv.tool) })
        ctx.visibility = View.VISIBLE; ctx.popIn()
    }

    // ---- popovers ----

    private fun show(anchor: View, content: View, below: Boolean = true): PopupWindow {
        openPopup?.dismiss()
        return popover(anchor, content, below).also { openPopup = it }
    }

    private fun menuRow(icon: Int, text: String, act: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(16), 0, dpi(24), 0); minimumWidth = dpi(260)
        val r = TypedValueHelper.selectable(context); if (r != 0) setBackgroundResource(r)
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(themeColor(Attr.onSurfaceVariant)) }, LinearLayout.LayoutParams(dpi(24), dpi(24)))
        addView(TextView(context).apply { this.text = text; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge); setPadding(dpi(16), 0, 0, 0) })
        setOnClickListener { it.hTick(); openPopup?.dismiss(); act() }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(themeColor(Attr.primary)); setPadding(dpi(16), dpi(12), dpi(16), dpi(4))
    }

    private fun list(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dpi(4), 0, dpi(8))
        for (r in rows) addView(r, LinearLayout.LayoutParams(-1, if (r is TextView) -2 else dpi(52)))
    }

    private fun actionsMenu(a: View) = show(a, list(
        menuRow(R.drawable.ic_tune, "Preferences") { prefsDialog() },
        header("Add"),
        menuRow(R.drawable.ic_image, "Insert a photo") { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        menuRow(R.drawable.ic_search, "Insert from the web") { webImage.launch(Intent(this, WebImageActivity::class.java)) },
        header("Share"),
        menuRow(R.drawable.ic_share, "PNG") { export("png") },
        menuRow(R.drawable.ic_share, "JPEG") { export("jpg") },
        menuRow(R.drawable.ic_share, "PSD (layered)") { export("psd") },
        header("Canvas"),
        menuRow(R.drawable.ic_flip, "Flip canvas horizontally") { pv.flipCanvas(true) },
        menuRow(R.drawable.ic_flip, "Flip canvas vertically") { pv.flipCanvas(false) },
        menuRow(R.drawable.ic_text, "Rename") { ask("Rename", "Title", art.title) { art.title = it; markDirty() } },
    ).let { ScrollView(this).apply { addView(it) ; layoutParams = ViewGroup.LayoutParams(-2, min(dpi(560), (resources.displayMetrics.heightPixels * 0.7f).toInt())) } })

    private fun prefsDialog() = settings({ box ->
        box.addView(TextView(this).apply { text = "Pressure curve (left: soft, right: firm)"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall); setPadding(0, dpi(16), 0, 0) })
        box.addView(Slider(this).apply {
            valueFrom = 0.4f; valueTo = 2.5f; value = prefs.getFloat("gamma", 1f).coerceIn(0.4f, 2.5f); labelBehavior = LabelFormatter.LABEL_GONE
            addOnChangeListener { _, v, _ -> prefs.edit().putFloat("gamma", v).apply(); applySettings() }
        })
        box.addView(MaterialSwitch(this).apply {
            text = "Vertical symmetry"; isChecked = prefs.getBoolean("symmetry", false)
            setOnCheckedChangeListener { v, on -> v.hToggle(on); prefs.edit().putBoolean("symmetry", on).apply(); applySettings() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
    }) { applySettings() }

    private fun adjustMenu(a: View) = show(a, list(
        menuRow(R.drawable.ic_tune, "Hue, Saturation, Brightness") { hsb() },
        menuRow(R.drawable.ic_wand, "Gaussian Blur") {
            adjustBar("Gaussian Blur", listOf(Triple("Radius", 1f..60f, 8f)), null) { v ->
                val snack = Snackbar.make(root, "Blurring…", Snackbar.LENGTH_INDEFINITE).also { it.show() }
                pv.blur(v[0].toInt()) { snack.dismiss(); pv.hConfirm() }
            }
        },
        menuRow(R.drawable.ic_wand, "Invert") { pv.bakeFilter(ColorMatrixColorFilter(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))) },
        menuRow(R.drawable.ic_wand, "Desaturate") { pv.bakeFilter(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })) },
    ))

    /** Luminance-preserving hue rotation (the standard SVG/CSS hue-rotate matrix). */
    private fun hueMatrix(deg: Float): ColorMatrix {
        val a = Math.toRadians(deg.toDouble()); val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        val r = 0.213f; val g = 0.715f; val b = 0.072f
        return ColorMatrix(floatArrayOf(
            r + c * (1 - r) - s * r, g - c * g - s * g, b - c * b + s * (1 - b), 0f, 0f,
            r - c * r + s * 0.143f, g + c * (1 - g) + s * 0.140f, b - c * b - s * 0.283f, 0f, 0f,
            r - c * r - s * (1 - r), g - c * g + s * g, b + c * (1 - b) + s * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    private fun hsbMatrix(v: FloatArray) = hueMatrix(v[0]).apply {
        postConcat(ColorMatrix().apply { setSaturation(v[1]) })
        val b = v[2] * 255
        postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))
    }

    private fun hsb() = adjustBar(
        "Hue, Saturation, Brightness",
        listOf(Triple("Hue", -180f..180f, 0f), Triple("Saturation", 0f..2f, 1f), Triple("Brightness", -1f..1f, 0f)),
        live = { v -> pv.preview = ColorMatrixColorFilter(hsbMatrix(v)) },
    ) { v -> pv.bakeFilter(ColorMatrixColorFilter(hsbMatrix(v))) }

    private fun brushLibrary(anchor: View) {
        val t = pv.tool
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(8), dpi(4), dpi(8), dpi(12)) }
        val title = arrayOf("Brush", "Smudge", "Erase")[t]
        col.addView(TextView(this).apply { text = "$title library"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium); setPadding(dpi(12), dpi(12), 0, dpi(4)) })
        val previewColor = themeColor(Attr.onSurface)
        for ((group, idx) in Brushes.groups) {
            col.addView(header(group))
            for (i in idx) {
                val b = Brushes.all[i]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
                    if (pv.brush[t] == i) background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(themeColor(Attr.secondaryContainer)) }
                    addView(TextView(context).apply { text = b.name; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
                    addView(ImageView(context).apply { setImageBitmap(Brushes.preview(b, dpi(240), dpi(40), previewColor)) })
                    setOnClickListener { it.hTick(); pv.brush[t] = i; openPopup?.dismiss() }
                }
                col.addView(row, LinearLayout.LayoutParams(dpi(272), -2).apply { topMargin = dpi(2) })
            }
        }
        show(anchor, ScrollView(this).apply { addView(col); layoutParams = ViewGroup.LayoutParams(-2, min(dpi(600), (resources.displayMetrics.heightPixels * 0.75f).toInt())) })
    }

    // ---- colour + ColorDrop ----

    private fun setupColorButton() {
        var downX = 0f; var downY = 0f; var dragging = false
        var drop: Swatch? = null
        colorBtn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; v.springScale(0.88f, 1f, 1500f) }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > dp(16f)) {
                        dragging = true; v.hTick()
                        drop = Swatch(this).apply { col = pv.color; layoutParams = FrameLayout.LayoutParams(dpi(44), dpi(44)) }
                        root.addView(drop); drop!!.popIn()
                    }
                    drop?.let { val loc = IntArray(2); root.getLocationOnScreen(loc); it.x = e.rawX - loc[0] - dp(22f); it.y = e.rawY - loc[1] - dp(22f) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.springScale(1f, 0.35f, 500f)
                    val d = drop
                    if (dragging && d != null) {
                        val loc = IntArray(2); pv.getLocationOnScreen(loc)
                        pv.fillAt(e.rawX - loc[0], e.rawY - loc[1]) { ok -> if (ok) pv.hConfirm() else pv.hReject() }
                        d.popOut { root.removeView(d) }; drop = null
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) colorPanel(v)
                }
            }
            true
        }
    }

    private fun colorPanel(anchor: View) {
        show(anchor, colorPanel(this, prefs, pv.color) { c -> setColor(c) }).setOnDismissListener { Recent.push(prefs, pv.color) }
    }

    // ---- layers ----

    private fun layersPanel(anchor: View) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(8), dpi(4), dpi(8), dpi(8)) }
        fun rebuild() {
            col.removeAllViews()
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(12), dpi(4), 0, dpi(4)) }
            head.addView(TextView(this).apply { text = "Layers"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(ctxButton(R.drawable.ic_add, null) { if (pv.addLayer()) { it.hConfirm(); rebuildLater(col) } else layerLimit() }.apply { contentDescription = "Add layer" })
            col.addView(head)
            for (l in art.layers.asReversed()) col.addView(layerRow(l, col), LinearLayout.LayoutParams(dpi(300), dpi(64)).apply { topMargin = dpi(4) })
            col.addView(backgroundRow(col), LinearLayout.LayoutParams(dpi(300), dpi(56)).apply { topMargin = dpi(8) })
        }
        col.tag = Runnable { rebuild() }
        rebuild()
        show(anchor, ScrollView(this).apply { addView(col); layoutParams = ViewGroup.LayoutParams(-2, min(dpi(640), (resources.displayMetrics.heightPixels * 0.75f).toInt())) })
    }

    private fun rebuildLater(col: View) = (col.tag as? Runnable)?.run()

    private fun layerRow(l: ALayer, col: View): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(8), 0, dpi(4), 0)
            background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(if (l === pv.active) themeColor(Attr.secondaryContainer) else 0) }
        }
        val thumb = art.thumb(l, dpi(64), dpi(48))
        row.addView(ImageView(this).apply {
            setImageBitmap(thumb); scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8f); setColor(0xFFFFFFFF.toInt()); setStroke(dpi(1), themeColor(Attr.outlineVariant)) }
            setPadding(dpi(2), dpi(2), dpi(2), dpi(2))
        }, LinearLayout.LayoutParams(dpi(64), dpi(48)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, 0, 0) }
        texts.addView(TextView(this).apply { text = l.name; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge); maxLines = 1 })
        texts.addView(TextView(this).apply {
            text = "${Blend.names[l.blend]} · ${(l.opacity * 100).toInt()}%" + if (l.alphaLock) " · α lock" else ""
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); setTextColor(themeColor(Attr.onSurfaceVariant))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MaterialCheckBox(this).apply {
            isChecked = l.visible; contentDescription = "Show ${l.name}"
            setOnCheckedChangeListener { v, on -> v.hToggle(on); val b = pv.snap(); l.visible = on; pv.commit(b) }
        })
        row.setOnClickListener {
            it.hTick()
            if (l === pv.active) layerOptions(it, l, col) else { pv.active = l; pv.invalidate(); rebuildLater(col) }
        }
        return row
    }

    private fun backgroundRow(col: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(8), 0, dpi(4), 0)
        addView(Swatch(context).apply { this.col = art.bg }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        addView(TextView(context).apply {
            text = "Background colour"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge); setPadding(dpi(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(MaterialCheckBox(context).apply {
            isChecked = art.bgVisible; contentDescription = "Show background"
            setOnCheckedChangeListener { v, on -> v.hToggle(on); val b = pv.snap(); art.bgVisible = on; pv.commit(b) }
        })
        setOnClickListener { it.hTick(); val b = pv.snap(); art.bg = pv.color; pv.commit(b); rebuildLater(col) }
    }

    private fun layerOptions(anchor: View, l: ALayer, col: View) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16), dpi(12), dpi(16), dpi(12)) }
        box.addView(TextView(this).apply { text = "Opacity"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge) })
        var before: PaintView.Snap? = null
        box.addView(Slider(this).apply {
            valueFrom = 0f; valueTo = 1f; value = l.opacity; contentDescription = "Layer opacity"
            setLabelFormatter { "${(it * 100).toInt()}%" }
            addOnChangeListener { _, v, user -> if (user) { l.opacity = v; pv.invalidate() } }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) { before = pv.snap() }
                override fun onStopTrackingTouch(s: Slider) { before?.let { pv.commit(it) }; rebuildLater(col) }
            })
        }, LinearLayout.LayoutParams(dpi(280), -2))
        box.addView(TextView(this).apply { text = "Blend mode"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge); setPadding(0, dpi(8), 0, 0) })
        val chips = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true; chipSpacingVertical = 0 }
        Blend.names.forEachIndexed { i, name ->
            chips.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = name; isCheckable = true; isChecked = l.blend == i
                setOnClickListener { it.hTick(); val b = pv.snap(); l.blend = i; pv.commit(b); rebuildLater(col) }
            })
        }
        box.addView(chips, LinearLayout.LayoutParams(dpi(280), -2))
        box.addView(MaterialSwitch(this).apply {
            text = "Alpha lock"; isChecked = l.alphaLock
            setOnCheckedChangeListener { v, on -> v.hToggle(on); val b = pv.snap(); l.alphaLock = on; pv.commit(b); rebuildLater(col) }
        })
        fun act(text: String, f: () -> Unit) = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            this.text = text; setOnClickListener { it.hTick(); f(); openPopup?.dismiss() }
        }
        val grid = ChipGroup(this)
        grid.addView(act("Rename") { ask("Rename layer", "Name", l.name) { n -> val b = pv.snap(); l.name = n; pv.commit(b) } })
        grid.addView(act("Duplicate") { if (!pv.duplicateLayer(l)) layerLimit() })
        grid.addView(act("Merge down") { if (!pv.mergeDown(l)) Toast.makeText(this, "Nothing below to merge into", Toast.LENGTH_SHORT).show() })
        grid.addView(act("Move up") { pv.moveLayer(l, 1) })
        grid.addView(act("Move down") { pv.moveLayer(l, -1) })
        grid.addView(act("Clear") { pv.clearLayer(l) })
        grid.addView(act("Fill") { pv.fillLayer(l, pv.color) })
        grid.addView(act("Delete") { if (art.layers.size > 1) { pv.hReject(); pv.deleteLayer(l) } })
        box.addView(grid, LinearLayout.LayoutParams(dpi(280), -2))
        popover(anchor, box)
    }

    private fun layerLimit() { pv.hReject(); Snackbar.make(root, "Layer limit reached for this canvas size", Snackbar.LENGTH_SHORT).show() }

    // ---- photos ----

    private fun placePhoto(fetch: () -> ByteArray?) {
        val snack = Snackbar.make(root, "Adding picture…", Snackbar.LENGTH_INDEFINITE).also { it.show() }
        bg.execute {
            val bytes = runCatching(fetch).getOrNull()
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            runOnUiThread {
                snack.dismiss()
                if (bmp == null) { root.hReject(); Snackbar.make(root, "Couldn't load that picture", Snackbar.LENGTH_LONG).show(); return@runOnUiThread }
                if (!pv.addLayer()) { layerLimit(); return@runOnUiThread }
                insertedLayer = true
                pv.beginTransformWith(bmp.copy(Bitmap.Config.ARGB_8888, true))
                pv.tool = PaintView.TRANSFORM
                for ((k, b) in toolBtns) b.isChecked = k == PaintView.TRANSFORM
                showCtx(PaintView.TRANSFORM); root.hConfirm()
            }
        }
    }

    // ---- export & save ----

    private fun export(kind: String) {
        if (pv.floating != null) pv.applyTransform()
        val snack = Snackbar.make(root, "Preparing…", Snackbar.LENGTH_INDEFINITE).also { it.show() }
        val f = exportFile(art.title, kind)
        val flat = if (kind == "psd") null else art.flatten()
        bg.execute {
            runCatching {
                f.outputStream().use { o ->
                    when (kind) {
                        "png" -> flat!!.compress(Bitmap.CompressFormat.PNG, 100, o)
                        "jpg" -> flat!!.compress(Bitmap.CompressFormat.JPEG, 95, o)
                        else -> ArtIO.psd(art, o)
                    }
                }
                flat?.recycle()
            }.onSuccess {
                runOnUiThread { snack.dismiss(); shareFile(f, if (kind == "png") "image/png" else if (kind == "jpg") "image/jpeg" else "image/vnd.adobe.photoshop") }
            }.onFailure { runOnUiThread { snack.dismiss(); Snackbar.make(root, "Export failed: ${it.message}", Snackbar.LENGTH_LONG).show() } }
        }
    }

    override fun save() {
        if (!dirty || !::art.isInitialized) return
        dirty = false
        ArtIO.save(this, art)
    }
}
