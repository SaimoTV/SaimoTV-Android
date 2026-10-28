package br.com.saimo.tv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * A lista de canais, baixada do endereço que a pessoa informou
 * ([Enderecos.canais]).
 *
 * O app não traz canal nenhum: abre na última lista que conseguiu ler (em
 * disco, da execução anterior) e troca pela nova quando ela chega, então a
 * falta de rede não custa os canais a quem já os tinha. Sem endereço, a lista
 * fica vazia e nenhum pedido de rede é feito.
 */
@UnstableApi
object Remote {

    /// catalogo.txt: o catálogo inteiro, com chave de ClearKey, Referer e
    /// agente. É a lista que manda.
    private const val CATALOGO = "catalogo.txt"
    /// canais.txt: extras em M3U. Um M3U não guarda chave nem cabeçalho, então
    /// ele entra como reserva, nunca no lugar do catálogo.
    private const val EXTRAS = "canais.txt"
    /// restritos.txt: os canais que só aparecem depois do código.
    private const val RESTRITOS = "restritos.txt"

    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    /// Lista restrita em uso: a baixada.
    @Volatile
    var restritos: List<Channel> = emptyList()
        private set

    /** Esquece o que está na memória (o endereço da lista mudou ou foi apagado). */
    fun limpar() {
        channels = emptyList()
        restritos = emptyList()
        conferidaEm = 0L
    }

    /** Lista disponível agora, sem tocar na rede. */
    fun loadCached(context: Context) {
        val base = ler(context, "catalogo.txt")
        val extras = ler(context, "canais.txt")
        if (base.isNotEmpty() || extras.isNotEmpty()) channels = merge(base, extras)
        ler(context, "restritos.txt").takeIf { it.isNotEmpty() }?.let { restritos = comoAdulto(it) }
    }

    private fun ler(context: Context, nome: String): List<Channel> {
        val text = runCatching { File(context.filesDir, nome).readText() }.getOrNull()
            ?: return emptyList()
        return parse(text)
    }

    /**
     * Junta as duas listas: o catálogo publicado manda, os extras entram atrás.
     *
     * O catálogo carrega o que um M3U não tem como carregar: a chave
     * do ClearKey dos 42 canais com DRM, o Referer e o User-Agent que certos
     * CDNs exigem, e a ordem em que as fontes devem ser tentadas. Trocar um pelo
     * outro apagaria tudo isso. Casando por nome, cada canal fica com as fontes
     * do catálogo primeiro e as publicadas logo atrás, como reserva; o que só
     * existe na lista publicada entra no fim, como canal novo.
     */
    internal fun merge(base: List<Channel>, published: List<Channel>): List<Channel> {
        val principal = base
        val extra = published.associateBy { Epg.normalise(it.name) }
        val usados = mutableSetOf<String>()
        val out = principal.map { channel ->
            val chave = Epg.normalise(channel.name)
            val vindas = extra[chave] ?: return@map channel
            usados += chave
            val conhecidas = channel.sources.map { it.url }.toSet()
            val novas = vindas.sources.filter { it.url !in conhecidas }
            if (novas.isEmpty()) channel else channel.copy(sources = channel.sources + novas)
        }
        return out + published.filter { Epg.normalise(it.name) !in usados }
    }

    /**
     * Baixa a lista publicada. Devolve falso quando não conseguiu ler algo
     * aproveitável, e aí quem chamou fica com o que já tinha em vez de esvaziar
     * a lista.
     */
    /// Quando a lista foi conferida pela última vez neste processo. Abrir o
    /// Ao vivo dez vezes seguidas baixava e lia os 330 KB dez vezes.
    @Volatile
    private var conferidaEm = 0L
    private const val CONFERIR_A_CADA_MS = 10 * 60_000L

    /** [forcar]: quem pediu foi a pessoa (Ajustes), não a abertura de uma tela. */
    suspend fun refresh(context: Context, forcar: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val raiz = Enderecos.canais ?: return@withContext false
        val agora = System.currentTimeMillis()
        if (!forcar && agora - conferidaEm < CONFERIR_A_CADA_MS) return@withContext false
        conferidaEm = agora
        // Pedido da pessoa baixa tudo de novo, sem confiar na etiqueta.
        if (forcar) for (nome in listOf("catalogo.txt", "canais.txt", "restritos.txt")) File(context.filesDir, "$nome.etag").delete()
        val base = baixar(context, raiz + CATALOGO, "catalogo.txt")
        val extras = baixar(context, raiz + EXTRAS, "canais.txt")
        val novosRestritos = baixar(context, raiz + RESTRITOS, "restritos.txt")
        var restritosMudaram = false
        if (novosRestritos.isNotEmpty() && comoAdulto(novosRestritos) != restritos) {
            restritos = comoAdulto(novosRestritos)
            restritosMudaram = true
        }
        if (base.isEmpty() && extras.isEmpty()) return@withContext restritosMudaram

        val merged = merge(base.ifEmpty { ler(context, "catalogo.txt") },
                           extras.ifEmpty { ler(context, "canais.txt") })
        if (merged.isEmpty() || merged == channels) return@withContext restritosMudaram
        channels = merged
        true
    }

