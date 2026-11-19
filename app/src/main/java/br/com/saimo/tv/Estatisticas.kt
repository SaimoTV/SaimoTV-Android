package br.com.saimo.tv

import android.content.Context

/**
 * Estatísticas pessoais do perfil: tempo assistido, filmes e episódios vistos.
 *
 * O tempo soma a cada gravação do ponto (a cada poucos segundos), nunca mais
 * que o intervalo plausível entre duas, para uma pausa longa ou um salto não
 * inflar o total.
 */
object Estatisticas {
    private const val ARQUIVO = "estatisticas"
    private const val MAXIMO_MS = 60_000L

    private var ultimo = 0L

    private fun prefs(context: Context) = Perfis.prefs(context, ARQUIVO)

    /** Chamar a cada gravação do ponto enquanto o vídeo toca. */
    fun tocando(context: Context, tocando: Boolean) {
        val agora = System.currentTimeMillis()
        val delta = agora - ultimo
        ultimo = if (tocando) agora else 0L
        if (!tocando || delta <= 0 || delta > MAXIMO_MS) return
        val p = prefs(context)
        p.edit().putLong("ms", p.getLong("ms", 0L) + delta).apply()
    }

    fun parar() { ultimo = 0L }

    data class Resumo(val horas: Long, val minutos: Long, val filmes: Int, val episodios: Int)

    fun resumo(context: Context): Resumo {
        val ms = prefs(context).getLong("ms", 0L)
        var filmes = 0
        var episodios = 0
        for ((k, v) in Perfis.prefs(context, "progresso").all) {
            if (v != true || !k.startsWith("v|")) continue
            if (k.startsWith("v|f|")) filmes++ else if (k.startsWith("v|s|")) episodios++
        }
        return Resumo(ms / 3_600_000L, ms / 60_000L % 60, filmes, episodios)
    }

    fun texto(context: Context): String {
        val r = resumo(context)
        return "${r.horas} h ${r.minutos} min · ${r.filmes} filmes · ${r.episodios} episódios"
    }
}
