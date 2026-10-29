package app.stylusnotes

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        Tex.init(this)
    }
}

/** Network and heavy pixel work; saves have their own queue ([Store.io]). */
val bg: ExecutorService = Executors.newFixedThreadPool(2)

fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.dpi(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dpi(v: Int) = context.dpi(v)
fun Context.themeColor(attr: Int) = MaterialColors.getColor(this, attr, Color.MAGENTA)

object Attr {
    val primary = androidx.appcompat.R.attr.colorPrimary
    val onSurface = com.google.android.material.R.attr.colorOnSurface
    val onSurfaceVariant = com.google.android.material.R.attr.colorOnSurfaceVariant
    val surfaceHigh = com.google.android.material.R.attr.colorSurfaceContainerHigh
    val surfaceHighest = com.google.android.material.R.attr.colorSurfaceContainerHighest
    val secondaryContainer = com.google.android.material.R.attr.colorSecondaryContainer
    val outlineVariant = com.google.android.material.R.attr.colorOutlineVariant
}

// ---- haptics (the Note 10.1 has no vibration motor, but phones and newer tablets do) ----

private fun View.fb(new: Int, old: Int, api: Int) = performHapticFeedback(if (Build.VERSION.SDK_INT >= api) new else old)
fun View.hTick() = fb(HapticFeedbackConstants.SEGMENT_TICK, HapticFeedbackConstants.CLOCK_TICK, 34)
fun View.hConfirm() = fb(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.VIRTUAL_KEY, 30)
fun View.hReject() = fb(HapticFeedbackConstants.REJECT, HapticFeedbackConstants.LONG_PRESS, 30)
fun View.hHeavy() = performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
fun View.hSnap() = fb(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, HapticFeedbackConstants.CONTEXT_CLICK, 34)
fun View.hToggle(on: Boolean) =
    fb(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF, HapticFeedbackConstants.CLOCK_TICK, 34)

// ---- springs ----

private fun View.spring(prop: DynamicAnimation.ViewProperty, key: Int, damping: Float, stiffness: Float): SpringAnimation {
    val a = getTag(key) as? SpringAnimation ?: SpringAnimation(this, prop).also { setTag(key, it) }
    a.spring = (a.spring ?: SpringForce()).apply { dampingRatio = damping; this.stiffness = stiffness }
    return a
}

fun View.springScale(to: Float, damping: Float = 0.5f, stiffness: Float = 600f) {
    spring(DynamicAnimation.SCALE_X, R.id.spring_sx, damping, stiffness).animateToFinalPosition(to)
    spring(DynamicAnimation.SCALE_Y, R.id.spring_sy, damping, stiffness).animateToFinalPosition(to)
}

fun View.springY(to: Float, damping: Float = 0.6f, stiffness: Float = 500f) =
    spring(DynamicAnimation.TRANSLATION_Y, R.id.spring_ty, damping, stiffness).animateToFinalPosition(to)

fun View.springX(to: Float, damping: Float = 0.6f, stiffness: Float = 500f) =
    spring(DynamicAnimation.TRANSLATION_X, R.id.spring_tx, damping, stiffness).animateToFinalPosition(to)

/** Scale + fade in with an overshoot. */
fun View.popIn(delay: Long = 0) {
    alpha = 0f; scaleX = 0.82f; scaleY = 0.82f
    animate().alpha(1f).setStartDelay(delay).setDuration(140).start()
    if (delay == 0L) springScale(1f, 0.55f, 450f) else postDelayed({ springScale(1f, 0.55f, 450f) }, delay)
}

fun View.popOut(end: () -> Unit = { visibility = View.GONE }) {
    animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setStartDelay(0).setDuration(130).withEndAction(end).start()
}

fun View.reveal(show: Boolean) {
    if (show == (visibility == View.VISIBLE)) return
    if (show) { visibility = View.VISIBLE; popIn() } else popOut()
}

/** Squash on press, bounce back on release. Returns false so clicks still fire. */
fun View.bouncy() = setOnTouchListener { v, e ->
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> v.springScale(0.88f, 1f, 1500f)
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.springScale(1f, 0.35f, 500f)
    }
    false
}

/** A Material card floating next to [anchor], springing out of it. */
fun popover(anchor: View, content: View, below: Boolean = true): PopupWindow {
    val c = anchor.context
    val card = MaterialCardView(c).apply {
        radius = c.dp(28f); cardElevation = c.dp(6f); strokeWidth = 0
        setCardBackgroundColor(c.themeColor(Attr.surfaceHigh))
        addView(content)
    }
    val m = c.dpi(12)
    val box = FrameLayout(c).apply { setPadding(m, m, m, m); clipToPadding = false; clipChildren = false; addView(card) }
    val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(0)); isOutsideTouchable = true; animationStyle = 0
    }
    // always the activity window: a popup can't be parented to another popup (anchors inside panels)
    val root = (c as? android.app.Activity)?.window?.decorView ?: anchor.rootView
    box.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST))
    val loc = IntArray(2); anchor.getLocationOnScreen(loc)
    val rl = IntArray(2); root.getLocationOnScreen(rl); loc[0] -= rl[0]; loc[1] -= rl[1]
    val x = (loc[0] + anchor.width / 2 - box.measuredWidth / 2).coerceIn(0, max(0, root.width - box.measuredWidth))
    var y = if (below) loc[1] + anchor.height - m / 2 else loc[1] - box.measuredHeight + m / 2
    y = y.coerceIn(0, max(0, root.height - box.measuredHeight))
    card.pivotX = (loc[0] + anchor.width / 2 - x - m).toFloat()
    card.pivotY = if (below) 0f else box.measuredHeight.toFloat()
    // explicit size: a WRAP_CONTENT popup is first laid out at a narrow "dialog width" on big screens,
    // which squashed views that need their full size (the colour wheel)
    pw.width = box.measuredWidth; pw.height = minOf(box.measuredHeight, root.height)
    pw.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
    card.popIn()
    anchor.hTick()
    return pw
}