    /// Baixa e guarda. Devolve vazio quando não veio nada aproveitável — ou
    /// quando o servidor disse que nada mudou (304) —, e aí quem chamou fica
    /// com o que já estava em disco.
    ///
    /// A etiqueta (ETag) da última versão baixada vai junto no pedido: sem
    /// mudança, o servidor responde só o cabeçalho, sem os 330 KB nem o parse.
    private fun baixar(context: Context, url: String, nome: String): List<Channel> {
        val arquivo = File(context.filesDir, nome)
        val etiqueta = File(context.filesDir, "$nome.etag")
        val text = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .header("Cache-Control", "no-cache")
                .apply {
                    val anterior = etiqueta.takeIf { arquivo.exists() && it.exists() }?.readText()?.trim()
                    if (!anterior.isNullOrEmpty()) header("If-None-Match", anterior)
                }
                .build()
            Playback.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()?.also { corpo ->
                    response.header("ETag")?.let { novo -> runCatching { etiqueta.writeText(novo) } }
                        ?: etiqueta.delete()
                    if (corpo.isEmpty()) etiqueta.delete()
                }
            }
        }.getOrNull() ?: return emptyList()

        val parsed = parse(text)
        if (parsed.isEmpty()) return emptyList()
        runCatching { arquivo.writeText(text) }
        return parsed
    }

    /** Sem categoria declarada, "Brazzers" cairia em Variedades pela regra do nome. */
    private fun comoAdulto(canais: List<Channel>) =
        canais.map { if (it.categoria == null) it.copy(categoria = "Adulto") else it }

    /**
     * One `chave: valor` per line. `canal:` opens a channel, `fonte:` adds a
     * source, and referer/agente/chave belong to the source above them.
     */
    fun parse(text: String): List<Channel> {
        if (text.trimStart().startsWith("#EXTM3U")) {
            return parseM3u(text)
        }
        return lerCampos(text)
    }

    /**
     * O parse do formato `chave: valor`.
     */
    internal fun lerCampos(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        var logo: String? = null
        var categoria: String? = null
        var sources = mutableListOf<Source>()

        fun flush() {
            val current = name
            if (current != null && sources.isNotEmpty()) {
                out += Channel(current, logo, sources.toList(), categoria)
            }
            name = null
            logo = null
            categoria = null
            sources = mutableListOf()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val field = line.substring(0, colon).lowercase()
            val value = line.substring(colon + 1).trim()
            if (value.isEmpty()) continue

            when (field) {
                "canal" -> { flush(); name = value }
                "logo" -> logo = value
                "categoria" -> categoria = value
                "fonte" -> sources += Source(value)
                "nome" -> if (sources.isNotEmpty()) {
                    sources[sources.size - 1] = sources.last().copy(nome = value)
                }
                "qualidade" -> if (sources.isNotEmpty()) {
                    sources[sources.size - 1] = sources.last().copy(quality = value)
                }
                "referer" -> if (sources.isNotEmpty()) {
                    sources[sources.size - 1] = sources.last().copy(referer = value)
                }
                "agente" -> if (sources.isNotEmpty()) {
                    sources[sources.size - 1] = sources.last().copy(userAgent = value)
                }
                // KID:CHAVE. O ExoPlayer precisa dos dois; sem o KID a licença
                // não é montável, então a fonte é descartada em vez de tocar mudo.
                "chave" -> if (sources.isNotEmpty()) {
                    val parts = value.split(":")
                    if (parts.size == 2 && parts.all { it.isNotBlank() }) {
                        sources[sources.size - 1] =
                            sources.last().copy(keyId = parts[0].trim(), key = parts[1].trim())
                    } else {
                        sources.removeAt(sources.size - 1)
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = mutableMapOf<String, Channel>()
        
        var currentTvgId: String? = null
        var currentName: String? = null
        var currentLogo: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line == "#EXTM3U") continue

            if (line.startsWith("#EXTINF:")) {
                val idMatch = "tvg-id=\"([^\"]+)\"".toRegex().find(line)
                currentTvgId = idMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

                val logoMatch = "tvg-logo=\"([^\"]+)\"".toRegex().find(line)
                currentLogo = logoMatch?.groupValues?.get(1)

                var inQuotes = false
                var commaIdx = -1
                for (i in line.indices) {
                    if (line[i] == '"') inQuotes = !inQuotes
                    if (line[i] == ',' && !inQuotes) {
                        commaIdx = i
                        break
                    }
                }
                val rawName = if (commaIdx != -1) line.substring(commaIdx + 1).trim() else line.substringAfterLast(",").trim()
                
                // Group by tvg-id if available, otherwise use the raw name without trailing parens (e.g., "(NX)")
                currentName = currentTvgId ?: rawName.replace(Regex("\\s*\\([^)]+\\)$"), "").trim()
            } else if (!line.startsWith("#")) {
                if (currentName != null) {
                    val existing = out[currentName!!]
                    if (existing != null) {
                        val updatedSources = existing.sources.toMutableList().apply { add(Source(line)) }
                        out[currentName!!] = existing.copy(sources = updatedSources, logo = existing.logo ?: currentLogo)
                    } else {
                        out[currentName!!] = Channel(currentName!!, currentLogo, listOf(Source(line)))
                    }
                }
            }
        }
        return out.values.toList()
    }
}
