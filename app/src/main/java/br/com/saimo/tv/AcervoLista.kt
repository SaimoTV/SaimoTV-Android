package br.com.saimo.tv

import java.io.File

/**
 * O acervo de filmes e séries de uma lista M3U/Xtream, no formato Saimo
 * (SaimoPlayer/docs/formato-saimo.md, item 5; regras em docs/listas-m3u.md).
 * Montado enquanto a lista é lida ([LeitorM3u.lerFluxo]) e gravado numa pasta
 * só da lista; o [Vod] lê essa pasta como lê o acervo publicado, sem `base:`.
 */
object AcervoLista {

    data class Resumo(val filmes: Int, val series: Int)

    /** A letra do título (item 5.1): primeira letra sem acento; o resto vai em `#`. */
    fun letra(titulo: String): String {
        val c = Classificar.dobrar(titulo).firstOrNull { !it.isWhitespace() } ?: return "#"
        return if (c in 'a'..'z') c.uppercaseChar().toString() else "#"
    }

    /** Nome do arquivo no disco: `#` vira `hash`, como no cache do acervo. */
    fun noDisco(nome: String) = nome.replace("%23", "hash").replace("#", "hash")

    private fun chave(t: String) = String(Classificar.dobrar(t).filter { it in 'a'..'z' || it in '0'..'9' }.toCharArray())
    private fun campo(t: String) = t.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    private fun fonte(url: String) = url.replace(",", "%2C").replace("\t", "")
    private fun genero(grupo: String) = campo(grupo.dropWhile { !it.isLetterOrDigit() }).replace(',', ' ')

    private class Titulo(val titulo: String, val ano: String) {
        var capa: String? = null
        val generos = ArrayList<String>(2)
        val versoes = LinkedHashMap<String, MutableList<String>>(2)
        /** "t|e|versão" -> fontes. */
        val episodios = LinkedHashMap<String, MutableList<String>>()
        fun completar(logo: String?, grupo: String) {
            if (capa == null) capa = logo
            val g = genero(grupo)
            if (g.isNotEmpty() && g !in generos) generos += g
        }
    }

    /**
     * Junta filmes e episódios conforme a lista é lida, sem segurar a lista na
     * memória: cada item vira uma linha no arquivo temporário da letra dele, e
     * [gravar] monta uma letra por vez. Uma lista de 300 mil itens passava de
     * 300 MB e o sistema matava o app numa TV Box de 1 GB.
     */
    class Montador(private val temporaria: File) {
        private val escritores = HashMap<String, java.io.BufferedWriter>()
        private var itens = 0
        private var resumo = Resumo(0, 0)

        val vazio get() = itens == 0
        val totalFilmes get() = resumo.filmes
        val totalSeries get() = resumo.series

        private fun escritor(l: String) = escritores.getOrPut(l) {
            temporaria.mkdirs()
            File(temporaria, "$l.tmp".replace("#", "hash")).bufferedWriter(Charsets.UTF_8, 64 * 1024)
        }

        fun adicionar(tipo: Classificar.Tipo, nome: String, grupo: String, url: String, logo: String?) {
            // f/s ⇥ título ⇥ ano ⇥ versão ⇥ temporada ⇥ episódio ⇥ endereço ⇥ capa ⇥ grupo
            val linha = when (tipo) {
                Classificar.Tipo.Canal -> return
                Classificar.Tipo.Filme -> {
                    val t = Classificar.tituloDoFilme(nome, grupo)
                    val titulo = campo(t.titulo)
                    if (titulo.isEmpty()) return
                    listOf("f", titulo, t.ano, t.versao, "0", "0")
                }
                is Classificar.Tipo.Episodio -> {
                    val m = tipo.marca
                    val t = Classificar.tituloDaSerie(nome, m.inicio, grupo)
                    listOf("s", campo(t.titulo), t.ano, t.versao, m.temporada.toString(), m.episodio.toString())
                }
            }
            val w = escritor(letra(linha[1]))
            for (c in linha) { w.write(c); w.write('\t'.code) }
            w.write(fonte(url)); w.write('\t'.code)
            w.write(logo?.let(::fonte).orEmpty()); w.write('\t'.code)
            w.write(genero(grupo)); w.write('\n'.code)
            itens++
        }

