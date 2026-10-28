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
import java.io.ByteArrayOutputStream
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
    private const val MAXIMO_BYTES = 30 * 1024 * 1024
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
    )

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
            escopo.launch(semDerrubar) { garantir(); atualizarVencidas() }
        }
    }

    fun todas(): List<Lista> = listas

    // MARK: - Índice e arquivos

    private fun lerIndice(): List<Lista> = runCatching {
        val a = JSONArray(prefs().getString("indice", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Lista(o.getString("id"), o.getString("nome"), if (o.isNull("link")) null else o.getString("link"),
                o.optLong("atualizadaEm"), o.optBoolean("ativa", true), o.optInt("canais"))
        }
    }.getOrDefault(emptyList())

    private fun gravarIndice(novas: List<Lista>) {
        listas = novas
        val a = JSONArray()
        for (l in novas) a.put(JSONObject().put("id", l.id).put("nome", l.nome)
            .put("link", l.link ?: JSONObject.NULL).put("atualizadaEm", l.atualizadaEm)
            .put("ativa", l.ativa).put("canais", l.canais))
        prefs().edit().putString("indice", a.toString()).apply()
        lidas = null
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

    // MARK: - Ações

    private fun novoId() = "l" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    private fun nomeLivre(digitado: String?): String {
        digitado?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(60) }
        var n = 1
        while (listas.any { it.nome == "Lista $n" }) n++
        return "Lista $n"
    }

    /** Baixa e lê. Erros em português, sem o endereço. */
    suspend fun baixar(link: String): List<LeitorM3u.CanalM3u> = withContext(Dispatchers.IO) {
        val url = link.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            throw Erro("O link precisa começar com http:// ou https://")
        }
        val texto = try {
            val cliente = Playback.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
            val pedido = Request.Builder().url(url).header("User-Agent", UA_VLC).build()
            cliente.newCall(pedido).execute().use { r ->
                if (!r.isSuccessful) throw Erro("Não consegui baixar essa lista")
                val saida = ByteArrayOutputStream()
                val fonte = r.body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fonte.read(buf)
                    if (n < 0) break
                    saida.write(buf, 0, n)
                    if (saida.size() > MAXIMO_BYTES) throw Erro("Não consegui baixar essa lista")
                }
                String(saida.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Erro) {
            throw e
        } catch (e: Exception) {
            throw Erro("Não consegui baixar essa lista")
        }
        validar(texto)
    }

    /** Lê o arquivo escolhido no seletor do sistema (ContentResolver). */
    suspend fun lerArquivo(uri: Uri): List<LeitorM3u.CanalM3u> = lerFluxo { app.contentResolver.openInputStream(uri)!! }

    /** Lê um arquivo da pasta Download/armazenamento (alternativa sem seletor do sistema). */
    suspend fun lerArquivo(arquivo: File): List<LeitorM3u.CanalM3u> = lerFluxo { arquivo.inputStream() }

    private suspend fun lerFluxo(abrir: () -> java.io.InputStream): List<LeitorM3u.CanalM3u> = withContext(Dispatchers.IO) {
        val texto = try {
            abrir().use { entrada ->
                val saida = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    saida.write(buf, 0, n)
                    if (saida.size() > MAXIMO_BYTES) throw Erro("Esse arquivo é grande demais")
                }
                String(saida.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Erro) {
            throw e
        } catch (e: Exception) {
            throw Erro("Não consegui abrir esse arquivo")
        }
        validar(texto)
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

    private fun validar(texto: String): List<LeitorM3u.CanalM3u> {
        val inicio = texto.trimStart('﻿').trimStart()
        if (!inicio.startsWith("#EXTM3U", true) && !texto.contains("#EXTINF")) {
            throw Erro("Isso não parece uma lista .m3u")
        }
        val canais = LeitorM3u.ler(texto)
        if (canais.isEmpty()) throw Erro("Essa lista não tem canais")
        return canais
    }

    /** Guarda uma lista nova. Lança [Erro] se já há [MAXIMO_LISTAS]. */
    @Synchronized
    fun adicionar(nome: String?, link: String?, canais: List<LeitorM3u.CanalM3u>) {
        if (listas.size >= MAXIMO_LISTAS) throw Erro("Você já tem $MAXIMO_LISTAS listas. Remova uma para adicionar outra.")
        val id = novoId()
        gravarCanais(id, canais)
        gravarIndice(listas + Lista(id, nomeLivre(nome), link?.trim(), System.currentTimeMillis(), true, canais.size))
    }

    /** Troca os canais de uma lista que já existe (atualização). */
    @Synchronized
    fun substituir(id: String, canais: List<LeitorM3u.CanalM3u>) {
        if (listas.none { it.id == id }) return
        gravarCanais(id, canais)
        gravarIndice(listas.map { if (it.id == id) it.copy(atualizadaEm = System.currentTimeMillis(), canais = canais.size) else it })
    }

    @Synchronized
    fun alternar(id: String) = gravarIndice(listas.map { if (it.id == id) it.copy(ativa = !it.ativa) else it })

    @Synchronized
    fun remover(id: String) {
        File(pasta(), "$id.json").delete()
        gravarIndice(listas.filterNot { it.id == id })
    }

    /** Atualiza pelo link; devolve a mensagem de erro, ou nulo se deu certo. */
    suspend fun atualizar(id: String): String? {
        val link = listas.firstOrNull { it.id == id }?.link ?: return "Essa lista veio de um arquivo: escolha o arquivo de novo"
        return try { substituir(id, baixar(link)); null } catch (e: Erro) { e.message }
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
