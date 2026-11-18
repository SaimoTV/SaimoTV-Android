package br.com.saimo.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Minhas listas": o usuário traz o link (ou o arquivo) de uma lista .m3u comum
 * e os canais entram no Ao vivo na seção [CATEGORIA]. Tudo fica só no aparelho:
 * um índice pequeno em SharedPreferences e, por lista, um arquivo JSON com os
 * canais já lidos em `filesDir/minhas_listas/`. O link que a pessoa digitou nunca
 * é mostrado de volta nem registrado em log. O catálogo oficial ([Remote]) e o
 * controle parental ([Unlock]) não são tocados: [somar] só junta as listas
 * ativas ao que o Ao vivo e a Busca já mostram.
 */
object MinhasListas {

    const val CATEGORIA = "Minhas listas"
    const val MAXIMO_LISTAS = 10
    /** Listas com filmes e séries passam fácil de 70 MB (300 mil itens): lidas em fluxo. */
    private const val MAXIMO_BYTES = 250L * 1024 * 1024
    private const val RENOVAR_APOS_MS = 12 * 3_600_000L
    private const val UA_VLC = "VLC/3.0.20 LibVLC/3.0.20"

    class Erro(mensagem: String) : Exception(mensagem)

    data class Lista(
        val id: String,
        val nome: String,
        /// Nulo = veio de arquivo: não há como atualizar sozinha.
        val link: String?,
        val atualizadaEm: Long,
        val ativa: Boolean,
        val canais: Int,
        /** Filmes e séries que foram para Filmes e séries (docs/listas-m3u.md). */
        val filmes: Int = 0,
        val series: Int = 0,
    )

    /** O que uma lista trouxe: os canais e o acervo de filmes e séries. */
    class Lido(val canais: List<LeitorM3u.CanalM3u>, val acervo: AcervoLista.Montador) {
        /** "120 canais, 3.400 filmes e 900 séries". */
        fun descricao(): String {
            val partes = listOfNotNull(
                "${canais.size} canais".takeIf { canais.isNotEmpty() || acervo.vazio },
                "${acervo.totalFilmes} filmes".takeIf { acervo.totalFilmes > 0 },
                "${acervo.totalSeries} séries".takeIf { acervo.totalSeries > 0 })
            return if (partes.size <= 1) partes.joinToString() else partes.dropLast(1).joinToString(", ") + " e " + partes.last()
        }
    }

    private class Lida(val lista: Lista, val canais: List<Channel>, val restritos: List<Channel>)

    private lateinit var app: Context
    @Volatile private var listas: List<Lista> = emptyList()
    @Volatile private var lidas: List<Lida>? = null
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs() = app.getSharedPreferences("minhas_listas", Context.MODE_PRIVATE)
    private fun pasta() = File(app.filesDir, "minhas_listas").apply { mkdirs() }

    /** Na criação do app: só o índice (barato). Os canais são lidos sob demanda. */
    fun iniciar(context: Context) {
        app = context.applicationContext
        listas = lerIndice()
        if (listas.isNotEmpty()) {
            escopo.launch(semDerrubar) {
                garantir()
                // Listas guardadas antes dos filmes e séries (docs/listas-m3u.md):
                // os filmes e episódios estão como canais. Baixa de novo uma vez.
                if (prefs().getInt("acervo", 0) < 1) {
                    for (l in listas.toList()) if (l.link != null) runCatching { atualizar(l.id) }
                    prefs().edit().putInt("acervo", 1).apply()
                }
                atualizarVencidas()
            }
        } else if (::app.isInitialized) prefs().edit().putInt("acervo", 1).apply()
    }

    fun todas(): List<Lista> = listas

    // MARK: - Índice e arquivos

