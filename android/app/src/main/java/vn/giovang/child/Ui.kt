package vn.giovang.child

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Giao diện dựng bằng code cho gọn, cùng bảng màu với mockup
object Ui {
    val BG = Color.parseColor("#F4F6F1")
    val CARD = Color.WHITE
    val LINE = Color.parseColor("#DDE3DD")
    val TEXT = Color.parseColor("#16231F")
    val MUTED = Color.parseColor("#55645E")
    val BRAND = Color.parseColor("#0F6E5C")
    val BRAND_SOFT = Color.parseColor("#DCEFE8")
    val WARN = Color.parseColor("#D9822B")
    val WARN_SOFT = Color.parseColor("#FDEFD3")
    val WARN_TEXT = Color.parseColor("#5C3600")
    val DARK = Color.parseColor("#0A3D34")
    val DARK_MUTED = Color.parseColor("#BFD9D1")
    val DANGER = Color.parseColor("#B42318")

    const val PLAIN = 0
    const val PRIMARY = 1
    const val ORANGE = 2
    const val GHOST_DARK = 3

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun shape(color: Int, radius: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun screen(a: Activity, bg: Int = BG): LinearLayout {
        val scroll = ScrollView(a).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(a, 20)
            setPadding(p, dp(a, 28), p, dp(a, 28))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        a.setContentView(scroll)
        a.window.statusBarColor = bg
        return root
    }

    fun col(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(c: Context, color: Int = CARD, stroke: Int = LINE): LinearLayout = col(c).apply {
        val p = dp(c, 16)
        setPadding(p, p, p, p)
        background = shape(color, dp(c, 20).toFloat(), dp(c, 1), stroke)
    }

    fun text(c: Context, s: CharSequence, sp: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

    fun button(c: Context, s: String, style: Int = PLAIN, onClick: () -> Unit): Button = Button(c).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = dp(c, 52)
        minimumHeight = dp(c, 52)
        setPadding(dp(c, 16), 0, dp(c, 16), 0)
        val r = dp(c, 16).toFloat()
        when (style) {
            PRIMARY -> { background = shape(BRAND, r); setTextColor(Color.WHITE) }
            ORANGE -> { background = shape(Color.parseColor("#F2A33A"), r); setTextColor(Color.parseColor("#3A2200")) }
            GHOST_DARK -> { background = shape(Color.TRANSPARENT, r, dp(c, 2), BRAND_SOFT); setTextColor(Color.WHITE) }
            else -> { background = shape(Color.WHITE, r, dp(c, 1), LINE); setTextColor(TEXT) }
        }
        setOnClickListener { onClick() }
    }

    fun chip(c: Context, s: String, on: Boolean, onClick: () -> Unit): Button = Button(c).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = dp(c, 48)
        minimumHeight = dp(c, 48)
        val r = dp(c, 12).toFloat()
        background = if (on) shape(BRAND_SOFT, r, dp(c, 2), BRAND) else shape(Color.WHITE, r, dp(c, 1), LINE)
        setTextColor(if (on) Color.parseColor("#0A4F42") else TEXT)
        setOnClickListener { onClick() }
    }

    fun input(c: Context, hint: String, numeric: Boolean = false, password: Boolean = false, multiLine: Boolean = false): EditText =
        EditText(c).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            val p = dp(c, 14)
            setPadding(p, dp(c, 12), p, dp(c, 12))
            minHeight = dp(c, 52)
            background = shape(Color.WHITE, dp(c, 12).toFloat(), dp(c, 1), LINE)
            inputType = when {
                numeric && password -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiLine) {
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
            }
        }
}

// Thêm view con với khoảng cách phía trên (dp)
fun LinearLayout.add(v: View, topDp: Int = 12, match: Boolean = true, weight: Float = 0f): View {
    val lp = if (orientation == LinearLayout.HORIZONTAL) {
        LinearLayout.LayoutParams(if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = Ui.dp(context, topDp)
        }
    } else {
        LinearLayout.LayoutParams(if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(context, topDp)
        }
    }
    addView(v, lp)
    return v
}
