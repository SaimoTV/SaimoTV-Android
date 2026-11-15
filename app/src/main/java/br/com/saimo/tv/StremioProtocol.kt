package br.com.saimo.tv

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Implementação limpa (clean-room) do protocolo HTTP/JSON aberto do Stremio.
 *
 * Contém normalização de Transport URLs (incluindo `stremio://`, configuração
 * no caminho e query strings), codificação por segmento, validação de manifestos
 * (com rejeição explícita de repositórios JS do Nuvio), e parser de streams,
 * catálogos, metadados e legendas inline.
 */
object StremioProtocol {

    class AddonException(mensagem: String) : Exception(mensagem)

    data class TransportUrl(
        val original: String,
        val baseUrl: String,
        val query: String = "",
    ) {
        /** URL completa para `manifest.json`, preservando path de configuração e `?query`. */
        fun manifestUrl(): String = anexarQuery("$baseUrl/manifest.json")

        /**
         * Monta URL de recurso Stremio (`/catalog/...`, `/meta/...`, `/stream/...`, `/subtitles/...`)
         * com percent-encoding por segmento e preservando `?query`.
         */
        fun resourceUrl(
            recurso: String,
            tipo: String,
            id: String,
            extra: Map<String, String> = emptyMap(),
        ): String {
            val recSeg = codificarSegmento(recurso)
            val tipoSeg = codificarSegmento(tipo)
            val idSeg = codificarSegmento(id)
            val caminho = if (extra.isNotEmpty()) {
                val extraSeg = formatarExtra(extra)
                "$baseUrl/$recSeg/$tipoSeg/$idSeg/$extraSeg.json"
            } else {
                "$baseUrl/$recSeg/$tipoSeg/$idSeg.json"
            }
            return anexarQuery(caminho)
        }

        private fun anexarQuery(url: String): String =
            if (query.isNotEmpty()) "$url?$query" else url
    }

    data class Recurso(
        val nome: String,
        val types: List<String> = emptyList(),
        val idPrefixes: List<String> = emptyList(),
    )

    data class ExtraCatalogo(
        val name: String,
        val isRequired: Boolean = false,
        val options: List<String> = emptyList(),
        val optionsLimit: Int = 1,
    )

    data class Catalogo(
        val type: String,
        val id: String,
        val name: String,
        val extra: List<ExtraCatalogo> = emptyList(),
    ) {
        fun aceitaSemFiltros(): Boolean = extra.none { it.isRequired }
        fun suportaBusca(): Boolean = extra.any { it.name == "search" }
        fun suportaGenero(): Boolean = extra.any { it.name == "genre" }
        fun suportaPaginacao(): Boolean = extra.any { it.name == "skip" }
    }

    data class HintsManifesto(
        val configurable: Boolean = false,
        val configurationRequired: Boolean = false,
        val adult: Boolean = false,
        val p2p: Boolean = false,
    )

    data class Manifesto(
        val id: String,
        val name: String,
        val version: String,
        val description: String = "",
        val logo: String? = null,
        val types: List<String> = emptyList(),
        val idPrefixes: List<String> = emptyList(),
        val resources: List<Recurso> = emptyList(),
        val catalogs: List<Catalogo> = emptyList(),
        val behaviorHints: HintsManifesto = HintsManifesto(),
    ) {
        /** Verifica se o addon declara o recurso para o [tipo] (e [id] quando `idPrefixes` existe). */
        fun suportaRecurso(nomeRecurso: String, tipo: String, id: String? = null): Boolean {
            val corresp = resources.filter { it.nome.equals(nomeRecurso, ignoreCase = true) }
            if (corresp.isEmpty()) return false
            return corresp.any { rec ->
                val tiposEfetivos = rec.types.ifEmpty { types }
                val tipoOk = tiposEfetivos.isEmpty() || tiposEfetivos.any { it.equals(tipo, ignoreCase = true) }
                if (!tipoOk) return@any false
                if (id == null) return@any true
                val prefixos = rec.idPrefixes.ifEmpty { idPrefixes }
                prefixos.isEmpty() || prefixos.any { prefixo -> id.startsWith(prefixo) }
            }
        }

        /** Retorna os prefixos efetivos aplicáveis a um recurso/tipo. */
        fun prefixosEfetivos(nomeRecurso: String): List<String> {
            val especificos = resources
                .firstOrNull { it.nome.equals(nomeRecurso, ignoreCase = true) }
                ?.idPrefixes
                .orEmpty()
            return especificos.ifEmpty { idPrefixes }
        }
    }