    private fun lerIndice(): List<Lista> = runCatching {
        val a = JSONArray(prefs().getString("indice", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Lista(o.getString("id"), o.getString("nome"), if (o.isNull("link")) null else o.getString("link"),
                o.optLong("atualizadaEm"), o.optBoolean("ativa", true), o.optInt("canais"),
                o.optInt("filmes"), o.optInt("series"))
        }
    }.getOrDefault(emptyList())

    private fun gravarIndice(novas: List<Lista>) {
        listas = novas
        val a = JSONArray()
        for (l in novas) a.put(JSONObject().put("id", l.id).put("nome", l.nome)
            .put("link", l.link ?: JSONObject.NULL).put("atualizadaEm", l.atualizadaEm)
            .put("ativa", l.ativa).put("canais", l.canais).put("filmes", l.filmes).put("series", l.series))
        prefs().edit().putString("indice", a.toString()).apply()
        lidas = null
        // Os filmes e séries das listas entram no acervo: o Vod lê de novo.
        if (::app.isInitialized) { Vod.liberar(); Generos.limpar() }
    }

    /** A pasta do acervo (filmes e séries) da lista, no formato Saimo. */
    fun pastaDoAcervo(id: String) = File(pasta(), "$id-acervo")

    /** As pastas de acervo das listas ativas que têm filmes ou séries. */
    fun acervosAtivos(): List<File> {
        if (!::app.isInitialized) return emptyList()
        return listas.filter { it.ativa && it.filmes + it.series > 0 }.map { pastaDoAcervo(it.id) }
            .filter { File(it, "indice.txt").exists() }
    }

    private fun gravarCanais(id: String, canais: List<LeitorM3u.CanalM3u>) {
        val a = JSONArray()
        for (c in canais) {
            val fs = JSONArray()
            for (f in c.fontes) fs.put(JSONObject().put("url", f.url).put("referer", f.referer ?: JSONObject.NULL)
                .put("ua", f.ua ?: JSONObject.NULL).put("keyId", f.keyId ?: JSONObject.NULL)
                .put("key", f.key ?: JSONObject.NULL).put("qualidade", f.qualidade ?: JSONObject.NULL))
            a.put(JSONObject().put("nome", c.nome).put("grupo", c.grupo ?: JSONObject.NULL)
                .put("logo", c.logo ?: JSONObject.NULL).put("fontes", fs))
        }
        File(pasta(), "$id.json").writeText(a.toString())
    }

    private fun lerCanais(id: String): List<LeitorM3u.CanalM3u> = runCatching {
        val a = JSONArray(File(pasta(), "$id.json").readText())
        fun s(o: JSONObject, k: String) = if (o.isNull(k)) null else o.getString(k)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val fs = o.getJSONArray("fontes")
            LeitorM3u.CanalM3u(o.getString("nome"), s(o, "grupo"), s(o, "logo"), (0 until fs.length()).map { j ->
                val f = fs.getJSONObject(j)
                LeitorM3u.FonteM3u(f.getString("url"), s(f, "referer"), s(f, "ua"), s(f, "keyId"), s(f, "key"), s(f, "qualidade"))
            })
        }
    }.getOrDefault(emptyList())

    // MARK: - Canais para o app

    @Synchronized
    private fun garantir(): List<Lida> {
        lidas?.let { return it }
        val novas = listas.map { l ->
            val canais = ArrayList<Channel>()
            val restritos = ArrayList<Channel>()
            for (c in lerCanais(l.id)) {
                val canal = Channel(c.nome, c.logo, c.fontes.map {
                    Source(it.url, it.referer, it.ua, it.keyId, it.key, it.qualidade)
                }, CATEGORIA)
                if (LeitorM3u.ehRestrito(c.grupo, c.nome)) restritos += canal else canais += canal
            }
            Lida(l, canais, restritos)
        }
        lidas = novas
        return novas
    }

    /**
     * Junta os canais das listas ativas ao [base] (o que o app já mostra). Sem
     * lista nenhuma devolve o próprio [base], sem custo. Os restritos (grupo ou
     * nome adulto) só entram com [desbloqueado]. Nome que repete um canal já
     * existente ganha o nome da lista, para o canal certo tocar.
     */
    fun somar(base: List<Channel>, desbloqueado: Boolean): List<Channel> {
        if (listas.isEmpty()) return base
        val ativas = garantir().filter { it.lista.ativa }
        if (ativas.isEmpty()) return base
        val usados = HashSet<String>(base.size * 2)
        base.forEach { usados += it.name }
        val fora = ArrayList<Channel>()
        for (l in ativas) {
            val todos = if (desbloqueado) l.canais + l.restritos else l.canais
            for (c in todos) {
                var nome = c.name
                if (nome in usados) nome = "$nome (${l.lista.nome})"
                var n = 2
                while (nome in usados) nome = "${c.name} (${l.lista.nome}) $n".also { n++ }
                usados += nome
                fora += if (nome == c.name) c else c.copy(name = nome)
            }
        }
        return base + fora
    }

