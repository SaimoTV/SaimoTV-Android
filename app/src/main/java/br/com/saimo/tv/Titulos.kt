package br.com.saimo.tv

import android.content.Context
import androidx.media3.common.util.UnstableApi

/**
 * De um título do acervo ao que dá para tocar.
 *
 * O filme mora no arquivo da letra; a série, no pedaço dela; anime e dorama,
 * nas coleções. Antes cada tela fazia esse caminho do seu jeito — e cada uma
 * errava num lugar diferente. Aqui é um só, usado pela ficha, pelo player e
 * pelo "Continue assistindo".
 */
@UnstableApi
object Titulos {

    /** Uma fonte tocável, com a versão (dublado/legendado) e metadados opcionais de addon. */
    data class Opcao(
        val versao: String,
        val url: String = "",
        val origem: String = "",
        val titulo: String = "",
        val headers: Map<String, String> = emptyMap(),
        val bingeGroup: String? = null,
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val trackerSources: List<String> = emptyList(),
        val externalUrl: String? = null,
        val ytId: String? = null,
        val notWebReady: Boolean = false,
        val filename: String? = null,
        val videoSize: Long? = null,
        val videoHash: String? = null,
        val legendasInline: List<Legendas.Opcao> = emptyList(),
        val badges: List<String> = emptyList(),
        val addonId: String? = null,
    ) {
        val ehAddon: Boolean get() = origem.isNotEmpty()
        val ehTorrent: Boolean get() = !infoHash.isNullOrBlank() && url.isBlank()
        val ehExternoOuYt: Boolean get() = url.isBlank() && infoHash.isNullOrBlank() &&
            (!externalUrl.isNullOrBlank() || !ytId.isNullOrBlank())
        val chaveIdentidade: String
            get() = url.ifBlank {
                listOfNotNull(
                    infoHash?.let { "torrent:$it:${fileIdx ?: -1}" },
                    externalUrl?.let { "ext:$it" },
                    ytId?.let { "yt:$it" },
                ).firstOrNull().orEmpty()
            }
    }

    /** Um episódio com todas as versões e fontes juntas. */
    data class Ep(
        val temporada: Int,
        val numero: Int,
        val fontes: Map<String, List<String>>,
        val opcoesExtras: List<Opcao> = emptyList(),
    ) {
        val versoes: Set<String> get() = (fontes.keys + opcoesExtras.map { it.versao }).toSet()
        val temFontes: Boolean get() = fontes.isNotEmpty() || opcoesExtras.isNotEmpty()
    }

    data class Resolvido(
        val alvo: Alvo,
        /// O nome que vai na chave de progresso — o mesmo desde a 1.x.
        val nomeChave: String,
        val filme: Filme?,
        val episodios: List<Ep>,
        val tmdbId: Int,
        /// Título do Extras: não deixa rastro (progresso, "continuar", Google TV).
        val reservado: Boolean = false,
        val imdbId: String? = null,
        val opcoesAddon: List<Opcao> = emptyList(),
    ) {
        val serie: Boolean get() = filme == null
        val temporadas: List<Int> get() = episodios.map { it.temporada }.distinct().sorted()

        fun chave(ep: Ep?): String =
            if (ep == null) Progresso.chaveFilme(nomeChave)
            else Progresso.chaveEpisodio(nomeChave, ep.temporada, ep.numero)

        fun episodio(temporada: Int, numero: Int) =
            episodios.firstOrNull { it.temporada == temporada && it.numero == numero }

        fun seguinte(ep: Ep): Ep? {
            val i = episodios.indexOf(ep)
            return if (i < 0) null else episodios.getOrNull(i + 1)
        }

        fun anterior(ep: Ep): Ep? {
            val i = episodios.indexOf(ep)
            return if (i <= 0) null else episodios.getOrNull(i - 1)
        }

        val endereco: Progresso.Endereco
            get() = Progresso.Endereco(alvo.titulo, alvo.serie, alvo.letra, alvo.ano, alvo.colecao, tmdbId)
    }

