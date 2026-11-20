package br.com.saimo.tv

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Conta Saimo (opcional): a TV entra pelo celular (QR) e sincroniza perfis,
 * onde parou, favoritos, listas e addons pela internet. Sem conta, nada muda:
 * os dados ficam no aparelho e o Saimo Connect segue na rede de casa. O que
 * viaja é o mesmo pacote do Connect ([SincroniaConnect.pacote] / [SincroniaConnect.aplicar]).
 * Servidor: SaimoConta (README lá). Em build de depuração, o servidor local do Mac.
 */
object Conta {

    val API: String = if (BuildConfig.DEBUG) "http://10.0.2.2:8787" else "https://conta.saimo-tv.com.br"
    private const val PREFS = "conta_saimo"
    private val JSON_TIPO = "application/json; charset=utf-8".toMediaType()

    class Erro(msg: String) : Exception(msg)

    data class Estado(val email: String?, val sincronizadaEm: Long, val erro: String?, val sincronizando: Boolean)

    private lateinit var app: Context
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trava = Mutex()
    private var agendado: Job? = null
    private var ultimoEnviado = ""
    @Volatile var estado = Estado(null, 0, null, false)
        private set
    private val ouvintes = java.util.concurrent.CopyOnWriteArraySet<(Estado) -> Unit>()

    fun assinar(f: (Estado) -> Unit) { ouvintes += f }
    fun cancelar(f: (Estado) -> Unit) { ouvintes -= f }
    private fun mudar(novo: Estado) { estado = novo; ouvintes.forEach { runCatching { it(novo) } } }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val token: String? get() = if (::app.isInitialized) prefs().getString("token", null) else null
    val logado: Boolean get() = token != null

    fun iniciar(context: Context) {
        app = context.applicationContext
        mudar(Estado(prefs().getString("email", null), prefs().getLong("sincronizadaEm", 0), null, false))
        if (logado) escopo.launch(semDerrubar) {
            delay(5000)
            sincronizar()
            // A cada 10 minutos enquanto o app está aberto.
            while (true) { delay(10 * 60_000L); if (logado) sincronizar() }
        }
    }

    private fun api(metodo: String, rota: String, corpo: JSONObject? = null, comToken: Boolean = true): JSONObject {
        val req = Request.Builder().url(API + rota)
            .method(metodo, corpo?.toString()?.toRequestBody(JSON_TIPO) ?: if (metodo == "GET") null else "".toRequestBody(JSON_TIPO))
            .apply { if (comToken) token?.let { header("Authorization", "Bearer $it") } }
            .build()
        val resposta = try {
            Playback.client.newCall(req).execute()
        } catch (e: Exception) {
            throw Erro("Sem conexão com a conta Saimo agora")
        }
        resposta.use { r ->
            val j = runCatching { JSONObject(r.body.string()) }.getOrDefault(JSONObject())
            if (r.code == 401 && comToken && token != null) {
                // Sessão encerrada em outro lugar (sair de todos, conta apagada).
                prefs().edit().remove("token").remove("email").apply()
                mudar(Estado(null, 0, null, false))
            }
            if (!r.isSuccessful) throw Erro(j.optString("mensagem").ifBlank { "Não deu certo: tente de novo" })
            return j
        }
    }

    private fun nomeDoAparelho() = "Saimo na ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(60)

    // ---------- entrar pelo celular (QR) ----------

    data class Pedido(val aparelhoCodigo: String, val codigo: String, val url: String, val expiraEm: Long, val intervaloMs: Long)

    /** Pede um código ao servidor para mostrar na tela (QR + ABCD-2345). */
    suspend fun pedirCodigoDaTv(): Pedido = withContext(Dispatchers.IO) {
        val j = api("POST", "/v1/tv/pedir", JSONObject().put("aparelho", nomeDoAparelho()), comToken = false)
        Pedido(j.getString("aparelhoCodigo"), j.getString("codigo"), j.getString("url"), j.getLong("expiraEm"),
            j.optLong("intervalo", 5) * 1000)
    }

