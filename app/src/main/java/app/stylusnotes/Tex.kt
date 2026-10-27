package app.stylusnotes

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/** LaTeX typesetting via jlatexmath. Its parser keeps global state, so every build goes through one lock. */
object Tex {
    private var ready = false

    @Synchronized fun init(c: Context) {
        if (!ready) { JLatexMathAndroid.init(c.applicationContext); ready = true }
    }

    @Synchronized fun drawable(latex: String, size: Float, color: Int): Drawable = try {
        JLatexMathDrawable.builder(latex).textSize(size).color(color).padding((size / 6).toInt()).build()
    } catch (e: Throwable) {
        TextDrawable("⚠ $latex", size * 0.8f, 0xFFB3261E.toInt())
    }

    val templates = listOf(
        "a/b" to "\\frac{a}{b}", "√" to "\\sqrt{x}", "xⁿ" to "x^{n}", "xᵢ" to "x_{i}", "Σ" to "\\sum_{i=1}^{n}",
        "∫" to "\\int_{a}^{b}", "lim" to "\\lim_{x \\to 0}", "π" to "\\pi", "α" to "\\alpha", "β" to "\\beta",
        "θ" to "\\theta", "∞" to "\\infty", "≤" to "\\leq", "≥" to "\\geq", "≠" to "\\neq", "±" to "\\pm",
        "·" to "\\cdot", "→" to "\\rightarrow", "∂" to "\\partial", "v⃗" to "\\vec{v}",
        "matrix" to "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
    )

    /** Equation editor with live preview and quick-insert chips. */
    fun editor(c: Context, initial: String, color: Int, done: (String) -> Unit) {
        val pad = c.dpi(24)
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, c.dpi(8), pad, 0) }
        val preview = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = android.widget.FrameLayout.LayoutParams(-1, c.dpi(88)) }
        val previewCard = MaterialCardView(c).apply {
            radius = c.dp(20f); strokeWidth = 0; setCardBackgroundColor(c.themeColor(Attr.surfaceHighest))
            setContentPadding(c.dpi(16), c.dpi(16), c.dpi(16), c.dpi(16))
            addView(preview)
        }
        val field = TextInputLayout(c, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "LaTeX" }
        val et = TextInputEditText(field.context).apply {
            setText(initial); typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        field.addView(et)
        fun refresh() { preview.setImageDrawable(drawable(et.text.toString().ifBlank { "\\square" }, c.dp(26f), color)) }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        val chips = ChipGroup(c).apply { isSingleLine = true }
        for ((label, snippet) in templates) chips.addView(Chip(c).apply {
            text = label
            setOnClickListener { hTick(); et.text?.insert(et.selectionStart.coerceAtLeast(0), snippet) }
        })
        box.addView(previewCard)
        box.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dpi(16) })
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dpi(8) })
        refresh()
        MaterialAlertDialogBuilder(c).setTitle(if (initial.isEmpty()) "Insert equation" else "Edit equation").setView(android.widget.ScrollView(c).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ -> et.text.toString().trim().let { if (it.isNotEmpty()) done(it) } }
            .show()
        et.requestFocus()
    }
}

/** Plain text as a Drawable, for formulas that fail to parse. */
class TextDrawable(private val text: String, size: Float, color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = Typeface.MONOSPACE }
    override fun getIntrinsicWidth() = p.measureText(text).toInt() + 2
    override fun getIntrinsicHeight() = (p.fontSpacing + 2).toInt()
    override fun draw(c: Canvas) { c.drawText(text, bounds.left.toFloat(), bounds.top - p.ascent(), p) }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