    suspend fun resolver(context: Context, alvo: Alvo): Resolvido? {
        Generos.carregar(context)
        if (alvo.colecao.isNotEmpty()) {
            val achado = Vod.colecao(context, alvo.colecao)
                .firstOrNull { it.titulo.equals(alvo.titulo, ignoreCase = true) } ?: return null
            val tmdb = alvo.tmdbId.takeIf { it > 0 } ?: achado.tmdbId.toIntOrNull() ?: 0
            return Resolvido(alvo, Serie(achado.titulo, achado.ano, -1, 0).nomeCompleto, null,
                agrupar(achado.episodios), tmdb)
        }
        val achado = Vod.Achado(alvo.titulo, alvo.serie, alvo.letra, alvo.ano)
        var tmdb = alvo.tmdbId.takeIf { it > 0 } ?: Generos.id(alvo.nomeCompleto, alvo.serie)
            ?: Generos.id(alvo.titulo, alvo.serie) ?: 0
        if (tmdb <= 0 && alvo.imdbId.isNotBlank()) {
            tmdb = IdResolver.referenciaPorImdb(alvo.imdbId, alvo.serie)?.tmdbId ?: 0
        }
        if (tmdb <= 0) {
            tmdb = Detalhes.buscarId(alvo.titulo, alvo.serie, alvo.ano) ?: 0
        }
        val imdbId = alvo.imdbId.ifBlank {
            if (tmdb > 0) IdResolver.imdbPorTmdb(tmdb, alvo.serie).orEmpty() else ""
        }.ifBlank { null }

        return if (alvo.serie) {
            val serie = if (alvo.letra.isNotBlank()) Vod.serie(context, achado) else null
            val epsLocais = if (serie != null) Vod.episodios(context, alvo.letra, serie) else emptyList()
            val agrupados = agrupar(epsLocais)
            val nome = serie?.nomeCompleto ?: alvo.nomeCompleto

            if (tmdb > 0) {
                val temporadasTmdb = Detalhes.temporadasDaSerie(tmdb)
                if (temporadasTmdb.isNotEmpty()) {
                    val mapa = agrupados.associateBy { it.temporada to it.numero }.toMutableMap()
                    for (t in temporadasTmdb) {
                        for (n in 1..t.totalEpisodios) {
                            if ((t.temporada to n) !in mapa) {
                                mapa[t.temporada to n] = Ep(t.temporada, n, emptyMap())
                            }
                        }
                    }
                    val todosEps = mapa.values.sortedWith(compareBy({ it.temporada }, { it.numero }))
                    val primeiro = todosEps.firstOrNull()
                    val listaComPrimeiro = if (primeiro != null && !primeiro.temFontes) {
                        val extras = Resolvedor404.fontes(tmdb, serie = true, temporada = primeiro.temporada, numero = primeiro.numero)
                        val addonOps = if (AddonsStore.temStreamAtivo()) {
                            AddonsService.buscarStreams(tmdb, imdbId, serie = true, primeiro.temporada, primeiro.numero)
                        } else emptyList()
                        if (extras.isNotEmpty() || addonOps.isNotEmpty()) {
                            todosEps.map {
                                if (it.temporada == primeiro.temporada && it.numero == primeiro.numero) {
                                    Ep(it.temporada, it.numero, extras, addonOps)
                                } else it
                            }
                        } else todosEps
                    } else todosEps

                    return Resolvido(alvo, nome, null, listaComPrimeiro, tmdb, imdbId = imdbId)
                }
            }

            if (agrupados.isEmpty()) {
                val epsMeta = AddonsService.buscarEpisodiosMeta(tmdb, imdbId)
                if (epsMeta.isNotEmpty()) {
                    return Resolvido(alvo, nome, null, epsMeta, tmdb, imdbId = imdbId)
                }
                // 404 + Addons: série sem catálogo local
                if (tmdb > 0 || !imdbId.isNullOrBlank()) {
                    val extras = if (tmdb > 0) Resolvedor404.fontes(tmdb, serie = true, temporada = 1, numero = 1) else emptyMap()
                    val addonOps = if (AddonsStore.temStreamAtivo()) {
                        AddonsService.buscarStreams(tmdb, imdbId, serie = true, temporada = 1, episodio = 1)
                    } else emptyList()
                    if (extras.isNotEmpty() || addonOps.isNotEmpty()) {
                        val ep404 = Ep(1, 1, extras, addonOps)
                        return Resolvido(alvo, nome, null, listOf(ep404), tmdb, imdbId = imdbId)
                    }
                }
                return null
            }
            Resolvido(alvo, nome, null, agrupados, tmdb, imdbId = imdbId)
        } else {
            val filme = if (alvo.letra.isNotBlank()) juntarGrafias(context, achado) else null
            val addonOps = if (AddonsStore.temStreamAtivo() && (tmdb > 0 || !imdbId.isNullOrBlank())) {
                AddonsService.buscarStreams(tmdb, imdbId, serie = false)
            } else emptyList()

            if (filme != null) {
                // 404: acrescenta fontes dos addons 404 como reserva no fim da fila
                val extras = if (tmdb > 0) Resolvedor404.fontes(tmdb, serie = false) else emptyMap()
                val comExtras = if (extras.isNotEmpty()) {
                    val todas = filme.fontes.keys + extras.keys
                    todas.associateWith { v ->
                        (filme.fontes[v].orEmpty() + extras[v].orEmpty()).distinct()
                    }
                } else filme.fontes
                return Resolvido(alvo, filme.titulo, Filme(filme.titulo, comExtras), emptyList(), tmdb, imdbId = imdbId, opcoesAddon = addonOps)
            }
            // 404 + Addons: filme fora do catálogo local
            if (tmdb > 0 || !imdbId.isNullOrBlank()) {
                val extras = if (tmdb > 0) Resolvedor404.fontes(tmdb, serie = false) else emptyMap()
                if (extras.isNotEmpty() || addonOps.isNotEmpty()) {
                    return Resolvido(alvo, alvo.titulo, Filme(alvo.titulo, extras), emptyList(), tmdb, imdbId = imdbId, opcoesAddon = addonOps)
                }
            }
            if (!Unlock.unlocked || alvo.letra.isBlank()) return null
            val reservado = Vod.filmes(context, alvo.letra, reservados = true)
                .firstOrNull { it.titulo == alvo.titulo } ?: return null
            Resolvido(alvo, reservado.titulo, reservado, emptyList(), 0, reservado = true)
        }
    }

