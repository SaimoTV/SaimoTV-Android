package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Novelinhas: minisséries verticais (720x1280, ~1 min por episódio) da
 * RedeFlix, em `novelinhas.txt` (atualizar_novelinhas.py):
 *
 *   base: https://.../novelinhas/
 *   id  título  pasta  episódios  áudio(dub|leg)  capa  gêneros  sinopse
 *
 * Episódio N = base + pasta + "/episode-" + N com três dígitos + ".mp4".
 */
object Novelinhas {

    data class Novelinha(
        val id: String, val titulo: String, val pasta: String, val episodios: Int,
        val dublada: Boolean, val capa: String, val generos: List<String>, val sinopse: String,
    )

    var base: String = ""
        private set
    var lista: List<Novelinha> = emptyList()
        private set

    fun url(n: Novelinha, ep: Int): String = "$base${n.pasta}/episode-${ep.toString().padStart(3, '0')}.mp4"

    fun porId(id: String): Novelinha? = lista.firstOrNull { it.id == id }

    suspend fun carregar(context: Context): List<Novelinha> = withContext(Dispatchers.IO) {
        if (lista.isNotEmpty()) return@withContext lista
        val texto = Vod.texto(context, "novelinhas.txt") ?: return@withContext emptyList()
        var b = ""
        val saida = ArrayList<Novelinha>(700)
        for (linha in texto.lineSequence()) {
            if (linha.isEmpty() || linha[0] == '#') continue
            if (linha.startsWith("base:")) { b = linha.removePrefix("base:").trim(); continue }
            val c = linha.split('\t')
            val eps = c.getOrNull(3)?.toIntOrNull() ?: 0
            if (c.size < 6 || eps <= 0) continue
            saida += Novelinha(c[0], c[1], c[2], eps, c[4] == "dub", c[5],
                c.getOrNull(6).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                c.getOrNull(7).orEmpty())
        }
        if (b.isNotEmpty() && saida.isNotEmpty()) { base = b; lista = saida }
        lista
    }

    /* Onde parou em cada novelinha: "id" -> "episódio:segundo". */
    private const val PREFS = "novelinhas"

    fun parada(context: Context, id: String): Pair<Int, Long>? =
        Perfis.prefs(context, PREFS).getString(id, null)?.split(':')?.let {
            val ep = it.getOrNull(0)?.toIntOrNull() ?: return null
            ep to (it.getOrNull(1)?.toLongOrNull() ?: 0L)
        }

    fun guardar(context: Context, id: String, ep: Int, ms: Long) {
        Perfis.prefs(context, PREFS).edit()
            .putString(id, "$ep:$ms").putLong("$id.quando", System.currentTimeMillis()).apply()
    }

    fun recentes(context: Context): List<String> {
        val p = Perfis.prefs(context, PREFS).all
        return p.keys.filter { !it.endsWith(".quando") }
            .sortedByDescending { (p["$it.quando"] as? Long) ?: 0L }
    }
}
