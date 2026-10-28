package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Servidores desligados à mão, no painel do monitor.
 *
 * Quando um provedor cai, cai inteiro: não é a fonte 3 de um canal que morreu,
 * é o servidor que parou de responder para todo mundo. Editar o catálogo
 * publicado a cada queda é lento e some com o link, que depois precisa voltar.
 * Desligar o servidor no painel some com ele de todo canal e de todo filme, em
 * todos os aplicativos, e religar devolve tudo.
 *
 * A lista é baixada sem chave nenhuma: são nomes de servidor, que o catálogo
 * publicado já mostra, e exigir segredo significaria embutir um segredo num
 * aplicativo que qualquer um baixa.
 *
 * Sem rede a lista fica vazia e nada é escondido — o erro certo a cometer: um
 * canal a mais na tela é melhor que a lista inteira sumindo porque o monitor
 * não respondeu.
 *
 * Cada pedido ao monitor custa, então a lista vale uma hora e fica guardada no
 * aparelho: abrir e fechar o app dentro dessa hora não pede de novo.
 */
object FontesDesativadas {

    private const val ENDERECO = "https://saimo-tv.pages.dev/fontes-desativadas.json"
    /// Uma hora: desligar um servidor no painel chega a todos em até uma hora,
    /// sem cada aparelho pedir a lista de dois em dois minutos.
    private const val VALIDADE_MS = 3_600_000L
    private const val PREFS = "fontes_desativadas"

    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    private var hosts: Set<String> = emptySet()
    @Volatile
    private var lidoEm = 0L

    val atuais: Set<String> get() = hosts

    /** No Application.onCreate: a última lista boa volta antes do primeiro catálogo. */
    fun iniciar(contexto: Context) {
        val p = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        hosts = p.getStringSet("hosts", null)?.toSet() ?: emptySet()
        lidoEm = p.getLong("lido_em", 0L)
    }

    /**
     * Busca a lista quando ela envelheceu.
     *
     * Devolve `true` quando mudou, que é quando quem chamou precisa remontar a
     * lista de canais.
     */
    suspend fun atualizar(): Boolean = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lidoEm in 0 until VALIDADE_MS) return@withContext false
        // Sem rede o pedido nem chega ao monitor: tenta de novo na próxima vez.
        // Com resposta (boa ou não), só daqui a uma hora.
        val corpo = runCatching {
            val pedido = Request.Builder().url(ENDERECO)
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .build()
            Playback.client.newCall(pedido).execute().use { resposta ->
                lidoEm = System.currentTimeMillis()
                prefs?.edit()?.putLong("lido_em", lidoEm)?.apply()
                if (!resposta.isSuccessful) null else resposta.body?.string()
            }
        }.getOrNull() ?: return@withContext false
        val lista = runCatching {
            val arranjo = JSONObject(corpo).optJSONArray("desativados") ?: return@runCatching emptySet()
            (0 until arranjo.length()).mapNotNull { arranjo.optString(it).lowercase().ifBlank { null } }.toSet()
        }.getOrNull() ?: return@withContext false

        if (lista == hosts) return@withContext false
        hosts = lista
        prefs?.edit()?.putStringSet("hosts", lista)?.apply()
        Log.i("Saimo", if (lista.isEmpty()) "nenhum servidor desligado"
                       else "servidores desligados: ${lista.sorted().joinToString(", ")}")
        true
    }

    fun desligado(url: String): Boolean {
        if (url.isBlank() || hosts.isEmpty()) return false
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()
        return host != null && host in hosts
    }

    /** Os endereços de um filme ou episódio sem os que estão desligados. */
    fun peneirar(urls: List<String>): List<String> =
        urls.filterNot { desligado(it) }

    /**
     * O catálogo sem o que está desligado.
     *
     * Canal que fica sem nenhuma fonte sai da lista: ele não abriria mesmo, e
     * deixá-lo ali só rende clique frustrado. Volta sozinho quando o servidor
     * for religado.
     */
    fun peneirarCanais(canais: List<Channel>): List<Channel> {
        return canais.mapNotNull { canal ->
            val vivas = canal.sources.filterNot { desligado(it.url) }
            when {
                vivas.isEmpty() -> null
                vivas.size == canal.sources.size -> canal
                else -> canal.copy(sources = vivas)
            }
        }
    }

    private object Log {
        fun i(etiqueta: String, mensagem: String) {
            android.util.Log.i(etiqueta, mensagem)
        }
    }
}
