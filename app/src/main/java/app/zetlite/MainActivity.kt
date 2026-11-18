package app.zetlite

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.URLDecoder

/**
 * Tela única estilo Zettlr: barra lateral (arquivos, busca, tags, relacionados, sumário),
 * abas, editor, prévia e modo dividido. Em telas largas a barra lateral fica fixa;
 * no celular ela vira uma gaveta.
 */
class MainActivity : Activity() {
    private class Tab(var file: File, var cursor: Int = 0)

    private class Row(
        val title: String,
        val sub: String = "",
        val depth: Int = 0,
        val icon: String = "",
        val bold: Boolean = false,
        val onClick: () -> Unit,
        val onLong: (() -> Unit)? = null
    )

    private lateinit var store: Store
    private lateinit var pal: Pal
    private val ui = Handler(Looper.getMainLooper())

    // estado
    private val tabs = ArrayList<Tab>()
    private var cur = -1
    private var mode = 0              // 0 editor, 1 prévia, 2 dividido
    private var section = 0           // 0 arquivos, 1 busca, 2 tags, 3 relacionados, 4 sumário
    private var wide = false
    private var wasWide: Boolean? = null
    private var sideOpen = false
    private var focus = false
    private var dirty = false
    private var loading = false
    private var qSearch = ""
    private var qRefs = ""
    private var settingText = false
    private val applied = ArrayList<Any>()
    private lateinit var saveBtn: TextView
    private var rows: List<Row> = emptyList()
    private val expanded = HashSet<String>()
    private lateinit var selDir: File

    // views
    private lateinit var sidebar: LinearLayout
    private lateinit var scrim: View
    private lateinit var topBar: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabBox: LinearLayout
    private lateinit var edit: EditText
    private lateinit var web: WebView
    private lateinit var welcome: LinearLayout
    private lateinit var fmtBar: HorizontalScrollView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var modeBtn: TextView
    private lateinit var sideList: ListView
    private lateinit var sideTitle: TextView
    private lateinit var sideSearch: EditText
    private lateinit var sideTools: LinearLayout
    private lateinit var wsName: TextView
    private val secBtns = ArrayList<TextView>()
    private var sideWidth = 0

    private val saveTask = Runnable { saveCurrent() }
    private val hlTask = Runnable { highlight() }
    private val prevTask = Runnable { updatePreview() }

    // ------------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        selDir = store.root
        buildUi()
        // restaura abas da sessão anterior
        store.openTabs.map { File(it) }.filter { it.isFile }.forEach { tabs.add(Tab(it)) }
        cur = if (tabs.isEmpty()) -1 else store.curTab.coerceIn(0, tabs.size - 1)
        renderAll()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        saveCurrent()
        buildUi()
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        if (!hasStorage()) askStorage() else {
            store.ensureRoot()
            refreshSide()
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrent()
        persistTabs()
    }

    override fun onBackPressed() {
        when {
            !wide && sideOpen -> setSide(false)
            focus -> setFocus(false)
            mode != 0 -> setMode(0)
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ permissões
    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT >= 33

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            AlertDialog.Builder(this)
                .setTitle("Acesso a arquivos")
                .setMessage("O ZetLite guarda suas notas como arquivos .md em pastas do aparelho. Na próxima tela, ative \"Permitir acesso para gerenciar todos os arquivos\".")
                .setPositiveButton("Abrir configuração") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setCancelable(false)
                .show()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasStorage()) { store.ensureRoot(); refreshSide() }
    }

