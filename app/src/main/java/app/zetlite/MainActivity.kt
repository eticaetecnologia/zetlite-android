package app.zetlite

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var pal: Pal
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var shown: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        build()
    }

    private fun build() {
        pal = Pal(store.dark)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.bar)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(24), dp(4), 0)
        }
        bar.addView(TextView(this).apply {
            text = "ZetLite"
            textSize = 20f
            setTextColor(pal.accent)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(flatButton("+ Nova", pal) { newNote() })
        lateinit var menuBtn: android.view.View
        menuBtn = flatButton("⋮", pal) { showMenu(menuBtn) }
        bar.addView(menuBtn)
        root.addView(bar)

        search = EditText(this).apply {
            hint = "Buscar (texto, #tag, [[link]])"
            setHintTextColor(pal.dim)
            setTextColor(pal.fg)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = refresh()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search)

        empty = TextView(this).apply {
            setTextColor(pal.dim)
            setPadding(dp(16), dp(24), dp(16), 0)
        }
        root.addView(empty)

        list = ListView(this).apply {
            divider = null
            setOnItemClickListener { _, _, pos, _ -> open(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> noteActions(shown[pos]); true }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!hasStorage()) askStorage() else {
            store.ensureRoot()
            refresh()
        }
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT >= 33

    private fun askStorage() {
        empty.text = "Permita o acesso a arquivos para ler e salvar suas notas .md."
        empty.visible(true)
        if (Build.VERSION.SDK_INT >= 30) {
            AlertDialog.Builder(this)
                .setTitle("Acesso a arquivos")
                .setMessage("O ZetLite guarda suas notas como arquivos .md numa pasta (${store.root.path}). Na próxima tela, ative \"Permitir acesso para gerenciar todos os arquivos\".")
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
        if (hasStorage()) { store.ensureRoot(); refresh() }
    }

    private fun refresh() {
        val q = search.text.toString().trim().lowercase()
        val all = store.notes()
        shown = if (q.isEmpty()) all else all.filter {
            store.title(it).lowercase().contains(q) || store.read(it).lowercase().contains(q)
        }
        empty.text = if (all.isEmpty()) "Nenhuma nota em ${store.root.path}. Toque em \"+ Nova\"." else
            if (shown.isEmpty()) "Nada encontrado." else ""
        empty.visible(empty.text.isNotEmpty())
        list.adapter = object : ArrayAdapter<File>(this, 0, shown) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val f = shown[position]
                val tv = (convertView as? TextView) ?: TextView(context)
                val preview = store.read(f).lineSequence().map { it.trim().trimStart('#', ' ') }
                    .drop(1).firstOrNull { it.isNotEmpty() }?.take(80) ?: ""
                val t = store.title(f)
                tv.text = if (preview.isEmpty()) t else "$t\n$preview"
                tv.setTextColor(pal.fg)
                tv.textSize = 16f
                tv.setPadding(dp(16), dp(12), dp(16), dp(12))
                return tv
            }
        }
    }

    private fun open(f: File) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("path", f.path))
    }

    private fun ask(title: String, initial: String = "", ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun newNote() {
        ask("Nome da nota") { n ->
            if (n.isNotBlank()) open(store.create(n))
        }
    }

    private fun noteActions(f: File) {
        AlertDialog.Builder(this).setTitle(store.title(f))
            .setItems(arrayOf("Renomear", "Excluir")) { _, which ->
                if (which == 0) ask("Novo nome", store.title(f)) { n ->
                    if (n.isNotBlank()) {
                        val dest = File(f.parentFile, n.trim() + ".md")
                        if (dest.exists()) toast("Já existe uma nota com esse nome")
                        else { f.renameTo(dest); refresh() }
                    }
                } else AlertDialog.Builder(this).setMessage("Excluir \"${store.title(f)}\"? Não há como desfazer.")
                    .setPositiveButton("Excluir") { _, _ -> f.delete(); refresh() }
                    .setNegativeButton("Cancelar", null).show()
            }.show()
    }

    private fun showMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Tags")
            menu.add(0, 2, 1, "Pasta das notas")
            menu.add(0, 3, 2, if (store.dark) "Tema claro" else "Tema escuro")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showTags()
                    2 -> ask("Caminho da pasta", store.root.path) { p ->
                        if (p.isNotBlank()) { store.root = File(p.trim()); store.ensureRoot(); refresh() }
                    }
                    3 -> { store.dark = !store.dark; build(); refresh() }
                }
                true
            }
            show()
        }
    }

    private fun showTags() {
        val tags = store.allTags()
        if (tags.isEmpty()) { toast("Nenhuma tag ainda. Use #tag no texto."); return }
        val names = tags.keys.toList()
        AlertDialog.Builder(this).setTitle("Tags")
            .setItems(names.map { "#$it (${tags[it]})" }.toTypedArray()) { _, i ->
                search.setText("#" + names[i])
            }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
