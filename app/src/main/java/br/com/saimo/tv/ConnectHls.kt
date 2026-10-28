package br.com.saimo.tv

import androidx.media3.common.util.UnstableApi
import okhttp3.Request
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A lista do canal para o celular, já na régua da TV.
 *
 * `GET /hls?u=<lista>&r=<referer>&ua=<user-agent>`: a TV baixa a lista com os
 * cabeçalhos certos e devolve com os endereços absolutos (os pedaços o celular
 * busca direto na origem) e, quando o canal não traz marca de horário, com a
 * marca sintética de cada trecho escrita (ver [Sincronia]). Lista mestra tem
 * as variantes reescritas para passar por aqui também.
 */
@UnstableApi
object ConnectHls {

    fun responder(primeiraLinha: String, cabecalho: String, saida: OutputStream) {
        val alvo = primeiraLinha.split(" ").getOrNull(1).orEmpty()
        val parametros = alvo.substringAfter('?', "").split("&").mapNotNull {
            val (k, v) = it.split("=", limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to URLDecoder.decode(v, "UTF-8")
        }.toMap()
        val url = parametros["u"]
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            escrever(saida, 400, "text/plain", "lista?")
            return
        }
        val referer = parametros["r"]
        val agente = parametros["ua"]
        val host = Regex("(?im)^Host:\\s*([^\\r\\n]+)").find(cabecalho)?.groupValues?.get(1)?.trim()
            ?: "${Connect.obterIpLocal()}:${Connect.PORTA}"

        val texto = runCatching {
            val pedido = Request.Builder().url(url)
                .header("User-Agent", agente ?: Playback.DEFAULT_USER_AGENT)
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
            Playback.client.newCall(pedido).execute().use { r ->
                if (!r.isSuccessful) null else (r.request.url.toString() to r.body?.string())
            }
        }.getOrNull()
        val (urlFinal, corpo) = texto ?: (url to null)
        if (corpo == null || !corpo.trimStart().startsWith("#EXTM3U")) {
            escrever(saida, 502, "text/plain", "origem")
            return
        }
        val base = runCatching { URI(urlFinal) }.getOrNull()
        val absoluto = { u: String -> runCatching { base!!.resolve(u.trim()).toString() }.getOrDefault(u) }
        val peloProxy = { u: String ->
            buildString {
                append("http://").append(host).append("/hls?u=").append(URLEncoder.encode(absoluto(u), "UTF-8"))
                if (!referer.isNullOrBlank()) append("&r=").append(URLEncoder.encode(referer, "UTF-8"))
                if (!agente.isNullOrBlank()) append("&ua=").append(URLEncoder.encode(agente, "UTF-8"))
            }
        }
        val uriAtributo = Regex("URI=\"([^\"]+)\"")
        val linhas = corpo.lines()
        val mestra = linhas.any { it.startsWith("#EXT-X-STREAM-INF") }
        val saidaTexto = StringBuilder()

        if (mestra) {
            var proximaEhVariante = false
            for (l in linhas) {
                when {
                    l.startsWith("#EXT-X-STREAM-INF") -> { saidaTexto.appendLine(l); proximaEhVariante = true }
                    l.startsWith("#EXT-X-MEDIA") || l.startsWith("#EXT-X-I-FRAME-STREAM-INF") ->
                        saidaTexto.appendLine(uriAtributo.replace(l) { "URI=\"${peloProxy(it.groupValues[1])}\"" })
                    l.isNotBlank() && !l.startsWith("#") && proximaEhVariante -> {
                        saidaTexto.appendLine(peloProxy(l)); proximaEhVariante = false
                    }
                    else -> saidaTexto.appendLine(l)
                }
            }
        } else {
            val estendida = Historico.estender(url, linhas, absoluto)
            if (estendida != null) {
                saidaTexto.append(estendida)
            } else {
                val temMarca = linhas.any { it.startsWith("#EXT-X-PROGRAM-DATE-TIME") }
                val fim = linhas.any { it.startsWith("#EXT-X-ENDLIST") }
                val sequencia = linhas.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
                    ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
                val passo = linhas.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                    ?.substringAfter(':')?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }?.coerceAtLeast(1) ?: 6000L
                val escreverMarca = !temMarca && !fim
                var indice = 0L
                for (l in linhas) {
                    when {
                        l.startsWith("#EXT-X-KEY") || l.startsWith("#EXT-X-MAP") ->
                            saidaTexto.appendLine(uriAtributo.replace(l) { "URI=\"${absoluto(it.groupValues[1])}\"" })
                        l.startsWith("#EXTINF") -> {
                            // A marca vai antes do EXTINF de cada trecho.
                            if (escreverMarca) saidaTexto.appendLine("#EXT-X-PROGRAM-DATE-TIME:${Sincronia.marcaSintetica(sequencia + indice, passo)}")
                            saidaTexto.appendLine(l)
                        }
                        l.isNotBlank() && !l.startsWith("#") -> {
                            saidaTexto.appendLine(absoluto(l))
                            indice++
                        }
                        else -> saidaTexto.appendLine(l)
                    }
                }
            }
        }
        escrever(saida, 200, "application/vnd.apple.mpegurl", saidaTexto.toString())
    }

    private fun escrever(saida: OutputStream, codigo: Int, tipo: String, corpo: String) {
        val bytes = corpo.toByteArray(StandardCharsets.UTF_8)
        val status = when (codigo) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Bad Gateway" }
        val cabecalho = "HTTP/1.1 $codigo $status\r\n" +
            "Content-Type: $tipo\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        saida.write(cabecalho.toByteArray(StandardCharsets.ISO_8859_1))
        saida.write(bytes)
        saida.flush()
    }
}
