package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * As tabelas do guia de programação, lidas de `guia.txt` (servido pelo
 * endereço da lista de canais). Nenhuma tabela por canal mora no código.
 *
 * Linhas com TAB; `#` comenta:
 *  - `meuguia<TAB>nome do canal no catálogo<TAB>código`
 *  - `guiadetv<TAB>nome normalizado<TAB>endereço (ou parte final do endereço)`
 *  - `alias<TAB>nome normalizado<TAB>nome no feed`
 *  - `feed<TAB>-<TAB>url` (vale a ordem)
 *  - `pluto<TAB>-<TAB>url`
 *
 * Sem o arquivo, o guia fica vazio: não sobra nenhuma fonte para consultar.
 */
object GuiaTabelas {

    private const val ARQUIVO = "guia.txt"

    data class Dados(
        val meuguia: Map<String, String> = emptyMap(),
        val guiadetv: Map<String, String> = emptyMap(),
        val alias: Map<String, String> = emptyMap(),
        val feeds: List<String> = emptyList(),
        val pluto: String? = null,
    ) {
        val vazio: Boolean get() = meuguia.isEmpty() && guiadetv.isEmpty() && feeds.isEmpty() && pluto == null
    }

    @Volatile
    var atual: Dados = Dados()
        private set

    /** Muda o que as telas veem de uma vez (testes e leitura do disco). */
    internal fun definir(dados: Dados) { atual = dados }

    fun parse(texto: String): Dados {
        val meuguia = LinkedHashMap<String, String>()
        val guiadetv = LinkedHashMap<String, String>()
        val alias = LinkedHashMap<String, String>()
        val feeds = ArrayList<String>()
        var pluto: String? = null
        for (bruta in texto.lineSequence()) {
            val linha = bruta.trimEnd('\r')
            if (linha.isBlank() || linha.trimStart().startsWith("#")) continue
            val campos = linha.split("\t")
            if (campos.size < 3) continue
            val tipo = campos[0].trim().lowercase()
            val chave = campos[1].trim()
            val valor = campos[2].trim()
            if (valor.isEmpty()) continue
            when (tipo) {
                "meuguia" -> if (chave.isNotEmpty()) meuguia[chave] = valor
                "guiadetv" -> if (chave.isNotEmpty()) guiadetv[chave] = valor
                "alias" -> if (chave.isNotEmpty()) alias[chave] = valor
                "feed" -> if (valor.startsWith("http")) feeds += valor
                "pluto" -> if (valor.startsWith("http")) pluto = valor
            }
        }
        return Dados(meuguia, guiadetv, alias, feeds, pluto)
    }

    fun limpar() { atual = Dados() }

    /** Lê o que já está em disco, sem rede. */
    fun carregarCache(context: Context) {
        if (!atual.vazio) return
        val texto = runCatching { File(context.filesDir, ARQUIVO).readText() }.getOrNull() ?: return
        atual = parse(texto)
    }

    /**
     * Baixa `guia.txt` do endereço dos canais (ETag; 304 mantém o de disco).
     * Devolve verdadeiro quando as tabelas mudaram. Sem endereço, nada é pedido.
     */
    suspend fun atualizar(context: Context): Boolean = withContext(Dispatchers.IO) {
        carregarCache(context)
        val base = Enderecos.canais ?: return@withContext false
        val arquivo = File(context.filesDir, ARQUIVO)
        val etiqueta = File(context.filesDir, "$ARQUIVO.etag")
        val texto = runCatching {
            val pedido = Request.Builder().url(base + ARQUIVO)
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .header("Cache-Control", "no-cache")
                .apply {
                    val anterior = etiqueta.takeIf { arquivo.exists() && it.exists() }?.readText()?.trim()
                    if (!anterior.isNullOrEmpty()) header("If-None-Match", anterior)
                }.build()
            Playback.client.newCall(pedido).execute().use { r ->
                if (!r.isSuccessful) null
                else r.body?.string()?.also {
                    r.header("ETag")?.let { novo -> runCatching { etiqueta.writeText(novo) } } ?: etiqueta.delete()
                }
            }
        }.getOrNull() ?: return@withContext false
        val novos = parse(texto)
        if (novos.vazio) return@withContext false
        runCatching { arquivo.writeText(texto) }
        if (novos == atual) return@withContext false
        atual = novos
        true
    }
}