    // ------------------------------------------------------------------ construção da interface
    private fun tv(text: String, size: Float = 14f, color: Int = pal.fg): TextView =
        TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }

    private fun buildUi() {
        pal = Pal(store.dark)
        wide = resources.configuration.screenWidthDp >= 600
        if (wasWide != wide) { sideOpen = wide; wasWide = wide }
        window.statusBarColor = pal.bar
        var f = window.decorView.systemUiVisibility
        f = if (!store.dark) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        window.decorView.systemUiVisibility = f

        sideWidth = if (wide) dp(300) else minOf(dp(310), (resources.displayMetrics.widthPixels * 0.85f).toInt())
        secBtns.clear()
        sidebar = buildSidebar()
        val main = buildMain()

        val frame = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        if (wide) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(sidebar, LinearLayout.LayoutParams(sideWidth, -1))
            row.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
            frame.addView(row, FrameLayout.LayoutParams(-1, -1))
            scrim = View(this)
        } else {
            frame.addView(main, FrameLayout.LayoutParams(-1, -1))
            scrim = View(this).apply {
                setBackgroundColor(Color.parseColor("#66000000"))
                visibility = View.GONE
                setOnClickListener { setSide(false) }
            }
            frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            frame.addView(sidebar, FrameLayout.LayoutParams(sideWidth, -1))
        }
        setContentView(frame)
        applySide(false)
    }

    private fun buildSidebar(): LinearLayout {
        val sb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.side)
            isClickable = true
        }
        // cabeçalho: pasta de trabalho
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(4), dp(4))
        }
        wsName = tv(store.root.name.ifEmpty { "/" }, 16f, pal.accent).apply {
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { workspaceMenu() }
        }
        head.addView(wsName)
        head.addView(flatButton("Abrir pasta", pal) { workspaceMenu() })
        sb.addView(head, LinearLayout.LayoutParams(-1, dp(52)))

        // seções
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val icons = listOf("📁", "🔍", "#", "🔗", "≡", "📚")
        icons.forEachIndexed { i, ic ->
            val b = tv(ic, 18f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { goSection(i) }
            }
            secBtns.add(b)
            seg.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        sb.addView(seg, LinearLayout.LayoutParams(-1, -2))

        sideTitle = tv("", 12f, pal.dim).apply { setPadding(dp(14), dp(8), dp(14), dp(4)) }
        sb.addView(sideTitle)

        sideTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sideTools.addView(flatButton("+ Nota", pal) { newNote(selDir) })
        sideTools.addView(flatButton("+ Pasta", pal) { newFolder(selDir) })
        sb.addView(sideTools)

        sideSearch = EditText(this).apply {
            hint = "Buscar em todas as notas"
            setHintTextColor(pal.dim)
            setTextColor(pal.fg)
            setSingleLine()
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (settingText) return
                    if (section == 1) qSearch = s.toString() else if (section == 5) qRefs = s.toString()
                    if (section == 1 || section == 5) refreshSide()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        sb.addView(sideSearch)

        sideList = ListView(this).apply {
            divider = null
            setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.onClick?.invoke() }
            setOnItemLongClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.onLong?.invoke(); rows.getOrNull(pos)?.onLong != null }
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(p: Int): Any = rows[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, v: View?, parent: ViewGroup): View = rowView(rows[p])
            }
        }
        sb.addView(sideList, LinearLayout.LayoutParams(-1, 0, 1f))
        return sb
    }

    private fun rowView(r: Row): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10 + r.depth * 16), dp(9), dp(10), dp(9))
        }
        l.addView(tv(r.icon, 14f, pal.dim).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(22), -2))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(r.title, 15f, if (r.bold) pal.accent else pal.fg).apply {
            if (r.bold) typeface = Typeface.DEFAULT_BOLD
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (r.sub.isNotEmpty()) col.addView(tv(r.sub, 12f, pal.dim).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        l.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return l
    }

    private fun buildMain(): LinearLayout {
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }

        // barra superior
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.bar)
            setPadding(dp(2), 0, dp(2), 0)
        }
        topBar.addView(flatButton("☰", pal) { if (wide) { sideOpen = !sideOpen; applySide(false) } else setSide(!sideOpen) })
        title = tv("ZetLite", 16f, pal.fg).apply {
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        topBar.addView(title)
        saveBtn = flatButton("💾 Salvar", pal) { saveNow() }
        saveBtn.visible(false)
        topBar.addView(saveBtn)
        modeBtn = flatButton("Editor", pal) { cycleMode() }
        topBar.addView(modeBtn)
        lateinit var more: View
        more = flatButton("⋮", pal) { showMenu(more) }
        topBar.addView(more)
        main.addView(topBar, LinearLayout.LayoutParams(-1, dp(48)))

        // abas
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(pal.side)
        }
        tabBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabBox)
        main.addView(tabScroll, LinearLayout.LayoutParams(-1, dp(40)))

        // conteúdo
        val content = FrameLayout(this)
        val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        edit = EditText(this).apply {
            setBackgroundColor(pal.bg)
            setTextColor(pal.fg)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (loading) return
                    val was = dirty
                    dirty = true
                    if (!was) { saveBtn.visible(true); renderTabs() }
                    ui.removeCallbacks(saveTask); ui.postDelayed(saveTask, 1500)
                    ui.removeCallbacks(hlTask); ui.postDelayed(hlTask, 250)
                    if (mode == 2) { ui.removeCallbacks(prevTask); ui.postDelayed(prevTask, 700) }
                    updateStatus()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        web = WebView(this).apply {
            setBackgroundColor(pal.bg)
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    return when (u.scheme) {
                        "zl" -> { openFile(store.resolve(URLDecoder.decode(u.lastPathSegment ?: "", "UTF-8"))); true }
                        "http", "https" -> { startActivity(Intent(Intent.ACTION_VIEW, u)); true }
                        else -> false
                    }
                }
            }
        }
        split.addView(edit, LinearLayout.LayoutParams(0, -1, 1f))
        split.addView(web, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(split, FrameLayout.LayoutParams(-1, -1))
        welcome = buildWelcome()
        content.addView(welcome, FrameLayout.LayoutParams(-1, -1))
        main.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // formatação
        fmtBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(pal.bar)
        }
        val fl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items: List<Pair<String, () -> Unit>> = listOf(
            "H" to { linePrefix("## ") },
            "B" to { wrap("**", "**") },
            "I" to { wrap("*", "*") },
            "S" to { wrap("~~", "~~") },
            "`" to { wrap("`", "`") },
            "[[ ]]" to { pickNote() },
            "Link" to { wrap("[", "](url)") },
            "•" to { linePrefix("- ") },
            "1." to { linePrefix("1. ") },
            "☐" to { linePrefix("- [ ] ") },
            ">" to { linePrefix("> ") },
            "Tabela" to { insert("| A | B |\n|---|---|\n|   |   |\n") },
            "[@]" to { pickCitation() },
            "[^]" to { footnote() },
            "\$" to { wrap("$", "$") },
            "#" to { insert("#") }
        )
        items.forEach { (t, a) -> fl.addView(flatButton(t, pal, a)) }
        fmtBar.addView(fl)
        main.addView(fmtBar, LinearLayout.LayoutParams(-1, dp(44)))

        status = tv("", 12f, pal.dim).apply {
            setBackgroundColor(pal.bar)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { if (focus) setFocus(false) else setTarget() }
        }
        main.addView(status)
        return main
    }

    private fun buildWelcome(): LinearLayout {
        val w = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(pal.bg)
            isClickable = true
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        w.addView(tv("ZetLite", 28f, pal.accent).apply { typeface = Typeface.DEFAULT_BOLD })
        w.addView(tv("Abra uma pasta de trabalho ou crie sua primeira nota.", 14f, pal.dim).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16))
        })
        w.addView(flatButton("📂  Abrir pasta de trabalho", pal) { workspaceMenu() })
        w.addView(flatButton("✎  Nova nota", pal) { newNote(selDir) })
        w.addView(flatButton("?  Ajuda de sintaxe", pal) { showHelp() })
        return w
    }

    // ------------------------------------------------------------------ gaveta / barra lateral
    private fun setSide(open: Boolean) {
        sideOpen = open
        applySide(true)
        if (open) refreshSide()
    }

    private fun applySide(animate: Boolean) {
        if (wide) {
            sidebar.visible(sideOpen && !focus)
            return
        }
        val target = if (sideOpen && !focus) 0f else -sideWidth.toFloat()
        scrim.visible(sideOpen && !focus)
        if (animate) sidebar.animate().translationX(target).setDuration(160).start()
        else sidebar.translationX = target
    }

    private fun refreshSide() {
        wsName.text = store.root.name.ifEmpty { "/" }
        secBtns.forEachIndexed { i, b ->
            b.setBackgroundColor(if (i == section) pal.bg else 0)
            b.setTextColor(if (i == section) pal.accent else pal.dim)
        }
        sideTools.removeAllViews()
        if (section == 0) {
            sideTools.addView(flatButton("+ Nota", pal) { newNote(selDir) })
            sideTools.addView(flatButton("+ Pasta", pal) { newFolder(selDir) })
        } else if (section == 5) {
            sideTools.addView(flatButton("Zotero / arquivos…", pal) { zoteroMenu() })
        }
        sideTools.visible(section == 0 || section == 5)
        sideSearch.visible(section == 1 || section == 5)
        val list = ArrayList<Row>()
        when (section) {
            0 -> {
                sideTitle.text = "ARQUIVOS · novos itens em: ${selDir.name.ifEmpty { "/" }}"
                if (store.root.exists()) fileRows(store.root, 0, list)
                if (list.isEmpty()) list.add(Row("Pasta vazia. Toque em + Nota.", icon = "", onClick = {}))
            }
            1 -> {
                sideTitle.text = if (qSearch.isBlank()) "NOTAS RECENTES" else "RESULTADOS"
                searchRows(list)
            }
            2 -> {
                sideTitle.text = "TAGS"
                val tags = store.allTags()
                tags.forEach { (t, n) ->
                    list.add(Row("#$t", "$n nota(s)", icon = "#", onClick = {
                        goSection(1, "#$t")
                    }))
                }
                if (tags.isEmpty()) list.add(Row("Nenhuma tag. Escreva #tag no texto.", onClick = {}))
            }
            3 -> {
                sideTitle.text = "RELACIONADOS"
                val t = tabs.getOrNull(cur)
                if (t == null) list.add(Row("Abra uma nota.", onClick = {})) else {
                    val all = store.notes()
                    store.linksOf(edit.text.toString()).forEach { name ->
                        val hit = all.firstOrNull { store.title(it).equals(name, true) }
                        list.add(Row(name, if (hit == null) "nota ainda não existe" else "link de saída",
                            icon = "→", onClick = { openFile(store.resolve(name)) }))
                    }
                    store.backlinks(t.file).forEach { f ->
                        list.add(Row(store.title(f), "backlink", icon = "←", onClick = { openFile(f) }))
                    }
                    if (list.isEmpty()) list.add(Row("Sem links. Use [[nome]] no texto.", onClick = {}))
                }
            }
            4 -> {
                sideTitle.text = "SUMÁRIO"
                var off = 0
                edit.text.toString().split("\n").forEach { line ->
                    val m = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
                    if (m != null) {
                        val o = off
                        list.add(Row(m.groupValues[2], depth = m.groupValues[1].length - 1, icon = "·", onClick = {
                            if (mode == 1) setMode(0)
                            edit.requestFocus()
                            edit.setSelection(o.coerceAtMost(edit.text.length))
                            if (!wide) setSide(false)
                        }))
                    }
                    off += line.length + 1
                }
                if (list.isEmpty()) list.add(Row("Sem títulos (#) nesta nota.", onClick = {}))
            }
            5 -> {
                val files = store.bibFiles()
                val all = store.refs()
                sideTitle.text = "REFERÊNCIAS · ${all.size} em ${files.size} arquivo(s) · toque para citar"
                val q = qRefs.trim().lowercase()
                if (q.isEmpty()) files.forEach { f ->
                    list.add(Row(f.name, "${store.refsOf(f).size} entradas · ${f.parent}", icon = "📖",
                        onClick = { refFileActions(f) }, onLong = { refFileActions(f) }))
                }
                val found = if (q.isEmpty()) all else all.filter {
                    (it.key + " " + it.label + " " + it.title + " " + it.authors).lowercase().contains(q)
                }
                found.take(200).forEach { r ->
                    list.add(Row(r.label, (if (r.title.isNotEmpty()) r.title + " · " else "") + "@" + r.key,
                        icon = "@", onClick = { insertCitation(r.key) }))
                }
                if (all.isEmpty()) list.add(Row("Nenhuma referência ainda",
                    "Toque aqui para sincronizar com o Zotero ou escolher um arquivo .bib/.json", onClick = { zoteroMenu() }))
                else if (found.isEmpty()) list.add(Row("Nada encontrado.", onClick = {}))
            }
        }
        rows = list
        (sideList.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun fileRows(dir: File, depth: Int, out: MutableList<Row>) {
        val kids = dir.listFiles()?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        val curFile = tabs.getOrNull(cur)?.file
        for (k in kids) {
            if (k.isDirectory) {
                val open = expanded.contains(k.path)
                out.add(Row(k.name, depth = depth, icon = if (open) "▾" else "▸", onClick = {
                    if (open) expanded.remove(k.path) else expanded.add(k.path)
                    selDir = k
                    refreshSide()
                }, onLong = { fileActions(k) }))
                if (open) fileRows(k, depth + 1, out)
            } else {
                val ext = k.extension.lowercase()
                if (ext == "md" || ext == "bib") {
                    out.add(Row(if (ext == "md") k.nameWithoutExtension else k.name, depth = depth,
                        icon = if (ext == "md") "•" else "📖", bold = k == curFile,
                        onClick = { openFile(k) }, onLong = { fileActions(k) }))
                }
            }
        }
    }

    private fun searchRows(out: MutableList<Row>) {
        val q = qSearch.trim().lowercase()
        val notes = store.notes()
        if (q.isEmpty()) {
            notes.take(30).forEach { f -> out.add(Row(store.title(f), icon = "•", onClick = { openFile(f) })) }
            return
        }
        for (f in notes) {
            val text = store.read(f)
            val low = text.lowercase()
            val inTitle = store.title(f).lowercase().contains(q)
            val idx = low.indexOf(q)
            if (!inTitle && idx < 0) continue
            val snippet = if (idx < 0) "" else {
                val s = text.lastIndexOf('\n', idx) + 1
                val e = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
                text.substring(s, e).trim().take(90)
            }
            out.add(Row(store.title(f), snippet, icon = "•", onClick = { openFile(f) }))
            if (out.size >= 100) break
        }
        if (out.isEmpty()) out.add(Row("Nada encontrado.", onClick = {}))
    }

    // ------------------------------------------------------------------ abas e arquivos
    private fun renderAll() {
        renderTabs()
        loadCurrent()
        goSection(section)
    }

    private fun renderTabs() {
        tabBox.removeAllViews()
        tabs.forEachIndexed { i, t ->
            val active = i == cur
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (active) pal.bg else 0)
            }
            cell.addView(tv((if (active && dirty) "● " else "") + t.file.nameWithoutExtension.ifEmpty { t.file.name }, 14f,
                if (active) pal.accent else pal.dim).apply {
                setPadding(dp(14), 0, dp(4), 0)
                setSingleLine()
                maxWidth = dp(180)
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (active) typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { switchTo(i) }
            }, LinearLayout.LayoutParams(-2, -1))
            cell.addView(tv("×", 18f, pal.dim).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(12), 0)
                setOnClickListener { closeTab(i) }
            }, LinearLayout.LayoutParams(-2, -1))
            tabBox.addView(cell, LinearLayout.LayoutParams(-2, -1))
        }
        tabScroll.visible(tabs.isNotEmpty() && !focus)
        tabScroll.post { tabBox.getChildAt(cur)?.let { tabScroll.smoothScrollTo(it.left - dp(40), 0) } }
    }

    private fun persistTabs() {
        store.openTabs = tabs.map { it.file.path }
        store.curTab = cur
    }

    private fun openFile(f: File) {
        saveCurrent()
        val i = tabs.indexOfFirst { it.file == f }
        if (i >= 0) cur = i else { tabs.add(Tab(f)); cur = tabs.size - 1 }
        f.parentFile?.let { selDir = it }
        if (!wide) setSide(false)
        persistTabs()
        renderAll()
    }

    private fun switchTo(i: Int) {
        if (i == cur) return
        saveCurrent()
        cur = i
        persistTabs()
        renderAll()
    }

    private fun closeTab(i: Int) {
        if (i == cur) saveCurrent()
        tabs.removeAt(i)
        cur = when {
            tabs.isEmpty() -> -1
            i < cur -> cur - 1
            i == cur -> minOf(i, tabs.size - 1)
            else -> cur
        }
        persistTabs()
        renderAll()
    }

    private fun loadCurrent() {
        val t = tabs.getOrNull(cur)
        welcome.visible(t == null)
        if (t == null) {
            loading = true; edit.setText(""); loading = false
            title.text = "ZetLite"
            saveBtn.visible(false)
            status.text = ""
            applyMode()
            return
        }
        loading = true
        edit.setText(store.read(t.file))
        edit.setSelection(t.cursor.coerceIn(0, edit.text.length))
        loading = false
        dirty = false
        saveBtn.visible(false)
        title.text = t.file.name
        highlight()
        updateStatus()
        applyMode()
    }

    private fun saveCurrent() {
        val t = tabs.getOrNull(cur) ?: return
        t.cursor = edit.selectionStart.coerceAtLeast(0)
        if (dirty) {
            store.write(t.file, edit.text.toString())
            dirty = false
            saveBtn.visible(false)
            renderTabs()
            updateStatus()
        }
    }

    private fun saveNow() {
        if (cur < 0) return
        val was = dirty
        saveCurrent()
        toast(if (was) "Salvo" else "Já está salvo")
    }

    /** Atalhos de teclado físico (tablets/teclado Bluetooth). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.isCtrlPressed && e.repeatCount == 0) {
            val handled = when (e.keyCode) {
                KeyEvent.KEYCODE_S -> { saveNow(); true }
                KeyEvent.KEYCODE_B -> { wrap("**", "**"); true }
                KeyEvent.KEYCODE_I -> { wrap("*", "*"); true }
                KeyEvent.KEYCODE_K -> { wrap("[", "](url)"); true }
                KeyEvent.KEYCODE_N -> { newNote(selDir); true }
                KeyEvent.KEYCODE_O -> { workspaceMenu(); true }
                KeyEvent.KEYCODE_W -> { if (cur >= 0) closeTab(cur); true }
                KeyEvent.KEYCODE_P -> { if (cur >= 0) cycleMode(); true }
                KeyEvent.KEYCODE_F -> { goSection(1); setSide(true); sideSearch.requestFocus(); true }
                KeyEvent.KEYCODE_BACKSLASH -> {
                    if (wide) { sideOpen = !sideOpen; applySide(false) } else setSide(!sideOpen); true
                }
                KeyEvent.KEYCODE_TAB -> {
                    if (tabs.size > 1) switchTo(if (e.isShiftPressed) (cur - 1 + tabs.size) % tabs.size else (cur + 1) % tabs.size)
                    true
                }
                else -> false
            }
            if (handled) return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun goSection(i: Int, text: String? = null) {
        section = i
        if (i == 1 && text != null) qSearch = text
        settingText = true
        sideSearch.setText(if (i == 1) qSearch else if (i == 5) qRefs else "")
        sideSearch.hint = if (i == 5) "Buscar referência (autor, título, chave)" else "Buscar em todas as notas"
        settingText = false
        refreshSide()
    }

    // ------------------------------------------------------------------ modos
    private fun cycleMode() = setMode(if (wide) when (mode) { 0 -> 2; 2 -> 1; else -> 0 } else if (mode == 0) 1 else 0)

    private fun setMode(m: Int) {
        saveCurrent()
        mode = m
        applyMode()
    }

    private fun applyMode() {
        val has = cur >= 0
        modeBtn.text = when (mode) { 1 -> "Prévia"; 2 -> "Dividido"; else -> "Editor" }
        modeBtn.visible(has)
        edit.visible(has && mode != 1)
        web.visible(has && mode != 0)
        fmtBar.visible(has && mode != 1 && !focus)
        topBar.visible(!focus)
        status.visible(has)
        if (has && mode != 0) updatePreview()
    }

    private fun updatePreview() {
        val t = tabs.getOrNull(cur) ?: return
        web.loadDataWithBaseURL("file://" + t.file.parent + "/",
            Md.page(store.title(t.file), edit.text.toString(), store.bib(), store.dark),
            "text/html", "utf-8", null)
    }

    private fun setFocus(on: Boolean) {
        focus = on
        applySide(false)
        renderTabs()
        applyMode()
        if (on) toast("Modo foco: toque na barra inferior para sair")
    }

    // ------------------------------------------------------------------ edição
    private fun insert(text: String) {
        if (cur < 0) return
        val a = minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val b = maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        edit.text.replace(a, b, text)
    }

    private fun wrap(pre: String, suf: String) {
        if (cur < 0) return
        val a = minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val b = maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val sel = edit.text.substring(a, b)
        edit.text.replace(a, b, pre + sel + suf)
        edit.setSelection(a + pre.length, a + pre.length + sel.length)
    }

    private fun linePrefix(p: String) {
        if (cur < 0) return
        val pos = edit.selectionStart.coerceAtLeast(0)
        val start = edit.text.lastIndexOf('\n', pos - 1) + 1
        edit.text.insert(start, p)
    }

    private fun footnote() {
        if (cur < 0) return
        val t = edit.text.toString()
        var n = 1
        while (t.contains("[^$n]")) n++
        insert("[^$n]")
        edit.text.append("\n\n[^$n]: ")
    }

    private fun pickNote() {
        if (cur < 0) return
        val notes = store.notes().filter { it != tabs[cur].file }
        val labels = listOf("＋ Nova nota…") + notes.map { store.title(it) }
        AlertDialog.Builder(this).setTitle("Inserir link [[ ]]")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == 0) ask("Nome da nota") { n -> if (n.isNotBlank()) insert("[[${n.trim()}]]") }
                else insert("[[${store.title(notes[i - 1])}]]")
            }.show()
    }

    /** Botão [@]: abre o painel Referências na barra lateral; tocar em uma referência insere [@chave]. */
    private fun pickCitation() {
        if (cur < 0) return
        goSection(5)
        setSide(true)
    }

    private fun insertCitation(key: String) {
        if (cur < 0) { toast("Abra uma nota para inserir a citação"); return }
        if (mode == 1) setMode(0)
        insert("[@$key]")
        if (!wide) setSide(false)
        edit.requestFocus()
        toast("Inserido [@$key]")
    }

    // ------------------------------------------------------------------ realce de sintaxe
    private fun sp(t: Editable, what: Any, s: Int, e: Int) {
        if (e <= s) return
        t.setSpan(what, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        applied.add(what)
    }

    private val reHead = Regex("(?m)^(#{1,6}) .*$")
    private val reQuote = Regex("(?m)^>.*$")
    private val reListM = Regex("(?m)^\\s*([-*+]|\\d+[.)])\\s(\\[[ xX]\\]\\s)?")
    private val reHrule = Regex("(?m)^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val reBoldH = Regex("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__")
    private val reItalH = Regex("(?<![*\\w])\\*(?!\\*)[^*\\n]+\\*(?!\\*)|(?<![\\w_])_[^_\\n]+_(?![\\w_])")
    private val reStrikeH = Regex("~~[^~\\n]+~~")
    private val reCodeH = Regex("`[^`\\n]+`")
    private val reMathH = Regex("\\$[^$\\n]+\\$")
    private val reUrlH = Regex("https?://[^\\s)\\]]+")
    private val reLinkH = Regex("!?\\[([^\\]\\n]+)\\]\\(([^)\\n]+)\\)")
    private val reWikiH = Regex("\\[\\[[^\\]\\n]+\\]\\]")
    private val reFnDefH = Regex("(?m)^\\[\\^[^\\]\\n]+\\]:")
    private val reFnRefH = Regex("\\[\\^[^\\]\\n]+\\](?!:)")
    private val reCiteH = Regex("\\[@[^\\]\\n]+\\]")
    private val reTagH = Regex("(?<![\\w&;#/\"=])#[\\p{L}][\\p{L}\\p{N}_/\\-]*")
    private val reFenceH = Regex("(?s)```.*?```")

    private fun highlight() {
        val t = edit.text
        applied.forEach { t.removeSpan(it) }
        applied.clear()
        val sizes = floatArrayOf(1f, 1.45f, 1.3f, 1.18f, 1.1f, 1.05f, 1f)

        reHead.findAll(t).forEach { m ->
            val s = m.range.first; val e = m.range.last + 1
            sp(t, ForegroundColorSpan(pal.accent), s, e)
            sp(t, StyleSpan(Typeface.BOLD), s, e)
            sp(t, RelativeSizeSpan(sizes[m.groupValues[1].length]), s, e)
        }
        reQuote.findAll(t).forEach { m ->
            sp(t, ForegroundColorSpan(pal.dim), m.range.first, m.range.last + 1)
            sp(t, StyleSpan(Typeface.ITALIC), m.range.first, m.range.last + 1)
        }
        reListM.findAll(t).forEach { m ->
            sp(t, ForegroundColorSpan(pal.accent), m.range.first, m.range.last + 1)
            sp(t, StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1)
        }
        reHrule.findAll(t).forEach { m -> sp(t, ForegroundColorSpan(pal.dim), m.range.first, m.range.last + 1) }

        // negrito (laranja) e itálico (roxo); os marcadores ficam apagados
        reBoldH.findAll(t).forEach { m ->
            val s = m.range.first; val e = m.range.last + 1
            sp(t, StyleSpan(Typeface.BOLD), s, e)
            sp(t, ForegroundColorSpan(pal.bold), s, e)
            sp(t, ForegroundColorSpan(pal.dim), s, s + 2)
            sp(t, ForegroundColorSpan(pal.dim), e - 2, e)
        }
        reItalH.findAll(t).forEach { m ->
            val s = m.range.first; val e = m.range.last + 1
            sp(t, StyleSpan(Typeface.ITALIC), s, e)
            sp(t, ForegroundColorSpan(pal.italic), s, e)
            sp(t, ForegroundColorSpan(pal.dim), s, s + 1)
            sp(t, ForegroundColorSpan(pal.dim), e - 1, e)
        }
        reStrikeH.findAll(t).forEach { m ->
            sp(t, StrikethroughSpan(), m.range.first, m.range.last + 1)
            sp(t, ForegroundColorSpan(pal.dim), m.range.first, m.range.last + 1)
        }
        reCodeH.findAll(t).forEach { m ->
            sp(t, BackgroundColorSpan(pal.codeBg), m.range.first, m.range.last + 1)
            sp(t, ForegroundColorSpan(pal.codeFg), m.range.first, m.range.last + 1)
        }
        reMathH.findAll(t).forEach { m -> sp(t, ForegroundColorSpan(pal.math), m.range.first, m.range.last + 1) }

        // links: texto sublinhado em azul, endereço em cor fraca
        reUrlH.findAll(t).forEach { m ->
            sp(t, ForegroundColorSpan(pal.link), m.range.first, m.range.last + 1)
            sp(t, UnderlineSpan(), m.range.first, m.range.last + 1)
        }
        reLinkH.findAll(t).forEach { m ->
            sp(t, ForegroundColorSpan(pal.dim), m.range.first, m.range.last + 1)
            val g = m.groups[1]!!.range
            sp(t, ForegroundColorSpan(pal.link), g.first, g.last + 1)
            sp(t, UnderlineSpan(), g.first, g.last + 1)
        }
        reWikiH.findAll(t).forEach { m ->
            sp(t, ForegroundColorSpan(pal.accent), m.range.first, m.range.last + 1)
            sp(t, UnderlineSpan(), m.range.first, m.range.last + 1)
        }

        // notas de rodapé (azul-petróleo) e citações (rosa)
        reFnDefH.findAll(t).forEach { m ->
            sp(t, ForegroundColorSpan(pal.note), m.range.first, m.range.last + 1)
            sp(t, StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1)
        }
        reFnRefH.findAll(t).forEach { m ->
            sp(t, ForegroundColorSpan(pal.note), m.range.first, m.range.last + 1)
            sp(t, RelativeSizeSpan(0.85f), m.range.first, m.range.last + 1)
        }
        reCiteH.findAll(t).forEach { m ->
            sp(t, ForegroundColorSpan(pal.cite), m.range.first, m.range.last + 1)
            sp(t, StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1)
        }
        reTagH.findAll(t).forEach { m -> sp(t, ForegroundColorSpan(pal.accent), m.range.first, m.range.last + 1) }

        // blocos de código por último: ignoram o realce interno
        reFenceH.findAll(t).forEach { m ->
            sp(t, BackgroundColorSpan(pal.codeBg), m.range.first, m.range.last + 1)
            sp(t, ForegroundColorSpan(pal.codeFg), m.range.first, m.range.last + 1)
        }
    }

    // ------------------------------------------------------------------ referências / Zotero
    private fun zoteroMenu() {
        AlertDialog.Builder(this).setTitle("Referências")
            .setItems(arrayOf(
                "Sincronizar com o Zotero (internet)",
                "Escolher arquivo .bib / .json do aparelho",
                "Como exportar do Zotero"
            )) { _, i ->
                when (i) { 0 -> zoteroSync(); 1 -> pickRefFile(); 2 -> zoteroHelp() }
            }.show()
    }

    private fun pickRefFile() {
        pickFolder(store.root, listOf("bib", "json")) { f ->
            store.addBib(f)
            toast("Adicionado: ${f.name} (${store.refsOf(f).size} referências)")
            goSection(5)
        }
    }

    private fun refFileActions(f: File) {
        val extra = store.isExtraBib(f)
        val opts = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (f.extension.equals("bib", true)) { opts.add("Abrir no editor"); acts.add { openFile(f) } }
        if (extra) { opts.add("Remover da lista (não apaga o arquivo)"); acts.add { store.removeBib(f); goSection(5) } }
        else { opts.add("Está na pasta de trabalho"); acts.add { toast("Qualquer .bib da pasta de trabalho é lido automaticamente") } }
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(opts.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun zoteroSync() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = "No site zotero.org/settings/keys copie o seu \"userID\" (número) e crie uma chave de API com acesso somente leitura à sua biblioteca. O ZetLite baixa as referências para o arquivo zotero.bib na pasta de trabalho."
            textSize = 13f
        })
        val id = EditText(this).apply {
            hint = "ID numérico do usuário"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(store.zotUser)
        }
        val key = EditText(this).apply {
            hint = "Chave de API"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(store.zotKey)
        }
        box.addView(id); box.addView(key)
        AlertDialog.Builder(this).setTitle("Sincronizar com o Zotero").setView(box)
            .setPositiveButton("Sincronizar") { _, _ ->
                val uid = id.text.toString().trim()
                val k = key.text.toString().trim()
                if (uid.isEmpty()) { toast("Informe o ID do usuário"); return@setPositiveButton }
                store.zotUser = uid; store.zotKey = k
                store.ensureRoot()
                toast("Baixando referências…")
                Thread {
                    try {
                        val txt = Zotero.fetchBibtex(uid, k)
                        val f = File(store.root, "zotero.bib")
                        f.writeText(txt)
                        val n = store.refsOf(f).size
                        runOnUiThread { toast("Zotero: $n referências salvas em zotero.bib", true); goSection(5) }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Falha: ${e.message ?: "sem conexão"}", true) }
                    }
                }.start()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun zoteroHelp() {
        AlertDialog.Builder(this).setTitle("Como usar o Zotero")
            .setMessage(
                "O ZetLite lê referências de arquivos BibTeX (.bib) ou CSL JSON (.json).\n\n" +
                    "A) Pela internet: use \"Sincronizar com o Zotero\". Você precisa do userID e de uma chave de API somente leitura (zotero.org/settings/keys).\n\n" +
                    "B) Pelo computador: no Zotero, clique com o botão direito na coleção > Exportar > BibTeX (ou CSL JSON). " +
                    "Se usar o plugin Better BibTeX, dá para manter o arquivo atualizado automaticamente. " +
                    "Leve o arquivo ao celular (Syncthing, Google Drive etc.) e escolha-o em \"Escolher arquivo .bib / .json\".\n\n" +
                    "Se o .bib estiver dentro da pasta de trabalho, ele é lido sozinho.\n\n" +
                    "Para citar: botão [@] da barra de formatação, ou a aba 📚 da barra lateral. Escreva [@chave] no texto."
            ).setPositiveButton("OK", null).show()
    }

    private fun updateStatus() {
        val t = tabs.getOrNull(cur) ?: return
        val w = edit.text.toString().split(Regex("\\s+")).count { it.isNotEmpty() }
        val tg = store.target(t.file)
        status.text = "$w palavras · ${edit.text.length} caracteres" +
            (if (tg > 0) " · meta $tg (${(w * 100 / tg).coerceAtMost(999)}%)" else "") +
            (if (dirty) " · ● não salvo" else " · ✓ salvo")
    }

    private fun setTarget() {
        val t = tabs.getOrNull(cur) ?: return
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(store.target(t.file).takeIf { it > 0 }?.toString() ?: "")
            hint = "Palavras (0 remove)"
        }
        AlertDialog.Builder(this).setTitle("Meta de palavras").setView(et)
            .setPositiveButton("OK") { _, _ ->
                store.setTarget(t.file, et.text.toString().toIntOrNull() ?: 0); updateStatus()
            }.setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------------ pasta de trabalho
    private fun workspaceMenu() {
        val rec = store.recents().filter { File(it).isDirectory && it != store.root.path }
        val labels = listOf("\uD83D\uDCC2 Procurar pasta\u2026") + rec.map { "\uD83D\uDD58 " + File(it).name + "   (" + it + ")" }
        AlertDialog.Builder(this).setTitle("Pasta de trabalho")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == 0) pickFolder(store.root) { setWorkspace(it) } else setWorkspace(File(rec[i - 1]))
            }.show()
    }

    private fun setWorkspace(dir: File) {
        saveCurrent()
        tabs.clear(); cur = -1
        store.root = dir
        store.addRecent(dir)
        expanded.clear()
        selDir = dir
        section = 0
        persistTabs()
        renderAll()
    }

    /** Armazenamentos disponíveis: interno e, se houver, cartão SD / USB. */
    private fun volumes(): List<Pair<String, File>> {
        val internal = Environment.getExternalStorageDirectory()
        val out = ArrayList<Pair<String, File>>()
        out.add("\uD83D\uDCF1 Interno" to internal)
        getExternalFilesDirs(null).filterNotNull().forEach { d ->
            val p = d.path.substringBefore("/Android/")
            val f = File(p)
            if (f.path != internal.path && f.exists() && out.none { it.second.path == f.path }) {
                out.add("\uD83D\uDCBE Cartão/USB (${f.name})" to f)
            }
        }
        return out
    }

    private fun pickFolder(start: File, exts: List<String>? = null, onPick: (File) -> Unit) {
        var dir = if (start.isDirectory) start else Environment.getExternalStorageDirectory()
        val vols = volumes()
        val dlg = android.app.Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }

        rootView.addView(tv(if (exts == null) "Escolher pasta de trabalho" else "Escolher arquivo de referências (.bib / .json)", 18f, pal.accent).apply {
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(pal.bar)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })

        // locais: armazenamentos e atalhos
        val placesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(pal.side)
        }
        val places = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        placesScroll.addView(places)
        rootView.addView(placesScroll)

        // trilha de navegação
        val crumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val crumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        crumbScroll.addView(crumbs)
        rootView.addView(crumbScroll, LinearLayout.LayoutParams(-1, dp(44)))

        var list: List<Row> = emptyList()
        val lv = ListView(this)
        lv.divider = null
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(p: Int): Any = list[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View = rowView(list[p])
        }
        lv.setOnItemClickListener { _, _, pos, _ -> list.getOrNull(pos)?.onClick?.invoke() }
        rootView.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))

        val selected = tv("", 12f, pal.dim).apply {
            setPadding(dp(16), dp(8), dp(16), dp(2))
            setBackgroundColor(pal.bar)
        }
        rootView.addView(selected)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(pal.bar) }
        rootView.addView(bottom)

        fun load() {
            val vol = vols.firstOrNull { dir.path == it.second.path || dir.path.startsWith(it.second.path + "/") }
            // trilha
            crumbs.removeAllViews()
            val chain = ArrayList<File>()
            var f: File? = dir
            while (f != null) {
                chain.add(0, f)
                if (vol != null && f.path == vol.second.path) break
                f = f.parentFile
            }
            chain.forEachIndexed { i, d ->
                val label = if (i == 0 && vol != null) vol.first else d.name.ifEmpty { "/" }
                crumbs.addView(tv(label, 14f, pal.accent).apply {
                    setPadding(dp(10), dp(10), dp(6), dp(10))
                    setOnClickListener { dir = d; load() }
                })
                if (i < chain.size - 1) crumbs.addView(tv("\u203A", 14f, pal.dim))
            }
            crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }

            // lista de subpastas
            val out = ArrayList<Row>()
            val kids = dir.listFiles()
            if (kids == null) {
                out.add(Row("Sem acesso a esta pasta", "Verifique a permissão \"gerenciar todos os arquivos\" nas configurações do app", onClick = {}))
            } else {
                val par = dir.parentFile
                if (par != null && dir.path != "/" && (vol == null || vol.second.path != dir.path)) {
                    out.add(Row("Subir um nível", par.name.ifEmpty { "/" }, icon = "\u2B06", onClick = { dir = par; load() }))
                }
                kids.filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase() }
                    .forEach { d ->
                        val inner = d.listFiles()
                        val subs = inner?.count { it.isDirectory } ?: 0
                        val notes = inner?.count { it.isFile && it.extension.equals("md", true) } ?: 0
                        val info = listOfNotNull(
                            if (subs > 0) "$subs pasta(s)" else null,
                            if (notes > 0) "$notes nota(s)" else null
                        ).joinToString(" \u00B7 ")
                        out.add(Row(d.name, info, icon = "\uD83D\uDCC1", onClick = { dir = d; load() }))
                    }
                if (exts != null) {
                    kids.filter { it.isFile && it.extension.lowercase() in exts }
                        .sortedBy { it.name.lowercase() }
                        .forEach { f ->
                            out.add(Row(f.name, "${f.length() / 1024} KB", icon = "\uD83D\uDCD6",
                                onClick = { dlg.dismiss(); onPick(f) }))
                        }
                    if (out.none { it.icon == "\uD83D\uDCC1" || it.icon == "\uD83D\uDCD6" }) {
                        out.add(Row("Nenhum arquivo .bib/.json aqui", "Navegue pelas pastas acima", onClick = {}))
                    }
                } else if (out.none { it.icon == "\uD83D\uDCC1" }) {
                    out.add(Row("Nenhuma subpasta aqui", "Use \"Nova pasta\" ou escolha esta pasta", onClick = {}))
                }
            }
            list = out
            (lv.adapter as BaseAdapter).notifyDataSetChanged()
            selected.text = if (exts == null) "Selecionada: ${dir.path}" else "Toque em um arquivo para escolher"
        }

        // chips de locais
        vols.forEach { (label, f) -> places.addView(flatButton(label, pal) { dir = f; load() }) }
        val internal = Environment.getExternalStorageDirectory()
        listOf("\u2B07 Downloads" to "Download", "\uD83D\uDCC4 Documentos" to "Documents", "\u270E ZetLite" to "ZetLite")
            .forEach { (label, name) ->
                val f = File(internal, name)
                if (f.isDirectory) places.addView(flatButton(label, pal) { dir = f; load() })
            }

        bottom.addView(flatButton("+ Nova pasta", pal) {
            ask("Nome da nova pasta") { n ->
                if (n.isNotBlank()) {
                    val d = File(dir, n.trim())
                    if (d.mkdirs()) { dir = d; load() } else toast("Não foi possível criar a pasta")
                }
            }
        })
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(flatButton("Cancelar", pal) { dlg.dismiss() })
        if (exts == null) bottom.addView(flatButton("Usar esta pasta", pal) { dlg.dismiss(); onPick(dir) }.apply {
            typeface = Typeface.DEFAULT_BOLD
        })

        dlg.setContentView(rootView)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.statusBarColor = pal.bar
        dlg.show()
        load()
    }

    // ------------------------------------------------------------------ operações com arquivos
    private fun ask(title: String, initial: String = "", ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun newNote(dir: File) {
        ask("Nome da nota") { n -> if (n.isNotBlank()) { openFile(store.create(n, dir)) } }
    }

    private fun newFolder(dir: File) {
        ask("Nome da pasta") { n ->
            if (n.isNotBlank()) {
                val d = File(dir, n.trim()); d.mkdirs(); expanded.add(dir.path); selDir = d; refreshSide()
            }
        }
    }

    private fun fileActions(f: File) {
        val opts = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (f.isDirectory) {
            opts.add("Nova nota aqui"); acts.add { newNote(f) }
            opts.add("Nova subpasta"); acts.add { newFolder(f) }
        }
        opts.add("Renomear"); acts.add { renameFile(f) }
        opts.add("Excluir"); acts.add { deleteFile(f) }
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(opts.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun closeTabsUnder(f: File) {
        saveCurrent()
        val keep = tabs.filter { !(it.file == f || it.file.path.startsWith(f.path + "/")) }
        val curFile = tabs.getOrNull(cur)?.file
        tabs.clear(); tabs.addAll(keep)
        cur = tabs.indexOfFirst { it.file == curFile }.let { if (it < 0) tabs.size - 1 else it }
    }

    private fun renameFile(f: File) {
        val base = if (f.isDirectory) f.name else f.nameWithoutExtension
        ask("Novo nome", base) { n ->
            if (n.isBlank()) return@ask
            val dest = File(f.parentFile, n.trim() + if (f.isDirectory || f.extension.isEmpty()) "" else "." + f.extension)
            if (dest.exists()) toast("Já existe um item com esse nome")
            else { closeTabsUnder(f); f.renameTo(dest); persistTabs(); renderAll() }
        }
    }

    private fun deleteFile(f: File) {
        val msg = if (f.isDirectory) "Excluir a pasta \"${f.name}\" e todo o conteúdo? Não há como desfazer."
        else "Excluir \"${f.name}\"? Não há como desfazer."
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Excluir") { _, _ ->
                closeTabsUnder(f); f.deleteRecursively(); persistTabs(); renderAll()
            }.setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------------ menu, exportação, ajuda
    private fun showMenu(anchor: View) {
        val has = cur >= 0
        PopupMenu(this, anchor).apply {
            menu.add(0, 10, 0, "Salvar (Ctrl+S)").isEnabled = has
            menu.add(0, 1, 0, "Nova nota")
            menu.add(0, 2, 1, "Nova pasta")
            menu.add(0, 3, 2, "Abrir pasta de trabalho")
            menu.add(0, 4, 3, "Exportar HTML").isEnabled = has
            menu.add(0, 5, 4, "Exportar PDF").isEnabled = has
            menu.add(0, 6, 5, "Modo foco").isEnabled = has
            menu.add(0, 7, 6, "Meta de palavras").isEnabled = has
            menu.add(0, 8, 7, if (store.dark) "Tema claro" else "Tema escuro")
            menu.add(0, 11, 8, "Referências / Zotero")
            menu.add(0, 9, 9, "Ajuda de sintaxe")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> newNote(selDir)
                    2 -> newFolder(selDir)
                    3 -> workspaceMenu()
                    4 -> exportHtml()
                    5 -> exportPdf()
                    6 -> setFocus(true)
                    7 -> setTarget()
                    8 -> { saveCurrent(); store.dark = !store.dark; buildUi(); renderAll() }
                    9 -> showHelp()
                    10 -> saveNow()
                    11 -> { goSection(5); setSide(true) }
                }
                true
            }
            show()
        }
    }

    private fun exportHtml() {
        val t = tabs.getOrNull(cur) ?: return
        saveCurrent()
        val out = File(t.file.parentFile, store.title(t.file) + ".html")
        out.writeText(Md.page(store.title(t.file), edit.text.toString(), store.bib(), false))
        toast("Salvo: ${out.name}")
        refreshSide()
    }

    private fun exportPdf() {
        val t = tabs.getOrNull(cur) ?: return
        val w = WebView(this)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(store.title(t.file), v.createPrintDocumentAdapter(store.title(t.file)),
                    PrintAttributes.Builder().build())
            }
        }
        w.loadDataWithBaseURL("file://" + t.file.parent + "/",
            Md.page(store.title(t.file), edit.text.toString(), store.bib(), false), "text/html", "utf-8", null)
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("Ajuda de sintaxe")
            .setMessage(
                "# Título   ## Subtítulo\n**negrito**  *itálico*  ~~riscado~~\n- lista   1. numerada   - [ ] tarefa\n" +
                    "> citação\n[[outra nota]]  link entre notas\n#tag  etiqueta\n[texto](https://...)  link\n" +
                    "[@chave]  citação (arquivo .bib na pasta)\nTexto[^1]  e depois  [^1]: nota de rodapé\n" +
                    "| A | B |  tabela\n\nDicas:\n• ☰ abre a barra lateral\n• Toque longo em um arquivo: renomear/excluir\n" +
                    "• O botão Editor/Prévia/Dividido alterna a visualização\n• Salva sozinho; 💾 ou Ctrl+S salva na hora\n" +
                    "• Teclado físico: Ctrl+S salvar, B negrito, I itálico, K link, N nova nota, O abrir pasta, W fechar aba, P prévia, F buscar, \\ barra lateral, Tab próxima aba"
            ).setPositiveButton("OK", null).show()
    }

    private fun toast(s: String, long: Boolean = false) =
        Toast.makeText(this, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
