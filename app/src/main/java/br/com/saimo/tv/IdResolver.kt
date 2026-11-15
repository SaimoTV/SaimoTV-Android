package br.com.saimo.tv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Ponte bidirecional TMDB <-> IMDb com cache em RAM e SharedPreferences (`saimo_id_bridge_v1`).
 * Funciona independentemente de existir lista M3U/VOD configurada.
 */
object IdResolver {

    private const val PREF_NOME = "saimo_id_bridge_v1"
    private const val LIMITE_CACHE = 3000

    data class ReferenciaTitulo(
        val tmdbId: Int,
        val imdbId: String?,
        val titulo: String = "",
        val ano: String = "",
        val capa: String? = null,
        val serie: Boolean = false,
    )

    private var prefs: SharedPreferences? = null
    private val cacheTmdbParaImdb = mutableMapOf<String, String>()
    private val cacheImdbParaRef = mutableMapOf<String, ReferenciaTitulo>()

    fun iniciar(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREF_NOME, Context.MODE_PRIVATE)
        prefs = sp
        // O cache só cresce (cada título aberto grava uma entrada) e é lido inteiro
        // na abertura do app, na thread principal: passou do limite, recomeça vazio.
        val todas = sp.all
        if (todas.size > LIMITE_CACHE) {
            sp.edit().clear().apply()
            return
        }
        synchronized(this) {
            for ((k, v) in todas) {
                val s = v as? String ?: continue
                if (k.startsWith("t2i:")) cacheTmdbParaImdb[k.removePrefix("t2i:")] = s
                else if (k.startsWith("i2t:")) {
                    runCatching { JSONObject(s) }.getOrNull()?.let { o ->
                        val tmdb = o.optInt("tmdbId", 0)
                        if (tmdb > 0) {
                            cacheImdbParaRef[k.removePrefix("i2t:")] = ReferenciaTitulo(
                                tmdbId = tmdb,
                                imdbId = o.optString("imdbId", "").ifBlank { null },
                                titulo = o.optString("titulo", ""),
                                ano = o.optString("ano", ""),
                                capa = o.optString("capa", "").ifBlank { null },
                                serie = o.optBoolean("serie", false),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Converte um ID no estilo Stremio (`tt123`, `tmdb:456`, etc.) em partes estruturadas. */
    fun extrairIdentidade(rawId: String): Pair<Int, String?> {
        val limpo = rawId.trim().substringBefore(':')
        if (limpo.startsWith("tt", ignoreCase = true)) {
            val emCache = synchronized(this) { cacheImdbParaRef[limpo.lowercase()] }
            return (emCache?.tmdbId ?: 0) to limpo
        }
        if (rawId.trim().startsWith("tmdb:", ignoreCase = true)) {
            val num = rawId.trim().substringAfter(':').substringBefore(':').toIntOrNull() ?: 0
            val imdb = if (num > 0) synchronized(this) {
                cacheTmdbParaImdb["movie:$num"] ?: cacheTmdbParaImdb["tv:$num"]
            } else null
            return num to imdb
        }
        return 0 to null
    }

    /** Resolve `imdbId` (`tt...`) a partir de [tmdbId] + [serie], consultando cache e TMDB `/external_ids`. */
    suspend fun imdbPorTmdb(tmdbId: Int, serie: Boolean): String? = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext null
        val kind = if (serie) "tv" else "movie"
        val chave = "$kind:$tmdbId"
        synchronized(this@IdResolver) { cacheTmdbParaImdb[chave] }?.let { return@withContext it }

        val url = "${Detalhes.BASE}/$kind/$tmdbId/external_ids?api_key=${Detalhes.CHAVE}"
        val corpo = buscarTexto(url) ?: return@withContext null
        val imdb = runCatching { JSONObject(corpo).optString("imdb_id", "").trim() }
            .getOrNull()?.takeIf { it.startsWith("tt") } ?: return@withContext null

        salvarMapeamento(tmdbId, imdb, serie)
        imdb
    }

    /** Resolve título no TMDB a partir de um `imdbId` (`tt...`) via `/3/find/{imdbId}`. */
    suspend fun referenciaPorImdb(imdbId: String, dicaSerie: Boolean? = null): ReferenciaTitulo? =
        withContext(Dispatchers.IO) {
            val imdb = imdbId.trim().substringBefore(':')
            if (!imdb.startsWith("tt", ignoreCase = true)) return@withContext null
            val chave = imdb.lowercase()
            synchronized(this@IdResolver) { cacheImdbParaRef[chave] }?.let { return@withContext it }

            val url = "${Detalhes.BASE}/find/$imdb?external_source=imdb_id&language=pt-BR&api_key=${Detalhes.CHAVE}"
            val corpo = buscarTexto(url) ?: return@withContext null
            val ref = interpretarFindImdb(corpo, imdb, dicaSerie) ?: return@withContext null
            salvarReferencia(ref)
            ref
        }

    fun interpretarFindImdb(corpo: String, imdbId: String, dicaSerie: Boolean?): ReferenciaTitulo? {
        val obj = runCatching { JSONObject(corpo) }.getOrNull() ?: return null
        val filmes = obj.optJSONArray("movie_results")
        val series = obj.optJSONArray("tv_results")
        val filme0 = if (filmes != null && filmes.length() > 0) filmes.optJSONObject(0) else null
        val serie0 = if (series != null && series.length() > 0) series.optJSONObject(0) else null

        val usarSerie = when {
            dicaSerie == true && serie0 != null -> true
            dicaSerie == false && filme0 != null -> false
            filme0 != null -> false
            serie0 != null -> true
            else -> return null
        }
        val escolhido = (if (usarSerie) serie0 else filme0) ?: return null
        val tmdbId = escolhido.optInt("id", 0).takeIf { it > 0 } ?: return null
        val titulo = escolhido.optString(if (usarSerie) "name" else "title", "").trim()
        val data = escolhido.optString(if (usarSerie) "first_air_date" else "release_date", "")
        val ano = data.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
        val posterPath = escolhido.optString("poster_path", "").trim()
        val capa = if (posterPath.startsWith("/")) "${Detalhes.IMAGENS}w342$posterPath" else null
        return ReferenciaTitulo(
            tmdbId = tmdbId,
            imdbId = imdbId,
            titulo = titulo,
            ano = ano,
            capa = capa,
            serie = usarSerie,
        )
    }

    /**
     * Escolhe o ID mais adequado para consultar [manifesto] no recurso [recurso],
     * respeitando `idPrefixes` (`tt` vs `tmdb:`) e formatando episódio de série (`id:T:E`).
     */
    fun idParaAddon(
        manifesto: StremioProtocol.Manifesto,
        recurso: String,
        serie: Boolean,
        tmdbId: Int,
        imdbId: String?,
        temporada: Int = 0,
        episodio: Int = 0,
    ): String? {
        val tipo = if (serie) "series" else "movie"
        if (!manifesto.suportaRecurso(recurso, tipo)) return null

        val prefixos = manifesto.prefixosEfetivos(recurso)
        val candidatos = buildList {
            if (!imdbId.isNullOrBlank()) add(imdbId)
            if (tmdbId > 0) add("tmdb:$tmdbId")
        }
        val baseId = if (prefixos.isEmpty()) {
            candidatos.firstOrNull { it.startsWith("tt") } ?: candidatos.firstOrNull()
        } else {
            candidatos.firstOrNull { cand -> prefixos.any { p -> cand.startsWith(p) } }
                ?: candidatos.firstOrNull()?.takeIf { manifesto.suportaRecurso(recurso, tipo, it) }
        } ?: return null

        if (!manifesto.suportaRecurso(recurso, tipo, baseId)) return null
        return if (serie && temporada > 0 && episodio > 0 && recurso in setOf("stream", "subtitles")) {
            "$baseId:$temporada:$episodio"
        } else {
            baseId
        }
    }

    private fun salvarMapeamento(tmdbId: Int, imdbId: String, serie: Boolean) {
        val kind = if (serie) "tv" else "movie"
        synchronized(this) {
            cacheTmdbParaImdb["$kind:$tmdbId"] = imdbId
        }
        prefs?.edit()?.putString("t2i:$kind:$tmdbId", imdbId)?.apply()
    }

    private fun salvarReferencia(ref: ReferenciaTitulo) {
        val imdb = ref.imdbId?.lowercase() ?: return
        val kind = if (ref.serie) "tv" else "movie"
        val json = JSONObject().apply {
            put("tmdbId", ref.tmdbId)
            put("imdbId", ref.imdbId)
            put("titulo", ref.titulo)
            put("ano", ref.ano)
            put("capa", ref.capa.orEmpty())
            put("serie", ref.serie)
        }
        synchronized(this) {
            cacheTmdbParaImdb["$kind:${ref.tmdbId}"] = ref.imdbId
            cacheImdbParaRef[imdb] = ref
        }
        prefs?.edit()
            ?.putString("t2i:$kind:${ref.tmdbId}", ref.imdbId)
            ?.putString("i2t:$imdb", json.toString())
            ?.apply()
    }

    private fun buscarTexto(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Playback.DEFAULT_USER_AGENT)
            .build()
        Playback.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body.string()
        }
    }.getOrNull()
}
