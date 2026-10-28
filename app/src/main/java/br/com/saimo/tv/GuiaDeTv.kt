package br.com.saimo.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Guia de programação extraído de guiadetv.com.
 *
 * Cobre 121+ canais com horários precisos no fuso de Brasília, sem bloqueio 429
 * e trazendo a sinopse em português diretamente na página.
 * É a fonte primária rápida do aplicativo.
 */
object GuiaDeTv {

    /// Nome normalizado -> slug do guiadetv.com, de `guia.txt`.
    private val CODES: Map<String, String> get() = GuiaTabelas.atual.guiadetv

    private val SUFIXO = Regex("""\s*\((?:s\d+|ot|rk|b\d+|alt)\)\s*$""", RegexOption.IGNORE_CASE)

    fun slug(name: String): String? {
        val clean = name.replace(SUFIXO, "")
        val key = Epg.normalise(clean)
        return CODES[key] ?: CODES[Epg.normalise(name)]
    }

    /// Em aparelho de 1 GB, 2 por vez; nos demais, 6.
    private val gate by lazy { Semaphore(if (Aparelho.poucaMemoria) 2 else 6) }

    suspend fun fetch(names: List<String>, from: Long, to: Long): Map<String, List<Programme>> =
        coroutineScope {
            // Agrupar canais por slug para baixar cada página apenas uma vez
            val slugToNames = LinkedHashMap<String, MutableList<String>>()
            for (name in names) {
                val s = slug(name) ?: continue
                slugToNames.getOrPut(s) { ArrayList(1) }.add(name)
            }
            if (slugToNames.isEmpty()) return@coroutineScope emptyMap()

            val results = slugToNames.keys.map { slug ->
                async(Dispatchers.IO) {
                    val lista = gate.withPermit {
                        runCatching {
                            parse(Epg.download(if (slug.startsWith("http")) slug else "https://www.guiadetv.com/canal/$slug"))
                                .filter { it.stop > from && it.start < to }
                        }.getOrNull()
                    } ?: emptyList()
                    slug to lista
                }
            }.awaitAll()

            val out = LinkedHashMap<String, List<Programme>>()
            for ((slug, lista) in results) {
                if (lista.isEmpty()) continue
                val canais = slugToNames[slug] ?: continue
                for (name in canais) {
                    out[name] = lista
                }
            }
            out
        }

    /**
     * `https://www.guiadetv.com/canal/<slug>`
     * Cada programa é um `<li class="flex gap-4 ...">` contendo:
     * - `<time datetime="AAAA-MM-DDTHH:MM:SS-03:00">HH:MM</time>`
     * - `<span class="text-xs ...">1h30</span>`
     * - `<a href="/programa/..." ...>Título</a>`
     * - `<p class="mt-1 ...">Sinopse...</p>`
     *
     * Feito por split("<li") sem regex sobre o documento inteiro, evitando
     * consumo excessivo de memória nativa do ICU em TV Box com pouca RAM.
     */
    fun parse(html: String): List<Programme> {
        val itens = html.split("<li")
        data class ItemBruto(val start: Long, val duration: Long, val title: String, val desc: String?)
        val lista = ArrayList<ItemBruto>(itens.size)

        for (i in 1 until itens.size) {
            val item = itens[i]
            val dt = between(item, "datetime=\"", "\"", "<time") ?: continue
            if (dt.length < 19) continue

            val ano = dt.substring(0, 4).toIntOrNull() ?: continue
            val mes = dt.substring(5, 7).toIntOrNull() ?: continue
            val dia = dt.substring(8, 10).toIntOrNull() ?: continue
            val hora = dt.substring(11, 13).toIntOrNull() ?: continue
            val minuto = dt.substring(14, 16).toIntOrNull() ?: continue

            // Fuso de Brasília (-03:00) convertido em millis UTC
            val start = (Epg.diasDesde1970(ano, mes, dia) * 86_400L + (hora + 3) * 3_600L + minuto * 60L) * 1000L

            val rawTitle = between(item, ">", "</a>", "/programa/") ?: continue
            val title = Epg.decodeEntities(rawTitle).trim().replace(Regex("""\s+"""), " ")
            if (title.length < 2) continue

            var duration = 3_600_000L
            val durText = between(item, ">", "</span>", "</time>")
            if (durText != null) {
                val t = durText.trim()
                if ('h' in t) {
                    val parts = t.split("h")
                    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    duration = (h * 60L + m) * 60_000L
                } else if (t.endsWith("min")) {
                    val m = t.removeSuffix("min").trim().toIntOrNull() ?: 0
                    if (m > 0) duration = m * 60_000L
                }
            }

            val desc = between(item, ">", "</p>", "<p")?.let {
                Epg.decodeEntities(it).trim().replace(Regex("""\s+"""), " ").ifEmpty { null }
            }

            lista += ItemBruto(start, duration, title, desc)
        }

        lista.sortBy { it.start }

        // Deduplicar mesmo horário de início
        val unicos = ArrayList<ItemBruto>(lista.size)
        for (item in lista) {
            val last = unicos.lastOrNull()
            if (last != null && last.start == item.start) continue
            unicos += item
        }

        return unicos.mapIndexed { index, item ->
            var stop = item.start + item.duration
            val next = unicos.getOrNull(index + 1)
            if (next != null) {
                if (stop > next.start || kotlin.math.abs(stop - next.start) <= 60_000L) {
                    stop = next.start
                }
            }
            Programme(
                title = item.title,
                category = "",
                start = item.start,
                stop = if (stop > item.start) stop else item.start + 3_600_000L,
                description = item.desc
            )
        }
    }

    private fun between(text: String, open: String, close: String, after: String? = null): String? {
        var from = 0
        if (after != null) {
            val marker = text.indexOf(after)
            if (marker < 0) return null
            from = marker + after.length
        }
        val start = text.indexOf(open, from)
        if (start < 0) return null
        val end = text.indexOf(close, start + open.length)
        if (end < 0) return null
        return text.substring(start + open.length, end)
    }
}
