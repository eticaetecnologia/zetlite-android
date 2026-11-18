package app.zetlite

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Baixa a biblioteca pessoal do Zotero em BibTeX pela API web (somente leitura). */
object Zotero {
    fun fetchBibtex(userId: String, apiKey: String): String {
        val sb = StringBuilder()
        var start = 0
        val filter = URLEncoder.encode("-attachment || note", "UTF-8")
        while (true) {
            val url = URL("https://api.zotero.org/users/$userId/items?format=bibtex&limit=100&start=$start&itemType=$filter")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("Zotero-API-Version", "3")
            if (apiKey.isNotBlank()) c.setRequestProperty("Zotero-API-Key", apiKey.trim())
            val code = c.responseCode
            if (code == 403 || code == 401) throw Exception("Acesso negado (verifique a chave de API)")
            if (code == 404) throw Exception("Usuário não encontrado (verifique o ID numérico)")
            if (code != 200) throw Exception("Erro do Zotero: HTTP $code")
            val txt = c.inputStream.bufferedReader().readText()
            val total = c.getHeaderField("Total-Results")?.toIntOrNull() ?: 0
            c.disconnect()
            if (txt.isNotBlank()) sb.append(txt).append("\n")
            start += 100
            if (txt.isBlank() || start >= total) break
        }
        return sb.toString()
    }
}