    /**
     * O filme com as fontes de todas as grafias do mesmo título.
     *
     * O acervo junta listas de origens diferentes, e o mesmo filme aparece
     * duas vezes: "Backrooms Um Nao-Lugar (2026)" com um servidor só (que
     * estava desligado) e "Backrooms: Um Não-Lugar" com vários. Abrir a
     * primeira dava "título indisponível" com o filme ali do lado. Aqui as
     * duas viram uma — a pedida primeiro, as outras como reserva.
     */
    private suspend fun juntarGrafias(context: Context, achado: Vod.Achado): Filme? {
        val chave = Vod.normalizar(Generos.semAno(achado.titulo))
        val daLetra = Vod.filmes(context, achado.letra)
        val iguais = daLetra.filter { Vod.normalizar(Generos.semAno(it.titulo)) == chave }
        val pedido = daLetra.firstOrNull { it.titulo == achado.titulo }
            ?: Vod.filme(context, achado)
        val todos = listOfNotNull(pedido) + iguais.filter { it.titulo != pedido?.titulo }
        if (todos.isEmpty()) return null
        val fontes = linkedMapOf<String, List<String>>()
        for (f in todos) for ((v, urls) in f.fontes) fontes[v] = (fontes[v].orEmpty() + urls).distinct()
        return Filme(pedido?.titulo ?: achado.titulo, fontes)
    }

