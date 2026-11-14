package br.com.saimo.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolvedor client-side via REST para torrents (`infoHash`) usando os provedores Debrid
 * configurados pelo usuário: TorBox, Real-Debrid, AllDebrid e Premiumize.
 *
 * Não executa P2P nem libtorrent no aparelho; apenas converte o hash/magnet
 * na URL HTTPS direta retornada pelo provedor ativo.
 */
object DebridResolver {

    sealed class Resultado {
        data class Sucesso(
            val url: String,
            val provedor: DebridStore.Provedor,
            val nomeArquivo: String? = null,
        ) : Resultado()

        data class SemConfiguracao(val mensagem: String) : Resultado()
        data class Erro(val mensagem: String) : Resultado()
    }

    data class ArquivoTorrent(
        val id: String,
        val nome: String,
        val tamanho: Long,
        val urlDireta: String? = null,
        val indiceOriginal: Int = 0,
    )

    private val EXTENSOES_VIDEO = setOf(
        "mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "m2ts", "flv", "wmv", "mpg", "mpeg",
    )

    private fun codificarQuery(texto: String): String =
        java.net.URLEncoder.encode(texto, "UTF-8").replace("+", "%20")

    /** Monta URI magnet padrão com trackers opcionais do stream Stremio. */
    fun montarMagnet(
        infoHash: String,
        trackers: List<String> = emptyList(),
        titulo: String? = null,
    ): String {
        val hashLimpo = infoHash.trim().lowercase()
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(hashLimpo)
        if (!titulo.isNullOrBlank()) {
            sb.append("&dn=").append(codificarQuery(titulo.trim()))
        }
        for (src in trackers) {
            val s = src.trim()
            if (s.startsWith("tracker:", ignoreCase = true)) {
                val tr = s.substringAfter(':').trim()
                if (tr.isNotEmpty()) sb.append("&tr=").append(codificarQuery(tr))
            } else if (s.contains("://")) {
                sb.append("&tr=").append(codificarQuery(s))
            }
        }
        return sb.toString()
    }

    /** Seleciona o melhor arquivo de vídeo respeitando [fileIdx] quando válido, senão o maior vídeo. */
    fun selecionarArquivoVideo(arquivos: List<ArquivoTorrent>, fileIdx: Int?): ArquivoTorrent? {
        if (arquivos.isEmpty()) return null
        if (fileIdx != null && fileIdx in arquivos.indices) {
            val escolhido = arquivos[fileIdx]
            if (ehVideo(escolhido.nome) || arquivos.none { ehVideo(it.nome) }) return escolhido
        }
        val somenteVideos = arquivos.filter { ehVideo(it.nome) }
        val candidatos = somenteVideos.ifEmpty { arquivos }
        return candidatos.maxByOrNull { it.tamanho } ?: candidatos.first()
    }

    fun ehVideo(nome: String): Boolean {
        val ext = nome.substringAfterLast('.', "").lowercase()
        return ext in EXTENSOES_VIDEO
    }

    /** Tenta resolver [infoHash] pelos provedores Debrid habilitados, em ordem. */
    suspend fun resolver(
        infoHash: String,
        fileIdx: Int? = null,
        trackers: List<String> = emptyList(),
        titulo: String? = null,
    ): Resultado = withContext(Dispatchers.IO) {
        val ativos = DebridStore.provedoresAtivos()
        if (ativos.isEmpty()) {
            return@withContext Resultado.SemConfiguracao(
                "Esta fonte é um torrent. Configure uma chave TorBox, Real-Debrid, AllDebrid ou Premiumize em Ajustes → Addons e Debrid."
            )
        }

        val hashLimpo = infoHash.trim().lowercase()
        val magnet = montarMagnet(hashLimpo, trackers, titulo)
        val falhas = mutableListOf<String>()

        for (cfg in ativos) {
            val tentativa = try {
                when (cfg.provedor) {
                    DebridStore.Provedor.TORBOX -> resolverTorBox(cfg.apiKey, hashLimpo, magnet, fileIdx)
                    DebridStore.Provedor.REAL_DEBRID -> resolverRealDebrid(cfg.apiKey, magnet, fileIdx)
                    DebridStore.Provedor.ALL_DEBRID -> resolverAllDebrid(cfg.apiKey, magnet, fileIdx)
                    DebridStore.Provedor.PREMIUMIZE -> resolverPremiumize(cfg.apiKey, magnet, fileIdx)
                }
            } catch (e: Exception) {
                Resultado.Erro("${cfg.provedor.nome}: ${e.message ?: "falha na consulta"}")
            }
            if (tentativa is Resultado.Sucesso) return@withContext tentativa
            if (tentativa is Resultado.Erro) falhas.add(tentativa.mensagem)
        }

        Resultado.Erro(
            falhas.joinToString(" | ").ifBlank { "Nenhum provedor Debrid conseguiu resolver este torrent." }
        )
    }

