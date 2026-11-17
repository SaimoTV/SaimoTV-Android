package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Categoria de cada título do acervo: anime, dorama, novela e kids. Vem de
 * `categorias.txt` (gerar_categorias.py, pelo país de origem e o idioma no
 * TMDB). Título fora do arquivo é filme ou série comum.
 */
object CategoriasVod {
    const val ANIME = "anime"
    const val DORAMA = "dorama"
    const val NOVELA = "novela"
    const val KIDS = "kids"

    private var filmes: HashMap<String, List<String>> = HashMap()
    private var series: HashMap<String, List<String>> = HashMap()

    val prontas: Boolean get() = filmes.isNotEmpty() || series.isNotEmpty()

    fun tem(titulo: String, serie: Boolean, categoria: String): Boolean {
        val mapa = if (serie) series else filmes
        val lista = mapa[titulo] ?: mapa[Generos.semAno(titulo)] ?: return false
        return categoria in lista
    }

    suspend fun carregar(context: Context) = withContext(Dispatchers.IO) {
        if (prontas) return@withContext
        val texto = Vod.texto(context, "categorias.txt") ?: return@withContext
        val f = HashMap<String, List<String>>(4_000)
        val s = HashMap<String, List<String>>(6_000)
        for (linha in texto.lineSequence()) {
            if (linha.isEmpty() || linha[0] == '#') continue
            val c = linha.split('\t')
            if (c.size < 3) continue
            val cats = c[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (cats.isEmpty()) continue
            if (c[0] == "s") s[c[1]] = cats else f[c[1]] = cats
        }
        filmes = f
        series = s
    }
}