    enum class TipoStream {
        DIRETO,
        TORRENT_DEBRID,
        EXTERNO,
        YOUTUBE,
        INVALIDO,
    }

    data class LegendaInline(
        val id: String,
        val lang: String,
        val url: String,
    )

    data class StreamHints(
        val notWebReady: Boolean = false,
        val bingeGroup: String? = null,
        val filename: String? = null,
        val videoSize: Long? = null,
        val videoHash: String? = null,
        val requestHeaders: Map<String, String> = emptyMap(),
    )

    data class Stream(
        val url: String? = null,
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val sources: List<String> = emptyList(),
        val ytId: String? = null,
        val externalUrl: String? = null,
        val name: String = "",
        val title: String = "",
        val description: String = "",
        val subtitles: List<LegendaInline> = emptyList(),
        val behaviorHints: StreamHints = StreamHints(),
    ) {
        val tipo: TipoStream
            get() = when {
                !url.isNullOrBlank() -> TipoStream.DIRETO
                !infoHash.isNullOrBlank() -> TipoStream.TORRENT_DEBRID
                !externalUrl.isNullOrBlank() -> TipoStream.EXTERNO
                !ytId.isNullOrBlank() -> TipoStream.YOUTUBE
                else -> TipoStream.INVALIDO
            }

        val rotuloPrincipal: String
            get() = listOf(name, title.ifBlank { description })
                .filter { it.isNotBlank() }
                .joinToString(" • ")
                .replace('\n', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "Fonte do addon" }

        val badges: List<String>
            get() = buildList {
                when (tipo) {
                    TipoStream.TORRENT_DEBRID -> add("[Torrent]")
                    TipoStream.EXTERNO -> add("[Externo]")
                    TipoStream.YOUTUBE -> add("[YouTube]")
                    else -> Unit
                }
                if (behaviorHints.requestHeaders.isNotEmpty()) add("[Headers]")
                if (behaviorHints.notWebReady) add("[Formato pesado]")
            }
    }

    data class ItemCatalogo(
        val id: String,
        val type: String,
        val name: String,
        val poster: String? = null,
        val background: String? = null,
        val description: String = "",
        val releaseInfo: String = "",
        val genres: List<String> = emptyList(),
    )

    data class VideoMeta(
        val id: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val overview: String = "",
        val thumbnail: String? = null,
    )

    data class MetaDetalhada(
        val id: String,
        val type: String,
        val name: String,
        val poster: String? = null,
        val background: String? = null,
        val description: String = "",
        val releaseInfo: String = "",
        val genres: List<String> = emptyList(),
        val videos: List<VideoMeta> = emptyList(),
    )

    private val ERRO_NUVIO_JS =
        "Este endereço aponta para um repositório/plugin JS do Nuvio. " +
            "O Saimo TV suporta apenas addons HTTP do Stremio (manifest.json)."

    /**
     * Normaliza a URL de transporte de um addon Stremio:
     * - remove espaços
     * - converte `stremio://` em `https://`
     * - preserva query string (ex.: `?config=...`)
     * - remove `/manifest.json` e `/` finais mantendo configurações no path
     */
    fun normalizarUrl(raw: String): TransportUrl {
        val original = raw.trim()
        if (original.isEmpty()) throw AddonException("Informe a URL do addon Stremio.")

        val comEsquema = if (original.startsWith("stremio://", ignoreCase = true)) {
            "https://" + original.substring("stremio://".length)
        } else {
            original
        }

        if (!comEsquema.startsWith("https://", ignoreCase = true) &&
            !comEsquema.startsWith("http://", ignoreCase = true)
        ) {
            throw AddonException("URL inválida. Use https://, http:// ou stremio://.")
        }

        val idxQuery = comEsquema.indexOf('?')
        val parteCaminho = (if (idxQuery >= 0) comEsquema.substring(0, idxQuery) else comEsquema).trimEnd('/')
        val query = if (idxQuery >= 0) comEsquema.substring(idxQuery + 1).trim() else ""

        val baseLimpa = if (parteCaminho.endsWith("/manifest.json", ignoreCase = true)) {
            parteCaminho.dropLast("/manifest.json".length).trimEnd('/')
        } else {
            parteCaminho
        }

        val semEsquema = baseLimpa.substringAfter("://")
        val host = semEsquema.substringBefore('/').substringBefore(':')
        if (host.isBlank()) {
            throw AddonException("URL do addon sem endereço de servidor válido.")
        }

        return TransportUrl(original = original, baseUrl = baseLimpa, query = query)
    }

