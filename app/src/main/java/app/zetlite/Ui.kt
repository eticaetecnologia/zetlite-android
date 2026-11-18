package app.zetlite

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/** Cores e helpers de interface (sem XML, sem bibliotecas). */
class Pal(dark: Boolean) {
    val bg = if (dark) Color.parseColor("#16181c") else Color.WHITE
    val fg = if (dark) Color.parseColor("#e6e6e6") else Color.parseColor("#1c1c1c")
    val bar = if (dark) Color.parseColor("#22262b") else Color.parseColor("#eef3f1")
    val accent = if (dark) Color.parseColor("#6fd0b5") else Color.parseColor("#1E6F5C")
    val dim = if (dark) Color.parseColor("#8a9099") else Color.parseColor("#6b7280")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.flatButton(text: String, pal: Pal, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(pal.accent)
        setBackgroundColor(0)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
        setOnClickListener { onClick() }
    }

fun View.visible(show: Boolean) {
    visibility = if (show) View.VISIBLE else View.GONE
}
