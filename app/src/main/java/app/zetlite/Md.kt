package app.zetlite

import java.net.URLEncoder

/** Conversor Markdown -> HTML mínimo (sem dependências), com links [[wiki]], tags, citações e rodapés. */
object Md {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val reCode = Regex("`([^`]+)`")
    private val reMath = Regex("\\$([^$\\n]+)\\$")
    private val reImg = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
    private val reWiki = Regex("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]")
    private val reCite = Regex("\\[(@[^\\]]+)\\]")
    private val reCiteKey = Regex("@([\\w:.\\-]+)")
    private val reLink = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    private val reFnRef = Regex("\\[\\^([^\\]]+)\\]")
    private val reBold = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
    private val reItalic = Regex("\\*([^*\\n]+)\\*|(?<![\\w])_([^_\\n]+)_(?![\\w])")
    private val reStrike = Regex("~~(.+?)~~")
    private val reTag = Regex("(?<![\\w&;#/\"=])#([\\p{L}][\\p{L}\\p{N}_/\\-]*)")
    private val rePh = Regex("\u0000(\\d+)\u0000")

    fun inline(src: String, bib: Map<String, String>): String {
        val hold = ArrayList<String>()
        fun keep(h: String): String {
            hold.add(h)
            return "\u0000${hold.size - 1}\u0000"
        }
        var s = esc(src)
        s = reCode.replace(s) { keep("<code>${it.groupValues[1]}</code>") }
        s = reMath.replace(s) { keep("<span class=m>${it.groupValues[1]}</span>") }
        s = reImg.replace(s) { keep("<img alt=\"${it.groupValues[1]}\" src=\"${it.groupValues[2]}\">") }
        s = reWiki.replace(s) {
            val name = it.groupValues[1].trim()
            val label = it.groupValues[2].ifEmpty { name }
            val href = "zl://note/" + URLEncoder.encode(name, "UTF-8")
            keep("<a class=w href=\"$href\">$label</a>")
        }
        s = reCite.replace(s) { m ->
            val keys = reCiteKey.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            keep("<span class=c>(" + keys.joinToString("; ") { bib[it] ?: it } + ")</span>")
        }
        s = reLink.replace(s) { keep("<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>") }
        s = reFnRef.replace(s) {
            val k = it.groupValues[1]
            keep("<sup><a href=\"#fn-$k\">$k</a></sup>")
        }
        s = reBold.replace(s) { "<strong>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</strong>" }
        s = reItalic.replace(s) { "<em>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</em>" }
        s = reStrike.replace(s) { "<del>${it.groupValues[1]}</del>" }
        s = reTag.replace(s) { "<span class=t>#${it.groupValues[1]}</span>" }
        // restaura (pode haver placeholders aninhados)
        repeat(2) { s = rePh.replace(s) { m -> hold[m.groupValues[1].toInt()] } }
        return s
    }

    private val reHead = Regex("^(#{1,6})\\s+(.*)$")
    private val reHr = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val reList = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val reTableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val reFnDef = Regex("^\\[\\^([^\\]]+)]:\\s*(.*)$")

    private fun isBlockStart(l: String) =
        l.startsWith("```") || reHead.matches(l) || reHr.matches(l) || l.startsWith(">") ||
            reList.matches(l) || reFnDef.matches(l)

    private fun cells(row: String): List<String> =
        row.trim().trim('|').split("|").map { it.trim() }