    private fun agrupar(eps: List<Episodio>): List<Ep> =
        eps.groupBy { it.temporada to it.numero }
            .map { (chave, lista) ->
                val fontes = linkedMapOf<String, List<String>>()
                for (e in lista) fontes[e.versao] = (fontes[e.versao].orEmpty() + e.urls).distinct()
                Ep(chave.first, chave.second, fontes)
            }
            .sortedWith(compareBy({ it.temporada }, { it.numero }))

    /**
     * Todas as fontes, na ordem em que devem ser tentadas.
     *
     * A versão escolhida vem primeiro, com todas as suas fontes; as outras
     * versões ficam atrás, como último recurso — melhor legendado que nada.
     */
    fun ordem(fontes: Map<String, List<String>>, versao: String, soEssa: Boolean = false): List<Opcao> {
        val versoes = fontes.keys.sortedBy { if (it == versao) 0 else if (it == "dub") 1 else 2 }
        val out = mutableListOf<Opcao>()
        for (v in versoes) {
            if (soEssa && v != versao && out.isNotEmpty()) continue
            fontes[v].orEmpty().forEach { out += Opcao(v, it) }
        }
        return out
    }

    /** Combina fontes locais/404 com streams vindos de addons Stremio mantendo a versão preferida primeiro. */
    fun mesclarOpcoes(
        fontes: Map<String, List<String>>,
        opcoesAddon: List<Opcao>,
        versao: String,
        soEssa: Boolean = false,
    ): List<Opcao> {
        val locais = ordem(fontes, versao, soEssa)
        if (opcoesAddon.isEmpty()) return locais
        val vistas = linkedSetOf<String>()
        val daVersao = opcoesAddon.filter { it.versao == versao }
        val outras = if (soEssa && (locais.isNotEmpty() || daVersao.isNotEmpty())) {
            emptyList()
        } else {
            opcoesAddon.filter { it.versao != versao }
        }
        val mescladas = buildList {
            for (op in locais.filter { it.versao == versao } + daVersao +
                locais.filter { it.versao != versao } + outras) {
                val chave = op.chaveIdentidade
                if (chave.isEmpty() || vistas.add(chave)) add(op)
            }
        }
        return mescladas
    }

    /** Servidor (host sem "www.") de uma url de fonte, ou vazio. */
    fun servidor(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")?.lowercase().orEmpty()

    /**
     * Fonte do episódio novo que mais se parece com a que tocava:
     * primeiro `bingeGroup` (Stremio), depois mesmo servidor HTTP, depois mesma posição.
     */
    fun preferida(
        opcoes: List<Opcao>,
        servidor: String,
        posicao: Int,
        bingeGroup: String? = null,
    ): Int {
        val versao = opcoes.firstOrNull()?.versao ?: return 0
        val doGrupo = opcoes.indices.filter { opcoes[it].versao == versao }
        if (!bingeGroup.isNullOrBlank()) {
            doGrupo.firstOrNull { opcoes[it].bingeGroup == bingeGroup }?.let { return it }
        }
        if (servidor.isNotEmpty()) {
            doGrupo.firstOrNull { opcoes[it].url.isNotEmpty() && servidor(opcoes[it].url) == servidor }?.let { return it }
        }
        return doGrupo.getOrNull(posicao) ?: 0
    }

    fun rotulo(versao: String) = if (versao == "leg") "Legendado" else "Dublado"

    /** Episódio por onde uma série deve começar ou continuar. */
    fun ondeContinuar(context: Context, r: Resolvido): Ep? {
        if (r.episodios.isEmpty()) return null
        val ultimo = Progresso.ultimoEpisodio(context, r.nomeChave)
            ?.let { (t, e) -> r.episodio(t, e) }
        if (ultimo != null) {
            val chave = r.chave(ultimo)
            val terminou = Progresso.visto(context, chave) && Progresso.posicao(context, chave) <= 0
            return if (terminou) r.seguinte(ultimo) ?: ultimo else ultimo
        }
        return r.episodios.first()
    }
}
