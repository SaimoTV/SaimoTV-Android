package br.com.saimo.tv

import android.content.Context
import org.json.JSONObject

/**
 * Avisos de episódios novos nas séries favoritas.
 *
 * O catálogo diz quantos episódios cada série tem. Para cada série favorita
 * guarda-se a contagem "já vista"; se o catálogo passa a ter mais, nasce um
 * aviso, que fica até a pessoa dispensar. A primeira olhada numa série só
 * marca a contagem, sem avisar.
 */
object NovosEpisodios {
    private const val ARQUIVO = "novosepisodios"

    private fun prefs(context: Context) = Perfis.prefs(context, ARQUIVO)

    private fun chave(i: VodFavoritos.Item) = "${i.titulo}|${i.ano}"

    /** "Título|ano" -> episódios a mais desde a última vez. */
    fun avisos(context: Context): Map<String, Int> {
        val p = prefs(context)
        return p.all.filterKeys { it.startsWith("n|") }.mapKeys { it.key.removePrefix("n|") }
            .mapValues { (it.value as? Int) ?: 0 }.filterValues { it > 0 }
    }

    fun resumo(context: Context): String {
        val a = avisos(context)
        return if (a.isEmpty()) "Nenhum episódio novo" else "${a.values.sum()} novos em ${a.size} séries · OK dispensa"
    }

    fun dispensarTodos(context: Context) {
        val p = prefs(context)
        val e = p.edit()
        for ((k, v) in p.all) {
            if (k.startsWith("n|")) e.remove(k)
            if (k.startsWith("t|")) {
                // a contagem vista passa a ser o total atual
                e.putInt("v|" + k.removePrefix("t|"), (v as? Int) ?: 0)
            }
        }
        e.apply()
    }

    /** Confere as séries favoritas. Devolve os títulos que ganharam episódios agora. */
    suspend fun verificar(context: Context): List<String> {
        val favoritas = VodFavoritos.lista(context).filter { it.serie && it.letra.isNotBlank() }
        if (favoritas.isEmpty()) return emptyList()
        val p = prefs(context)
        val e = p.edit()
        val novas = mutableListOf<String>()
        val porLetra = HashMap<String, List<Serie>>()
        for (item in favoritas) {
            val lista = porLetra.getOrPut(item.letra) { runCatching { Vod.series(context, item.letra) }.getOrDefault(emptyList()) }
            val achada = lista.firstOrNull { it.titulo == item.titulo && it.ano == item.ano }
                ?: lista.firstOrNull { it.titulo == item.titulo } ?: continue
            if (achada.episodios <= 0) continue
            val k = chave(item)
            e.putInt("t|$k", achada.episodios)
            if (!p.contains("v|$k")) { e.putInt("v|$k", achada.episodios); continue }
            val visto = p.getInt("v|$k", achada.episodios)
            when {
                achada.episodios > visto -> {
                    val n = achada.episodios - visto
                    if (p.getInt("n|$k", 0) == 0) novas += item.titulo
                    e.putInt("n|$k", n)
                }
                achada.episodios < visto -> { e.putInt("v|$k", achada.episodios); e.remove("n|$k") }
            }
        }
        e.apply()
        return novas
    }
}
