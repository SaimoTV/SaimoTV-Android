package br.com.saimo.tv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistência local (`saimo_addons_v1`) e gerenciamento dos addons Stremio instalados.
 * Começa 100% vazio — o app não embute nem pré-instala addons de terceiros.
 */
object AddonsStore {

    private const val PREF_NOME = "saimo_addons_v1"
    private const val CHAVE_ADDONS = "addons"

    data class AddonInstalado(
        val id: String,
        val transportUrl: String,
        val manifesto: StremioProtocol.Manifesto,
        val habilitado: Boolean = true,
        val adicionadoEm: Long = 0L,
    ) {
        val nome: String get() = manifesto.name
        val versao: String get() = manifesto.version
    }

    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private var lista: List<AddonInstalado> = emptyList()

    private val _versao = MutableStateFlow(0)
    val versao: StateFlow<Int> = _versao

    fun iniciar(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREF_NOME, Context.MODE_PRIVATE)
        synchronized(lock) {
            prefs = sp
            val bruto = sp.getString(CHAVE_ADDONS, null)
            lista = if (bruto.isNullOrBlank()) emptyList() else deJsonArray(JSONArray(bruto))
        }
        notificar()
    }

    fun listar(): List<AddonInstalado> = synchronized(lock) { lista.toList() }

    fun ativos(): List<AddonInstalado> = synchronized(lock) { lista.filter { it.habilitado } }

    fun temAtivo(): Boolean = synchronized(lock) { lista.any { it.habilitado } }

    fun temStreamAtivo(): Boolean = ativos().any { addon ->
        addon.manifesto.resources.any { it.nome.equals("stream", ignoreCase = true) }
    }

    fun temCatalogoAtivo(): Boolean = ativos().any { addon ->
        addon.manifesto.catalogs.isNotEmpty() ||
            addon.manifesto.resources.any { it.nome.equals("catalog", ignoreCase = true) }
    }

    fun resumo(): String {
        val todos = listar()
        val hab = todos.count { it.habilitado }
        val debrid = DebridStore.resumoCurto()
        val addonsTexto = when {
            todos.isEmpty() -> "Nenhum addon"
            hab == todos.size -> "$hab ${if (hab == 1) "addon ativo" else "addons ativos"}"
            else -> "$hab de ${todos.size} ativos"
        }
        return if (debrid.isNotEmpty()) "$addonsTexto • $debrid" else addonsTexto
    }

    /** Busca e valida `manifest.json` remoto, instalando ou atualizando o addon. */
    suspend fun instalar(urlBruta: String): AddonInstalado {
        val transport = StremioProtocol.normalizarUrl(urlBruta)
        val manifesto = buscarManifestoRemoto(transport)
        val novo = AddonInstalado(
            id = manifesto.id,
            transportUrl = transport.manifestUrl(),
            manifesto = manifesto,
            habilitado = true,
            adicionadoEm = System.currentTimeMillis(),
        )
        salvarOuAtualizar(novo)
        return novo
    }

    /** Rebusca o `manifest.json` de um addon já instalado para testar conexão/atualizar metadados. */
    suspend fun testar(transportUrl: String): AddonInstalado {
        val transport = StremioProtocol.normalizarUrl(transportUrl)
        val manifesto = buscarManifestoRemoto(transport)
        var atualizado: AddonInstalado? = null
        atualizarLista { atual ->
            atual.map { item ->
                if (mesmaUrl(item.transportUrl, transport.manifestUrl())) {
                    item.copy(id = manifesto.id, manifesto = manifesto).also { atualizado = it }
                } else item
            }
        }
        return atualizado ?: AddonInstalado(
            id = manifesto.id,
            transportUrl = transport.manifestUrl(),
            manifesto = manifesto,
            habilitado = true,
            adicionadoEm = System.currentTimeMillis(),
        )
    }

    fun salvarOuAtualizar(addon: AddonInstalado) {
        atualizarLista { atual ->
            val idx = atual.indexOfFirst {
                mesmaUrl(it.transportUrl, addon.transportUrl) ||
                    (it.id == addon.id && mesmaBaseHost(it.transportUrl, addon.transportUrl))
            }
            if (idx >= 0) {
                atual.toMutableList().apply {
                    this[idx] = addon.copy(adicionadoEm = this[idx].adicionadoEm.takeIf { it > 0L } ?: addon.adicionadoEm)
                }
            } else {
                atual + addon
            }
        }
    }

    fun alternar(chave: String, habilitado: Boolean? = null): Boolean {
        var mudou = false
        atualizarLista { atual ->
            atual.map { item ->
                if (bateChave(item, chave)) {
                    mudou = true
                    item.copy(habilitado = habilitado ?: !item.habilitado)
                } else item
            }
        }
        return mudou
    }

    fun remover(chave: String): Boolean {
        var removeu = false
        atualizarLista { atual ->
            val filtrada = atual.filterNot { item ->
                bateChave(item, chave).also { if (it) removeu = true }
            }
            filtrada
        }
        return removeu
    }

    fun mover(chave: String, delta: Int): Boolean {
        if (delta == 0) return false
        var moveu = false
        atualizarLista { atual ->
            val idx = atual.indexOfFirst { bateChave(it, chave) }
            val destino = (idx + delta).coerceIn(0, (atual.size - 1).coerceAtLeast(0))
            if (idx >= 0 && destino != idx) {
                val copia = atual.toMutableList()
                val item = copia.removeAt(idx)
                copia.add(destino, item)
                moveu = true
                copia
            } else atual
        }
        return moveu
    }

    fun limpar() {
        atualizarLista { emptyList() }
    }

    /** Serializa todos os addons instalados para persistência ou sincronia Connect. */
    fun exportarJsonArray(): JSONArray = paraJsonArray(listar())

    /**
     * Mescla addons vindos do Saimo Connect sem perder dados quando o campo estiver ausente.
     * Usado apenas quando `dados.has("addons") && dados.optJSONArray("addons") != null`.
     */
    fun mesclarDoSync(recebidos: JSONArray) {
        val remotos = deJsonArray(recebidos)
        atualizarLista { locais -> mesclarListas(locais, remotos) }
    }

    fun mesclarListas(
        locais: List<AddonInstalado>,
        remotos: List<AddonInstalado>,
    ): List<AddonInstalado> {
        if (remotos.isEmpty()) return locais
        val mapa = linkedMapOf<String, AddonInstalado>()
        for (item in locais) {
            mapa[chaveNormalizada(item.transportUrl)] = item
        }
        for (item in remotos) {
            mapa[chaveNormalizada(item.transportUrl)] = item
        }
        return mapa.values.toList()
    }

    fun paraJsonArray(itens: List<AddonInstalado>): JSONArray = JSONArray().apply {
        itens.forEach { a ->
            put(JSONObject().apply {
                put("id", a.id)
                put("transportUrl", a.transportUrl)
                put("habilitado", a.habilitado)
                put("adicionadoEm", a.adicionadoEm)
                put("manifesto", StremioProtocol.manifestoParaJson(a.manifesto))
            })
        }
    }

    fun deJsonArray(arr: JSONArray): List<AddonInstalado> = buildList(arr.length()) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val transportUrl = o.optString("transportUrl", "").trim()
            val manifestoObj = o.optJSONObject("manifesto") ?: continue
            if (transportUrl.isEmpty()) continue
            val manifesto = runCatching {
                StremioProtocol.interpretarManifesto(manifestoObj.toString())
            }.getOrNull() ?: continue
            add(
                AddonInstalado(
                    id = o.optString("id", manifesto.id).ifBlank { manifesto.id },
                    transportUrl = transportUrl,
                    manifesto = manifesto,
                    habilitado = o.optBoolean("habilitado", true),
                    adicionadoEm = o.optLong("adicionadoEm", 0L),
                )
            )
        }
    }

    private suspend fun buscarManifestoRemoto(transport: StremioProtocol.TransportUrl): StremioProtocol.Manifesto =
        withContext(Dispatchers.IO) {
            val url = transport.manifestUrl()
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.5")
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .build()
            val resp = try {
                Playback.client.newCall(req).execute()
            } catch (e: Exception) {
                throw StremioProtocol.AddonException(
                    "Não foi possível conectar ao addon: ${e.message ?: "erro de rede"}"
                )
            }
            resp.use { r ->
                if (!r.isSuccessful) {
                    throw StremioProtocol.AddonException("Addon respondeu com HTTP ${r.code} em manifest.json.")
                }
                val corpo = r.body.string()
                StremioProtocol.interpretarManifesto(corpo)
            }
        }

    private fun atualizarLista(transform: (List<AddonInstalado>) -> List<AddonInstalado>) {
        synchronized(lock) {
            lista = transform(lista)
            val json = paraJsonArray(lista).toString()
            prefs?.edit()?.putString(CHAVE_ADDONS, json)?.apply()
        }
        notificar()
    }

    private fun notificar() {
        _versao.value = _versao.value + 1
    }

    private fun bateChave(item: AddonInstalado, chave: String): Boolean {
        val c = chave.trim()
        return c == item.transportUrl || c == item.id || mesmaUrl(item.transportUrl, c)
    }

    private fun chaveNormalizada(url: String): String =
        runCatching { StremioProtocol.normalizarUrl(url).manifestUrl().lowercase() }
            .getOrElse { url.trim().lowercase() }

    private fun mesmaUrl(a: String, b: String): Boolean =
        chaveNormalizada(a) == chaveNormalizada(b)

    private fun mesmaBaseHost(a: String, b: String): Boolean {
        val hostA = runCatching { StremioProtocol.normalizarUrl(a).baseUrl.substringBefore('?') }.getOrNull()
        val hostB = runCatching { StremioProtocol.normalizarUrl(b).baseUrl.substringBefore('?') }.getOrNull()
        return hostA != null && hostA.equals(hostB, ignoreCase = true)
    }
}
