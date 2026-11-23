package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * A página de um tipo — filmes, séries, animes, doramas — em fileiras.
 *
 * Como a tela inicial: o que a pessoa estava vendo daquele tipo, os destaques,
 * os lançamentos, e uma fileira por gênero. O acervo inteiro de A a Z fica na
 * última fileira, para quem quer procurar sem saber o nome.
 *
 * Com o tipo [TODOS] é o On Demand: abre com os cartões das quatro categorias
 * e mistura os tipos nas fileiras de baixo.
 */
@UnstableApi
open class PaginaActivity : TelaComMenu() {

    override val aba: Aba get() = Aba.ON_DEMAND

    companion object {
        private const val TIPO = "pagina.tipo"
        /// O On Demand: as quatro categorias juntas.
        const val TODOS = "todos"

        fun intent(context: Context, tipo: String): Intent =
            Intent(context, PaginaActivity::class.java).putExtra(TIPO, tipo)

        fun abrir(context: Context, tipo: String) = context.startActivity(intent(context, tipo))

        /// Os gêneros mais procurados primeiro; o resto entra depois, em ordem.
        private val ORDEM_GENEROS = listOf(
            "Ação", "Comédia", "Drama", "Terror", "Thriller", "Suspense", "Ficção científica",
            "Animação", "Romance", "Aventura", "Crime", "Família", "Fantasia", "Mistério",
            "Documentário", "Guerra", "Faroeste", "História", "Música", "Action & Adventure",
            "Sci-Fi & Fantasy", "Kids", "Reality", "Soap", "Talk", "War & Politics")

        /// No On Demand, só os gêneros que leem bem como "Filmes de ...".
        private val GENEROS_ON_DEMAND = listOf(
            "Ação", "Comédia", "Terror", "Ficção científica", "Romance", "Suspense",
            "Animação", "Aventura", "Drama", "Crime", "Fantasia")

        private val ANO = Regex("\\((\\d{4})\\)\\s*$")

        /// A cor de cada categoria nos cartões do On Demand.
        private const val COR_FILMES = 0xFF4FE3D0.toInt()
        private const val COR_SERIES = 0xFF7AA2FF.toInt()
        private const val COR_ANIMES = 0xFFFFB454.toInt()
        private const val COR_DORAMAS = 0xFFFF7EB6.toInt()
        private const val COR_NOVELAS = 0xFFC792EA.toInt()
        private const val COR_KIDS = 0xFF7EE787.toInt()
        private const val COR_NOVELINHAS = 0xFFFF7EB6.toInt()

        /**
         * A última página montada de cada tipo (e perfil), enquanto o app está
         * aberto: voltar ao On Demand ou trocar de aba mostra na hora o que já
         * estava pronto; a montagem nova roda por trás e só troca o que mudou.
         */
        private val guardadas = HashMap<String, List<Inicio.Fila>>()

        /** Memória apertada ou app em segundo plano: a próxima visita monta de novo. */
        fun esquecerGuardadas() = guardadas.clear()

        /// Os cartões do topo do On Demand, na ordem em que aparecem.
        private val CATEGORIAS = listOf(
            GradeActivity.FILMES, GradeActivity.SERIES, GradeActivity.ANIMES, GradeActivity.DORAMAS,
            GradeActivity.NOVELAS, GradeActivity.NOVELINHAS, GradeActivity.KIDS)
    }

    /// O tipo da página; o On Demand responde [TODOS].
    protected open fun tipoDaPagina(): String = intent.getStringExtra(TIPO) ?: GradeActivity.FILMES

    private lateinit var tipo: String
    private lateinit var filas: RecyclerView
    private val adaptador by lazy {
        Inicio.Adapter(escopo = lifecycleScope, capaDe = { t, s -> Generos.capa(t, s) },
            aoFocar = { destaque.mostrar(it) })
    }
    private lateinit var destaque: Destaque
    private var montou = false
    /// Os cartões das categorias do topo; o "Explorar" do fim usa o mesmo desenho.
    private var categoriasProntas: Inicio.Fila? = null
    /// O tipo de cada cartão do topo (categoria vazia não ganha cartão).
    private var tiposDosCartoes: List<String> = CATEGORIAS