    // region --- TorBox ---

    private suspend fun resolverTorBox(
        apiKey: String,
        hash: String,
        magnet: String,
        fileIdx: Int?,
    ): Resultado {
        val base = "https://api.torbox.app/v1/api"
        // Consulta rápida de cache (opcional; createtorrent também funciona se já em fila/cache)
        runCatching {
            val checkUrl = "$base/torrents/checkcached?hash=$hash&format=object&list_files=true"
            getJson(checkUrl, bearer = apiKey)
        }

        val form = FormBody.Builder()
            .add("magnet", magnet)
            .add("allow_zip", "false")
            .build()
        val criado = postFormJson("$base/torrents/createtorrent", form, bearer = apiKey)
        val torrentId = interpretarTorBoxCreate(criado)
            ?: return Resultado.Erro("TorBox: ${criado.optString("detail", "não foi possível criar torrent")}")

        var info: Pair<Boolean, List<ArquivoTorrent>> = false to emptyList()
        for (tentativa in 0 until 6) {
            val listaJson = getJson("$base/torrents/mylist?id=$torrentId&bypass_cache=true", bearer = apiKey)
            info = interpretarTorBoxLista(listaJson, torrentId)
            if (info.first && info.second.isNotEmpty()) break
            if (tentativa < 5) delay(1200)
        }
        val escolhido = selecionarArquivoVideo(info.second, fileIdx)
            ?: return Resultado.Erro("TorBox: torrent ainda não está pronto em cache.")
        if (!info.first) {
            return Resultado.Erro("TorBox: torrent ainda em preparação (fora do cache imediato).")
        }

        val dlUrl = "$base/torrents/requestdl?token=${StremioProtocol.codificarSegmento(apiKey)}" +
            "&torrent_id=$torrentId&file_id=${escolhido.id}&zip_link=false"
        val dlJson = getJson(dlUrl, bearer = apiKey)
        val linkFinal = interpretarTorBoxRequestDl(dlJson)
            ?: return Resultado.Erro("TorBox: link direto indisponível.")
        return Resultado.Sucesso(linkFinal, DebridStore.Provedor.TORBOX, escolhido.nome)
    }

    fun interpretarTorBoxCheckCached(json: JSONObject, hash: String): Boolean {
        if (!json.optBoolean("success", false)) return false
        val data = json.opt("data") ?: return false
        return when (data) {
            is JSONObject -> data.has(hash) || data.has(hash.lowercase()) || data.length() > 0
            is JSONArray -> data.length() > 0
            is Boolean -> data
            else -> false
        }
    }

    fun interpretarTorBoxCreate(json: JSONObject): String? {
        if (!json.optBoolean("success", true) && !json.has("data")) return null
        val data = json.optJSONObject("data") ?: return null
        val id = data.opt("torrent_id") ?: data.opt("id") ?: return null
        return id.toString().takeIf { it.isNotBlank() && it != "null" }
    }

    fun interpretarTorBoxLista(json: JSONObject, torrentId: String? = null): Pair<Boolean, List<ArquivoTorrent>> {
        val item = when (val data = json.opt("data")) {
            is JSONObject -> data
            is JSONArray -> {
                var encontrado: JSONObject? = null
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    if (torrentId == null || o.opt("id")?.toString() == torrentId) {
                        encontrado = o
                        break
                    }
                }
                encontrado ?: data.optJSONObject(0)
            }
            else -> null
        } ?: return false to emptyList()