    /** Os canais que o Ao vivo mostra: a lista publicada (se há) mais as listas da pessoa. */
    fun canaisDaTela(): List<Channel> = somar(Unlock.channels(), Unlock.unlocked)

    // MARK: - Ações

    private fun novoId() = "l" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    private fun nomeLivre(digitado: String?): String {
        digitado?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(60) }
        var n = 1
        while (listas.any { it.nome == "Lista $n" }) n++
        return "Lista $n"
    }

    /** Baixa e lê, em fluxo. Erros em português, sem o endereço. */
    suspend fun baixar(link: String): Lido = withContext(Dispatchers.IO) {
        val url = link.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            throw Erro("O link precisa começar com http:// ou https://")
        }
        // Conta com usuário e senha (Xtream): canais pela API do servidor; filmes
        // e séries pelo M3U completo da conta.
        Xtream.deLink(url)?.let { conta ->
            val acervo = AcervoLista.Montador()
            val canais = runCatching { Xtream.canais(conta) }
            runCatching { abrirLink(Xtream.linkM3u(conta.base, conta.usuario, conta.senha)) { LeitorM3u.lerFluxo(it, acervo) } }
            canais.getOrNull()?.let { return@withContext Lido(it, acervo) }
            if (!acervo.vazio) return@withContext Lido(emptyList(), acervo)
            throw canais.exceptionOrNull() ?: Erro("Não consegui falar com o servidor")
        }
        val acervo = AcervoLista.Montador()
        val canais = try {
            abrirLink(url) { leitor -> validar(leitor, acervo) }
        } catch (e: Erro) {
            throw e
        } catch (e: Exception) {
            throw Erro("Não consegui baixar essa lista")
        }
        conferir(Lido(canais, acervo))
    }

    private fun <T> abrirLink(url: String, ler: (java.io.BufferedReader) -> T): T {
        val cliente = Playback.client.newBuilder().callTimeout(300, TimeUnit.SECONDS).followRedirects(true).build()
        val pedido = Request.Builder().url(url).header("User-Agent", UA_VLC).build()
        return cliente.newCall(pedido).execute().use { r ->
            if (!r.isSuccessful) throw Erro("Não consegui baixar essa lista")
            r.body.byteStream().limitado().bufferedReader(Charsets.UTF_8).use(ler)
        }
    }

    /** Corta o que passar de [MAXIMO_BYTES]. */
    private fun java.io.InputStream.limitado(): java.io.InputStream = object : java.io.FilterInputStream(this) {
        private var lidos = 0L
        override fun read(): Int = super.read().also { if (it >= 0) contar(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) contar(it.toLong()) }
        private fun contar(n: Long) { lidos += n; if (lidos > MAXIMO_BYTES) throw Erro("Essa lista é grande demais") }
    }

    /** Lê o arquivo escolhido no seletor do sistema (ContentResolver). */
    suspend fun lerArquivo(uri: Uri): Lido = lerFluxo { app.contentResolver.openInputStream(uri)!! }

    /** Lê um arquivo da pasta Download/armazenamento (alternativa sem seletor do sistema). */
    suspend fun lerArquivo(arquivo: File): Lido = lerFluxo { arquivo.inputStream() }

    private suspend fun lerFluxo(abrir: () -> java.io.InputStream): Lido = withContext(Dispatchers.IO) {
        val acervo = AcervoLista.Montador()
        val canais = try {
            abrir().limitado().bufferedReader(Charsets.UTF_8).use { validar(it, acervo) }
        } catch (e: Erro) {
            throw e
        } catch (e: Exception) {
            throw Erro("Não consegui abrir esse arquivo")
        }
        conferir(Lido(canais, acervo))
    }

    /** Arquivos .m3u/.m3u8/.m3 de Download, Documents e da raiz do armazenamento (sem recursão). */
    fun arquivosNaPasta(): List<File> {
        val raiz = android.os.Environment.getExternalStorageDirectory()
        val pastas = listOf(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            raiz)
        return pastas.flatMap { p -> runCatching { p.listFiles()?.toList() }.getOrNull().orEmpty() }
            .filter { it.isFile && it.extension.lowercase() in setOf("m3u", "m3u8", "m3") }
            .distinctBy { it.absolutePath }
            .sortedBy { it.name.lowercase() }
    }

    /** Confere o começo (64 KB) e lê o resto em fluxo. */
    private fun validar(leitor: java.io.BufferedReader, acervo: AcervoLista.Montador): List<LeitorM3u.CanalM3u> {
        leitor.mark(64 * 1024)
        val buf = CharArray(64 * 1024)
        var n = 0
        while (n < buf.size) { val r = leitor.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        val inicio = String(buf, 0, n)
        if (!inicio.trimStart('\ufeff').trimStart().startsWith("#EXTM3U", true) && !inicio.contains("#EXTINF")) {
            throw Erro("Isso não parece uma lista .m3u")
        }
        leitor.reset()
        return LeitorM3u.lerFluxo(leitor, acervo)
    }

    private fun conferir(lido: Lido): Lido {
        if (lido.canais.isEmpty() && lido.acervo.vazio) throw Erro("Essa lista não tem canais, filmes nem séries")
        return lido
    }

    /** Guarda uma lista nova. Lança [Erro] se já há [MAXIMO_LISTAS]. */
    @Synchronized
    fun adicionar(nome: String?, link: String?, lido: Lido) {
        if (listas.size >= MAXIMO_LISTAS) throw Erro("Você já tem $MAXIMO_LISTAS listas. Remova uma para adicionar outra.")
        val id = novoId()
        gravarCanais(id, lido.canais)
        val r = lido.acervo.gravar(pastaDoAcervo(id))
        gravarIndice(listas + Lista(id, nomeLivre(nome), link?.trim(), System.currentTimeMillis(), true, lido.canais.size,
            r.filmes, r.series))
        Connect.avisarMudancaLocal(app, 1000)
    }

    /** Troca os canais de uma lista que já existe (atualização). */
    @Synchronized
    fun substituir(id: String, lido: Lido) {
        if (listas.none { it.id == id }) return
        gravarCanais(id, lido.canais)
        val r = lido.acervo.gravar(pastaDoAcervo(id))
        gravarIndice(listas.map {
            if (it.id == id) it.copy(atualizadaEm = System.currentTimeMillis(), canais = lido.canais.size,
                filmes = r.filmes, series = r.series) else it
        })
    }

    @Synchronized
    fun alternar(id: String) = gravarIndice(listas.map { if (it.id == id) it.copy(ativa = !it.ativa) else it })

    @Synchronized
    fun remover(id: String) {
        File(pasta(), "$id.json").delete()
        pastaDoAcervo(id).deleteRecursively()
        gravarIndice(listas.filterNot { it.id == id })
    }

    /** Atualiza pelo link; devolve a mensagem de erro, ou nulo se deu certo. */
    suspend fun atualizar(id: String): String? {
        val link = listas.firstOrNull { it.id == id }?.link ?: return "Essa lista veio de um arquivo: escolha o arquivo de novo"
        return try {
            val lido = baixar(link)
            withContext(Dispatchers.IO) { substituir(id, lido) }
            null
        } catch (e: Erro) { e.message }
    }

    /** Ao abrir o app: listas com link e mais de 12 h. Falha é silenciosa (fica a versão guardada). */
    private suspend fun atualizarVencidas() {
        val agora = System.currentTimeMillis()
        for (l in listas.toList()) {
            if (l.link != null && agora - l.atualizadaEm > RENOVAR_APOS_MS) runCatching { atualizar(l.id) }
        }
    }

    fun quando(l: Lista): String {
        val min = (System.currentTimeMillis() - l.atualizadaEm) / 60_000
        return when {
            min < 1 -> "atualizada agora"
            min < 60 -> "atualizada há $min min"
            min < 24 * 60 -> "atualizada há ${min / 60} h"
            else -> "atualizada há ${min / (24 * 60)} d"
        }
    }

    fun resumo(): String = when (val n = listas.size) {
        0 -> "Nenhuma lista"
        1 -> "1 lista"
        else -> "$n listas"
    }
}
