package org.vera20k

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The palette and the small drawable helpers every screen here shares.
 *
 * Kept as top-level declarations so the launcher, the viewers and the settings
 * screen cannot drift apart: one accent, one surface, one radius.
 */
internal val COL_BG = 0xFF0B0D10.toInt()
internal val COL_SURFACE = 0xFF15181D.toInt()
internal val COL_SURFACE_HI = 0xFF1B1F26.toInt()
internal val COL_OUTLINE = 0x1FFFFFFF
internal val COL_TEXT = 0xFFECEEF1.toInt()
internal val COL_TEXT_DIM = 0xFF8B929C.toInt()
internal val COL_TEXT_FAINT = 0xFF5F666F.toInt()
internal val COL_ACCENT = 0xFFEFB44C.toInt()
internal val COL_ACCENT_DEEP = 0xFFDCA13C.toInt()
internal val COL_GOOD = 0xFF6BD08A.toInt()
internal val COL_BAD = 0xFFE8837A.toInt()
internal val COL_DARK_TEXT = 0xFF14161A.toInt()

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun TextView.sp(value: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, value)

/** A rounded rectangle with a hairline outline. */
internal fun Context.pill(color: Int, radiusDp: Int, stroke: Int = 0): Drawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

internal fun Context.square(color: Int, radiusDp: Int): Drawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

/** The card surface used by every panel and list group. */
internal fun Context.cardBg(): Drawable = pill(COL_SURFACE, 18, COL_OUTLINE)

/** Wraps a shape in a ripple so a tap has feedback on the dark surface. */
internal fun ripple(content: Drawable, color: Int = 0x26FFFFFF): Drawable =
    RippleDrawable(ColorStateList.valueOf(color), content, null)

/** The gold call-to-action, filled left to right. */
internal fun Context.accentButton(radiusDp: Int): Drawable =
    GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFFF4C566.toInt(), COL_ACCENT_DEEP),
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

/**
 * A bottom sheet built from the same tokens, for screens that keep no action
 * list of their own. The platform dialog would arrive light and square-cornered
 * on this surface, which is the one thing the redesign was meant to remove.
 */
internal fun Activity.darkSheet(
    title: String,
    subtitle: String,
    actions: List<Pair<String, () -> Unit>>,
    content: View? = null,
) {
    val dialog = Dialog(this)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = pill(COL_SURFACE, 20, COL_OUTLINE)
    }
    panel.addView(TextView(this).apply {
        text = title
        sp(15f)
        setTextColor(COL_TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    })
    if (subtitle.isNotEmpty()) {
        panel.addView(TextView(this).apply {
            text = subtitle
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(5), 0, dp(10))
        })
    }
    if (content != null) {
        panel.addView(content as View, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
    }
    for ((label, run) in actions) {
        panel.addView(TextView(this).apply {
            text = label
            sp(14f)
            setTextColor(COL_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = ripple(pill(COL_SURFACE_HI, 12, COL_OUTLINE))
            setOnClickListener {
                dialog.dismiss()
                run()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(7) })
    }

    dialog.setContentView(panel)
    dialog.show()
    dialog.window?.let {
        it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        it.setGravity(Gravity.BOTTOM)
        it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