        val pronto = item.optBoolean("download_present", false) ||
            item.optBoolean("download_finished", false) ||
            item.optBoolean("cached", false)
        val filesArr = item.optJSONArray("files") ?: return pronto to emptyList()
        val arquivos = buildList(filesArr.length()) {
            for (i in 0 until filesArr.length()) {
                val f = filesArr.optJSONObject(i) ?: continue
                val id = f.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: i.toString()
                val nome = f.optString("short_name", "").ifBlank { f.optString("name", "") }
                val tam = f.optLong("size", 0L)
                add(ArquivoTorrent(id = id, nome = nome, tamanho = tam, indiceOriginal = i))
            }
        }
        return pronto to arquivos
    }

    fun interpretarTorBoxRequestDl(json: JSONObject): String? {
        if (json.has("success") && !json.optBoolean("success", false)) return null
        val link = json.optString("data", "").trim()
        return link.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    // endregion

    // region --- Real-Debrid ---

    private suspend fun resolverRealDebrid(
        apiKey: String,
        magnet: String,
        fileIdx: Int?,
    ): Resultado {
        val base = "https://api.real-debrid.com/rest/1.0"
        val addForm = FormBody.Builder().add("magnet", magnet).build()
        val addJson = postFormJson("$base/torrents/addMagnet", addForm, bearer = apiKey)
        val id = interpretarRealDebridAdd(addJson)
            ?: return Resultado.Erro("Real-Debrid: ${addJson.optString("error", "falha ao enviar magnet")}")

        var infoJson = getJson("$base/torrents/info/$id", bearer = apiKey)
        for (i in 0 until 5) {
            if ((infoJson.optJSONArray("files")?.length() ?: 0) > 0) break
            delay(800)
            infoJson = getJson("$base/torrents/info/$id", bearer = apiKey)
        }

        val info = interpretarRealDebridInfo(infoJson)
        val escolhido = selecionarArquivoVideo(info.arquivos, fileIdx)
        val selectParam = escolhido?.id ?: "all"
        postFormRaw("$base/torrents/selectFiles/$id", FormBody.Builder().add("files", selectParam).build(), bearer = apiKey)

        var links = info.links
        for (i in 0 until 6) {
            infoJson = getJson("$base/torrents/info/$id", bearer = apiKey)
            val atual = interpretarRealDebridInfo(infoJson)
            links = atual.links
            if (links.isNotEmpty()) break
            if (atual.status in setOf("error", "magnet_error", "virus", "dead")) {
                return Resultado.Erro("Real-Debrid: torrent indisponível (${atual.status}).")
            }
            if (i < 5) delay(1200)
        }

        val primeiroLink = links.firstOrNull()
            ?: return Resultado.Erro("Real-Debrid: torrent não está em cache imediato.")
        val unJson = postFormJson(
            "$base/unrestrict/link",
            FormBody.Builder().add("link", primeiroLink).build(),
            bearer = apiKey,
        )
        val urlFinal = interpretarRealDebridUnrestrict(unJson)
            ?: return Resultado.Erro("Real-Debrid: ${unJson.optString("error", "falha ao liberar link")}")
        return Resultado.Sucesso(urlFinal, DebridStore.Provedor.REAL_DEBRID, escolhido?.nome)
    }

    data class RealDebridInfo(
        val status: String,
        val arquivos: List<ArquivoTorrent>,
        val links: List<String>,
    )

    fun interpretarRealDebridAdd(json: JSONObject): String? =
        json.optString("id", "").trim().ifBlank { null }

    fun interpretarRealDebridInfo(json: JSONObject): RealDebridInfo {
        val status = json.optString("status", "").trim().lowercase()
        val filesArr = json.optJSONArray("files")
        val arquivos = buildList {
            if (filesArr != null) {
                for (i in 0 until filesArr.length()) {
                    val f = filesArr.optJSONObject(i) ?: continue
                    val id = f.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    val path = f.optString("path", "").trimStart('/')
                    val bytes = f.optLong("bytes", 0L)
                    add(ArquivoTorrent(id = id, nome = path, tamanho = bytes, indiceOriginal = i))
                }
            }
        }
        val linksArr = json.optJSONArray("links")
        val links = buildList {
            if (linksArr != null) {
                for (i in 0 until linksArr.length()) {
                    val l = linksArr.optString(i, "").trim()
                    if (l.isNotEmpty()) add(l)
                }
            }
        }
        return RealDebridInfo(status = status, arquivos = arquivos, links = links)
    }

    fun interpretarRealDebridUnrestrict(json: JSONObject): String? {
        val dl = json.optString("download", "").trim()
        return dl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    // endregion

    // region --- AllDebrid ---

    private suspend fun resolverAllDebrid(
        apiKey: String,
        magnet: String,
        fileIdx: Int?,
    ): Resultado {
        val base = "https://api.alldebrid.com/v4"
        val authQuery = "agent=SaimoTV&apikey=${StremioProtocol.codificarSegmento(apiKey)}"
        val uploadJson = postFormJson(
            "$base/magnet/upload?$authQuery",
            FormBody.Builder().add("magnets[]", magnet).build(),
            bearer = apiKey,
        )
        val magnetId = interpretarAllDebridUpload(uploadJson)
            ?: return Resultado.Erro("AllDebrid: falha ao enviar magnet.")

        var links = emptyList<ArquivoTorrent>()
        for (i in 0 until 6) {
            val statusJson = getJson("$base/magnet/status?$authQuery&id=$magnetId", bearer = apiKey)
            val parsed = interpretarAllDebridStatus(statusJson)
            if (parsed.first && parsed.second.isNotEmpty()) {
                links = parsed.second
                break
            }
            if (parsed.first && parsed.second.isEmpty()) {
                val filesJson = runCatching {
                    getJson("https://api.alldebrid.com/v4.1/magnet/files?$authQuery&id[]=$magnetId", bearer = apiKey)
                }.getOrNull()
                if (filesJson != null) {
                    val v41 = interpretarAllDebridStatus(filesJson)
                    if (v41.second.isNotEmpty()) {
                        links = v41.second
                        break
                    }
                }
            }
            if (i < 5) delay(1200)
        }

        val escolhido = selecionarArquivoVideo(links, fileIdx)
        val linkProtegido = escolhido?.urlDireta
            ?: return Resultado.Erro("AllDebrid: nenhum arquivo pronto em cache.")
        val unlockJson = getJson(
            "$base/link/unlock?$authQuery&link=${StremioProtocol.codificarSegmento(linkProtegido)}",
            bearer = apiKey,
        )
        val urlFinal = interpretarAllDebridUnlock(unlockJson)
            ?: return Resultado.Erro("AllDebrid: não foi possível destravar o link.")
        return Resultado.Sucesso(urlFinal, DebridStore.Provedor.ALL_DEBRID, escolhido.nome)
    }

    fun interpretarAllDebridUpload(json: JSONObject): String? {
        if (!json.optString("status", "").equals("success", ignoreCase = true)) return null
        val arr = json.optJSONObject("data")?.optJSONArray("magnets") ?: return null
        val first = arr.optJSONObject(0) ?: return null
        val id = first.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        return id
    }

    fun interpretarAllDebridStatus(json: JSONObject): Pair<Boolean, List<ArquivoTorrent>> {
        if (!json.optString("status", "").equals("success", ignoreCase = true)) return false to emptyList()
        val data = json.optJSONObject("data") ?: return false to emptyList()
        val mag = when (val m = data.opt("magnets")) {
            is JSONObject -> m
            is JSONArray -> m.optJSONObject(0)
            else -> null
        } ?: return false to emptyList()

        val pronto = mag.optBoolean("ready", false) ||
            mag.optInt("statusCode", 0) == 4
        val coleta = mutableListOf<ArquivoTorrent>()
        val linksArr = mag.optJSONArray("links")
        if (linksArr != null) {
            for (i in 0 until linksArr.length()) {
                val l = linksArr.optJSONObject(i) ?: continue
                val url = l.optString("link", "").trim()
                val nome = l.optString("filename", l.optString("n", "")).trim()
                val tam = l.optLong("size", l.optLong("s", 0L))
                if (url.isNotEmpty()) {
                    coleta.add(ArquivoTorrent(id = i.toString(), nome = nome, tamanho = tam, urlDireta = url, indiceOriginal = i))
                }
            }
        }
        val filesArr = mag.optJSONArray("files")
        if (coleta.isEmpty() && filesArr != null) {
            coletarArquivosAllDebrid(filesArr, coleta)
        }
        return pronto to coleta
    }

    private fun coletarArquivosAllDebrid(arr: JSONArray, out: MutableList<ArquivoTorrent>) {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val link = item.optString("l", item.optString("link", "")).trim()
            val nome = item.optString("n", item.optString("name", "")).trim()
            val tam = item.optLong("s", item.optLong("size", 0L))
            if (link.isNotEmpty()) {
                val idx = out.size
                out.add(ArquivoTorrent(id = idx.toString(), nome = nome, tamanho = tam, urlDireta = link, indiceOriginal = idx))
            }
            val sub = item.optJSONArray("e")
            if (sub != null) coletarArquivosAllDebrid(sub, out)
        }
    }

    fun interpretarAllDebridUnlock(json: JSONObject): String? {
        if (!json.optString("status", "").equals("success", ignoreCase = true)) return null
        val link = json.optJSONObject("data")?.optString("link", "")?.trim().orEmpty()
        return link.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    // endregion

    // region --- Premiumize ---

    private fun resolverPremiumize(
        apiKey: String,
        magnet: String,
        fileIdx: Int?,
    ): Resultado {
        val form = FormBody.Builder()
            .add("apikey", apiKey)
            .add("src", magnet)
            .build()
        val json = postFormJson("https://www.premiumize.me/api/transfer/directdl", form, bearer = null)
        val arquivos = interpretarPremiumizeDirectDl(json)
        val escolhido = selecionarArquivoVideo(arquivos, fileIdx)
        val urlFinal = escolhido?.urlDireta
            ?: return Resultado.Erro("Premiumize: ${json.optString("message", "torrent indisponível em cache")}")
        return Resultado.Sucesso(urlFinal, DebridStore.Provedor.PREMIUMIZE, escolhido.nome)
    }

    fun interpretarPremiumizeDirectDl(json: JSONObject): List<ArquivoTorrent> {
        if (!json.optString("status", "").equals("success", ignoreCase = true)) return emptyList()
        val content = json.optJSONArray("content") ?: return emptyList()
        return buildList(content.length()) {
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                val link = c.optString("link", "").trim()
                    .ifBlank { c.optString("stream_link", "").trim() }
                if (link.isEmpty()) continue
                val path = c.optString("path", c.optString("name", "")).trim()
                val size = c.optLong("size", 0L)
                add(ArquivoTorrent(id = i.toString(), nome = path, tamanho = size, urlDireta = link, indiceOriginal = i))
            }
        }
    }

    // endregion

    // region --- HTTP helpers ---

    private fun getJson(url: String, bearer: String?): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", Playback.DEFAULT_USER_AGENT)
            .apply { if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer") }
            .build()
        Playback.client.newCall(req).execute().use { r ->
            val texto = r.body.string()
            return runCatching { JSONObject(texto) }.getOrElse {
                JSONObject().put("error", "HTTP ${r.code}")
            }
        }
    }

    private fun postFormJson(url: String, form: FormBody, bearer: String?): JSONObject {
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("Accept", "application/json")
            .header("User-Agent", Playback.DEFAULT_USER_AGENT)
            .apply { if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer") }
            .build()
        Playback.client.newCall(req).execute().use { r ->
            val texto = r.body.string()
            return runCatching { JSONObject(texto) }.getOrElse {
                JSONObject().put("error", "HTTP ${r.code}")
            }
        }
    }

    private fun postFormRaw(url: String, form: FormBody, bearer: String?) {
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("User-Agent", Playback.DEFAULT_USER_AGENT)
            .apply { if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer") }
            .build()
        Playback.client.newCall(req).execute().close()
    }

    // endregion
}