    /**
     * Codificação RFC 3986 para segmento único de caminho HTTP (UTF-8).
     * Espaços são codificados como `%20` (nunca `+`), e `/` / `:` / `?` são escapados.
     */
    fun codificarSegmento(valor: String): String {
        if (valor.isEmpty()) return ""
        val bytes = valor.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if ((c in 'a'.code..'z'.code) ||
                (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code || c == ':'.code
            ) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c ushr 4])
                sb.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun formatarExtra(extra: Map<String, String>): String =
        extra.entries.joinToString("&") { (k, v) ->
            "${codificarSegmento(k.trim())}=${codificarSegmento(v.trim())}"
        }

    /**
     * Valida e interpreta o texto retornado por `/manifest.json`.
     * Rejeita explicitamente manifestos/repositórios JS do Nuvio e HTML de erro.
     */
    fun interpretarManifesto(textoBruto: String): Manifesto {
        val limpo = textoBruto.trim().removePrefix("\uFEFF")
        if (limpo.isEmpty()) throw AddonException("O servidor retornou um manifest.json vazio.")

        if (pareceCodigoJsOuHtml(limpo)) {
            if (limpo.contains("module.exports") || limpo.contains("getStreams") || limpo.contains("export default")) {
                throw AddonException(ERRO_NUVIO_JS)
            }
            throw AddonException("O endereço não retornou um JSON de manifesto Stremio válido.")
        }

        val obj = runCatching { JSONObject(limpo) }.getOrElse {
            throw AddonException("Manifesto JSON inválido: ${it.message ?: "formato incorreto"}")
        }

        // Repositórios de scrapers Nuvio (JSON com scrapers sem resources) são aceitos e convertidos
        if (ehRepositorioNuvio(obj)) {
            val name = obj.optString("name", "Nuvio Plugin").trim().ifEmpty { "Nuvio Plugin" }
            val id = obj.optString("id", "").trim().ifEmpty {
                "nuvio." + name.lowercase().replace(" ", "-")
            }
            val version = obj.optString("version", "1.0.0").trim().ifEmpty { "1.0.0" }
            val scrapersCount = obj.optJSONArray("scrapers")?.length() ?: 0
            val desc = obj.optString("description", "").trim().ifEmpty {
                "Repositório Nuvio ($scrapersCount scrapers)"
            }
            return Manifesto(
                id = id,
                name = name,
                version = version,
                description = desc,
                logo = obj.optString("logo", "").trim().ifBlank { null },
                types = listOf("movie", "series"),
                idPrefixes = listOf("tt", "tmdb:"),
                resources = listOf(Recurso(nome = "stream", types = listOf("movie", "series"), idPrefixes = listOf("tt", "tmdb:"))),
                catalogs = emptyList(),
                behaviorHints = HintsManifesto(),
            )
        }

        val id = obj.optString("id", "").trim()
        val name = obj.optString("name", "").trim()
        val version = obj.optString("version", "1.0.0").trim().ifEmpty { "1.0.0" }
        val description = obj.optString("description", "").trim()
        val logo = obj.optString("logo", "").trim().ifBlank { null }
        val types = lerListaString(obj.optJSONArray("types"))
        val idPrefixes = lerListaString(obj.optJSONArray("idPrefixes"))
        val resources = lerRecursos(obj.optJSONArray("resources"))
        val catalogs = lerCatalogos(obj.optJSONArray("catalogs"))
        val hintsObj = obj.optJSONObject("behaviorHints")
        val hints = HintsManifesto(
            configurable = hintsObj?.optBoolean("configurable", false) == true,
            configurationRequired = hintsObj?.optBoolean("configurationRequired", false) == true,
            adult = hintsObj?.optBoolean("adult", false) == true,
            p2p = hintsObj?.optBoolean("p2p", false) == true,
        )

        if (id.isEmpty() || name.isEmpty()) {
            throw AddonException("Manifesto Stremio inválido: campos 'id' e 'name' são obrigatórios.")
        }
        if (resources.isEmpty() && catalogs.isEmpty()) {
            throw AddonException("Manifesto sem recursos Stremio ('resources' ou 'catalogs').")
        }

        return Manifesto(
            id = id,
            name = name,
            version = version,
            description = description,
            logo = logo,
            types = types,
            idPrefixes = idPrefixes,
            resources = resources,
            catalogs = catalogs,
            behaviorHints = hints,
        )
    }

