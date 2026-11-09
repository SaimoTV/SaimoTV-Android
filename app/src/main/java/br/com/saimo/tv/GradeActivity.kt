package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Um tipo inteiro em capas: filmes, séries, animes, doramas ou favoritos.
 *
 * Antes era letra → lista de nomes → título. Agora são capas em colunas, com
 * as letras numa coluna à esquerda para pular direto (vinte mil filmes não se
 * descem com a seta), o gênero e a ordem no alto.
 */
@UnstableApi
class GradeActivity : TelaComMenu() {

    override val aba: Aba get() = when (intent.getStringExtra(TIPO)) {
        FAVORITOS -> Aba.FAVORITOS
        EXTRAS -> Aba.EXTRAS
        SERIES -> Aba.ON_DEMAND
        ANIMES -> Aba.ON_DEMAND
        DORAMAS -> Aba.ON_DEMAND
        else -> Aba.ON_DEMAND
    }


    companion object {
        const val FILMES = "filmes"
        const val SERIES = "series"
        const val ANIMES = "animes"
        const val DORAMAS = "doramas"
        const val FAVORITOS = "favoritos"
        const val EXTRAS = "extras"
        private const val TIPO = "grade.tipo"
        private const val GENERO = "grade.genero"

        fun intent(context: Context, tipo: String, genero: String = ""): Intent =
            Intent(context, GradeActivity::class.java).putExtra(TIPO, tipo).putExtra(GENERO, genero)

        fun abrir(context: Context, tipo: String, genero: String = "") =
            context.startActivity(intent(context, tipo, genero))

        private val ANO = Regex("\\((\\d{4})\\)\\s*$")
    }

    private data class Item(val alvo: Alvo, val chave: String, val ano: Int) {
        /// Título em outro alfabeto (grego, cirílico, hindi) normaliza para
        /// vazio: vai para o fim, sob "…", e não para o começo da lista.
        val letra: String = chave.firstOrNull()?.let {
            when { it in 'a'..'z' -> it.uppercase(); it.isDigit() -> "#"; else -> "…" }
        } ?: "…"
        val ordem: String = when (letra) { "#" -> "0$chave"; "…" -> "~" + alvo.titulo; else -> "1$chave" }
    }

