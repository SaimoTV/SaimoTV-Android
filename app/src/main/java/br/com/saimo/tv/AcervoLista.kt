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

    /** Junta filmes e episódios conforme a lista é lida. */
    class Montador {
        private val filmes = LinkedHashMap<String, Titulo>()
        private val series = LinkedHashMap<String, Titulo>()
        // O começo dos endereços (servidor/usuário/senha) se repete em 300 mil
        // linhas: guardado uma vez só.
        private val prefixos = HashMap<String, String>()
        private fun encurtar(url: String): String {
            val f = fonte(url)
            val corte = f.lastIndexOf('/') + 1
            if (corte <= 0) return f
            val p = f.substring(0, corte)
            return prefixos.getOrPut(p) { p } + f.substring(corte)
        }

        val vazio get() = filmes.isEmpty() && series.isEmpty()
        val totalFilmes get() = filmes.size
        val totalSeries get() = series.size

        fun adicionar(tipo: Classificar.Tipo, nome: String, grupo: String, url: String, logo: String?) {
            when (tipo) {
                Classificar.Tipo.Canal -> return
                Classificar.Tipo.Filme -> {
                    val t = Classificar.tituloDoFilme(nome, grupo)
                    val titulo = campo(t.titulo)
                    if (titulo.isEmpty()) return
                    val f = filmes.getOrPut(chave(titulo)) { Titulo(titulo, t.ano) }
                    f.completar(logo, grupo)
                    val urls = f.versoes.getOrPut(t.versao) { ArrayList(1) }
                    val u = encurtar(url)
                    if (u !in urls) urls += u
                }
                is Classificar.Tipo.Episodio -> {
                    val m = tipo.marca
                    val t = Classificar.tituloDaSerie(nome, m.inicio, grupo)
                    val titulo = campo(t.titulo)
                    val s = series.getOrPut("${chave(titulo)}|${t.ano}") { Titulo(titulo, t.ano) }
                    s.completar(logo, grupo)
                    val urls = s.episodios.getOrPut("${m.temporada}|${m.episodio}|${t.versao}") { ArrayList(1) }
                    val u = encurtar(url)
                    if (u !in urls) urls += u
                }
            }
        }

        /** Grava os arquivos em [pasta] (apaga o que havia). Sem nada, a pasta some. */
        fun gravar(pasta: File): Resumo {
            pasta.deleteRecursively()
            if (vazio) return Resumo(0, 0)
            val ordem = { t: Titulo -> Classificar.dobrarTexto(t.titulo) }
            val fs = filmes.values.sortedBy(ordem)
            val ss = series.values.sortedBy(ordem)
            val porLetra = java.util.TreeMap<String, Pair<MutableList<Titulo>, MutableList<Titulo>>>()
            for (f in fs) porLetra.getOrPut(letra(f.titulo)) { mutableListOf<Titulo>() to mutableListOf() }.first += f
            for (s in ss) porLetra.getOrPut(letra(s.titulo)) { mutableListOf<Titulo>() to mutableListOf() }.second += s

            val tmp = File(pasta.path + ".novo").apply { deleteRecursively(); mkdirs() }
            fun escrever(nome: String, texto: CharSequence) = File(tmp, noDisco(nome)).writeText(texto.toString())
            val indice = StringBuilder()
            val busca = StringBuilder()
            for ((l, par) in porLetra) {
                val (lf, ls) = par
                indice.append(l).append('\t').append(lf.size).append('\t').append(ls.size).append("\t0\n")
                if (lf.isNotEmpty()) {
                    val texto = StringBuilder()
                    for (f in lf) {
                        texto.append(f.titulo)
                        for ((v, urls) in f.versoes) texto.append('\t').append(v).append('=').append(urls.joinToString(","))
                        texto.append('\n')
                        busca.append(f.titulo).append("\tf\t").append(l).append('\t').append(f.ano).append('\n')
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
                    }
                    if (atual.isNotEmpty()) escrever("series-$l-$pedaco.txt", atual)
                    escrever("series-$l.txt", lista)
                }
            }
            escrever("indice.txt", indice)
            escrever("busca.txt", busca)
            // Capas e gêneros. Imagem usada por mais de 3 títulos é a genérica do
            // provedor: melhor sem capa que com a errada.
            val usos = HashMap<String, Int>()
            for (t in fs + ss) t.capa?.let { usos[it] = (usos[it] ?: 0) + 1 }
            val fichas = StringBuilder("capa: \n")
            for ((tipo, lista) in listOf("f" to fs, "s" to ss)) {
                for (t in lista) {
                    val capa = t.capa?.takeIf { (usos[it] ?: 0) <= 3 }?.let(::fonte).orEmpty()
                    if (capa.isEmpty() && t.generos.isEmpty()) continue
                    fichas.append(tipo).append('\t').append(t.titulo).append("\t0\t").append(capa).append('\t')
                        .append(t.generos.joinToString(",")).append('\n')
                }
            }
            escrever("fichas.txt", fichas)
            tmp.renameTo(pasta)
            return Resumo(fs.size, ss.size)
        }
    }
}
