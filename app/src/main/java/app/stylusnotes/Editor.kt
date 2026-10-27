package app.stylusnotes

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import java.io.File

/** What both editors share: the open transition, autosave, insets, settings, sharing, the touch debug overlay. */
abstract class EditorBase : AppCompatActivity() {
    lateinit var prefs: SharedPreferences
    protected val h = Handler(Looper.getMainLooper())
    protected var dirty = false
    private val autosave = Runnable { save() }
    private val debugLines = ArrayDeque<String>()

    /** Writes the document if [dirty]. */
    abstract fun save()

    override fun onCreate(b: Bundle?) {
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementEnterTransition = MaterialContainerTransform().apply { addTarget(android.R.id.content); duration = 420 }
        window.sharedElementReturnTransition = MaterialContainerTransform().apply { addTarget(android.R.id.content); duration = 320 }
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("p", 0)
    }

    override fun setContentView(v: View?) {
        super.setContentView(v)
        findViewById<View>(android.R.id.content).transitionName = "doc"
    }

    override fun setContentView(id: Int) {
        super.setContentView(id)
        findViewById<View>(android.R.id.content).transitionName = "doc"
    }

    fun markDirty() { dirty = true; h.removeCallbacks(autosave); h.postDelayed(autosave, 4000) }

    override fun onPause() { h.removeCallbacks(autosave); save(); super.onPause() }

    /** Pushes [top] views below the status bar and [bottom] views above the navigation bar, on top of their XML margins. */
    fun applyInsets(root: View, top: List<View>, bottom: List<View>) {
        fun base(v: View, key: Int, get: (ViewGroup.MarginLayoutParams) -> Int) =
            (v.getTag(key) as? Int) ?: get(v.layoutParams as ViewGroup.MarginLayoutParams).also { v.setTag(key, it) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            for (v in top) { val lp = v.layoutParams as ViewGroup.MarginLayoutParams; lp.topMargin = base(v, R.id.inset_top) { it.topMargin } + s.top; v.layoutParams = lp }
            for (v in bottom) { val lp = v.layoutParams as ViewGroup.MarginLayoutParams; lp.bottomMargin = base(v, R.id.inset_bottom) { it.bottomMargin } + s.bottom; v.layoutParams = lp }
            ins
        }
    }

    fun attachDebug(router: InputRouter, tv: TextView) {
        val on = prefs.getBoolean("debug", false)
        tv.visibility = if (on) View.VISIBLE else View.GONE
        router.onDebug = if (!on) null else { s ->
            debugLines.addLast(s); while (debugLines.size > 6) debugLines.removeFirst()
            tv.text = debugLines.joinToString("\n")
        }
    }

    fun exportFile(title: String, ext: String) =
        File(File(cacheDir, "share").apply { mkdirs() }, title.ifEmpty { "untitled" }.replace(Regex("[^A-Za-z0-9_-]+"), "_") + ".$ext")

    fun shareFile(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }

    fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES): Pair<TextInputLayout, TextInputEditText> {
        val l = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = hint }
        val e = TextInputEditText(l.context).apply { setText(value); inputType = type; setSelection(value.length) }
        l.addView(e)
        return l to e
    }

    fun padded(v: View) = FrameLayout(this).apply { setPadding(dpi(24), dpi(8), dpi(24), 0); addView(v, ViewGroup.LayoutParams(-1, -2)) }

    fun ask(title: String, hint: String, value: String, ok: String = "OK", done: (String) -> Unit) {
        val (l, e) = field(hint, value)
        MaterialAlertDialogBuilder(this).setTitle(title).setView(padded(l))
            .setNegativeButton("Cancel", null).setPositiveButton(ok) { _, _ -> done(e.text.toString().trim()) }.show()
        e.requestFocus()
    }

    /** Pen/palm settings shared by both editors; [extra] adds editor-specific rows. */
    fun settings(extra: (LinearLayout) -> Unit, changed: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(24), dpi(8), dpi(24), 0) }
        fun label(t: String) = TextView(this).apply { text = t; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall); setPadding(0, dpi(12), 0, 0) }
        box.addView(label("StreamLine (stabilizer)"))
        box.addView(Slider(this).apply {
            valueFrom = 0f; valueTo = 10f; stepSize = 1f; value = prefs.getInt("stab", 2).toFloat(); labelBehavior = LabelFormatter.LABEL_FLOATING
            addOnChangeListener { s, v, user -> if (user) { s.hTick(); prefs.edit().putInt("stab", v.toInt()).apply(); changed() } }
        })
        fun sw(text: String, key: String, def: Boolean) = box.addView(MaterialSwitch(this).apply {
            this.text = text; isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { v, on -> v.hToggle(on); prefs.edit().putBoolean(key, on).apply(); changed() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
        sw("Hold to snap lines and shapes (QuickShape)", "snap", true)
        sw("Draw with finger", "finger", false)
        sw("Show touch debug overlay", "debug", false)
        extra(box)
        box.addView(TextView(this).apply {
            text = "Gestures: two-finger tap undoes, three-finger tap redoes, pinch to zoom. " +
                "Touches are ignored while the pen is near the glass, and any contact wider than a fingertip counts as a palm."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(themeColor(Attr.onSurfaceVariant)); setPadding(0, dpi(16), 0, dpi(8))
        })
        MaterialAlertDialogBuilder(this).setTitle("Settings").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).show()
    }
}

/** A sample stroke drawn with the real note renderer, for the pen picker. */
class StrokePreview(c: android.content.Context, private val kind: Int, private val color: Int) : View(c) {
    private val painter = Painter()
    private val d = c.dp(1f)
    private val s = Stroke(color, kind, 3.5f, FloatArray(30 * 3).also {
        for (i in 0 until 30) {
            val t = i / 29f
            it[i * 3] = t * 96f; it[i * 3 + 1] = kotlin.math.sin(t * 2 * Math.PI).toFloat() * 7f
            it[i * 3 + 2] = kotlin.math.sin(t * Math.PI).toFloat() * 0.85f + 0.15f
        }
    })
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((112 * d).toInt(), (40 * d).toInt())
    override fun onDraw(c: Canvas) { c.save(); c.translate(8 * d, height / 2f); c.scale(d, d); painter.drawStroke(c, s); c.restore() }
}

