package br.com.saimo.tv

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * Leitor de lista .m3u comum ("Minhas listas"). Kotlin puro, sem Android: roda
 * em teste de JVM contra os gabaritos (`edge` e `br`) e devolve exatamente o que
 * os outros apps Saimo devolvem. Nunca lança exceção: texto malformado, enorme
 * ou binário dá lista vazia (ou só o que foi possível ler).
 */
object LeitorM3u {

    const val LIMITE_CANAIS = 20_000

    data class FonteM3u(
        val url: String,
        val referer: String? = null,
        val ua: String? = null,
        val keyId: String? = null,
        val key: String? = null,
        val qualidade: String? = null,
    )

    data class CanalM3u(
        val nome: String,
        val grupo: String?,
        val logo: String?,
        val fontes: List<FonteM3u>,
    )

    private val TAG_RUIM = Pattern.compile(
        "\\d{3,4}[pi]|not 24/7|geo-?blocked|backup|vertical|\\bsd\\b|\\bhd\\b|fhd|\\b4k\\b",
        Pattern.CASE_INSENSITIVE)
    private val RES = Pattern.compile("(\\d{3,4}[pi])", Pattern.CASE_INSENSITIVE)
    private val TAG_FIM = Pattern.compile("\\s*[(\\[]([^)\\]]*)[)\\]]\\s*$")
    private val HTTP = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val CHAVE_CLEAR = Pattern.compile("^[0-9a-fA-F]{32}:[0-9a-fA-F]{32}$")
    private val ADULTO = Pattern.compile(
        "(?:^|[^a-z0-9])(?:adult|adulto|adultos|xxx|porn|porno|sexo|sex|erotic|erotico|hentai)(?:[^a-z0-9]|$)|\\+18|18\\+")

    private fun semAcentos(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(n.length)
        for (c in n) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
        return sb.toString()
    }

    fun chave(nome: String): String {
        val s = semAcentos(nome).lowercase(Locale.ROOT)
        val sb = StringBuilder(s.length)
        for (c in s) if (c in 'a'..'z' || c in '0'..'9') sb.append(c)
        return sb.toString()
    }

    /** Grupo ou nome de conteúdo adulto: só aparece depois do desbloqueio. "Adult Swim" não conta. */
    fun ehRestrito(grupo: String?, nome: String): Boolean {
        val texto = semAcentos("${grupo.orEmpty()} $nome").lowercase(Locale.ROOT).replace("adult swim", " ")
        return ADULTO.matcher(texto).find()
    }

    private fun atributo(linha: String, nome: String): String? {
        val m = Pattern.compile("(?:^|[\\s:])" + Pattern.quote(nome) + "=\"([^\"]*)\"").matcher(linha)
        return if (m.find()) m.group(1).trim() else null
    }

    private fun nomeExibido(linha: String): String {
        var dentro = false
        for (i in linha.indices) {
            val c = linha[i]
            if (c == '"') dentro = !dentro
            else if (c == ',' && !dentro) return linha.substring(i + 1).trim()
        }
        return ""
    }

    private fun tirarTags(bruto: String): Pair<String, String?> {
        // Nome absurdo (lixo): não vale varrer o regex; fica um pedaço dele.
        if (bruto.length > 2000) return bruto.substring(0, 200).trim() to null
        var nome = bruto
        var qualidade: String? = null
        while (true) {
            val m = TAG_FIM.matcher(nome)
            if (!m.find() || !TAG_RUIM.matcher(m.group(1)).find()) break
            val r = RES.matcher(m.group(1))
            if (qualidade == null && r.find()) qualidade = r.group(1).lowercase(Locale.ROOT)
            nome = nome.substring(0, m.start())
        }
        return nome.trim() to qualidade
    }

    private class Pendente(
        val exibido: String, val tvgName: String?, val tvgId: String?,
        val logo: String?, val grupo: String?,
    )

    private class Montagem(
        var nome: String, var grupo: String?, var logo: String?, val fontes: MutableList<FonteM3u>,
    )

    fun ler(textoOriginal: String): List<CanalM3u> = try {
        lerSemRede(textoOriginal)
    } catch (e: Exception) {
        emptyList()
    }

