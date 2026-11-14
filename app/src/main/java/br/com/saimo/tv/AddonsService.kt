package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * Orquestra consultas paralelas aos addons Stremio habilitados (stream, catalog,
 * meta, subtitles) e fornece descoberta via TMDB quando não há lista VOD local.
 */
object AddonsService {

    private const val TIMEOUT_MS = 12_000L

    data class ItemDescoberta(
        val titulo: String,
        val serie: Boolean,
        val capa: String?,
        val ano: String,
        val sinopse: String,
        val tmdbId: Int,
        val imdbId: String?,
    ) {
        fun paraAlvo(): Alvo = Alvo(
            titulo = titulo,
            serie = serie,
            letra = "",
            ano = ano,
            tmdbId = tmdbId,
            imdbId = imdbId.orEmpty(),
        )
    }

    /** Busca streams em todos os addons habilitados que suportam o recurso `stream`. */
    suspend fun buscarStreams(
        tmdbId: Int,
        imdbId: String?,
        serie: Boolean,
        temporada: Int = 0,
        episodio: Int = 0,
    ): List<Titulos.Opcao> = withContext(Dispatchers.IO) {
        val addons = AddonsStore.ativos()
        if (addons.isEmpty()) return@withContext emptyList()

        val tipo = if (serie) "series" else "movie"
        val imdbResolvido = imdbId?.takeIf { it.isNotBlank() }
            ?: if (tmdbId > 0) IdResolver.imdbPorTmdb(tmdbId, serie) else null

        if (tmdbId <= 0 && imdbResolvido.isNullOrBlank()) return@withContext emptyList()

        val resultadosPorAddon = coroutineScope {
            addons.map { addon ->
                async {
                    val idRecurso = IdResolver.idParaAddon(
                        manifesto = addon.manifesto,
                        recurso = "stream",
                        serie = serie,
                        tmdbId = tmdbId,
                        imdbId = imdbResolvido,
                        temporada = temporada,
                        episodio = episodio,
                    ) ?: return@async emptyList()

                    val transport = runCatching {
                        StremioProtocol.normalizarUrl(addon.transportUrl)
                    }.getOrNull() ?: return@async emptyList()

                    val url = transport.resourceUrl("stream", tipo, idRecurso)
                    val corpo = withTimeoutOrNull(TIMEOUT_MS) { buscarTexto(url) }
                        ?: return@async emptyList()
                    val streams = StremioProtocol.interpretarStreams(corpo)
                    streams.map { converterParaOpcao(addon, it) }
                }
            }.awaitAll()
        }

        val unicos = linkedSetOf<String>()
        buildList {
            for (lista in resultadosPorAddon) {
                for (op in lista) {
                    val chave = op.chaveIdentidade.ifBlank { "${op.addonId}:${op.titulo}" }
                    if (unicos.add(chave)) add(op)
                }
            }
        }
    }

    /** Busca legendas em todos os addons Stremio habilitados com recurso `subtitles`. */
    suspend fun buscarLegendas(
        tmdbId: Int,
        imdbId: String?,
        serie: Boolean,
        temporada: Int = 0,
        episodio: Int = 0,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): List<Legendas.Opcao> = withContext(Dispatchers.IO) {
        val addons = AddonsStore.ativos()
        if (addons.isEmpty()) return@withContext emptyList()

        val tipo = if (serie) "series" else "movie"
        val imdbResolvido = imdbId?.takeIf { it.isNotBlank() }
            ?: if (tmdbId > 0) IdResolver.imdbPorTmdb(tmdbId, serie) else null

        if (tmdbId <= 0 && imdbResolvido.isNullOrBlank()) return@withContext emptyList()

        val extra = linkedMapOf<String, String>().apply {
            if (!videoHash.isNullOrBlank()) put("videoHash", videoHash)
            if (videoSize != null && videoSize > 0L) put("videoSize", videoSize.toString())
            if (!filename.isNullOrBlank()) put("filename", filename)
        }

        coroutineScope {
            addons.map { addon ->
                async {
                    val idRecurso = IdResolver.idParaAddon(
                        manifesto = addon.manifesto,
                        recurso = "subtitles",
                        serie = serie,
                        tmdbId = tmdbId,
                        imdbId = imdbResolvido,
                        temporada = temporada,
                        episodio = episodio,
                    ) ?: return@async emptyList()

                    val transport = runCatching {
                        StremioProtocol.normalizarUrl(addon.transportUrl)
                    }.getOrNull() ?: return@async emptyList()

                    val url = transport.resourceUrl("subtitles", tipo, idRecurso, extra)
                    val corpo = withTimeoutOrNull(TIMEOUT_MS) { buscarTexto(url) }
                        ?: return@async emptyList()
                    StremioProtocol.interpretarLegendas(corpo, addon.nome)
                }
            }.awaitAll().flatten()
        }
    }

