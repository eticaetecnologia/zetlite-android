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

    /** Lê todos os .bib da pasta: chave -> "Sobrenome, Ano". */
    fun bib(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!root.exists()) return out
        root.walkTopDown().filter { it.isFile && it.extension.equals("bib", true) }.forEach { bf ->
            val txt = try { bf.readText() } catch (e: Exception) { "" }
            txt.split(Regex("(?m)^\\s*@")).drop(1).forEach { entry ->
                val key = entry.substringAfter("{", "").substringBefore(",").trim()
                if (key.isEmpty()) return@forEach
                var author = ""
                var year = ""
                entry.lines().forEach { line ->
                    val m = Regex("^\\s*(\\w+)\\s*=\\s*[{\"]*(.*?)[}\"]*\\s*,?\\s*$").matchEntire(line) ?: return@forEach
                    when (m.groupValues[1].lowercase()) {
                        "author" -> author = m.groupValues[2]
                        "year" -> year = m.groupValues[2]
                        "date" -> if (year.isEmpty()) year = m.groupValues[2].take(4)
                    }
                }
                val first = author.substringBefore(" and ").trim()
                val last = if (first.contains(",")) first.substringBefore(",").trim() else first.substringAfterLast(" ").trim()
                val more = if (author.contains(" and ")) " et al." else ""
                out[key] = (if (last.isEmpty()) key else last + more) + (if (year.isEmpty()) "" else ", $year")
            }
        }
        return out
    }
}