    private val todos get() = tipo == TODOS
    private val serie get() = tipo != GradeActivity.FILMES
    private val colecao get() = tipo == GradeActivity.ANIMES || tipo == GradeActivity.DORAMAS
    /// Novelas e Kids misturam filmes e séries.
    private val categoria get() = GradeActivity.categoriaDe(tipo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tipo = tipoDaPagina()
        setContentView(R.layout.activity_pagina)
        filas = findViewById(R.id.inicioFilas)
        filas.layoutManager = Inicio.Filas(this)
        filas.adapter = adaptador
        destaque = Destaque(this, lifecycleScope, findViewById<ImageView>(R.id.inicioFundo),
            findViewById(R.id.inicioTitulo), findViewById(R.id.inicioMeta), findViewById(R.id.inicioSinopse)) { it.aoEscolher() }
        val nome = getString(when (tipo) {
            TODOS -> R.string.menu_on_demand
            GradeActivity.SERIES -> R.string.vod_series
            GradeActivity.ANIMES -> R.string.vod_animes
            GradeActivity.DORAMAS -> R.string.vod_doramas
            GradeActivity.NOVELAS -> R.string.vod_novelas
            GradeActivity.KIDS -> R.string.vod_kids
            else -> R.string.vod_filmes
        })
        // O menu do topo já diz em que página se está.
        findViewById<TextView>(R.id.paginaNome).visibility = android.view.View.GONE
        destaque.padrao(nome, getString(R.string.vod_carregando))
        // Depois que a janela já está na tela: antes disso o foco pedido no
        // primeiro cartão era trocado pelo avatar do perfil.
        guardadas[chaveDaPagina()]?.let { guardada -> filas.post { if (!montou) mostrar(guardada) } }
        lifecycleScope.launch(semDerrubar) {
            // Abre com o que já está guardado; depois confere a novidade e só
            // remonta a página se o arquivo mudou.
            montar()
            if (Destaques.novidades(this@PaginaActivity) != null) montar()
        }
    }

    override fun onRestart() {
        super.onRestart()
        // Voltar de um filme não refaz a página (era o que a deixava lenta e
        // perdia o lugar): só o "continuar assistindo", que muda depois de assistir.
        val atual = ultimaLista
        if (atual == null) { lifecycleScope.launch(semDerrubar) { montar() }; return }
        val titulo = getString(R.string.vod_continuar)
        val novo = continuar()
        val i = atual.indexOfFirst { it.titulo == titulo }
        val lista = atual.toMutableList()
        when {
            i >= 0 && novo != null -> lista[i] = novo
            i >= 0 -> lista.removeAt(i)
            novo != null -> lista.add(if (todos) minOf(1, lista.size) else 0, novo)
            else -> return
        }
        mostrar(lista)
    }

    /**
     * Tira do "continuar assistindo" e refaz a página aberta agora: o cartão pode
     * ter vindo da memória (guardadas), montado por uma tela que já fechou.
     */
    private fun tirarDoContinuar(a: Progresso.Andamento) {
        val tela = (atual() as? PaginaActivity) ?: this
        Progresso.esquecer(tela, a)
        tela.lifecycleScope.launch(semDerrubar) { tela.montar() }
    }

    override fun onDestroy() {
        // Os cartões guardados seguram esta tela até a próxima visita ao mesmo
        // tipo de página: sem as vistas e as imagens, ela fica leve (só os dados).
        filas.adapter = null
        filas.recycledViewPool.clear()
        findViewById<ImageView>(R.id.inicioFundo)?.setImageDrawable(null)
        super.onDestroy()
    }

    /** O que está na tela, para a volta não refazer tudo. */
    private var ultimaLista: List<Inicio.Fila>? = null

    private data class Candidato(val alvo: Alvo, val ano: Int, val capa: String, val generos: Int)

    /** A letra de cada tipo em destaques.txt. */
    private fun letraDe(t: String) = when (t) {
        GradeActivity.FILMES -> 'f'; GradeActivity.SERIES -> 's'
        GradeActivity.ANIMES -> 'a'; else -> 'd'
    }