    /** Busca episódios de série via recurso `meta` dos addons quando o TMDB não tiver temporadas. */
    suspend fun buscarEpisodiosMeta(
        tmdbId: Int,
        imdbId: String?,
    ): List<Titulos.Ep> = withContext(Dispatchers.IO) {
        val addons = AddonsStore.ativos()
        if (addons.isEmpty()) return@withContext emptyList()

        val imdbResolvido = imdbId?.takeIf { it.isNotBlank() }
            ?: if (tmdbId > 0) IdResolver.imdbPorTmdb(tmdbId, true) else null

        for (addon in addons) {
            val idRecurso = IdResolver.idParaAddon(
                manifesto = addon.manifesto,
                recurso = "meta",
                serie = true,
                tmdbId = tmdbId,
                imdbId = imdbResolvido,
            ) ?: continue
            val transport = runCatching { StremioProtocol.normalizarUrl(addon.transportUrl) }.getOrNull()
                ?: continue
            val url = transport.resourceUrl("meta", "series", idRecurso)
            val corpo = withTimeoutOrNull(TIMEOUT_MS) { buscarTexto(url) } ?: continue
            val meta = StremioProtocol.interpretarMeta(corpo) ?: continue
            if (meta.videos.isNotEmpty()) {
                return@withContext meta.videos
                    .filter { it.season > 0 && it.episode > 0 }
                    .distinctBy { it.season to it.episode }
                    .sortedWith(compareBy({ it.season }, { it.episode }))
                    .map { Titulos.Ep(it.season, it.episode, emptyMap()) }
            }
        }
        emptyList()
    }

    /**
     * Carrega fileiras para Início / OnDemand / Filmes / Séries combinando catálogos dos
     * addons habilitados e descoberta TMDB quando não há lista VOD local.
     */
    suspend fun carregarFilasCatalogo(
        context: Context,
        filtroSerie: Boolean? = null,
        maxPorCatalogo: Int = 20,
    ): List<Inicio.Fila> = withContext(Dispatchers.IO) {
        if (!AddonsStore.temAtivo()) return@withContext emptyList()

        val tiposAceitos = when (filtroSerie) {
            null -> setOf("movie", "series")
            false -> setOf("movie")
            true -> setOf("series")
        }

        val pedidosCatalogo = AddonsStore.ativos().flatMap { addon ->
            addon.manifesto.catalogs
                .filter { it.type.lowercase() in tiposAceitos && it.aceitaSemFiltros() }
                .take(4)
                .map { addon to it }
        }

        val filasAddons = coroutineScope {
            pedidosCatalogo.map { (addon, cat) ->
                async {
                    val transport = runCatching { StremioProtocol.normalizarUrl(addon.transportUrl) }.getOrNull()
                        ?: return@async null
                    val url = transport.resourceUrl("catalog", cat.type, cat.id)
                    val corpo = withTimeoutOrNull(TIMEOUT_MS) { buscarTexto(url) } ?: return@async null
                    val itens = StremioProtocol.interpretarCatalogo(corpo).take(maxPorCatalogo)
                    if (itens.isEmpty()) return@async null

                    val serie = cat.type.equals("series", ignoreCase = true)
                    val cartoes = itens.map { item ->
                        val (tmdbId, imdbId) = IdResolver.extrairIdentidade(item.id)
                        val ano = Regex("\\d{4}").find(item.releaseInfo)?.value.orEmpty()
                        val alvo = Alvo(
                            titulo = item.name,
                            serie = serie,
                            letra = "",
                            ano = ano,
                            tmdbId = tmdbId,
                            imdbId = imdbId.orEmpty(),
                        )
                        Inicio.Cartao(
                            titulo = item.name,
                            capa = item.poster.orEmpty(),
                            inicial = item.name.take(1).uppercase(),
                            procurarCapa = item.poster.isNullOrBlank(),
                            nomeDaCapa = item.name,
                            alvo = alvo,
                            sinopse = item.description.ifBlank { null },
                            aoEscolher = { abrirFichaComBridge(context, alvo) },
                        )
                    }
                    Inicio.Fila(
                        titulo = "${cat.name} (${addon.nome})",
                        cartoes = cartoes,
                    )
                }
            }.awaitAll().filterNotNull()
        }

        if (filasAddons.isNotEmpty()) return@withContext filasAddons

        // Quando só há addons de stream (sem catálogos), popula via descoberta TMDB
        descobrirTmdb(context, filtroSerie)
    }

