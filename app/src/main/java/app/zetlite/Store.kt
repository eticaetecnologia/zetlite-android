package app.zetlite

import android.content.Context
import android.os.Environment
import java.io.File

/** Acesso às notas (.md numa pasta), tags, links, backlinks e bibliografia (.bib). */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("zl", Context.MODE_PRIVATE)

    var dark: Boolean
        get() = prefs.getBoolean("dark", false)
        set(v) = prefs.edit().putBoolean("dark", v).apply()

    var root: File
        get() = File(prefs.getString("root", null)
            ?: File(Environment.getExternalStorageDirectory(), "ZetLite").path)
        set(v) = prefs.edit().putString("root", v.path).apply()

    fun target(f: File): Int = prefs.getInt("t_" + f.path.hashCode(), 0)
    fun setTarget(f: File, n: Int) = prefs.edit().putInt("t_" + f.path.hashCode(), n).apply()

    fun recents(): List<String> =
        (prefs.getString("recents", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun addRecent(d: File) {
        val l = listOf(d.path) + recents().filter { it != d.path }
        prefs.edit().putString("recents", l.take(8).joinToString("\n")).apply()
    }

    var openTabs: List<String>
        get() = (prefs.getString("tabs", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("tabs", v.joinToString("\n")).apply()

    var curTab: Int
        get() = prefs.getInt("cur", -1)
        set(v) = prefs.edit().putInt("cur", v).apply()

    fun ensureRoot(): Boolean = root.exists() || root.mkdirs()

    fun notes(): List<File> =
        if (!root.exists()) emptyList()
        else root.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.equals("md", true) }
            .sortedByDescending { it.lastModified() }
            .toList()

    fun title(f: File) = f.nameWithoutExtension

    fun read(f: File): String = try { f.readText() } catch (e: Exception) { "" }

    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun safe(name: String) = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    fun create(name: String, dir: File = root): File {
        val f = File(dir, safe(name) + ".md")
        if (!f.exists()) write(f, "# ${name.trim()}\n\n")
        return f
    }

    /** Resolve [[nome]] para um arquivo existente (ignora maiúsculas) ou cria um novo. */
    fun resolve(name: String): File {
        val n = name.trim()
        val hit = notes().firstOrNull { title(it).equals(n, true) }
        return hit ?: create(n)
    }

    private val reTag = Regex("(?<![\\w&;#/\"=])#([\\p{L}][\\p{L}\\p{N}_/\\-]*)")
    private val reWiki = Regex("\\[\\[([^\\]|#]+)")

    fun tagsOf(text: String): Set<String> = reTag.findAll(text).map { it.groupValues[1] }.toSet()
    fun linksOf(text: String): List<String> = reWiki.findAll(text).map { it.groupValues[1].trim() }.distinct().toList()

    fun allTags(): Map<String, Int> {
        val m = HashMap<String, Int>()
        notes().forEach { f -> tagsOf(read(f)).forEach { m[it] = (m[it] ?: 0) + 1 } }
        return m.toSortedMap()
    }

    fun backlinks(f: File): List<File> {
        val name = title(f)
        return notes().filter { it != f && linksOf(read(it)).any { l -> l.equals(name, true) } }
    }

    // ------------------------------------------------------------------ referências
    class Ref(val key: String, val label: String, val title: String, val authors: String,
              val year: String, val src: File)

    var zotUser: String
        get() = prefs.getString("zuser", "") ?: ""
        set(v) = prefs.edit().putString("zuser", v).apply()

    var zotKey: String
        get() = prefs.getString("zkey", "") ?: ""
        set(v) = prefs.edit().putString("zkey", v).apply()

    /** Arquivos de referência escolhidos manualmente (fora da pasta de trabalho). */
    fun extraBibs(): List<File> =
        (prefs.getString("bibs", "") ?: "").split("\n").filter { it.isNotBlank() }.map { File(it) }

    fun addBib(f: File) {
        val l = extraBibs().map { it.path }.filter { it != f.path } + f.path
        prefs.edit().putString("bibs", l.joinToString("\n")).apply()
    }

    fun removeBib(f: File) {
        prefs.edit().putString("bibs", extraBibs().map { it.path }.filter { it != f.path }.joinToString("\n")).apply()
    }

    fun isExtraBib(f: File) = extraBibs().any { it.path == f.path }

    /** .bib da pasta de trabalho + arquivos .bib/.json escolhidos manualmente. */
    fun bibFiles(): List<File> {
        val ws = if (root.exists()) root.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.equals("bib", true) }.toList() else emptyList()
        return (ws + extraBibs().filter { it.isFile }).distinctBy { it.path }
    }

    private val cache = HashMap<String, Pair<Long, List<Ref>>>()

    fun refsOf(f: File): List<Ref> {
        val c = cache[f.path]
        if (c != null && c.first == f.lastModified()) return c.second
        val txt = try { f.readText() } catch (e: Exception) { "" }
        val list = try {
            if (f.extension.equals("json", true)) parseCsl(txt, f) else parseBib(txt, f)
        } catch (e: Exception) { emptyList() }
        cache[f.path] = f.lastModified() to list
        return list
    }

    fun refs(): List<Ref> = bibFiles().flatMap { refsOf(it) }.distinctBy { it.key }
        .sortedBy { it.label.lowercase() }

    /** chave -> "Autor, Ano" (usado na prévia). */
    fun bib(): Map<String, String> = refs().associate { it.key to it.label }

    private fun labelOf(authors: String, year: String, key: String): String {
        val names = authors.split(" and ").map { it.trim() }.filter { it.isNotEmpty() }
        fun last(n: String) = if (n.contains(",")) n.substringBefore(",").trim() else n.substringAfterLast(" ").trim()
        val a = when (names.size) {
            0 -> key
            1 -> last(names[0])
            2 -> last(names[0]) + " & " + last(names[1])
            else -> last(names[0]) + " et al."
        }
        return if (year.isEmpty()) a else "$a, $year"
    }

    private fun parseBib(txt: String, src: File): List<Ref> {
        val out = ArrayList<Ref>()
        var i = 0
        while (true) {
            val at = txt.indexOf('@', i)
            if (at < 0) break
            val ob = txt.indexOf('{', at)
            if (ob < 0) break
            val type = txt.substring(at + 1, ob).trim().lowercase()
            var depth = 0
            var j = ob
            while (j < txt.length) {
                val c = txt[j]
                if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) break }
                j++
            }
            val body = txt.substring(ob + 1, minOf(j, txt.length))
            i = j + 1
            if (type == "comment" || type == "string" || type == "preamble") continue
            val comma = body.indexOf(',')
            if (comma < 0) continue
            val key = body.substring(0, comma).trim()
            if (key.isEmpty()) continue
            val fields = HashMap<String, String>()
            var p = comma + 1
            while (p < body.length) {
                val eq = body.indexOf('=', p)
                if (eq < 0) break
                val name = body.substring(p, eq).trim { it == ',' || it.isWhitespace() }.lowercase()
                var q = eq + 1
                while (q < body.length && body[q].isWhitespace()) q++
                if (q >= body.length) break
                val value: String
                if (body[q] == '{') {
                    var d = 0
                    var k = q
                    while (k < body.length) {
                        if (body[k] == '{') d++ else if (body[k] == '}') { d--; if (d == 0) break }
                        k++
                    }
                    value = body.substring(q + 1, minOf(k, body.length))
                    p = k + 1
                } else if (body[q] == '"') {
                    var k = q + 1
                    while (k < body.length && body[k] != '"') k++
                    value = body.substring(q + 1, minOf(k, body.length))
                    p = k + 1
                } else {
                    var k = q
                    while (k < body.length && body[k] != ',') k++
                    value = body.substring(q, k)
                    p = k
                }
                fields[name] = value.replace(Regex("[{}]"), "").replace(Regex("\\s+"), " ").trim()
            }
            val authors = fields["author"] ?: fields["editor"] ?: ""
            val year = fields["year"]?.takeIf { it.isNotEmpty() }
                ?: Regex("\\d{4}").find(fields["date"] ?: "")?.value ?: ""
            out.add(Ref(key, labelOf(authors, year, key), fields["title"] ?: "", authors, year, src))
        }
        return out
    }

    private fun parseCsl(txt: String, src: File): List<Ref> {
        val arr = org.json.JSONArray(txt)
        val out = ArrayList<Ref>()
        for (n in 0 until arr.length()) {
            val o = arr.optJSONObject(n) ?: continue
            val key = o.optString("citation-key", o.optString("id", "")).trim()
            if (key.isEmpty()) continue
            val names = ArrayList<String>()
            val au = o.optJSONArray("author")
            if (au != null) for (k in 0 until au.length()) {
                val a = au.optJSONObject(k) ?: continue
                val fam = a.optString("family", "")
                val giv = a.optString("given", "")
                names.add(if (fam.isNotEmpty()) (if (giv.isNotEmpty()) "$fam, $giv" else fam) else a.optString("literal", ""))
            }
            val year = o.optJSONObject("issued")?.optJSONArray("date-parts")?.optJSONArray(0)?.optInt(0, 0)
                ?.takeIf { it > 0 }?.toString() ?: ""
            val authors = names.filter { it.isNotEmpty() }.joinToString(" and ")
            out.add(Ref(key, labelOf(authors, year, key), o.optString("title", ""), authors, year, src))
        }
        return out
    }
}