    /** Pergunta se o celular já aprovou. `true` quando entrou; `null` se o código venceu. */
    suspend fun conferirAprovacao(p: Pedido): Boolean? = withContext(Dispatchers.IO) {
        val j = api("POST", "/v1/tv/estado", JSONObject().put("aparelhoCodigo", p.aparelhoCodigo), comToken = false)
        when (j.optString("estado")) {
            "aprovado" -> {
                val email = j.optJSONObject("conta")?.optString("email")?.takeIf { it.isNotBlank() && it != "null" }
                prefs().edit().putString("token", j.getString("token")).putString("email", email).apply()
                ultimoEnviado = ""
                mudar(estado.copy(email = email, erro = null))
                escopo.launch(semDerrubar) { sincronizar(forcar = true) }
                true
            }
            "vencido" -> null
            else -> false
        }
    }

    /** Entrar com e-mail e senha; `criar` cria a conta (o e-mail não é verificado). */
    suspend fun entrarComSenha(email: String, senha: String, criar: Boolean = false) = withContext(Dispatchers.IO) {
        val j = api("POST", if (criar) "/v1/senha/cadastrar" else "/v1/senha/entrar",
            JSONObject().put("email", email.trim()).put("senha", senha).put("aparelho", nomeDoAparelho()), comToken = false)
        val e = j.optJSONObject("conta")?.optString("email")?.takeIf { it.isNotBlank() && it != "null" } ?: email.trim().lowercase()
        prefs().edit().putString("token", j.getString("token")).putString("email", e).apply()
        ultimoEnviado = ""
        mudar(estado.copy(email = e, erro = null))
        escopo.launch(semDerrubar) { sincronizar(forcar = true) }
    }

    suspend fun sair() = withContext(Dispatchers.IO) {
        runCatching { api("POST", "/v1/sair") }
        prefs().edit().clear().apply()
        ultimoEnviado = ""
        mudar(Estado(null, 0, null, false))
    }

    // ---------- sincronizar ----------

    /**
     * Com mudança aqui: manda (PUT) e o servidor devolve tudo já juntado, que é
     * aplicado aqui. Sem mudança: só baixa (GET) e junta. Um pedido por
     * sincronia (cada pedido ao servidor é cobrado).
     */
    suspend fun sincronizar(forcar: Boolean = false) {
        if (!logado) return
        trava.withLock {
            mudar(estado.copy(sincronizando = true))
            try {
                val pacote = SincroniaConnect.pacote(app).toString()
                val remoto = if (forcar || pacote != ultimoEnviado) {
                    api("PUT", "/v1/dados", JSONObject().put("dados", JSONObject(pacote))).optJSONObject("dados")
                } else {
                    api("GET", "/v1/dados").optJSONObject("dados")
                }
                // As telas releem o que mudou ao voltar para elas.
                if (remoto != null) SincroniaConnect.aplicar(app, remoto)
                // O servidor já tem tudo o que ficou aqui depois de juntar: só manda de novo se mudar.
                ultimoEnviado = SincroniaConnect.pacote(app).toString()
                val agora = System.currentTimeMillis()
                prefs().edit().putLong("sincronizadaEm", agora).apply()
                mudar(estado.copy(sincronizadaEm = agora, erro = null, sincronizando = false))
            } catch (e: Exception) {
                mudar(estado.copy(erro = e.message ?: "Não consegui sincronizar", sincronizando = false))
            }
        }
    }

    /** O app voltou para a frente: sincroniza se faz mais de 1 minuto. */
    fun aoVoltar() {
        if (!::app.isInitialized || !logado) return
        if (System.currentTimeMillis() - estado.sincronizadaEm > 60_000 && !estado.sincronizando) {
            escopo.launch(semDerrubar) { sincronizar() }
        }
    }

    /** Entre um envio e outro, no mínimo isso (assistindo, o ponto muda o tempo todo). */
    private const val INTERVALO_MINIMO = 120_000L

    /**
     * Algo mudou aqui (o Connect avisa): manda em alguns segundos, várias
     * mudanças num envio só, no máximo um envio a cada 2 min. Sem mudança desde
     * o último envio (eco de juntar), não vai à rede.
     */
    fun agendar(atrasoMs: Long = 15_000) {
        if (!::app.isInitialized || !logado) return
        agendado?.cancel()
        val espera = maxOf(atrasoMs, INTERVALO_MINIMO - (System.currentTimeMillis() - estado.sincronizadaEm))
        agendado = escopo.launch(semDerrubar) {
            delay(espera)
            if (runCatching { SincroniaConnect.pacote(app).toString() == ultimoEnviado }.getOrDefault(false)) return@launch
            sincronizar()
        }
    }
}
