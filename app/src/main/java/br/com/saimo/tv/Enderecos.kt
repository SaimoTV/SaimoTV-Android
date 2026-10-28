package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Os dois endereços das listas, digitados por quem usa o app.
 *
 * O app não carrega lista nenhuma: só dois endereços-base ([PADRAO_CANAIS] e
 * [PADRAO_FILMES]), ativos de fábrica, e concatena o nome do arquivo. Em
 * Ajustes > Listas a pessoa troca, desativa (valor vazio) ou restaura o padrão.
 * Lista desativada não faz nenhum pedido de rede.
 */
object Enderecos {

    private const val PREFS = "listas"
    private const val CANAIS = "linkCanais"
    private const val FILMES = "linkFilmes"
    private const val MIGRADO = "migradoSemListas"

    /// Os únicos dois endereços embutidos: valem enquanto a pessoa não trocar.
    const val PADRAO_CANAIS = "https://canais-saimo.pages.dev/"
    const val PADRAO_FILMES = "https://filmes-saimo.pages.dev/"

    /// Base já normalizada (termina em `/`), ou nulo quando não há endereço.
    @Volatile
    var canais: String? = null
        private set

    @Volatile
    var filmes: String? = null
        private set

    val temAlgum: Boolean get() = canais != null || filmes != null

    /** Chamado uma vez, na criação do app, antes de qualquer tela. */
    fun iniciar(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Valor guardado (mesmo vazio = desativada) vale; ausente = padrão.
        canais = if (prefs.contains(CANAIS)) normalizar(prefs.getString(CANAIS, "").orEmpty()) else PADRAO_CANAIS
        filmes = if (prefs.contains(FILMES)) normalizar(prefs.getString(FILMES, "").orEmpty()) else PADRAO_FILMES
        if (!prefs.getBoolean(MIGRADO, false)) {
            // Versões anteriores baixavam as listas de endereços fixos. Quem
            // atualiza começa vazio: favoritos, progresso e ajustes ficam.
            apagarDadosDeCanais(context)
            apagarDadosDeFilmes(context)
            prefs.edit().putBoolean(MIGRADO, true).apply()
        }
    }

    /**
     * Guarda os endereços (já normalizados; nulo apaga). Quem mudou tem os
     * dados antigos apagados do cache.
     */
    fun definir(context: Context, novoCanais: String?, novoFilmes: String?) {
        val mudouCanais = novoCanais != canais
        val mudouFilmes = novoFilmes != filmes
        canais = novoCanais
        filmes = novoFilmes
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(CANAIS, novoCanais.orEmpty())
            putString(FILMES, novoFilmes.orEmpty())
        }.apply()
        if (mudouCanais) apagarDadosDeCanais(context)
        if (mudouFilmes) apagarDadosDeFilmes(context)
    }

    /** Volta aos dois endereços de fábrica. */
    fun restaurarPadrao(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CANAIS).remove(FILMES).apply()
        val mudouCanais = canais != PADRAO_CANAIS
        val mudouFilmes = filmes != PADRAO_FILMES
        canais = PADRAO_CANAIS
        filmes = PADRAO_FILMES
        if (mudouCanais) apagarDadosDeCanais(context)
        if (mudouFilmes) apagarDadosDeFilmes(context)
    }

    /** Catálogo, restritos, rádios, guia e o que foi montado em cima deles. */
    fun apagarDadosDeCanais(context: Context) {
        for (nome in listOf("catalogo.txt", "canais.txt", "restritos.txt", "radios.txt", "guia.txt")) {
            File(context.filesDir, nome).delete()
            File(context.filesDir, "$nome.etag").delete()
        }
        File(context.cacheDir, "epg.json").delete()
        File(context.cacheDir, "epg.sig").delete()
        Remote.limpar()
        GuiaTabelas.limpar()
        Epg.liberar()
    }

    /** A pasta vod/ inteira: índice, fatias, fichas, destaques, gêneros. */
    fun apagarDadosDeFilmes(context: Context) {
        File(context.filesDir, "vod").deleteRecursively()
        Vod.limpar()
    }

    // MARK: - Normalização

    /**
     * Endereço digitado -> endereço-base com `/` no fim, ou nulo se vazio ou
     * inválido. Mesma regra em todos os apps Saimo:
     * trim; sem `://` vira `https://`; só http/https; sem espaço no meio e com
     * host; tira `?query` e `#fragmento`; descarta o último trecho do caminho
     * se tiver ponto (a pessoa colou o arquivo, não a pasta); `/` no fim.
     */
    fun normalizar(texto: String): String? {
        val limpo = texto.trim()
        if (limpo.isEmpty() || limpo.any { it.isWhitespace() }) return null
        val completo = if ("://" in limpo) limpo else "https://$limpo"
        val esquema = completo.substringBefore("://").lowercase()
        if (esquema != "http" && esquema != "https") return null
        var resto = completo.substringAfter("://")
        resto = resto.substringBefore('#').substringBefore('?')
        val host = resto.substringBefore('/')
        if (host.isEmpty() || host.startsWith(":") || host.contains("@")) return null
        var caminho = if ('/' in resto) resto.substring(resto.indexOf('/')) else ""
        val ultimo = caminho.substringAfterLast('/')
        if ('.' in ultimo) caminho = caminho.removeSuffix(ultimo)
        if (!caminho.endsWith("/")) caminho += "/"
        return "$esquema://$host$caminho"
    }

    /** O endereço responde 200 para o arquivo de prova (catalogo.txt / indice.txt)? */
    suspend fun testar(base: String, arquivo: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cliente = Playback.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            val pedido = Request.Builder().url(base + arquivo)
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .header("Cache-Control", "no-cache").build()
            cliente.newCall(pedido).execute().use { it.code == 200 }
        }.getOrDefault(false)
    }
}