    private suspend fun montar() {
        Generos.carregar(this)
        CategoriasVod.carregar(this)
        if (todos) Novelinhas.carregar(this)
        val lista = mutableListOf<Inicio.Fila>()
        val destaques = Destaques.filas(this)

        // Sem endereço (ou com endereço que não carregou): destrava via Addons + TMDB quando houver addons ativos.
        if (destaques.isEmpty() && Vod.entradas(this).isEmpty()) {
            if (AddonsStore.temAtivo()) {
                esconderVazio()
                continuar()?.let { lista += it }
                if (!todos) {
                    lista += Inicio.Fila(getString(R.string.pagina_explorar), listOf(
                        Inicio.Cartao(getString(R.string.pagina_todos_az), "", "A–Z") {
                            GradeActivity.abrir(atual(), tipo)
                        }
                    ))
                } else {
                    lista += Inicio.Fila(getString(R.string.pagina_explorar), listOf(
                        Inicio.Cartao(getString(R.string.vod_filmes), "", "F") {
                            GradeActivity.abrir(atual(), GradeActivity.FILMES)
                        },
                        Inicio.Cartao(getString(R.string.vod_series), "", "S") {
                            GradeActivity.abrir(atual(), GradeActivity.SERIES)
                        },
                    ), Inicio.Tipo.SECAO)
                }
                val filtro = if (todos) null else serie
                lista += AddonsService.carregarFilasCatalogo(this, filtroSerie = filtro)
                mostrar(lista)
                return
            }
            val msg = getString(if (Enderecos.filmes == null) R.string.vazio_filmes else R.string.vazio_falhou)
            destaque.padrao(getString(R.string.menu_on_demand), msg)
            mostrar(emptyList())
            mostrarVazio(msg)
            return
        }
        esconderVazio()

        // Primeira vez nesta abertura do app: a página aparece já com o que está
        // à mão (destaques e as contagens da última visita) e o acervo inteiro,
        // que leva segundos para ler, completa por trás.
        if (todos && !montou) contagensGuardadas()?.let { quantos ->
            val rapida = mutableListOf<Inicio.Fila>()
            rapida += categorias(destaques, quantos, emptyMap()).also { categoriasProntas = it }
            continuar()?.let { rapida += it }
            rapida += filasDosDestaques(destaques)
            mostrar(rapida)
        }

        // As categorias entram já completas: redesenhar a primeira fileira depois
        // de a tela abrir tiraria o foco de quem já está nela.
        val colecoes = if (todos) withContext(Dispatchers.Default) {
            mapOf(GradeActivity.ANIMES to daColecao(GradeActivity.ANIMES),
                GradeActivity.DORAMAS to daColecao(GradeActivity.DORAMAS),
                GradeActivity.NOVELAS to daCategoria(CategoriasVod.NOVELA),
                GradeActivity.KIDS to daCategoria(CategoriasVod.KIDS))
        } else emptyMap()
        if (todos) {
            val quantos = withContext(Dispatchers.Default) { contagens() }
            guardarContagens(quantos)
            lista += categorias(destaques, quantos, colecoes).also { categoriasProntas = it }
        }
        continuar()?.let { lista += it }

        lista += filasDosDestaques(destaques)
        // Numa página de um tipo, o acervo inteiro com filtros (gênero, ano,
        // idade, nota) logo na segunda fileira, sem precisar descer a página toda.
        if (!todos) lista.add(minOf(1, lista.size), Inicio.Fila(getString(R.string.pagina_explorar), listOf(
            Inicio.Cartao(getString(R.string.pagina_todos_az), "", "A–Z") {
                GradeActivity.abrir(atual(), tipo)
            })))
        if (!montou) mostrar(lista)

        if (todos) montarTodos(lista) else montarTipo(lista)
        if (AddonsStore.temCatalogoAtivo()) {
            lista += AddonsService.carregarFilasCatalogo(this, filtroSerie = if (todos) null else serie)
        }
        mostrar(lista)
    }

    /** Uma página de um tipo só: lançamentos, gêneros e o A a Z. */
    private suspend fun montarTipo(lista: MutableList<Inicio.Fila>) {
        // Lançamentos e gêneros saem do acervo inteiro, fora da linha principal.
        val candidatos = withContext(Dispatchers.Default) {
            when {
                colecao -> daColecao(tipo).let { c ->
                    val nomes = c.map { Vod.normalizar(Generos.semAno(it.alvo.titulo)) }.toHashSet()
                    c + daCategoria(categoria!!).filter { Vod.normalizar(Generos.semAno(it.alvo.titulo)) !in nomes }
                }
                categoria != null -> daCategoria(categoria!!)
                else -> doAcervo(serie)
            }
        }
        // Novelas e Kids: os mais populares primeiro, antes dos lançamentos.
        if (categoria != null && !colecao) {
            InfoDosTitulos.carregar(this)
            candidatos.sortedByDescending { InfoDosTitulos.de(it.alvo.titulo, it.alvo.serie)?.popularidade ?: 0 }
                .take(30).takeIf { it.size >= 4 }
                ?.let { lista += Inicio.Fila(getString(R.string.grade_populares), it.map { c -> cartao(c.alvo, c.capa) }) }
        }
        lancamentos(candidatos)?.let { lista += Inicio.Fila(getString(R.string.pagina_lancamentos), it) }

        val porGenero = withContext(Dispatchers.Default) {
            val generos = Generos.todos.sortedBy { g -> ORDEM_GENEROS.indexOf(g).let { if (it < 0) 99 else it } }
            generos.mapNotNull { g -> porGenero(candidatos, g)?.let { g to it } }
        }
        for ((g, itens) in porGenero) {
            lista += Inicio.Fila(g, itens.map { cartao(it.alvo, it.capa) } + verMais(tipo, g))
        }
    }

