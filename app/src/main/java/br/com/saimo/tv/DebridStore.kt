package br.com.saimo.tv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Persistência (`saimo_debrid_v1`) das chaves de API Debrid no cliente:
 * TorBox, Real-Debrid, AllDebrid e Premiumize.
 *
 * Por padrão, `sincronizarConnect` começa desligado (`false`) para não
 * enviar chaves privadas pela rede local sem consentimento explícito.
 */
object DebridStore {

    private const val PREF_NOME = "saimo_debrid_v1"
    private const val CHAVE_ESTADO = "estado"

    enum class Provedor(val codigo: String, val nome: String, val dicaChave: String) {
        TORBOX("torbox", "TorBox", "API Key do TorBox (torbox.app)"),
        REAL_DEBRID("real_debrid", "Real-Debrid", "Private Token / API Key (real-debrid.com)"),
        ALL_DEBRID("all_debrid", "AllDebrid", "API Key do AllDebrid (alldebrid.com)"),
        PREMIUMIZE("premiumize", "Premiumize", "API Key / Customer PIN (premiumize.me)"),
        ;

        companion object {
            fun porCodigo(codigo: String): Provedor? =
                entries.firstOrNull { it.codigo.equals(codigo.trim(), ignoreCase = true) }
        }
    }

    data class ConfigProvedor(
        val provedor: Provedor,
        val apiKey: String = "",
        val habilitado: Boolean = false,
    ) {
        val pronto: Boolean get() = habilitado && apiKey.isNotBlank()
    }

    data class Estado(
        val configs: Map<Provedor, ConfigProvedor> = Provedor.entries.associateWith { ConfigProvedor(it) },
        val sincronizarConnect: Boolean = false,
    ) {
        fun provedoresAtivos(): List<ConfigProvedor> =
            Provedor.entries.mapNotNull { configs[it]?.takeIf { c -> c.pronto } }
    }

    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private var estado = Estado()

    private val _versao = MutableStateFlow(0)
    val versao: StateFlow<Int> = _versao

    fun iniciar(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREF_NOME, Context.MODE_PRIVATE)
        synchronized(lock) {
            prefs = sp
            val bruto = sp.getString(CHAVE_ESTADO, null)
            estado = if (bruto.isNullOrBlank()) Estado() else deJson(JSONObject(bruto))
        }
        notificar()
    }

    fun atual(): Estado = synchronized(lock) { estado }

    fun configDe(provedor: Provedor): ConfigProvedor =
        synchronized(lock) { estado.configs[provedor] ?: ConfigProvedor(provedor) }

    fun provedoresAtivos(): List<ConfigProvedor> =
        synchronized(lock) { estado.provedoresAtivos() }

    fun temAlgumAtivo(): Boolean = synchronized(lock) { estado.provedoresAtivos().isNotEmpty() }

    fun sincronizarConnectAtivo(): Boolean = synchronized(lock) { estado.sincronizarConnect }

    fun resumoCurto(): String {
        val ativos = provedoresAtivos()
        return if (ativos.isEmpty()) "" else "Debrid: ${ativos.joinToString(", ") { it.provedor.nome }}"
    }

    fun definirProvedor(provedor: Provedor, apiKey: String, habilitado: Boolean) {
        atualizar { atual ->
            val novoMapa = atual.configs.toMutableMap()
            novoMapa[provedor] = ConfigProvedor(
                provedor = provedor,
                apiKey = apiKey.trim(),
                habilitado = habilitado && apiKey.isNotBlank(),
            )
            atual.copy(configs = novoMapa)
        }
    }

    fun definirSincronizarConnect(ativo: Boolean) {
        atualizar { it.copy(sincronizarConnect = ativo) }
    }

    /** Exporta o estado completo para persistência ou sincronia Connect (quando autorizado). */
    fun exportarJson(incluirChaves: Boolean = true): JSONObject = paraJson(atual(), incluirChaves)

    /** Aplica dados recebidos via Connect/LAN, preservando o restante dos provedores. */
    fun aplicarDoSync(obj: JSONObject) {
        val recebido = deJson(obj)
        atualizar { atual ->
            val mapa = atual.configs.toMutableMap()
            for (p in Provedor.entries) {
                val cfg = recebido.configs[p] ?: continue
                if (cfg.apiKey.isNotBlank() || obj.optJSONObject("provedores")?.has(p.codigo) == true) {
                    mapa[p] = cfg
                }
            }
            val sync = if (obj.has("sincronizarConnect")) recebido.sincronizarConnect else atual.sincronizarConnect
            Estado(configs = mapa, sincronizarConnect = sync)
        }
    }

    fun paraJson(est: Estado, incluirChaves: Boolean = true): JSONObject = JSONObject().apply {
        put("sincronizarConnect", est.sincronizarConnect)
        put("provedores", JSONObject().apply {
            for (p in Provedor.entries) {
                val c = est.configs[p] ?: ConfigProvedor(p)
                put(p.codigo, JSONObject().apply {
                    put("habilitado", c.habilitado)
                    put("configurado", c.apiKey.isNotBlank())
                    if (incluirChaves) put("apiKey", c.apiKey)
                })
            }
        })
    }

    fun deJson(obj: JSONObject): Estado {
        val provObj = obj.optJSONObject("provedores")
        val mapa = Provedor.entries.associateWith { p ->
            val item = provObj?.optJSONObject(p.codigo)
            val key = item?.optString("apiKey", "")?.trim().orEmpty()
            val hab = item?.optBoolean("habilitado", false) == true && key.isNotBlank()
            ConfigProvedor(provedor = p, apiKey = key, habilitado = hab)
        }
        return Estado(
            configs = mapa,
            sincronizarConnect = obj.optBoolean("sincronizarConnect", false),
        )
    }

    private fun atualizar(transform: (Estado) -> Estado) {
        synchronized(lock) {
            estado = transform(estado)
            prefs?.edit()?.putString(CHAVE_ESTADO, paraJson(estado, incluirChaves = true).toString())?.apply()
        }
        notificar()
    }

    private fun notificar() {
        _versao.value = _versao.value + 1
    }
}
