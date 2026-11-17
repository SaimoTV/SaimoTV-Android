package br.com.saimo.tv

import android.content.Context

/**
 * Perfil Kids: o que aparece. Contrato em SaimoPlayer/docs/perfis.md.
 *
 * Filmes e séries só com classificação indicativa conhecida de Livre a 12 anos
 * (detalhes.txt, via [InfoDosTitulos]); sem classificação, escondido. O filtro
 * mora na leitura do acervo ([Vod], [Destaques]), então grades, fileiras, busca
 * e fichas já recebem a lista limpa. Conteúdo adulto nunca destranca no Kids
 * ([Unlock]) e os addons ficam desligados.
 */
object Kids {

    const val IDADE_MAXIMA = 12

    fun ativo(): Boolean = Perfis.kidsAtivo()

    fun liberado(titulo: String, serie: Boolean): Boolean {
        val info = InfoDosTitulos.de(titulo, serie) ?: return false
        return info.idade in 0..IDADE_MAXIMA
    }

    /** Fora do Kids devolve como veio; no Kids, só o liberado. */
    suspend fun <T> filtrar(context: Context, itens: List<T>, titulo: (T) -> String, serie: (T) -> Boolean?): List<T> {
        if (!ativo()) return itens
        InfoDosTitulos.carregar(context)
        return itens.filter { t ->
            val s = serie(t)
            if (s == null) liberado(titulo(t), true) || liberado(titulo(t), false) else liberado(titulo(t), s)
        }
    }
}

/**
 * Controle parental: a senha que libera o conteúdo adulto. Padrão 0000, trocável
 * nos Ajustes; vale para o aparelho todo. No perfil Kids nunca destranca.
 */
object Parental {

    const val PADRAO = "0000"
    private const val ARQUIVO = "parental"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    private fun hash(senha: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest("saimo-parental:$senha".toByteArray()).joinToString("") { "%02x".format(it) }

    fun conferir(context: Context, senha: String): Boolean {
        if (Kids.ativo()) return false
        val guardada = prefs(context).getString("senha", null)
        return if (guardada.isNullOrBlank()) senha == PADRAO else hash(senha) == guardada
    }

    fun ehPadrao(context: Context) = prefs(context).getString("senha", null).isNullOrBlank()

    fun trocar(context: Context, atual: String, nova: String): Boolean {
        if (!conferir(context, atual) || !Regex("\\d{4}").matches(nova)) return false
        prefs(context).edit().putString("senha", hash(nova)).apply()
        return true
    }
}