    /**
     * O On Demand: depois das categorias e dos destaques (que já trazem em
     * alta, lançamentos, animes e doramas), os gêneros de filme e o A a Z de
     * cada categoria no fim.
     */
    private suspend fun montarTodos(lista: MutableList<Inicio.Fila>) {
        val filmes = withContext(Dispatchers.Default) { doAcervo(serie = false) }

        val porGenero = withContext(Dispatchers.Default) {
            GENEROS_ON_DEMAND.filter { it in Generos.todos }.mapNotNull { g -> porGenero(filmes, g)?.let { g to it } }
        }
        for ((g, itens) in porGenero) {
            lista += Inicio.Fila(getString(R.string.ondemand_genero_filmes, g.lowercase(Locale.ROOT)),
                itens.map { cartao(it.alvo, it.capa) } + verMais(GradeActivity.FILMES, g))
        }

        // O fim da página: o acervo inteiro de cada categoria, de A a Z, com o
        // mesmo cartão das categorias do topo — só a ação muda.
        categoriasProntas?.let { topo ->
            lista += Inicio.Fila(getString(R.string.pagina_explorar), topo.cartoes.zip(tiposDosCartoes).map { (c, t) ->
                c.copy(titulo = getString(R.string.ondemand_az, c.titulo)) {
                    if (t == GradeActivity.NOVELINHAS) NovelinhasActivity.abrir(atual()) else GradeActivity.abrir(atual(), t)
                }
            }, Inicio.Tipo.SECAO)
        }
    }

    /** As fileiras do repositório: no On Demand todas, numa página só as do tipo dela. */
    private fun filasDosDestaques(destaques: List<Destaques.Fila>): List<Inicio.Fila> = destaques.mapNotNull { fila ->
        val itens = when {
            todos -> fila.itens
            categoria != null && !colecao -> fila.itens.filter { CategoriasVod.tem(it.titulo, it.serie, categoria!!) }
            colecao -> fila.itens.filter { it.tipo == letraDe(tipo) || (it.tipo == 's' && CategoriasVod.tem(it.titulo, true, categoria!!)) }
            else -> fila.itens.filter { it.tipo == letraDe(tipo) }
        }
        if (itens.size < 4) null else Inicio.Fila(fila.titulo, itens.map { item ->
            cartao(Alvo(item.titulo, item.serie, item.letra, item.ano,
                colecao = if (item.daColecao) item.colecao else ""), item.capa, item.trailer)
        })
    }

    /** As contagens da última visita (por lista), para a primeira tela não esperar o acervo. */
    private fun contagensGuardadas(): Map<String, Int>? {
        val texto = getSharedPreferences("ondemand", MODE_PRIVATE)
            .getString("contagens@" + Enderecos.filmes.orEmpty(), null) ?: return null
        return texto.split(";").mapNotNull { par ->
            val (k, v) = par.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
            v.toIntOrNull()?.let { k to it }
        }.toMap().takeIf { it.isNotEmpty() }
    }

    private fun guardarContagens(quantos: Map<String, Int>) {
        getSharedPreferences("ondemand", MODE_PRIVATE).edit()
            .putString("contagens@" + Enderecos.filmes.orEmpty(), quantos.entries.joinToString(";") { "${it.key}=${it.value}" })
            .apply()
    }

