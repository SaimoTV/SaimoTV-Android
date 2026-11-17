package br.com.saimo.tv

/**
 * Extra line-up, present only after the code is typed on the remote.
 *
 * The channels live in a separate list rather than behind a flag on the normal
 * ones so nothing in the interface has to know they exist: while locked they
 * are not in the list at all, so there is no label, no gap and no count to
 * notice. Nothing is written to disk either — closing the app locks it again,
 * and the next person to open it sees exactly what everyone else sees.
 */
object Unlock {

    private var destrancado = false

    /** Destrancado e fora do perfil Kids (no Kids o conteúdo adulto nunca aparece). */
    val unlocked: Boolean get() = destrancado && !Kids.ativo()

    /** Canais à vista agora: a lista publicada mais, se destrancado, os extras. */
    fun channels(): List<Channel> =
        if (unlocked) Remote.channels + Remote.restritos else Remote.channels

    /** Controle parental nos Ajustes: libera com a senha (padrão 0000). */
    fun liberar(context: android.content.Context, senha: String): Boolean {
        if (!Parental.conferir(context, senha)) return false
        destrancado = true
        return true
    }

    fun bloquear() { destrancado = false }

    /**
     * Diz se a sequência digitada era o código, alternando o estado quando for.
     *
     * Devolver falso é indistinguível de um número de canal que não existe: um
     * código errado se parece exatamente com nada acontecendo.
     */
    fun consume(typed: String): Boolean {
        if (typed.length != 4 || Remote.restritos.isEmpty() || !Perfis.estaPronto()) return false
        if (!Parental.conferir(Perfis.contexto(), typed)) return false
        destrancado = !destrancado
        return true
    }
}