    /** Serializa um [Manifesto] para persistência local ou troca via Saimo Connect. */
    fun manifestoParaJson(m: Manifesto): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("name", m.name)
        put("version", m.version)
        if (m.description.isNotEmpty()) put("description", m.description)
        if (!m.logo.isNullOrEmpty()) put("logo", m.logo)
        put("types", JSONArray(m.types))
        if (m.idPrefixes.isNotEmpty()) put("idPrefixes", JSONArray(m.idPrefixes))
        put("resources", JSONArray().apply {
            m.resources.forEach { r ->
                if (r.types.isEmpty() && r.idPrefixes.isEmpty()) {
                    put(r.nome)
                } else {
                    put(JSONObject().apply {
                        put("name", r.nome)
                        if (r.types.isNotEmpty()) put("types", JSONArray(r.types))
                        if (r.idPrefixes.isNotEmpty()) put("idPrefixes", JSONArray(r.idPrefixes))
                    })
                }
            }
        })
        put("catalogs", JSONArray().apply {
            m.catalogs.forEach { c ->
                put(JSONObject().apply {
                    put("type", c.type)
                    put("id", c.id)
                    put("name", c.name)
                    if (c.extra.isNotEmpty()) {
                        put("extra", JSONArray().apply {
                            c.extra.forEach { e ->
                                put(JSONObject().apply {
                                    put("name", e.name)
                                    if (e.isRequired) put("isRequired", true)
                                    if (e.options.isNotEmpty()) put("options", JSONArray(e.options))
                                    if (e.optionsLimit != 1) put("optionsLimit", e.optionsLimit)
                                })
                            }
                        })
                    }
                })
            }
        })
        put("behaviorHints", JSONObject().apply {
            put("configurable", m.behaviorHints.configurable)
            put("configurationRequired", m.behaviorHints.configurationRequired)
            put("adult", m.behaviorHints.adult)
            put("p2p", m.behaviorHints.p2p)
        })
    }

    /** Interpreta resposta JSON de `/stream/{type}/{id}.json`. */
    fun interpretarStreams(textoJson: String): List<Stream> {
        val obj = runCatching { JSONObject(textoJson) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("streams") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val stream = lerStream(item)
                if (stream.tipo != TipoStream.INVALIDO) add(stream)
            }
        }
    }

    fun lerStream(item: JSONObject): Stream {
        val url = item.optString("url", "").trim().ifBlank { null }
        val infoHash = item.optString("infoHash", "").trim().lowercase().ifBlank { null }
        val fileIdx = if (item.has("fileIdx") && !item.isNull("fileIdx")) {
            item.optInt("fileIdx", -1).takeIf { it >= 0 }
        } else null
        val sources = lerListaString(item.optJSONArray("sources"))
        val ytId = item.optString("ytId", "").trim().ifBlank { null }
        val externalUrl = item.optString("externalUrl", "").trim().ifBlank { null }
        val name = item.optString("name", "").trim()
        val title = item.optString("title", "").trim()
        val description = item.optString("description", "").trim()

        val subsArr = item.optJSONArray("subtitles")
        val legendas = buildList {
            if (subsArr != null) {
                for (j in 0 until subsArr.length()) {
                    val sObj = subsArr.optJSONObject(j) ?: continue
                    val sUrl = sObj.optString("url", "").trim()
                    if (sUrl.isEmpty()) continue
                    val sLang = sObj.optString("lang", "").trim().ifEmpty { "und" }
                    val sId = sObj.optString("id", "").trim().ifEmpty { "$sLang-$j" }
                    add(LegendaInline(sId, sLang, sUrl))
                }
            }
        }

        val hintsObj = item.optJSONObject("behaviorHints")
        val proxyReq = hintsObj?.optJSONObject("proxyHeaders")?.optJSONObject("request")
        val headers = linkedMapOf<String, String>()
        if (proxyReq != null) {
            val chaves = proxyReq.keys()
            while (chaves.hasNext()) {
                val k = chaves.next()
                val v = proxyReq.optString(k, "").trim()
                if (k.isNotBlank() && v.isNotEmpty()) headers[k.trim()] = v
            }
        }
        val videoSize = if (hintsObj != null && hintsObj.has("videoSize") && !hintsObj.isNull("videoSize")) {
            hintsObj.optLong("videoSize", -1L).takeIf { it > 0L }
        } else null

        val hints = StreamHints(
            notWebReady = hintsObj?.optBoolean("notWebReady", false) == true,
            bingeGroup = hintsObj?.optString("bingeGroup", "")?.trim()?.ifBlank { null },
            filename = hintsObj?.optString("filename", "")?.trim()?.ifBlank { null },
            videoSize = videoSize,
            videoHash = hintsObj?.optString("videoHash", "")?.trim()?.ifBlank { null },
            requestHeaders = headers,
        )

        return Stream(
            url = url,
            infoHash = infoHash,
            fileIdx = fileIdx,
            sources = sources,
            ytId = ytId,
            externalUrl = externalUrl,
            name = name,
            title = title,
            description = description,
            subtitles = legendas,
            behaviorHints = hints,
        )
    }

    /** Interpreta resposta JSON de `/catalog/{type}/{id}.json`. */
    fun interpretarCatalogo(textoJson: String): List<ItemCatalogo> {
        val obj = runCatching { JSONObject(textoJson) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("metas") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("id", "").trim()
                val name = m.optString("name", "").trim()
                val type = m.optString("type", "").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                add(
                    ItemCatalogo(
                        id = id,
                        type = type.ifEmpty { "movie" },
                        name = name,
                        poster = m.optString("poster", "").trim().ifBlank { null },
                        background = m.optString("background", "").trim().ifBlank { null },
                        description = m.optString("description", "").trim(),
                        releaseInfo = m.optString("releaseInfo", "").trim(),
                        genres = lerListaString(m.optJSONArray("genres")),
                    )
                )
            }
        }
    }

    /** Interpreta resposta JSON de `/meta/{type}/{id}.json`. */
    fun interpretarMeta(textoJson: String): MetaDetalhada? {
        val obj = runCatching { JSONObject(textoJson) }.getOrNull() ?: return null
        val m = obj.optJSONObject("meta") ?: return null
        val id = m.optString("id", "").trim()
        val name = m.optString("name", "").trim()
        if (id.isEmpty() || name.isEmpty()) return null
        val videosArr = m.optJSONArray("videos")
        val videos = buildList {
            if (videosArr != null) {
                for (i in 0 until videosArr.length()) {
                    val v = videosArr.optJSONObject(i) ?: continue
                    val vId = v.optString("id", "").trim()
                    val s = v.optInt("season", 0)
                    val ep = v.optInt("episode", v.optInt("number", 0))
                    if (vId.isEmpty() || ep <= 0) continue
                    add(
                        VideoMeta(
                            id = vId,
                            title = v.optString("title", v.optString("name", "")).trim(),
                            season = s,
                            episode = ep,
                            overview = v.optString("overview", "").trim(),
                            thumbnail = v.optString("thumbnail", "").trim().ifBlank { null },
                        )
                    )
                }
            }
        }
        return MetaDetalhada(
            id = id,
            type = m.optString("type", "movie").trim().ifEmpty { "movie" },
            name = name,
            poster = m.optString("poster", "").trim().ifBlank { null },
            background = m.optString("background", "").trim().ifBlank { null },
            description = m.optString("description", "").trim(),
            releaseInfo = m.optString("releaseInfo", "").trim(),
            genres = lerListaString(m.optJSONArray("genres")),
            videos = videos,
        )
    }

    /** Interpreta resposta JSON de `/subtitles/{type}/{id}.json` para [Legendas.Opcao]. */
    fun interpretarLegendas(textoJson: String, origemNome: String = "Addon"): List<Legendas.Opcao> {
        val obj = runCatching { JSONObject(textoJson) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("subtitles") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url", "").trim()
                if (url.isEmpty()) continue
                val lang = item.optString("lang", "").trim().lowercase().ifEmpty { "und" }
                val id = item.optString("id", "").trim().ifEmpty { "$origemNome-$lang-$i" }
                val labelBase = item.optString("SubFileName", "")
                    .ifBlank { item.optString("label", "") }
                    .trim()
                val nomeIdioma = nomeIdiomaLegenda(lang)
                val rotulo = if (labelBase.isNotEmpty()) "$nomeIdioma • $labelBase" else "$nomeIdioma ($origemNome)"
                add(Legendas.Opcao(id = id, idioma = lang, rotulo = rotulo, url = url))
            }
        }
    }

    fun nomeIdiomaLegenda(codigo: String): String = when (codigo.lowercase()) {
        "pob", "pt-br", "ptbr", "pb" -> "Português (BR)"
        "por", "pt", "pt-pt" -> "Português (PT)"
        "eng", "en" -> "Inglês"
        "spa", "es" -> "Espanhol"
        "fre", "fra", "fr" -> "Francês"
        else -> codigo.uppercase()
    }

    /** Infere `"dub"` ou `"leg"` a partir dos metadados textuais de uma fonte Stremio. */
    fun inferirVersaoAudio(stream: Stream): String {
        val texto = "${stream.name} ${stream.title} ${stream.description}".lowercase()
        val indicadoresDub = listOf(
            "dublado", "dual audio", "dual-áudio", "dual áudio", "português", "portugues",
            "pt-br", "ptbr", "pob", "🇧🇷", "🇵🇹", "áudio pt", "audio pt", "dub",
        )
        return if (indicadoresDub.any { texto.contains(it) }) "dub" else "leg"
    }

    private fun pareceCodigoJsOuHtml(texto: String): Boolean =
        !texto.startsWith("{")

    private fun ehRepositorioNuvio(obj: JSONObject): Boolean {
        if (obj.has("scrapers") && obj.optJSONArray("scrapers") != null && !obj.has("resources")) return true
        val temMarcadoresNuvio = obj.has("plugins") || obj.has("providers") || obj.has("manifestVersion")
        return temMarcadoresNuvio && !obj.has("resources") && !obj.has("catalogs")
    }

    private fun lerListaString(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) add(s)
            }
        }
    }

    private fun lerRecursos(arr: JSONArray?): List<Recurso> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                when (val raw = arr.opt(i)) {
                    is String -> {
                        val nome = raw.trim()
                        if (nome.isNotEmpty()) add(Recurso(nome = nome))
                    }
                    is JSONObject -> {
                        val nome = raw.optString("name", "").trim()
                        if (nome.isNotEmpty()) {
                            add(
                                Recurso(
                                    nome = nome,
                                    types = lerListaString(raw.optJSONArray("types")),
                                    idPrefixes = lerListaString(raw.optJSONArray("idPrefixes")),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun lerCatalogos(arr: JSONArray?): List<Catalogo> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val type = c.optString("type", "").trim()
                val id = c.optString("id", "").trim()
                val name = c.optString("name", "").trim().ifEmpty { id }
                if (type.isEmpty() || id.isEmpty()) continue
                val extraArr = c.optJSONArray("extra")
                val extras = buildList {
                    if (extraArr != null) {
                        for (j in 0 until extraArr.length()) {
                            val e = extraArr.optJSONObject(j) ?: continue
                            val eName = e.optString("name", "").trim()
                            if (eName.isEmpty()) continue
                            add(
                                ExtraCatalogo(
                                    name = eName,
                                    isRequired = e.optBoolean("isRequired", false),
                                    options = lerListaString(e.optJSONArray("options")),
                                    optionsLimit = e.optInt("optionsLimit", 1),
                                )
                            )
                        }
                    }
                }
                add(Catalogo(type = type, id = id, name = name, extra = extras))
            }
        }
    }
}
