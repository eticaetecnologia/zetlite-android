package app.zetlite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.URLDecoder

class EditorActivity : Activity() {
    private lateinit var store: Store
    private lateinit var pal: Pal
    private lateinit var file: File
    private lateinit var edit: EditText
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var tools: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var preview = false
    private var focus = false
    private var dirty = false

    private val saveTask = Runnable { save() }
    private val hlTask = Runnable { highlight() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        pal = Pal(store.dark)
        file = File(intent.getStringExtra("path") ?: run { finish(); return })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }
        tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bar)
            setPadding(0, dp(24), 0, 0)
        }
        tools.addView(row(
            "←" to { finish() },
            "Prévia" to { togglePreview() },
            "Links" to { showLinks() },
            "Exportar" to { exportMenu() },
            "Foco" to { setFocus(true) },
            "Meta" to { setTarget() }
        ))
        tools.addView(row(
            "H" to { linePrefix("## ") },
            "B" to { wrap("**", "**") },
            "I" to { wrap("*", "*") },
            "`" to { wrap("`", "`") },
            "[[ ]]" to { wrap("[[", "]]") },
            "Link" to { wrap("[", "](url)") },
            "•" to { linePrefix("- ") },
            "☐" to { linePrefix("- [ ] ") },
            ">" to { linePrefix("> ") },
            "Tabela" to { insert("| A | B |\n|---|---|\n|   |   |\n") },
            "[@]" to { pickCitation() },
            "[^]" to { footnote() },
            "$" to { wrap("$", "$") },
            "#" to { insert("#") }
        ))
        root.addView(tools)

        edit = EditText(this).apply {
            setBackgroundColor(pal.bg)
            setTextColor(pal.fg)
            setHintTextColor(pal.dim)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setText(store.read(file))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    dirty = true
                    ui.removeCallbacks(saveTask); ui.postDelayed(saveTask, 1500)
                    ui.removeCallbacks(hlTask); ui.postDelayed(hlTask, 250)
                    updateStatus()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(edit, LinearLayout.LayoutParams(-1, 0, 1f))

        web = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(pal.bg)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.scheme == "zl") {
                        val name = URLDecoder.decode(u.lastPathSegment ?: "", "UTF-8")
                        open(store.resolve(name))
                        return true
                    }
                    if (u.scheme == "http" || u.scheme == "https") {
                        startActivity(Intent(Intent.ACTION_VIEW, u)); return true
                    }
                    return false
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            setTextColor(pal.dim)
            textSize = 12f
            setBackgroundColor(pal.bar)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { if (focus) setFocus(false) else setTarget() }
        }
        root.addView(status)
        setContentView(root)
        highlight()
        updateStatus()
    }

    // ---------- barra de ferramentas ----------
    private fun row(vararg items: Pair<String, () -> Unit>): HorizontalScrollView {
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (t, a) -> ll.addView(flatButton(t, pal, a)) }
        sv.addView(ll)
        return sv
    }

    private fun setFocus(on: Boolean) {
        focus = on
        tools.visible(!on)
        if (on) toast("Modo foco: toque na barra inferior para sair")
    }

    // ---------- edição ----------
    private fun insert(text: String) {
        val a = edit.selectionStart.coerceAtLeast(0)
        val b = edit.selectionEnd.coerceAtLeast(0)
        edit.text.replace(minOf(a, b), maxOf(a, b), text)
    }

    private fun wrap(pre: String, suf: String) {
        val a = minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val b = maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val sel = edit.text.substring(a, b)
        edit.text.replace(a, b, pre + sel + suf)
        edit.setSelection(a + pre.length, a + pre.length + sel.length)
    }

    private fun linePrefix(p: String) {
        val t = edit.text
        val pos = edit.selectionStart.coerceAtLeast(0)
        val start = t.lastIndexOf('\n', pos - 1) + 1
        t.insert(start, p)
    }

    private fun footnote() {
        val t = edit.text.toString()
        var n = 1
        while (t.contains("[^$n]")) n++
        insert("[^$n]")
        edit.text.append("\n\n[^$n]: ")
    }

    private fun pickCitation() {
        val bib = store.bib()
        if (bib.isEmpty()) {
            toast("Coloque um arquivo .bib na pasta das notas"); return
        }
        val keys = bib.keys.toList()
        AlertDialog.Builder(this).setTitle("Citação")
            .setItems(keys.map { "$it — ${bib[it]}" }.toTypedArray()) { _, i -> insert("[@${keys[i]}]") }
            .show()
    }

    // ---------- realce de sintaxe ----------
    private val hl = listOf(
        Regex("(?m)^#{1,6} .*$") to 0,
        Regex("\\*\\*[^*\\n]+\\*\\*") to 1,
        Regex("(?<!\\*)\\*[^*\\n]+\\*(?!\\*)") to 2,
        Regex("`[^`\\n]+`") to 3,
        Regex("\\[\\[[^\\]\\n]+\\]\\]") to 4,
        Regex("\\[@[^\\]\\n]+\\]") to 4,
        Regex("(?<![\\w&;#/\"=])#[\\p{L}][\\p{L}\\p{N}_/\\-]*") to 4,
        Regex("\\$[^$\\n]+\\$") to 5,
        Regex("(?m)^>.*$") to 6,
        Regex("(?m)^\\s*([-*+]|\\d+\\.)\\s") to 4
    )

    private fun highlight() {
        val t = edit.text
        t.getSpans(0, t.length, ForegroundColorSpan::class.java).forEach { t.removeSpan(it) }
        t.getSpans(0, t.length, StyleSpan::class.java).forEach { t.removeSpan(it) }
        t.getSpans(0, t.length, BackgroundColorSpan::class.java).forEach { t.removeSpan(it) }
        t.getSpans(0, t.length, RelativeSizeSpan::class.java).forEach { t.removeSpan(it) }
        val fl = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        val code = if (store.dark) Color.parseColor("#2c3138") else Color.parseColor("#e8ece9")
        hl.forEach { (re, kind) ->
            re.findAll(t).forEach { m ->
                val s = m.range.first
                val e = m.range.last + 1
                when (kind) {
                    0 -> {
                        t.setSpan(ForegroundColorSpan(pal.accent), s, e, fl)
                        t.setSpan(StyleSpan(Typeface.BOLD), s, e, fl)
                        t.setSpan(RelativeSizeSpan(1.15f), s, e, fl)
                    }
                    1 -> t.setSpan(StyleSpan(Typeface.BOLD), s, e, fl)
                    2 -> t.setSpan(StyleSpan(Typeface.ITALIC), s, e, fl)
                    3 -> t.setSpan(BackgroundColorSpan(code), s, e, fl)
                    4 -> t.setSpan(ForegroundColorSpan(pal.accent), s, e, fl)
                    5 -> t.setSpan(ForegroundColorSpan(Color.parseColor("#b5651d")), s, e, fl)
                    6 -> t.setSpan(ForegroundColorSpan(pal.dim), s, e, fl)
                }
            }
        }
    }

    // ---------- status e meta de escrita ----------
    private fun words(): Int = edit.text.toString().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun updateStatus() {
        val w = words()
        val tg = store.target(file)
        status.text = "$w palavras · ${edit.text.length} caracteres" +
            if (tg > 0) " · meta $tg (${(w * 100 / tg).coerceAtMost(999)}%)" else ""
    }

    private fun setTarget() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(store.target(file).takeIf { it > 0 }?.toString() ?: "")
            hint = "Palavras (0 remove)"
        }
        AlertDialog.Builder(this).setTitle("Meta de palavras").setView(et)
            .setPositiveButton("OK") { _, _ ->
                store.setTarget(file, et.text.toString().toIntOrNull() ?: 0); updateStatus()
            }.setNegativeButton("Cancelar", null).show()
    }

    // ---------- links e backlinks ----------
    private fun showLinks() {
        val out = store.linksOf(edit.text.toString())
        val back = store.backlinks(file)
        val labels = out.map { "→ $it" } + back.map { "← ${store.title(it)}" }
        if (labels.isEmpty()) { toast("Sem links nem backlinks. Use [[nome]]."); return }
        AlertDialog.Builder(this).setTitle("Links e backlinks")
            .setItems(labels.toTypedArray()) { _, i ->
                open(if (i < out.size) store.resolve(out[i]) else back[i - out.size])
            }.show()
    }

    private fun open(f: File) {
        save()
        startActivity(Intent(this, EditorActivity::class.java).putExtra("path", f.path))
    }

    // ---------- prévia e exportação ----------
    private fun htmlNow(): String =
        Md.page(store.title(file), edit.text.toString(), store.bib(), store.dark)

    private fun togglePreview() {
        preview = !preview
        if (preview) {
            save()
            web.loadDataWithBaseURL("file://" + file.parent + "/", htmlNow(), "text/html", "utf-8", null)
        }
        web.visible(preview)
        edit.visible(!preview)
    }

    private fun exportMenu() {
        AlertDialog.Builder(this).setTitle("Exportar")
            .setItems(arrayOf("HTML (salva ao lado da nota)", "PDF (diálogo de impressão)")) { _, i ->
                if (i == 0) {
                    val html = Md.page(store.title(file), edit.text.toString(), store.bib(), false)
                    val out = File(file.parentFile, store.title(file) + ".html")
                    out.writeText(html)
                    toast("Salvo: ${out.name}")
                } else exportPdf()
            }.show()
    }

    private fun exportPdf() {
        val w = WebView(this)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(store.title(file), v.createPrintDocumentAdapter(store.title(file)),
                    PrintAttributes.Builder().build())
            }
        }
        w.loadDataWithBaseURL("file://" + file.parent + "/",
            Md.page(store.title(file), edit.text.toString(), store.bib(), false), "text/html", "utf-8", null)
    }

    // ---------- ciclo de vida ----------
    private fun save() {
        if (dirty) {
            store.write(file, edit.text.toString())
            dirty = false
        }
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onBackPressed() {
        if (preview) togglePreview()
        else if (focus) setFocus(false)
        else super.onBackPressed()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