        /** Grava os arquivos em [pasta] (apaga o que havia). Sem nada, a pasta some. */
        fun gravar(pasta: File): Resumo {
            escritores.values.forEach { runCatching { it.close() } }
            escritores.clear()
            pasta.deleteRecursively()
            if (vazio) { temporaria.deleteRecursively(); return Resumo(0, 0) }
            val tmp = File(pasta.path + ".novo").apply { deleteRecursively(); mkdirs() }
            fun escrever(nome: String, texto: CharSequence) = File(tmp, noDisco(nome)).writeText(texto.toString())
            val indice = StringBuilder()
            val busca = StringBuilder()
            // Só o necessário para as capas (a genérica do provedor é contada no fim).
            val fichas = ArrayList<Triple<String, String, Pair<String?, List<String>>>>()
            var nFilmes = 0
            var nSeries = 0
            val letras = (temporaria.listFiles()?.map { it.name.removeSuffix(".tmp").replace("hash", "#") } ?: emptyList())
                .sortedBy { if (it == "#") "" else it }
            for (l in letras) {
                val filmes = LinkedHashMap<String, Titulo>()
                val series = LinkedHashMap<String, Titulo>()
                File(temporaria, "$l.tmp".replace("#", "hash")).bufferedReader().useLines { linhas ->
                    for (linha in linhas) {
                        val c = linha.split('\t')
                        if (c.size < 9) continue
                        val logo = c[7].ifEmpty { null }
                        if (c[0] == "f") {
                            val f = filmes.getOrPut(chave(c[1])) { Titulo(c[1], c[2]) }
                            f.completar(logo, c[8])
                            val urls = f.versoes.getOrPut(c[3]) { ArrayList(1) }
                            if (c[6] !in urls) urls += c[6]
                        } else {
                            val s = series.getOrPut("${chave(c[1])}|${c[2]}") { Titulo(c[1], c[2]) }
                            s.completar(logo, c[8])
                            val urls = s.episodios.getOrPut("${c[4]}|${c[5]}|${c[3]}") { ArrayList(1) }
                            if (c[6] !in urls) urls += c[6]
                        }
                    }
                }
                val ordem = { t: Titulo -> Classificar.dobrarTexto(t.titulo) }
                val lf = filmes.values.sortedBy(ordem)
                val ls = series.values.sortedBy(ordem)
                nFilmes += lf.size
                nSeries += ls.size
                indice.append(l).append('\t').append(lf.size).append('\t').append(ls.size).append("\t0\n")
                if (lf.isNotEmpty()) {
                    val texto = StringBuilder()
                    for (f in lf) {
                        texto.append(f.titulo)
                        for ((v, urls) in f.versoes) texto.append('\t').append(v).append('=').append(urls.joinToString(","))
                        texto.append('\n')
                        busca.append(f.titulo).append("\tf\t").append(l).append('\t').append(f.ano).append('\n')
                        if (f.capa != null || f.generos.isNotEmpty()) fichas += Triple("f", f.titulo, f.capa to f.generos.toList())
                    }
                    escrever("filmes-$l.txt", texto)
                }
                if (ls.isNotEmpty()) {
                    val lista = StringBuilder()
                    var atual = StringBuilder()
                    var pedaco = 0
                    for (s in ls) {
                        val bloco = StringBuilder("@").append(s.titulo).append('\t').append(s.ano).append('\n')
                        val eps = s.episodios.entries.map { (k, urls) ->
                            val p = k.split('|')
                            Triple(p[0].toInt(), p[1].toInt(), p[2]) to urls
                        }.sortedWith(compareBy({ it.first.first }, { it.first.second }, { if (it.first.third == "dub") 0 else 1 }))
                        for ((k, urls) in eps) {
                            bloco.append(k.first).append('\t').append(k.second).append('\t').append(k.third).append('\t')
                                .append(urls.joinToString(",")).append('\n')
                        }
                        if (atual.isNotEmpty() && atual.length + bloco.length > 100 * 1024) {
                            escrever("series-$l-$pedaco.txt", atual)
                            atual = StringBuilder()
                            pedaco++
                        }
                        atual.append(bloco)
                        lista.append(s.titulo).append('\t').append(s.ano).append('\t').append(pedaco).append('\t')
                            .append(s.episodios.size).append('\n')
                        busca.append(s.titulo).append("\ts\t").append(l).append('\t').append(s.ano).append('\n')
                        if (s.capa != null || s.generos.isNotEmpty()) fichas += Triple("s", s.titulo, s.capa to s.generos.toList())
                    }
                    if (atual.isNotEmpty()) escrever("series-$l-$pedaco.txt", atual)
                    escrever("series-$l.txt", lista)
                }
            }
            escrever("indice.txt", indice)
            escrever("busca.txt", busca)
            // Imagem usada por mais de 3 títulos é a genérica do provedor: melhor sem capa que com a errada.
            val usos = HashMap<String, Int>()
            for ((_, _, d) in fichas) d.first?.let { usos[it] = (usos[it] ?: 0) + 1 }
            val texto = StringBuilder("capa: \n")
            for ((tipo, titulo, d) in fichas) {
                val capa = d.first?.takeIf { (usos[it] ?: 0) <= 3 }.orEmpty()
                if (capa.isEmpty() && d.second.isEmpty()) continue
                texto.append(tipo).append('\t').append(titulo).append("\t0\t").append(capa).append('\t')
                    .append(d.second.joinToString(",")).append('\n')
            }
            escrever("fichas.txt", texto)
            tmp.renameTo(pasta)
            temporaria.deleteRecursively()
            resumo = Resumo(nFilmes, nSeries)
            return resumo
        }
    }
}