    /** Quantos títulos cada categoria tem; o índice já fica em memória para a busca. */
    private suspend fun contagens(): Map<String, Int> {
        val entradas = Vod.entradas(this)
        return mapOf(
            GradeActivity.FILMES to entradas.count { !it.serie },
            GradeActivity.SERIES to entradas.count { it.serie },
            GradeActivity.ANIMES to comAcervo(GradeActivity.ANIMES, CategoriasVod.ANIME, entradas),
            GradeActivity.DORAMAS to comAcervo(GradeActivity.DORAMAS, CategoriasVod.DORAMA, entradas),
            GradeActivity.NOVELAS to entradas.count { CategoriasVod.tem(it.achado.titulo, it.serie, CategoriasVod.NOVELA) },
            GradeActivity.KIDS to entradas.count { CategoriasVod.tem(it.achado.titulo, it.serie, CategoriasVod.KIDS) },
            GradeActivity.NOVELINHAS to Novelinhas.lista.size,
        )
    }

    /**
     * Os quatro cartões grandes do topo do On Demand. Cada um abre a página da
     * categoria; no foco, o destaque mostra o que ela tem e o fundo de um título
     * em alta dela. As capas da colagem vêm dos destaques (já resolvidas, sem
     * ir ao TMDB) e, faltando, do acervo.
     */
    private fun categorias(
        destaques: List<Destaques.Fila>,
        contagens: Map<String, Int>,
        acervo: Map<String, List<Candidato>>,
    ): Inicio.Fila {
        val numero = java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR"))
        val itens = listOf(
            Triple(GradeActivity.FILMES, R.string.vod_filmes, R.string.ondemand_filmes_sobre),
            Triple(GradeActivity.SERIES, R.string.vod_series, R.string.ondemand_series_sobre),
            Triple(GradeActivity.ANIMES, R.string.vod_animes, R.string.ondemand_animes_sobre),
            Triple(GradeActivity.DORAMAS, R.string.vod_doramas, R.string.ondemand_doramas_sobre),
            Triple(GradeActivity.NOVELAS, R.string.vod_novelas, R.string.ondemand_novelas_sobre),
            Triple(GradeActivity.NOVELINHAS, R.string.vod_novelinhas, R.string.ondemand_novelinhas_sobre),
            Triple(GradeActivity.KIDS, R.string.vod_kids, R.string.ondemand_kids_sobre),
        ).filter { (t, _, _) ->
            // Categoria vazia (lista sem o arquivo dela) não ganha cartão.
            t in listOf(GradeActivity.FILMES, GradeActivity.SERIES, GradeActivity.ANIMES, GradeActivity.DORAMAS) ||
                (contagens[t] ?: 0) > 0
        }
        tiposDosCartoes = itens.map { it.first }
        return Inicio.Fila(getString(R.string.ondemand_categorias), itens.map { (t, nome, sobre) ->
            val emAlta = destaques.flatMap { it.itens }.filter { d ->
                d.capa.isNotEmpty() && when (t) {
                    GradeActivity.NOVELAS -> CategoriasVod.tem(d.titulo, d.serie, CategoriasVod.NOVELA)
                    GradeActivity.KIDS -> CategoriasVod.tem(d.titulo, d.serie, CategoriasVod.KIDS)
                    GradeActivity.NOVELINHAS -> false
                    else -> d.tipo == letraDe(t)
                }
            }.distinctBy { it.titulo }
            val colagem = (emAlta.map { it.capa } + acervo[t].orEmpty().take(8).map { it.capa } +
                (if (t == GradeActivity.NOVELINHAS) Novelinhas.lista.take(4).map { it.capa } else emptyList()))
                .distinct().take(4)
            val primeiro = emAlta.firstOrNull()?.let {
                Alvo(it.titulo, it.serie, it.letra, it.ano, colecao = if (it.daColecao) it.colecao else "")
            } ?: acervo[t]?.firstOrNull()?.alvo
            val quantos = contagens[t]?.takeIf { it > 0 }
                ?.let { getString(R.string.ondemand_titulos, numero.format(it)) }
            Inicio.Cartao(
                titulo = getString(nome), capa = "", inicial = getString(nome).take(1),
                subtitulo = quantos,
                colagem = colagem,
                icone = when (t) {
                    GradeActivity.FILMES -> R.drawable.ic_movie
                    GradeActivity.SERIES -> R.drawable.ic_episodes
                    GradeActivity.ANIMES -> R.drawable.ic_auto_awesome
                    GradeActivity.NOVELAS -> R.drawable.ic_theater_comedy
                    GradeActivity.KIDS -> R.drawable.ic_child_care
                    GradeActivity.NOVELINHAS -> R.drawable.ic_celular_em_pe
                    else -> R.drawable.ic_favorite
                },
                cor = when (t) {
                    GradeActivity.FILMES -> COR_FILMES
                    GradeActivity.SERIES -> COR_SERIES
                    GradeActivity.ANIMES -> COR_ANIMES
                    GradeActivity.NOVELAS -> COR_NOVELAS
                    GradeActivity.KIDS -> COR_KIDS
                    GradeActivity.NOVELINHAS -> COR_NOVELINHAS
                    else -> COR_DORAMAS
                },
                meta = quantos.orEmpty(),
                sinopse = getString(sobre),
                fundoDe = primeiro,
            ) { if (t == GradeActivity.NOVELINHAS) NovelinhasActivity.abrir(atual()) else PaginaActivity.abrir(atual(), t) }
        }, Inicio.Tipo.SECAO)
    }

