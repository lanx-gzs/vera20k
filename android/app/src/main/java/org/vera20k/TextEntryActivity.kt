package org.vera20k

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The screen that actually owns a keyboard.
 *
 * The game runs on a `NativeActivity`, whose window has no input connection:
 * anything added on top of its surface is either invisible or has no way to
 * raise the system input method. A separate, ordinary activity has neither
 * problem — its own window, its own `EditText`, and a keyboard the system is
 * obliged to show. The engine asks for this one at the moment a text field
 * takes focus, so the player never has to find a button first.
 *
 * The text is edited here, in a real editor with a real caret, then handed
 * back to the caller: the game's own edit stays the single source of truth
 * for what is drawn, and this screen is only the keyboard's host.
 */
class TextEntryActivity : Activity() {
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.25f)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = pill(COL_SURFACE, 18, COL_OUTLINE)
        }

        card.addView(TextView(this).apply {
            text = "输入到游戏"
            sp(14f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        card.addView(TextView(this).apply {
            text = "写回后出现在游戏里当前选中的输入框"
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(3), 0, dp(8))
        })

        input = EditText(this).apply {
            hint = "在此输入…"
            sp(15f)
            setTextColor(COL_TEXT)
            setHintTextColor(COL_TEXT_FAINT)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 3
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = pill(COL_SURFACE_HI, 12, COL_OUTLINE)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commit()
                    true
                } else {
                    false
                }
            }
        }
        card.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        buttons.addView(TextView(this).apply {
            text = "取消"
            sp(13f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(pill(COL_SURFACE_HI, 13, COL_OUTLINE))
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(10) })
        buttons.addView(TextView(this).apply {
            text = "写回"
            sp(14f)
            gravity = Gravity.CENTER
            setTextColor(COL_DARK_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ripple(accentButton(22), 0x40FFFFFF)
            setOnClickListener { commit() }
        }, LinearLayout.LayoutParams(0, dp(44), 1.6f))
        card.addView(buttons)

        root.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        setContentView(root)

        input.requestFocus()
        // A window that was attached a moment ago is not always allowed to take
        // the input method yet; asking twice is what makes this reliable across
        // the ROMs where one call is swallowed.
        input.postDelayed({
            val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    private fun commit() {
        val text = input.text?.toString().orEmpty()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun TextView.sp(value: Float) =
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, value)

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