    private fun lerSemRede(textoOriginal: String): List<CanalM3u> {
        val texto = textoOriginal.trimStart('﻿')
        val canais = ArrayList<Montagem>()
        val indice = HashMap<String, Montagem>()
        var pend: Pendente? = null
        var opts = HashMap<String, String>()
        val linhas = texto.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (bruta in linhas) {
            val linha = bruta.trim()
            if (linha.isEmpty()) continue
            if (linha.startsWith("#EXTM3U", ignoreCase = true)) continue
            if (linha.startsWith("#EXTINF")) {
                pend = Pendente(nomeExibido(linha), atributo(linha, "tvg-name"), atributo(linha, "tvg-id"),
                    atributo(linha, "tvg-logo"), atributo(linha, "group-title"))
                opts = HashMap()
                continue
            }
            if (linha.startsWith("#EXTGRP:")) { opts["extgrp"] = linha.substring(8).trim(); continue }
            if (linha.startsWith("#EXTVLCOPT:")) {
                val kv = linha.substring(11)
                val i = kv.indexOf('=')
                if (i >= 0) {
                    val k = kv.substring(0, i).trim().lowercase(Locale.ROOT)
                    val v = kv.substring(i + 1).trim()
                    if (k == "http-referrer" || k == "http-referer") opts["referer"] = v
                    else if (k == "http-user-agent") opts["ua"] = v
                }
                continue
            }
            if (linha.startsWith("#KODIPROP:")) {
                val kv = linha.substring(10)
                val i = kv.indexOf('=')
                if (i >= 0) {
                    val k = kv.substring(0, i).trim().lowercase(Locale.ROOT)
                    val v = kv.substring(i + 1).trim()
                    if (k == "inputstream.adaptive.license_type") opts["lic_tipo"] = v.lowercase(Locale.ROOT)
                    else if (k == "inputstream.adaptive.license_key") opts["lic_chave"] = v
                }
                continue
            }
            if (linha.startsWith("#")) continue

            // Linha de endereço.
            var url = linha
            val extra = HashMap<String, String>()
            val barra = linha.indexOf('|')
            if (barra >= 0) {
                val depois = linha.substring(barra + 1)
                if (depois.indexOf('=') >= 0) {
                    url = linha.substring(0, barra).trim()
                    for (par in depois.split('&')) {
                        val i = par.indexOf('=')
                        if (i >= 0) extra[par.substring(0, i).trim().lowercase(Locale.ROOT)] = par.substring(i + 1).trim()
                    }
                }
            }
            if (!HTTP.matcher(url).matches()) { pend = null; opts = HashMap(); continue }
            val p = pend ?: Pendente("", null, null, null, null)
            val bruto = p.exibido.ifEmpty { p.tvgName.orEmpty() }.ifEmpty { p.tvgId.orEmpty() }
            var (nome, qual) = if (bruto.isNotEmpty()) tirarTags(bruto) else "" to null
            if (nome.isEmpty()) nome = "Canal ${canais.size + 1}"
            var grupo = p.grupo?.ifEmpty { null } ?: opts["extgrp"]?.ifEmpty { null }
            if (grupo != null && ';' in grupo) grupo = grupo.substringBefore(';').trim()
            grupo = grupo?.ifEmpty { null }
            val logo = p.logo?.takeIf { it.isNotEmpty() && HTTP.matcher(it).matches() }
            var keyId: String? = null
            var key: String? = null
            val licChave = opts["lic_chave"].orEmpty()
            if ("clearkey" in opts["lic_tipo"].orEmpty() && CHAVE_CLEAR.matcher(licChave).matches()) {
                val partes = licChave.lowercase(Locale.ROOT).split(':')
                keyId = partes[0]; key = partes[1]
            }
            val fonte = FonteM3u(url,
                opts["referer"]?.ifEmpty { null } ?: extra["referer"]?.ifEmpty { null },
                opts["ua"]?.ifEmpty { null } ?: extra["user-agent"]?.ifEmpty { null },
                keyId, key, qual)
            val k = chave(nome)
            val c = indice[k]
            if (c != null) {
                if (c.fontes.none { it.url == url }) c.fontes.add(fonte)
                c.grupo = c.grupo ?: grupo
                c.logo = c.logo ?: logo
            } else if (canais.size < LIMITE_CANAIS) {
                val novo = Montagem(nome, grupo, logo, mutableListOf(fonte))
                indice[k] = novo
                canais.add(novo)
            }
            pend = null; opts = HashMap()
        }
        return canais.map { CanalM3u(it.nome, it.grupo, it.logo, it.fontes.toList()) }
    }
}