    private fun chaveDaPagina() = "$tipo@${Perfis.idAtivo()}"

    /**
     * A tela aberta na hora do clique. Os cartões ficam guardados de uma visita para
     * a outra (guardadas): a ação não pode presa à tela que os montou, já fechada.
     */
    private fun atual(): android.app.Activity =
        (SaimoApp.atividadeAtual as? PaginaActivity)?.takeIf { !it.isFinishing && !it.isDestroyed } ?: this

    /** O que a pessoa vê de uma página (para não redesenhar à toa a mesma coisa). */
    private fun assinatura(lista: List<Inicio.Fila>): String =
        lista.joinToString("|") { f -> f.titulo + ":" + f.cartoes.joinToString(",") { "${it.titulo}/${it.progresso}" } }

    private fun mostrar(lista: List<Inicio.Fila>) {
        if (isFinishing || isDestroyed) return
        // Igual ao que já está na tela (vinda da memória): não redesenha, não tira o foco.
        val atual = ultimaLista
        if (montou && atual != null && assinatura(atual) == assinatura(lista)) {
            guardadas[chaveDaPagina()] = lista.toList()
            return
        }
        ultimaLista = lista.toList()
        guardadas[chaveDaPagina()] = lista.toList()
        adaptador.trocar(lista.toList())
        if (!montou) {
            montou = true
            focarQuandoPronto {
                filas.findViewHolderForAdapterPosition(0)?.itemView
                    ?.findViewById<RecyclerView>(R.id.filaCapas)?.getChildAt(0)
            }
        }
    }

    private val esteAno get() = Calendar.getInstance().get(Calendar.YEAR)

    /** Do ano passado para cá, o mais novo primeiro. */
    private fun lancamentos(candidatos: List<Candidato>): List<Inicio.Cartao>? =
        candidatos.filter { it.ano >= esteAno - 1 }.sortedByDescending { it.ano }.take(30)
            .takeIf { it.size >= 4 }?.map { cartao(it.alvo, it.capa) }

    private fun porGenero(candidatos: List<Candidato>, genero: String): List<Candidato>? {
        val bit = Generos.bit(genero)
        return candidatos.filter { it.generos and bit != 0 }
            .sortedByDescending { it.ano }.take(25)
            .takeIf { it.size >= 6 }
    }

    /** Só títulos com capa: fileira de gênero cheia de quadrados vazios não convida ninguém. */
    private suspend fun daColecao(colecaoTipo: String): List<Candidato> =
        Vod.colecao(this, colecaoTipo).mapNotNull { c ->
            val alvo = Alvo(c.titulo, true, "", c.ano, colecaoTipo, c.tmdbId.toIntOrNull() ?: 0)
            val capa = Generos.capa(c.titulo, true) ?: return@mapNotNull null
            Candidato(alvo, anoDe(c.titulo, c.ano), capa, Generos.bits(c.titulo, true))
        }

    /** A coleção mais os títulos do acervo da categoria que ela não tem. */
    private suspend fun comAcervo(colecaoTipo: String, cat: String, entradas: List<Vod.Entrada>): Int {
        val colecao = Vod.colecao(this, colecaoTipo)
        val nomes = colecao.map { Vod.normalizar(Generos.semAno(it.titulo)) }.toHashSet()
        return colecao.size + entradas.count {
            CategoriasVod.tem(it.achado.titulo, it.serie, cat) && Vod.normalizar(Generos.semAno(it.achado.titulo)) !in nomes
        }
    }

