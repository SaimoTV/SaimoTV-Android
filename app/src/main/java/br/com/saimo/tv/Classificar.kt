package br.com.saimo.tv

import java.text.Normalizer

/**
 * Canal, filme ou episódio: a classificação dos itens de uma lista M3U.
 * Regras em SaimoPlayer/docs/listas-m3u.md; referência em
 * SaimoPlayer/scripts/classificar_m3u.py e gabarito em
 * app/src/test/resources/classificar.* (ClassificarTest confere).
 */
object Classificar {

    data class Marca(val temporada: Int, val episodio: Int, /** em caracteres do nome */ val inicio: Int, val forte: Boolean)

    sealed class Tipo {
        object Canal : Tipo()
        object Filme : Tipo()
        data class Episodio(val marca: Marca) : Tipo()
    }

    val VIDEO = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "divx", "ogv", "vob")
    private val TIPO_CANAL = setOf("live", "channel", "tv", "radio")
    private val TIPO_FILME = setOf("movie", "movies", "vod", "film", "filme")
    private val TIPO_SERIE = setOf("series", "serie", "tvshow", "show", "episode")
    private val SEG_FILME = setOf("movie", "movies", "vod", "filmes")
    private val SEG_SERIE = setOf("series", "serie")
    private val PALAVRAS_ACERVO = setOf("filme", "filmes", "movie", "movies", "vod", "serie", "series", "novela", "novelas",
        "anime", "animes", "dorama", "doramas", "temporada", "temporadas")
    private val SEPARADORES = setOf(' ', '-', ':', '|', '.', '_', '(', '[', '–', '—', '\t')
    private val TAGS_LEG = setOf("l", "leg", "legendado", "legendada")
    private val TAGS_DUB = setOf("d", "dub", "dublado", "dublada", "nac", "nacional")
    private val PALAVRAS_EP = listOf("episodio", "episode", "capitulo", "cap", "ep")

    /** Minúsculas sem acento, um caractere para cada caractere do original. */
    fun dobrar(t: String): CharArray {
        val saida = CharArray(t.length)
        for (i in t.indices) {
            val c = t[i]
            saida[i] = when {
                c in 'A'..'Z' -> c + 32
                c.code < 128 -> c
                else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar() ?: c
            }
        }
        return saida
    }

    fun dobrarTexto(t: String) = String(dobrar(t))

    private fun alnum(c: Char) = c.isLetterOrDigit()
    private fun digito(c: Char) = c in '0'..'9'
    private fun inicioDePalavra(n: CharArray, i: Int) = i == 0 || !alnum(n[i - 1])

    /** 1..maximo dígitos em n[j..], sem outro dígito logo depois: (valor, fim). */
    private fun lerDigitos(n: CharArray, j: Int, maximo: Int): Pair<Int, Int>? {
        var k = j
        while (k < n.size && digito(n[k]) && k - j < maximo) k++
        if (k == j || (k < n.size && digito(n[k]))) return null
        return String(n, j, k - j).toInt() to k
    }

    private fun pular(n: CharArray, j0: Int, chars: Set<Char>): Int {
        var j = j0
        while (j < n.size && n[j] in chars) j++
        return j
    }

    private fun comeca(n: CharArray, j: Int, p: String): Boolean {
        if (j + p.length > n.size) return false
        for (x in p.indices) if (n[j + x] != p[x]) return false
        return true
    }

    private fun palavraDeEpisodio(n: CharArray, j: Int): Int? {
        for (p in PALAVRAS_EP) {
            if (!comeca(n, j, p)) continue
            var k = j + p.length
            if (k < n.size && n[k].isLetter()) continue
            k = pular(n, k, setOf('.'))
            k = pular(n, k, setOf(' '))
            lerDigitos(n, k, 4)?.let { return it.first }
        }
        return null
    }

    private fun lerDigitosAte(n: CharArray, j: Int, maximo: Int, seguinte: Char): Pair<Int, Int>? {
        var k = j
        while (k < n.size && digito(n[k])) k++
        if (k == j || k - j > maximo || k >= n.size || n[k] != seguinte) return null
        return String(n, j, k - j).toInt() to k
    }

    /** A marca de episódio do nome (S01E02, 1x02, Temporada 2 Episódio 5, Ep 12…). */
    fun marca(nome: String): Marca? {
        val n = dobrar(nome)
        var fraca: Marca? = null
        for (i in n.indices) {
            if (!inicioDePalavra(n, i)) continue
            val c = n[i]
            if (c == 's' || c == 't') {
                lerDigitos(n, i + 1, 3)?.let { (t, j0) ->
                    var j = pular(n, j0, setOf(' ', '.', '_', '-'))
                    if (j < n.size && (n[j] == 'e' || (c == 's' && n[j] == 'x'))) {
                        j++
                        if (j < n.size && n[j] == 'p') j++
                        j = pular(n, j, setOf(' ', '.'))
                        lerDigitos(n, j, 4)?.let { (e, _) -> return Marca(t, e, i, true) }
                    }
                }
            }
            if (digito(c)) {
                lerDigitosAte(n, i, 2, 'x')?.let { (t, j) ->
                    lerDigitos(n, j + 1, 3)?.let { (e, k) ->
                        if (k == n.size || !alnum(n[k])) return Marca(t, e, i, true)
                    }
                }
            }
            for (p in listOf("temporada", "season")) {
                if (!comeca(n, i, p)) continue
                val j0 = pular(n, i + p.length, setOf(' '))
                lerDigitos(n, j0, 3)?.let { (t, j) ->
                    for (k in j until minOf(j + 13, n.size)) {
                        if (inicioDePalavra(n, k)) palavraDeEpisodio(n, k)?.let { e -> return Marca(t, e, i, true) }
                    }
                }
            }
            if (fraca == null) palavraDeEpisodio(n, i)?.let { fraca = Marca(1, it, i, false) }
        }
        return fraca
    }

    private fun caminho(url: String): List<String> {
        var u = url.substringBefore('#').substringBefore('?')
        val esquema = u.indexOf("://")
        if (esquema >= 0) {
            val resto = u.substring(esquema + 3)
            u = if ('/' in resto) resto.substringAfter('/') else ""
        }
        return u.lowercase().split('/').filter { it.isNotEmpty() }
    }

    private fun palavras(t: String): List<String> = t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun grupoDeAcervo(grupo: String): Boolean {
        val g = dobrarTexto(grupo)
        val ws = palavras(g)
        if (ws.none { it in PALAVRAS_ACERVO }) return false
        if ("24h" in g || "24 horas" in g || "24/7" in g || "ao vivo" in g) return false
        return ws.none { it == "canal" || it == "canais" || it == "live" }
    }

    fun anoNoFim(nome: String): Boolean {
        val c = nome.trimEnd()
        val n = c.length
        if (n < 6 || c[n - 1] != ')' || c[n - 6] != '(') return false
        if (!(n - 5 until n - 1).all { digito(c[it]) }) return false
        return (c[n - 5] == '1' && c[n - 4] == '9') || (c[n - 5] == '2' && c[n - 4] == '0')
    }

    private fun anoDoFim(nome: String): String { val c = nome.trimEnd(); return c.substring(c.length - 5, c.length - 1) }

    fun tipo(url: String, nome: String, grupo: String, declarado: String): Tipo {
        val m = marca(nome)
        val d = declarado.trim().lowercase()
        if (d in TIPO_CANAL) return Tipo.Canal
        var acervo = d in TIPO_FILME || d in TIPO_SERIE
        val segs = caminho(url)
        if (!acervo) {
            for (s in segs.dropLast(1)) {
                if (s in SEG_FILME || s in SEG_SERIE) { acervo = true; break }
                if (s == "live") return Tipo.Canal
            }
            // Canal Xtream sem prefixo: /usuario/senha/123(.ts|.m3u8).
            if (!acervo && segs.size == 3) {
                val num = segs[2].substringBefore('.')
                val ext = if ('.' in segs[2]) segs[2].substringAfter('.') else ""
                if (num.isNotEmpty() && num.all(::digito) && ext in setOf("", "ts", "m3u8")) return Tipo.Canal
            }
        }
        if (!acervo) segs.lastOrNull()?.let { u -> if ('.' in u) acervo = u.substringAfterLast('.') in VIDEO }
        if (acervo) return m?.let { Tipo.Episodio(it) } ?: Tipo.Filme
        if (grupoDeAcervo(grupo)) {
            if (m != null && m.forte) return Tipo.Episodio(m)
            if (anoNoFim(limpar(nome).first)) return Tipo.Filme
        }
        return Tipo.Canal
    }

    // ---------- títulos ----------

    private fun resolucao(t: String): Boolean {
        var i = 0
        while (i < t.length) {
            if (digito(t[i])) {
                val ini = i
                while (i < t.length && digito(t[i])) i++
                if (i - ini >= 3 && i < t.length && t[i] in "pPiI") return true
            } else i++
        }
        return false
    }

    private fun temPalavra(t: String, p: String): Boolean {
        fun palavra(x: Char) = x.isLetterOrDigit() || x == '_'
        var de = 0
        while (true) {
            val i = t.indexOf(p, de)
            if (i < 0) return false
            val f = i + p.length
            if ((i == 0 || !palavra(t[i - 1])) && (f >= t.length || !palavra(t[f]))) return true
            de = i + 1
        }
    }

    private fun tagRuim(conteudo: String): Boolean {
        val t = conteudo.lowercase()
        return resolucao(t) || "not 24/7" in t || "geoblocked" in t || "geo-blocked" in t || "backup" in t ||
            "vertical" in t || temPalavra(t, "sd") || temPalavra(t, "hd") || "fhd" in t || temPalavra(t, "4k")
    }

    /** Tira tags de qualidade e de versão; devolve (nome, versão). */
    fun limpar(nomeOriginal: String): Pair<String, String?> {
        var versao: String? = null
        var nome = nomeOriginal.trim()
        while (true) {
            val t = nome.trimEnd()
            val fecha = t.lastOrNull()
            if (fecha == ')' || fecha == ']') {
                val pos = t.lastIndexOf(if (fecha == ')') '(' else '[')
                if (pos >= 0) {
                    val conteudo = t.substring(pos + 1, t.length - 1).trim()
                    val c = dobrarTexto(conteudo)
                    if (c in TAGS_LEG) { versao = versao ?: "leg"; nome = t.substring(0, pos); continue }
                    if (c in TAGS_DUB) { versao = versao ?: "dub"; nome = t.substring(0, pos); continue }
                    if (tagRuim(conteudo)) { nome = t.substring(0, pos); continue }
                }
            }
            val espaco = t.lastIndexOf(' ')
            if (espaco >= 0 && dobrarTexto(t.substring(espaco + 1)) in setOf("leg", "legendado", "legendada")) {
                versao = versao ?: "leg"; nome = t.substring(0, espaco); continue
            }
            nome = t
            break
        }
        // Tags de versão no meio ("Filme [L] (2020)").
        for ((abre, fecha) in listOf('[' to ']', '(' to ')')) {
            var de = 0
            while (true) {
                val i = nome.indexOf(abre, de)
                if (i < 0) break
                val f = nome.indexOf(fecha, i + 1)
                if (f < 0) break
                val c = dobrarTexto(nome.substring(i + 1, f).trim())
                if (c in TAGS_LEG || c in TAGS_DUB) {
                    versao = versao ?: (if (c in TAGS_LEG) "leg" else "dub")
                    nome = (nome.substring(0, i).trimEnd() + " " + nome.substring(f + 1).trimStart()).trim()
                    de = minOf(i, nome.length)
                } else de = f + 1
            }
        }
        return nome.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") to versao
    }

    private fun anoRepetido(nome: String): String {
        var t = nome.trimEnd()
        while (anoNoFim(t)) {
            val antes = t.substring(0, t.length - 6).trimEnd()
            if (!anoNoFim(antes) || anoDoFim(antes) != anoDoFim(t)) break
            t = antes
        }
        return t
    }

    private fun semExtensao(nome: String): String {
        val t = nome.trim()
        val p = t.lastIndexOf('.')
        return if (p >= 0 && t.substring(p + 1).lowercase() in VIDEO) t.substring(0, p).trim() else t
    }

    private fun versaoDoGrupo(grupo: String) = if ("legendad" in dobrarTexto(grupo)) "leg" else "dub"

    data class Titulo(val titulo: String, val ano: String, val versao: String)

    fun tituloDoFilme(nome: String, grupo: String): Titulo {
        val (t0, versao) = limpar(semExtensao(nome))
        val t = anoRepetido(t0)
        return Titulo(t, if (anoNoFim(t)) anoDoFim(t) else "", versao ?: versaoDoGrupo(grupo))
    }

    private fun semSeparadoresNoFim(t: String) = t.trimEnd { it in SEPARADORES }

    fun tituloDaSerie(nome: String, inicio: Int, grupo: String): Titulo {
        val (t0, v0) = limpar(semSeparadoresNoFim(nome.substring(0, inicio)))
        var t = semSeparadoresNoFim(t0).trim()
        val versao = v0 ?: limpar(nome.substring(inicio)).second
        t = anoRepetido(t)
        var ano = ""
        if (anoNoFim(t)) {
            ano = anoDoFim(t)
            t = semSeparadoresNoFim(t.substring(0, t.length - 6)).trim()
        }
        if (t.isEmpty()) t = grupo.trim().ifEmpty { "Série" }
        return Titulo(t, ano, versao ?: versaoDoGrupo(grupo))
    }
}
