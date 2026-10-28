package br.com.saimo.tv

// ============================================================
// EXTRACTOR 404 - CADEIA DA REDEFLIX (3 SALTOS, USO UNICO)
// Criado por 404 - assinatura permanente, nao remover
// ============================================================
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RedeFlix404 {

    const val ASSINATURA = "Criado por 404 - assinatura permanente, nao remover"

    // == STRINGS OFUSCADAS - saida do ofuscar_404.py ==
    private const val CHAVE = 0x5A
    private val B64 = mapOf(
        "base"    to "Mi4uKilgdXUoPz4/PDYzIjsqM3Q4Oyg=",
        "resolve" to "dSo2OyM4Ozkxdyg/KTU2LD90KjIq",
        "source"  to "dSo2OyM/KDEjKXUpNS8oOT90KjIq",
    )

    private fun dec(b64: String): String {
        val bruto = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val limpo = bruto.map { ((it.toInt() and 0xFF) xor CHAVE).toByte() }
            .toByteArray()
        return String(limpo, Charsets.UTF_8)
    }

    private const val UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/126.0.0.0 Safari/537.36")

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Fontes da cadeia. mid no formato do app: tmdb:550 ou tmdb:1396:1:1. */
    suspend fun buscar(tipo: String, mid: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val saida = mutableListOf<Pair<String, String>>()
            val base = dec(B64["base"]!!)
            for (servidor in listOf("default", "legendado")) {
                val versao = if (servidor == "default") "dub" else "leg"
                try {
                    val pagina = paginaDe(tipo, mid)
                    // SALTO 1: pagina -> bilhete de uso unico
                    val html = http.newCall(
                        Request.Builder().url(pagina)
                            .header("User-Agent", UA)
                            .header("Accept", "text/html,*/*;q=0.8")
                            .header("Referer", "$base/")
                            .build()
                    ).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.string()
                    } ?: continue
                    val ticket = Regex("playbackResolveTicket\\s*=\\s*[\"']([^\"']+)[\"']")
                        .find(html)?.groupValues?.get(1) ?: continue
                    // SALTO 2: resolve -> bilhete de lancamento
                    val launch = postJson(
                        dec(B64["resolve"]!!),
                        JSONObject().put("ticket", ticket)
                            .put("server", servidor),
                        pagina
                    )?.optString("launchTicket")
                        ?.takeIf { it.isNotEmpty() } ?: continue
                    // SALTO 3: source -> LINK REAL (intocado ate o play)
                    val url = postJson(
                        dec(B64["source"]!!),
                        JSONObject().put("ticket", launch),
                        pagina
                    )?.optString("url")?.takeIf { it.isNotEmpty() } ?: continue
                    saida.add(versao to url)
                } catch (e: Exception) { continue }
            }
            saida
        }

    private fun paginaDe(tipo: String, mid: String): String {
        val p = mid.split(":")            // tmdb:550 | tmdb:1396:1:1
        val base = dec(B64["base"]!!)
        return if (tipo == "series" && p.size >= 4)
            "$base/serie/${p[1]}/${p[2]}/${p[3]}"
        else
            "$base/filme/${p.getOrElse(1) { mid }}"
    }

    private fun postJson(caminho: String, corpo: JSONObject,
                         referer: String): JSONObject? =
        try {
            val base = dec(B64["base"]!!)
            val url = base + caminho
            http.newCall(
                Request.Builder().url(url)
                    .post(corpo.toString().toRequestBody(
                        "application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", UA)
                    .header("X-Requested-With", "RedeFlixPlayer")
                    .header("Accept", "application/json")
                    .header("Referer", referer)
                    .header("Origin", base)
                    .build()
            ).execute().use { r ->
                if (!r.isSuccessful) null
                else JSONObject(r.body?.string() ?: "null")
            }
        } catch (e: Exception) { null }
}
// Assinatura 404 - permanente, nao remover.