    /** Filmes e séries do acervo marcados com a categoria, com capa. */
    private suspend fun daCategoria(cat: String): List<Candidato> =
        Vod.entradas(this).asSequence().filter { CategoriasVod.tem(it.achado.titulo, it.serie, cat) }.mapNotNull { e ->
            val a = e.achado
            val capa = Generos.capa(a.titulo, a.serie) ?: return@mapNotNull null
            Candidato(Alvo(a.titulo, a.serie, a.letra, a.ano), anoDe(a.titulo, a.ano), capa, Generos.bits(a.titulo, a.serie))
        }.toList()

    private suspend fun doAcervo(serie: Boolean): List<Candidato> =
        Vod.entradas(this).asSequence().filter { it.serie == serie }.mapNotNull { e ->
            val a = e.achado
            val capa = Generos.capa(a.titulo, a.serie) ?: return@mapNotNull null
            Candidato(Alvo(a.titulo, a.serie, a.letra, a.ano), anoDe(a.titulo, a.ano), capa, Generos.bits(a.titulo, a.serie))
        }.toList()

    private val anoMaximo = Calendar.getInstance().get(Calendar.YEAR) + 1

    /** Ano de lançamento; "2050" no meio do nome do filme não é ano. */
    private fun anoDe(titulo: String, ano: String): Int {
        val v = ano.toIntOrNull() ?: ANO.find(titulo)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (v in 1900..anoMaximo) v else 0
    }

    private fun continuar(): Inicio.Fila? {
        val itens = Progresso.emAndamento(this).filter { a ->
            val e = a.endereco ?: return@filter false
            when (tipo) {
                TODOS -> true
                GradeActivity.FILMES -> !e.serie
                GradeActivity.SERIES -> e.serie && e.colecao.isEmpty()
                GradeActivity.NOVELAS -> CategoriasVod.tem(e.alvo.titulo, e.serie, CategoriasVod.NOVELA)
                GradeActivity.KIDS -> CategoriasVod.tem(e.alvo.titulo, e.serie, CategoriasVod.KIDS)
                else -> e.colecao == tipo || (e.colecao.isEmpty() && categoria?.let { CategoriasVod.tem(e.alvo.titulo, e.serie, it) } == true)
            }
        }
        if (itens.isEmpty()) return null
        return Inicio.Fila(getString(R.string.vod_continuar), itens.map { a ->
            val alvo = a.endereco!!.alvo
            Inicio.Cartao(
                a.rotulo, "", a.titulo.take(1).uppercase(), progresso = a.fracao,
                procurarCapa = a.serie, nomeDaCapa = a.titulo, alvo = alvo,
                aoMenu = {
                    Painel.mostrar(atual(), a.rotulo, listOf(
                        Painel.Item(getString(R.string.inicio_abrir_ficha), icone = R.drawable.ic_info) { FichaActivity.abrir(atual(), alvo) },
                        Painel.Item(getString(R.string.inicio_remover_continuar)) { tirarDoContinuar(a) }))
                },
                aoRemover = { tirarDoContinuar(a) },
            ) {
                val (t, e) = if (a.terminado) a.temporada to a.episodio + 1 else a.temporada to a.episodio
                PlayerActivity.abrir(atual(), alvo, t, e)
            }
        })
    }

    private fun cartao(alvo: Alvo, capa: String, trailer: String = "") = Inicio.Cartao(
        titulo = alvo.nomeCompleto, capa = capa, inicial = alvo.titulo.take(1).uppercase(),
        trailer = trailer.ifEmpty { null },
        progresso = if (alvo.serie) null else Progresso.fracao(this, Progresso.chaveFilme(alvo.titulo)),
        alvo = alvo,
        aoMenu = if (alvo.colecao.isNotEmpty()) null else ({
            val marcado = VodFavoritos.alternar(atual(), VodFavoritos.Item(alvo.titulo, alvo.serie, alvo.letra, alvo.ano))
            Toast.makeText(atual(), if (marcado) R.string.vod_ficha_favorito else R.string.inicio_desfavoritar,
                Toast.LENGTH_SHORT).show()
        }),
    ) { FichaActivity.abrir(atual(), alvo) }

    private fun verMais(tipoDaGrade: String, genero: String) = Inicio.Cartao(getString(R.string.pagina_ver_mais), "", "+") {
        GradeActivity.abrir(atual(), tipoDaGrade, genero)
    }
}
