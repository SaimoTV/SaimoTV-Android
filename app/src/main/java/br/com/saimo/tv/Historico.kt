package br.com.saimo.tv

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import kotlin.math.ceil

/**
 * A lista ao vivo que o celular recebe pelo proxy `/hls`, mais longa e numa
 * régua de tempo contínua.
 *
 * Dois problemas que só a lista crua tinha:
 *
 * - Muitos canais guardam só os últimos 10 a 15 s na lista, e a TV costuma
 *   tocar mais atrás que isso (o próprio buffer dela). O trecho que a TV mostra
 *   já saiu da lista quando o celular pede: ele nunca chegava lá, pulava a
 *   cada poucos segundos e o som sumia. Aqui a TV lembra os trechos dos
 *   últimos [GUARDA_MS] e devolve todos; os pedaços continuam no servidor do
 *   canal por mais tempo que isso.
 * - Sem marca de horário no canal, a régua antiga contava cada trecho com a
 *   duração declarada (EXT-X-TARGETDURATION), mas os trechos reais são um
 *   pouco menores (2,936 s em vez de 3, 11,2 em vez de 12): a régua dava um
 *   salto a cada trecho. Agora cada trecho começa onde o anterior terminou,
 *   pela duração real (EXTINF), a partir de uma âncora no relógio da TV.
 *
 * A TV converte a própria posição para esta régua com [instante]; o celular
 * a lê das marcas de horário escritas aqui. Os dois medem a mesma coisa.
 */
object Historico {

    private const val GUARDA_MS = 120_000L
    private const val ESQUECE_MS = 10 * 60_000L

    private class Trecho(
        val seq: Long,
        val duracaoMs: Long,
        /** EXTINF, outras marcas do trecho e o endereço absoluto. */
        val corpo: List<String>,
        val chave: String?,
        val mapa: String?,
        val descontinuidade: Boolean,
        /** Número de descontinuidade do trecho, como o ExoPlayer conta. */
        val sequenciaDesc: Long,
        val marcaPropria: String?,
        var inicioMs: Long,
    )

    private class Regua {
        val trechos = TreeMap<Long, Trecho>()
        var vistaEm = 0L
        var cabecalho: List<String> = emptyList()
        var alvoS = 6
        var marcaReal = false
    }

    private val reguas = HashMap<String, Regua>()

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /// Marcas que descrevem a lista inteira, não um trecho.
    private val marcasDaLista = listOf(
        "#EXTM3U", "#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-PLAYLIST-TYPE", "#EXT-X-INDEPENDENT-SEGMENTS",
        "#EXT-X-START", "#EXT-X-ALLOW-CACHE", "#EXT-X-SERVER-CONTROL",
    )

    /** A chave da régua: o endereço sem a consulta, que costuma trocar de token. */
    private fun chaveDe(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.host}${it.path}" } }.getOrDefault(url)