    fun body(md: String, bib: Map<String, String>): String {
        var lines = md.replace("\r", "").split("\n")
        // remove front matter YAML
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" || it.trim() == "..." }
            if (end >= 0) lines = lines.drop(end + 2)
        }
        val out = StringBuilder()
        val foot = LinkedHashMap<String, String>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.isBlank() -> i++
                l.startsWith("```") -> {
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        sb.append(esc(lines[i])).append('\n'); i++
                    }
                    i++
                    out.append("<pre><code>").append(sb).append("</code></pre>\n")
                }
                reHead.matches(l) -> {
                    val m = reHead.matchEntire(l)!!
                    val n = m.groupValues[1].length
                    out.append("<h$n>").append(inline(m.groupValues[2], bib)).append("</h$n>\n")
                    i++
                }
                reHr.matches(l) -> { out.append("<hr>\n"); i++ }
                l.startsWith(">") -> {
                    val sb = StringBuilder()
                    while (i < lines.size && lines[i].startsWith(">")) {
                        sb.append(lines[i].removePrefix(">").removePrefix(" ")).append('\n'); i++
                    }
                    out.append("<blockquote>").append(body(sb.toString(), bib)).append("</blockquote>\n")
                }
                reFnDef.matches(l) -> {
                    val m = reFnDef.matchEntire(l)!!
                    foot[m.groupValues[1]] = m.groupValues[2]
                    i++
                }
                l.contains("|") && i + 1 < lines.size && reTableSep.matches(lines[i + 1]) -> {
                    val head = cells(l)
                    i += 2
                    out.append("<table><thead><tr>")
                    head.forEach { out.append("<th>").append(inline(it, bib)).append("</th>") }
                    out.append("</tr></thead><tbody>")
                    while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) {
                        out.append("<tr>")
                        cells(lines[i]).forEach { out.append("<td>").append(inline(it, bib)).append("</td>") }
                        out.append("</tr>")
                        i++
                    }
                    out.append("</tbody></table>\n")
                }
                reList.matches(l) -> {
                    val ordered = reList.matchEntire(l)!!.groupValues[2][0].isDigit()
                    val tag = if (ordered) "ol" else "ul"
                    out.append("<$tag>")
                    while (i < lines.size && reList.matches(lines[i])) {
                        val m = reList.matchEntire(lines[i])!!
                        val indent = m.groupValues[1].length
                        var text = m.groupValues[3]
                        var box = ""
                        if (text.startsWith("[ ] ")) { box = "<input type=checkbox disabled> "; text = text.substring(4) }
                        else if (text.startsWith("[x] ") || text.startsWith("[X] ")) {
                            box = "<input type=checkbox checked disabled> "; text = text.substring(4)
                        }
                        out.append("<li style=\"margin-left:${indent * 8}px\">").append(box)
                            .append(inline(text, bib)).append("</li>")
                        i++
                    }
                    out.append("</$tag>\n")
                }
                else -> {
                    val sb = StringBuilder()
                    while (i < lines.size && lines[i].isNotBlank() && (sb.isEmpty() || !isBlockStart(lines[i]))) {
                        sb.append(lines[i]).append(' '); i++
                    }
                    out.append("<p>").append(inline(sb.toString().trim(), bib)).append("</p>\n")
                }
            }
        }
        if (foot.isNotEmpty()) {
            out.append("<hr><ol class=f>")
            foot.forEach { (k, v) -> out.append("<li id=\"fn-$k\">").append(inline(v, bib)).append("</li>") }
            out.append("</ol>")
        }
        return out.toString()
    }

    fun page(title: String, md: String, bib: Map<String, String>, dark: Boolean): String {
        val bg = if (dark) "#16181c" else "#ffffff"
        val fg = if (dark) "#e6e6e6" else "#1c1c1c"
        val ac = if (dark) "#6fd0b5" else "#1E6F5C"
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>
body{background:$bg;color:$fg;font:16px/1.6 sans-serif;margin:0;padding:14px;max-width:46em}
a{color:$ac}a.w{text-decoration:none;border-bottom:1px dashed $ac}
code,pre{background:rgba(127,127,127,.18);border-radius:4px;font-family:monospace}
code{padding:1px 4px}pre{padding:10px;overflow:auto}pre code{background:none;padding:0}
blockquote{border-left:4px solid $ac;margin:8px 0;padding:0 12px;opacity:.85}
table{border-collapse:collapse}th,td{border:1px solid rgba(127,127,127,.5);padding:4px 8px}
img{max-width:100%}.t{color:$ac}.c{color:$ac}.m{font-family:serif;font-style:italic}
ol.f{font-size:.9em}hr{border:0;border-top:1px solid rgba(127,127,127,.4)}
</style></head><body>${body(md, bib)}</body></html>"""
    }
}