    private lateinit var tipo: String
    private lateinit var capas: RecyclerView
    private lateinit var letras: RecyclerView
    private lateinit var estado: TextView
    private var todos: List<Item> = emptyList()
    private var vistos: List<Item> = emptyList()
    private var genero = ""
    private enum class Ordem { AZ, NOVOS, MELHORES, POPULARES }
    private var ordem = Ordem.NOVOS
    private val novosPrimeiro: Boolean get() = ordem != Ordem.AZ
    /// Filtros do On Demand (ver Detalhes): faixa de anos, idade máxima e nota mínima.
    private var anos: IntRange? = null
    private var rotuloAnos = ""
    private var idadeMax: Int? = null
    private var notaMin = 0
    private val adaptador = Capas()
    private var colunas = 5
    private val adaptadorLetras = Letras()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tipo = intent.getStringExtra(TIPO) ?: FILMES
        genero = intent.getStringExtra(GENERO).orEmpty()
        // Sem o código não há Extras — nem por um atalho antigo.
        if (tipo == EXTRAS && !Unlock.unlocked) { finish(); return }
        setContentView(R.layout.activity_grade)
        if (Vr.ativo) findViewById<TextView>(R.id.gradeDica)?.setText(R.string.vr_grade_dica)
        capas = findViewById(R.id.gradeCapas)
        letras = findViewById(R.id.gradeLetras)
        estado = findViewById(R.id.gradeEstado)
        findViewById<TextView>(R.id.gradeTitulo).text = getString(when (tipo) {
            SERIES -> R.string.vod_series
            ANIMES -> R.string.vod_animes
            DORAMAS -> R.string.vod_doramas
            FAVORITOS -> R.string.vod_favoritos
            EXTRAS -> R.string.vod_extras
            else -> R.string.vod_filmes
        })
        val colunas = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 140) / 150)
            .toInt().coerceIn(4, 8)
        this.colunas = colunas
        capas.layoutManager = GridLayoutManager(this, colunas)
        capas.adapter = adaptador
        capas.setHasFixedSize(true)
        capas.setItemViewCacheSize(colunas * 2)
        letras.layoutManager = LinearLayoutManager(this)
        letras.adapter = adaptadorLetras

        findViewById<TextView>(R.id.gradeGenero).setOnClickListener { escolherGenero() }
        findViewById<TextView>(R.id.gradeAno).setOnClickListener { escolherAno() }
        findViewById<TextView>(R.id.gradeIdade).setOnClickListener { escolherIdade() }
        findViewById<TextView>(R.id.gradeNota).setOnClickListener { escolherNota() }
        findViewById<TextView>(R.id.gradeOrdem).setOnClickListener {
            val opcoes = mutableListOf(Ordem.NOVOS to R.string.grade_novos, Ordem.AZ to R.string.grade_az)
            if (InfoDosTitulos.prontos) {
                opcoes += Ordem.MELHORES to R.string.grade_melhores
                opcoes += Ordem.POPULARES to R.string.grade_populares
            }
            Painel.mostrar(this, getString(R.string.grade_ordem, "").trim(' ', ':'), opcoes.map { (o, r) ->
                Painel.Item(getString(r), marcado = ordem == o) { ordem = o; aplicar() }
            })
        }
        atualizarBotoes()
        lifecycleScope.launch(semDerrubar) { carregar() }
    }

    override fun onResume() {
        super.onResume()
        // Favoritos mudam de dentro da ficha; a grade de favoritos se refaz.
        if (tipo == FAVORITOS && todos.isNotEmpty()) lifecycleScope.launch(semDerrubar) { carregar() }
        else adaptador.notifyDataSetChanged()
    }

    private suspend fun carregar() {
        Generos.carregar(this)
        InfoDosTitulos.carregar(this)
        todos = withContext(Dispatchers.Default) {
            when (tipo) {
                FILMES, SERIES -> Vod.entradas(this@GradeActivity).filter { it.serie == (tipo == SERIES) && Generos.capa(it.achado.titulo, it.achado.serie) != null }
                    .map { e ->
                        val a = e.achado
                        Item(Alvo(a.titulo, a.serie, a.letra, a.ano), e.chave, anoDe(a.titulo, a.ano))
                    }
                ANIMES, DORAMAS -> Vod.colecao(this@GradeActivity, tipo).filter { Generos.capa(it.titulo, true) != null }.map { c ->
                    Item(Alvo(c.titulo, true, "", c.ano, tipo, c.tmdbId.toIntOrNull() ?: 0),
                        Vod.normalizar(c.titulo), anoDe(c.titulo, c.ano))
                }
                EXTRAS -> (listOf("#") + ('A'..'Z').map { it.toString() }).flatMap { letra ->
                    Vod.filmes(this@GradeActivity, letra, reservados = true).map { f ->
                        Item(Alvo(f.titulo, false, letra, ""), Vod.normalizar(f.titulo), anoDe(f.titulo, ""))
                    }
                }
                else -> VodFavoritos.lista(this@GradeActivity).map { f ->
                    Item(Alvo(f.titulo, f.serie, f.letra, f.ano), Vod.normalizar(f.titulo), anoDe(f.titulo, f.ano))
                }
            }
        }
        aplicar()
    }

    private fun anoDe(titulo: String, ano: String) =
        ano.toIntOrNull() ?: ANO.find(titulo)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun aplicar() {
        var lista = if (genero.isEmpty()) todos else todos.filter { Generos.tem(it.alvo.titulo, it.alvo.serie, genero) }
        if (anos != null || idadeMax != null || notaMin > 0) {
            lista = lista.filter { item ->
                val d = InfoDosTitulos.de(item.alvo.titulo, item.alvo.serie)
                val ano = d?.ano?.takeIf { it > 0 } ?: item.ano
                (anos?.let { ano in it } ?: true) &&
                    (idadeMax?.let { max -> d != null && d.idade in 0..max } ?: true) &&
                    (notaMin <= 0 || (d != null && d.nota >= notaMin))
            }
        }
        // O mesmo filme em duas pastas ("A Guerra dos Mundos" e "A Guerra dos
        // Mundos (2025)") aparece uma vez só. Nos favoritos fica como foi marcado.
        if (tipo != FAVORITOS) {
            val vistosAqui = HashSet<String>()
            lista = lista.filter { item ->
                val d = InfoDosTitulos.de(item.alvo.titulo, item.alvo.serie)
                val ano = d?.ano?.takeIf { it > 0 } ?: item.ano
                val base = Vod.normalizar(ANO.replace(item.alvo.titulo, ""))
                vistosAqui.add("${item.alvo.serie}|$base|$ano")
            }
        }
        lista = when (ordem) {
            Ordem.AZ -> lista.sortedBy { it.ordem }
            Ordem.NOVOS -> lista.sortedWith(compareByDescending<Item> {
                InfoDosTitulos.de(it.alvo.titulo, it.alvo.serie)?.ano?.takeIf { a -> a > 0 } ?: it.ano }.thenBy { it.ordem })
            Ordem.MELHORES -> lista.sortedWith(compareByDescending<Item> {
                InfoDosTitulos.de(it.alvo.titulo, it.alvo.serie)?.nota ?: 0f }.thenBy { it.ordem })
            Ordem.POPULARES -> lista.sortedWith(compareByDescending<Item> {
                InfoDosTitulos.de(it.alvo.titulo, it.alvo.serie)?.popularidade ?: 0 }.thenBy { it.ordem })
        }
        vistos = lista
        adaptador.notifyDataSetChanged()
        findViewById<TextView>(R.id.gradeContagem).text =
            // Em português "0 títulos" é plural; o milhar leva ponto (4.344).
            resources.getQuantityString(R.plurals.vod_titulos, if (lista.size == 1) 1 else 2,
                java.text.NumberFormat.getIntegerInstance(Locale("pt", "BR")).format(lista.size))
        // Acervo sem dados (sem endereço, ou endereço que não carregou).
        val semAcervo = todos.isEmpty() && tipo != FAVORITOS && tipo != EXTRAS
        if (semAcervo) {
            mostrarVazio(getString(if (Enderecos.filmes == null) R.string.vazio_filmes else R.string.vazio_falhou))
        } else esconderVazio()
        estado.visibility = if (lista.isEmpty() && !semAcervo) View.VISIBLE else View.GONE
        estado.text = if (tipo == FAVORITOS) getString(if (Vr.ativo) R.string.vr_grade_vazio_favoritos else R.string.grade_vazio_favoritos) else getString(R.string.vod_vazio)
        adaptadorLetras.letras = if (novosPrimeiro) emptyList() else lista.map { it.letra }.distinct()
        letras.visibility = if (adaptadorLetras.letras.isEmpty()) View.GONE else View.VISIBLE
        atualizarBotoes()
        capas.scrollToPosition(0)
        focarQuandoPronto { capas.getChildAt(0) }
    }

    private fun atualizarBotoes() {
        findViewById<TextView>(R.id.gradeGenero).apply {
            text = getString(R.string.grade_genero, genero.ifEmpty { getString(R.string.vod_todos) })
            Icones.fim(this, R.drawable.ic_dropdown)
            visibility = if (Generos.todos.isEmpty() || tipo == FAVORITOS || tipo == EXTRAS) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.gradeOrdem).apply {
            text = getString(R.string.grade_ordem, getString(when (ordem) {
                Ordem.AZ -> R.string.grade_az
                Ordem.NOVOS -> R.string.grade_novos
                Ordem.MELHORES -> R.string.grade_melhores
                Ordem.POPULARES -> R.string.grade_populares
            }))
            Icones.fim(this, R.drawable.ic_dropdown)
        }
        val comFiltros = InfoDosTitulos.prontos && tipo != FAVORITOS && tipo != EXTRAS
        findViewById<TextView>(R.id.gradeAno).apply {
            text = getString(R.string.grade_ano, rotuloAnos.ifEmpty { getString(R.string.vod_todos) })
            Icones.fim(this, R.drawable.ic_dropdown)
            visibility = if (comFiltros) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.gradeIdade).apply {
            text = getString(R.string.grade_idade, when (val m = idadeMax) {
                null -> getString(R.string.vod_todos)
                0 -> getString(R.string.grade_livre)
                else -> getString(R.string.grade_ate_idade, m)
            })
            Icones.fim(this, R.drawable.ic_dropdown)
            visibility = if (comFiltros) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.gradeNota).apply {
            text = getString(R.string.grade_nota, if (notaMin > 0) getString(R.string.grade_nota_min, notaMin) else getString(R.string.grade_qualquer))
            Icones.fim(this, R.drawable.ic_dropdown)
            visibility = if (comFiltros) View.VISIBLE else View.GONE
        }
    }

    private fun escolherAno() {
        val atual = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val opcoes = mutableListOf<Pair<String, IntRange?>>(getString(R.string.vod_todos) to null)
        for (a in atual downTo atual - 2) opcoes += a.toString() to a..a
        val decadaAtual = atual / 10 * 10
        opcoes += "$decadaAtual–${atual - 3}" to decadaAtual..(atual - 3)
        for (d in (decadaAtual - 10) downTo 1990 step 10) opcoes += getString(R.string.grade_decada, d) to d..(d + 9)
        opcoes += getString(R.string.grade_antes_1990) to 1..1989
        Painel.mostrar(this, getString(R.string.grade_ano, "").trim(' ', ':'), opcoes.filter { it.second == null || !it.second!!.isEmpty() }.map { (rotulo, faixa) ->
            Painel.Item(rotulo, marcado = anos == faixa) {
                anos = faixa; rotuloAnos = if (faixa == null) "" else rotulo; aplicar()
            }
        })
    }

    private fun escolherIdade() {
        val opcoes = listOf<Pair<String, Int?>>(getString(R.string.vod_todos) to null, getString(R.string.grade_livre) to 0) +
            listOf(10, 12, 14, 16).map { getString(R.string.grade_ate_idade, it) to it }
        Painel.mostrar(this, getString(R.string.grade_idade, "").trim(' ', ':'), opcoes.map { (rotulo, idade) ->
            Painel.Item(rotulo, marcado = idadeMax == idade) { idadeMax = idade; aplicar() }
        })
    }

    private fun escolherNota() {
        val opcoes = listOf(0 to getString(R.string.grade_qualquer)) + listOf(6, 7, 8).map { it to getString(R.string.grade_nota_min, it) }
        Painel.mostrar(this, getString(R.string.grade_nota, "").trim(' ', ':'), opcoes.map { (n, rotulo) ->
            Painel.Item(rotulo, marcado = notaMin == n) { notaMin = n; aplicar() }
        })
    }

    private fun escolherGenero() {
        val itens = listOf(Painel.Item(getString(R.string.vod_todos), marcado = genero.isEmpty()) {
            genero = ""; aplicar()
        }) + Generos.todosEmOrdem.map { g ->
            Painel.Item(g, marcado = g == genero) { genero = g; aplicar() }
        }
        Painel.mostrar(this, getString(R.string.grade_genero, "").trim(' ', ':'), itens)
    }

    private fun irParaLetra(letra: String) {
        val i = vistos.indexOfFirst { it.letra == letra }.takeIf { it >= 0 } ?: return
        (capas.layoutManager as GridLayoutManager).scrollToPositionWithOffset(i, 0)
        capas.post { capas.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
    }

    private fun alternarFavorito(item: Item) {
        // Extras não vai para os favoritos: lá ele apareceria para todo mundo.
        if (item.alvo.colecao.isNotEmpty() || tipo == EXTRAS) return
        val marcado = VodFavoritos.alternar(this,
            VodFavoritos.Item(item.alvo.titulo, item.alvo.serie, item.alvo.letra, item.alvo.ano))
        Toast.makeText(this, if (marcado) R.string.vod_ficha_favorito else R.string.inicio_desfavoritar,
            Toast.LENGTH_SHORT).show()
        if (tipo == FAVORITOS) lifecycleScope.launch(semDerrubar) { carregar() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Esquerda na primeira coluna leva às letras, já na letra do título focado.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && capas.hasFocus()) {
            val foco = currentFocus ?: return super.onKeyDown(keyCode, event)
            val pos = capas.findContainingViewHolder(foco)?.bindingAdapterPosition ?: -1
            val colunas = (capas.layoutManager as GridLayoutManager).spanCount
            if (pos >= 0 && pos % colunas == 0 && letras.visibility == View.VISIBLE) {
                val letra = vistos.getOrNull(pos)?.letra
                val i = adaptadorLetras.letras.indexOf(letra).coerceAtLeast(0)
                letras.scrollToPosition(i)
                letras.post { letras.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class Capas : RecyclerView.Adapter<Capas.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val imagem: ImageView = view.findViewById(R.id.capaImagem)
            val inicial: TextView = view.findViewById(R.id.capaInicial)
            val nome: TextView = view.findViewById(R.id.capaNome)
            val progresso: ProgressBar = view.findViewById(R.id.capaProgresso)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_capa, parent, false)
            // A capa ocupa a coluna inteira, na proporção do pôster.
            val largura = (parent.width - parent.paddingStart - parent.paddingEnd) / colunas
            if (largura > 0) {
                val d = resources.displayMetrics.density
                val util = largura - (16 * d).toInt()
                view.layoutParams = RecyclerView.LayoutParams(util, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt()) }
                view.findViewById<View>(R.id.capaImagem).parent.let { it as View }.layoutParams.height =
                    ((util - 12 * d) * 1.45f).toInt()
            }
            Cartoes.arredondar(view.findViewById<View>(R.id.capaImagem).parent as View)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = vistos[position]
            val alvo = item.alvo
            holder.nome.text = alvo.nomeCompleto
            holder.inicial.text = alvo.titulo.take(1).uppercase()
            val fracao = if (alvo.serie) null else Progresso.fracao(this@GradeActivity, Progresso.chaveFilme(alvo.titulo))
            holder.progresso.visibility = if (fracao != null && fracao > 0f) View.VISIBLE else View.GONE
            fracao?.let { holder.progresso.progress = (it * 1000).toInt() }
            val capa = Generos.capa(alvo.nomeCompleto, alvo.serie) ?: Generos.capa(alvo.titulo, alvo.serie)
            if (tipo == EXTRAS) {
                // Capa genérica: nada da imagem de origem aparece na grade.
                holder.imagem.dispose()
                holder.imagem.visibility = View.GONE
                holder.inicial.setBackgroundResource(R.drawable.capa_generica)
                holder.inicial.text = getString(R.string.vod_extras)
                holder.inicial.setTextColor(android.graphics.Color.WHITE)
                holder.inicial.textSize = 40f
                holder.inicial.visibility = View.VISIBLE
            } else {
                Cartoes.carregar(holder.imagem, holder.inicial, capa)
            }
            holder.itemView.setOnClickListener {
                // Extras vai direto ao player: sem ficha, sem favoritar.
                if (tipo == EXTRAS) PlayerActivity.abrir(this@GradeActivity, alvo)
                else FichaActivity.abrir(this@GradeActivity, alvo)
            }
            holder.itemView.setOnLongClickListener { alternarFavorito(item); true }
            holder.itemView.setOnKeyListener { _, codigo, evento ->
                if (evento.action == KeyEvent.ACTION_DOWN && codigo == KeyEvent.KEYCODE_MENU) {
                    alternarFavorito(item); true
                } else false
            }
        }

        override fun getItemCount() = vistos.size
    }

    private inner class Letras : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var letras: List<String> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_letra, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val letra = letras[position]
            (holder.itemView as TextView).text = letra
            // Focar a letra já leva a grade até ela; OK entra nas capas.
            holder.itemView.setOnFocusChangeListener { _, foco ->
                if (foco) {
                    val i = vistos.indexOfFirst { it.letra == letra }
                    if (i >= 0) (capas.layoutManager as GridLayoutManager).scrollToPositionWithOffset(i, 0)
                }
            }
            holder.itemView.setOnClickListener { irParaLetra(letra) }
            holder.itemView.setOnKeyListener { _, codigo, evento ->
                if (evento.action == KeyEvent.ACTION_DOWN && codigo == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    irParaLetra(letra); true
                } else false
            }
        }

        override fun getItemCount() = letras.size
    }
}