    /** Descoberta direta no TMDB (Em alta / Populares) para TV sem lista VOD local. */
    suspend fun descobrirTmdb(
        context: Context,
        filtroSerie: Boolean? = null,
    ): List<Inicio.Fila> = withContext(Dispatchers.IO) {
        if (!AddonsStore.temAtivo()) return@withContext emptyList()

        val pedidos = buildList {
            if (filtroSerie != true) {
                add(Triple("Filmes em Alta", false, "${Detalhes.BASE}/trending/movie/week?language=pt-BR&api_key=${Detalhes.CHAVE}"))
                add(Triple("Filmes Populares", false, "${Detalhes.BASE}/movie/popular?language=pt-BR&region=BR&api_key=${Detalhes.CHAVE}"))
            }
            if (filtroSerie != false) {
                add(Triple("Séries em Alta", true, "${Detalhes.BASE}/trending/tv/week?language=pt-BR&api_key=${Detalhes.CHAVE}"))
                add(Triple("Séries Populares", true, "${Detalhes.BASE}/tv/popular?language=pt-BR&api_key=${Detalhes.CHAVE}"))
            }
        }

        coroutineScope {
            pedidos.map { (tituloFila, serie, url) ->
                async {
                    val corpo = withTimeoutOrNull(TIMEOUT_MS) { buscarTexto(url) } ?: return@async null
                    val itens = interpretarListaTmdb(corpo, serie)
                    if (itens.isEmpty()) return@async null
                    Inicio.Fila(
                        titulo = tituloFila,
                        cartoes = itens.map { d ->
                            val alvo = d.paraAlvo()
                            Inicio.Cartao(
                                titulo = d.titulo,
                                capa = d.capa.orEmpty(),
                                inicial = d.titulo.take(1).uppercase(),
                                procurarCapa = false,
                                alvo = alvo,
                                sinopse = d.sinopse.ifBlank { null },
                                aoEscolher = { FichaActivity.abrir(context, alvo) },
                            )
                        },
                    )
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Busca itens nos catálogos Stremio habilitados com suporte ao extra `search`. */
    suspend fun buscarCatalogos(
        termo: String,
        filtroSerie: Boolean? = null,
    ): List<ItemDescoberta> = withContext(Dispatchers.IO) {
        val q = termo.trim()
        if (q.isEmpty() || !AddonsStore.temCatalogoAtivo()) return@withContext emptyList()

        val tiposAceitos = when (filtroSerie) {
            null -> setOf("movie", "series")
            false -> setOf("movie")
            true -> setOf("series")
        }

        val pedidos = AddonsStore.ativos().flatMap { addon ->
            addon.manifesto.catalogs
                .filter { it.type.lowercase() in tiposAceitos && it.suportaBusca() }
                .take(3)
                .map { addon to it }
        }
        if (pedidos.isEmpty()) return@withContext emptyList()

        val encontrados = coroutineScope {
            pedidos.map { (addon, cat) ->
                async {
                    val transport = runCatching { StremioProtocol.normalizarUrl(addon.transportUrl) }.getOrNull()
                        ?: return@async emptyList()
                    val url = transport.resourceUrl("catalog", cat.type, cat.id, mapOf("search" to q))
                    val corpo = withTimeoutOrNull(TIMEOUT_MS) { buscarTexto(url) } ?: return@async emptyList()
                    val serie = cat.type.equals("series", ignoreCase = true)
                    StremioProtocol.interpretarCatalogo(corpo).take(15).map { item ->
                        val (tmdbId, imdbId) = IdResolver.extrairIdentidade(item.id)
                        val ano = Regex("\\d{4}").find(item.releaseInfo)?.value.orEmpty()
                        ItemDescoberta(
                            titulo = item.name,
                            serie = serie,
                            capa = item.poster,
                            ano = ano,
                            sinopse = item.description,
                            tmdbId = tmdbId,
                            imdbId = imdbId,
                        )
                    }
                }
            }.awaitAll().flatten()
        }

        encontrados.distinctBy { "${it.serie}|${it.imdbId ?: ""}|${it.tmdbId}|${it.titulo.lowercase()}" }
    }

    /** Descoberta TMDB filtrada por gênero/categoria para `GradeActivity` quando não há lista local. */
    suspend fun descobrirTmdbLista(
        serie: Boolean,
        pagina: Int = 1,
    ): List<ItemDescoberta> = withContext(Dispatchers.IO) {
        val kind = if (serie) "tv" else "movie"
        val url = "${Detalhes.BASE}/$kind/popular?language=pt-BR&page=$pagina&api_key=${Detalhes.CHAVE}"
        val corpo = withTimeoutOrNull(TIMEOUT_MS) { buscarTexto(url) } ?: return@withContext emptyList()
        interpretarListaTmdb(corpo, serie)
    }

    private fun abrirFichaComBridge(context: Context, alvo: Alvo) {
        FichaActivity.abrir(context, alvo)
    }

    private fun interpretarListaTmdb(corpo: String, serie: Boolean): List<ItemDescoberta> {
        val obj = runCatching { JSONObject(corpo) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("results") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val id = r.optInt("id", 0).takeIf { it > 0 } ?: continue
                val titulo = r.optString(if (serie) "name" else "title", "").trim()
                if (titulo.isEmpty()) continue
                val data = r.optString(if (serie) "first_air_date" else "release_date", "")
                val ano = data.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
                val poster = r.optString("poster_path", "").trim()
                val capa = if (poster.startsWith("/")) "${Detalhes.IMAGENS}w342$poster" else null
                add(
                    ItemDescoberta(
                        titulo = titulo,
                        serie = serie,
                        capa = capa,
                        ano = ano,
                        sinopse = r.optString("overview", "").trim(),
                        tmdbId = id,
                        imdbId = null,
                    )
                )
            }
        }
    }

    fun converterParaOpcao(
        addon: AddonsStore.AddonInstalado,
        stream: StremioProtocol.Stream,
    ): Titulos.Opcao {
        val versao = StremioProtocol.inferirVersaoAudio(stream)
        val legendasInline = stream.subtitles.map { sub ->
            Legendas.Opcao(
                id = "${addon.id}:${sub.id}",
                idioma = sub.lang,
                rotulo = "${StremioProtocol.nomeIdiomaLegenda(sub.lang)} (${addon.nome})",
                url = sub.url,
            )
        }
        return Titulos.Opcao(
            versao = versao,
            url = stream.url.orEmpty(),
            origem = addon.nome,
            titulo = stream.rotuloPrincipal,
            headers = stream.behaviorHints.requestHeaders,
            bingeGroup = stream.behaviorHints.bingeGroup,
            infoHash = stream.infoHash,
            fileIdx = stream.fileIdx,
            trackerSources = stream.sources,
            externalUrl = stream.externalUrl,
            ytId = stream.ytId,
            notWebReady = stream.behaviorHints.notWebReady,
            filename = stream.behaviorHints.filename,
            videoSize = stream.behaviorHints.videoSize,
            videoHash = stream.behaviorHints.videoHash,
            legendasInline = legendasInline,
            badges = stream.badges,
            addonId = addon.id,
        )
    }

    private fun buscarTexto(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", Playback.DEFAULT_USER_AGENT)
            .build()
        Playback.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body.string()
        }
    }.getOrNull()
}