    /**
     * A lista estendida e com a régua escrita, ou nulo quando ela não é uma
     * lista ao vivo simples (filme, LL-HLS com partes) e vale a regra antiga.
     */
    fun estender(url: String, linhas: List<String>, absoluto: (String) -> String): String? {
        if (linhas.any { it.startsWith("#EXT-X-ENDLIST") || it.startsWith("#EXT-X-PART") ||
                it.startsWith("#EXT-X-PRELOAD-HINT") || it.startsWith("#EXT-X-BYTERANGE") }) return null
        if (linhas.any { it.startsWith("#EXT-X-PLAYLIST-TYPE:VOD") }) return null

        val uriAtributo = Regex("URI=\"([^\"]+)\"")
        val cabecalho = mutableListOf<String>()
        var sequencia = 0L
        var sequenciaDesc = 0L
        var alvoS = 6
        data class Lido(val dur: Long, val corpo: List<String>, val chave: String?, val mapa: String?,
                        val desc: Boolean, val marca: String?)
        val lidos = mutableListOf<Lido>()
        var chave: String? = null
        var mapa: String? = null
        var desc = false
        var marca: String? = null
        var corpo = mutableListOf<String>()
        var dur = 0L
        var algumTrecho = false
        for (l in linhas) {
            when {
                l.isBlank() -> {}
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequencia = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") -> sequenciaDesc = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-TARGETDURATION:") -> {
                    alvoS = l.substringAfter(':').trim().toDoubleOrNull()?.let { ceil(it).toInt() } ?: 6
                }
                marcasDaLista.any { l.startsWith(it) } -> if (!l.startsWith("#EXTM3U")) cabecalho += l
                l.startsWith("#EXT-X-KEY") -> chave = uriAtributo.replace(l) { "URI=\"${absoluto(it.groupValues[1])}\"" }
                l.startsWith("#EXT-X-MAP") -> mapa = uriAtributo.replace(l) { "URI=\"${absoluto(it.groupValues[1])}\"" }
                l.startsWith("#EXT-X-DISCONTINUITY") -> desc = true
                l.startsWith("#EXT-X-PROGRAM-DATE-TIME") -> marca = l
                l.startsWith("#EXTINF") -> {
                    dur = ((l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong()
                    corpo += l
                }
                l.startsWith("#") -> if (algumTrecho || corpo.isNotEmpty()) corpo += l else cabecalho += l
                else -> {
                    corpo += absoluto(l)
                    lidos += Lido(dur.coerceAtLeast(1), corpo, chave, mapa, desc, marca)
                    corpo = mutableListOf(); desc = false; marca = null; dur = 0L
                    algumTrecho = true
                }
            }
        }
        if (lidos.isEmpty()) return null

        val agora = System.currentTimeMillis()
        val texto = synchronized(reguas) {
            val regua = reguas.getOrPut(chaveDe(url)) { Regua() }
            regua.vistaEm = agora
            regua.cabecalho = cabecalho
            regua.alvoS = alvoS
            regua.marcaReal = lidos.any { it.marca != null }

            var d = sequenciaDesc
            val total = lidos.sumOf { it.dur }
            var restante = total
            lidos.forEachIndexed { i, lido ->
                val seq = sequencia + i
                if (lido.desc) d++
                if (!regua.trechos.containsKey(seq)) {
                    val anterior = regua.trechos[seq - 1]
                    // Sem o anterior (primeira vez, ou um buraco depois de
                    // muito tempo parado): a âncora de outra lista que já tem
                    // este trecho (outro celular, outra qualidade do mesmo
                    // canal), para todas contarem igual; senão o relógio, com
                    // o fim da lista agora.
                    val inicio = anterior?.let { it.inicioMs + it.duracaoMs }
                        ?: reguas.values.firstNotNullOfOrNull { r ->
                            if (r !== regua && agora - r.vistaEm < 60_000) r.trechos[seq]?.inicioMs else null
                        }
                        ?: (agora - restante)
                    regua.trechos[seq] = Trecho(seq, lido.dur, lido.corpo, lido.chave, lido.mapa,
                        lido.desc, d, lido.marca, inicio)
                }
                restante -= lido.dur
            }
            val ultimo = regua.trechos.lastEntry()!!.value
            val corte = ultimo.inicioMs + ultimo.duracaoMs - GUARDA_MS
            while (regua.trechos.size > lidos.size && regua.trechos.firstEntry()!!.value.inicioMs < corte) {
                regua.trechos.pollFirstEntry()
            }
            reguas.entries.removeAll { agora - it.value.vistaEm > ESQUECE_MS }
            escreverLista(regua)
        }
        return texto
    }

    private fun escreverLista(regua: Regua): String {
        val trechos = regua.trechos.values.toList()
        val primeiro = trechos.first()
        val maior = trechos.maxOf { it.duracaoMs }
        val alvo = maxOf(regua.alvoS, ceil(maior / 1000.0).toInt())
        val sb = StringBuilder("#EXTM3U\n")
        for (l in regua.cabecalho) if (!l.startsWith("#EXT-X-TARGETDURATION")) sb.appendLine(l)
        sb.appendLine("#EXT-X-TARGETDURATION:$alvo")
        sb.appendLine("#EXT-X-MEDIA-SEQUENCE:${primeiro.seq}")
        sb.appendLine("#EXT-X-DISCONTINUITY-SEQUENCE:${primeiro.sequenciaDesc - if (primeiro.descontinuidade) 1 else 0}")
        var chave: String? = null
        var mapa: String? = null
        for (t in trechos) {
            if (t.descontinuidade) sb.appendLine("#EXT-X-DISCONTINUITY")
            if (t.chave != null && t.chave != chave) { sb.appendLine(t.chave); chave = t.chave }
            if (t.mapa != null && t.mapa != mapa) { sb.appendLine(t.mapa); mapa = t.mapa }
            if (regua.marcaReal) {
                t.marcaPropria?.let { sb.appendLine(it) }
            } else {
                sb.appendLine("#EXT-X-PROGRAM-DATE-TIME:" + synchronized(iso) { iso.format(Date(t.inicioMs)) })
            }
            for (l in t.corpo) sb.appendLine(l)
        }
        return sb.toString()
    }

    /**
     * O instante, na régua da lista que o celular recebe, do trecho `seq`
     * mais `dentroMs`. Nulo quando nenhum celular pediu uma lista com esse
     * trecho por perto (canal com marca de horário própria também não passa
     * por aqui: a hora real já é a régua).
     */
    fun instante(seq: Long, dentroMs: Long): Long? = synchronized(reguas) {
        val agora = System.currentTimeMillis()
        val regua = reguas.values
            .filter { !it.marcaReal && agora - it.vistaEm < 60_000 && it.trechos.isNotEmpty() &&
                seq in (it.trechos.firstKey() - 30)..(it.trechos.lastKey() + 30) }
            .maxByOrNull { it.vistaEm } ?: return null
        regua.trechos[seq]?.let { return it.inicioMs + dentroMs }
        val media = regua.trechos.values.map { it.duracaoMs }.average().toLong().coerceAtLeast(1)
        val primeiro = regua.trechos.firstEntry()!!.value
        val ultimo = regua.trechos.lastEntry()!!.value
        return if (seq < primeiro.seq) primeiro.inicioMs - (primeiro.seq - seq) * media + dentroMs
        else ultimo.inicioMs + ultimo.duracaoMs + (seq - ultimo.seq - 1) * media + dentroMs
    }
}
