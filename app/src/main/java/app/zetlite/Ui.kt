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
    val side = if (dark) Color.parseColor("#1c1f23") else Color.parseColor("#f5f7f6")
    val accent = if (dark) Color.parseColor("#6fd0b5") else Color.parseColor("#1b9e77")
    val link = if (dark) Color.parseColor("#8ab4f8") else Color.parseColor("#1a5fd0")
    val bold = if (dark) Color.parseColor("#ffb86b") else Color.parseColor("#b45309")
    val italic = if (dark) Color.parseColor("#c39bff") else Color.parseColor("#7c3aed")
    val note = if (dark) Color.parseColor("#5ad1e6") else Color.parseColor("#0e7490")
    val cite = if (dark) Color.parseColor("#f78fb3") else Color.parseColor("#be185d")
    val math = if (dark) Color.parseColor("#e0a458") else Color.parseColor("#b5651d")
    val codeFg = if (dark) Color.parseColor("#e6b673") else Color.parseColor("#9a3412")
    val codeBg = if (dark) Color.parseColor("#2c3138") else Color.parseColor("#e8ece9")
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
