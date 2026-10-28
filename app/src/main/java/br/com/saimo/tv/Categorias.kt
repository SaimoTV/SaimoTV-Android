package br.com.saimo.tv

/**
 * Seções da lista de canais.
 *
 * Quem decide a seção de cada canal é o catálogo (`categoria:`); o app não
 * adivinha por palavras do nome. Canal sem categoria vai para "Outros".
 * [ORDEM] só diz em que ordem as seções conhecidas aparecem.
 */
object Categorias {

    const val FAVORITOS = "Favoritos"

    val ORDEM = listOf(
        "TV Aberta", "Filmes e Séries", "Esportes", "Notícias", "Infantil",
        "Documentários", "Pluto TV", "24 Horas", "Variedades", "Adulto", OUTROS,
    )

    const val OUTROS = "Outros"

    fun de(canal: Channel): String =
        canal.categoria?.trim()?.takeIf { it.isNotEmpty() } ?: OUTROS

    /** Seção em que o canal aparece na lista: favorito vai para o topo. */
    fun secao(canal: Channel): String =
        if (Favorites.contains(canal.name)) FAVORITOS else de(canal)

    /** Favoritos primeiro, depois seção por seção, cada uma em ordem alfabética. */
    fun ordenar(canais: List<Channel>): List<Channel> =
        canais.sortedWith(
            compareBy<Channel> { if (Favorites.contains(it.name)) -1 else posicao(de(it)) }
                .thenBy { it.name.lowercase() })

    /** A mesma ordem, sem favoritos no topo: é dela que saem os números fixos. */
    fun ordenarFixo(canais: List<Channel>): List<Channel> =
        canais.sortedWith(compareBy<Channel> { posicao(de(it)) }.thenBy { it.name.lowercase() })

    // "Minhas listas" (as do usuário) fica depois de todas as outras seções.
    private fun posicao(categoria: String): Int =
        if (categoria == MinhasListas.CATEGORIA) ORDEM.size + 1
        else ORDEM.indexOf(categoria).let { if (it < 0) ORDEM.size else it }

    /**
     * O rótulo a mostrar sobre a linha, ou nulo: só a primeira de cada seção
     * leva, que é o bastante para quem desce a lista saber onde está.
     */
    fun rotulo(canais: List<Channel>, posicao: Int): String? {
        val atual = canais.getOrNull(posicao)?.let { secao(it) } ?: return null
        val anterior = canais.getOrNull(posicao - 1)?.let { secao(it) }
        return if (atual != anterior) atual else null
    }
}
