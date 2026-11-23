package br.com.saimo.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A aba do menu do topo que cada tela representa. */
enum class Aba { INICIO, AO_VIVO, EVENTOS, ON_DEMAND, FAVORITOS, RADIOS, EXTRAS, BUSCAR, APOIE, PERFIL, AJUSTES, NENHUMA }

/**
 * O menu do topo, o mesmo em todas as telas.
 *
 * Antes ele só existia na tela inicial: de dentro de uma ficha, para ir às
 * séries era VOLTAR, VOLTAR, VOLTAR. Agora a seta para cima, de qualquer
 * tela, chega nele — e de lá tudo está a um OK.
 *
 * Navegar pelo menu não empilha telas: volta à inicial e abre o destino por
 * cima dela, então VOLTAR sempre leva à tela inicial, nunca a um labirinto.
 */
@UnstableApi
object MenuGlobal {

    fun criar(tela: Activity, atual: Aba, aoFocar: (() -> Unit)? = null, aoInicio: (() -> Unit)? = null): View {
        val d = tela.resources.displayMetrics.density
        // Acessibilidade: o menu cresce junto com o texto (até 1,3x, para as dez abas
        // caberem) e, ampliado, as abas ficam só com texto para sobrar espaço.
        val k = minOf(TamanhoDaTela.escalas[TamanhoDaTela.nivel(tela)], 1.3f)
        val ampliado = k > 1f
        val barra = LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((40 * d).toInt(), (18 * d).toInt(), (40 * d).toInt(), (4 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // O perfil ativo no lugar do ícone do app: o avatar leva à troca de perfil.
        // Mesmo espaço do ícone, para as dez abas continuarem cabendo em 960 dp.
        val perfil = ImageView(tela).apply {
            tag = Aba.PERFIL
            isFocusable = true
            isClickable = true
            setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 9 * d; setStroke((2 * d).toInt(), android.graphics.Color.WHITE)
                })
            }
            layoutParams = LinearLayout.LayoutParams((32 * k * d).toInt(), (32 * k * d).toInt()).apply { marginEnd = (6 * d).toInt() }
            mostrarPerfil(this)
            setOnFocusChangeListener { v, foco ->
                v.animate().scaleX(if (foco) 1.12f else 1f).scaleY(if (foco) 1.12f else 1f).setDuration(120).start()
                if (foco) aoFocar?.invoke()
            }
            setOnClickListener { tela.startActivity(Intent(tela, PerfisActivity::class.java)); tela.overridePendingTransition(0, 0) }
        }
        barra.addView(perfil)
        val rolagem = HorizontalScrollView(tela).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            // A rolagem em si não recebe foco — só as abas.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val abas = LinearLayout(tela).apply { orientation = LinearLayout.HORIZONTAL; id = R.id.menuGlobalAbas }
        rolagem.addView(abas)
        barra.addView(rolagem)

        val itens = listOf(
            Triple(Aba.BUSCAR, R.string.menu_buscar, R.drawable.ic_search),
            Triple(Aba.INICIO, R.string.menu_inicio, R.drawable.ic_home),
            Triple(Aba.AO_VIVO, R.string.menu_ao_vivo, R.drawable.ic_live_tv),
            Triple(Aba.EVENTOS, R.string.menu_eventos, R.drawable.ic_football),
            Triple(Aba.ON_DEMAND, R.string.menu_on_demand, R.drawable.ic_movie),
            Triple(Aba.FAVORITOS, R.string.vod_favoritos, R.drawable.ic_star),
            Triple(Aba.RADIOS, R.string.menu_radios, R.drawable.ic_radio),
            Triple(Aba.EXTRAS, R.string.vod_extras, R.drawable.ic_more),
            Triple(Aba.APOIE, R.string.menu_apoie, R.drawable.ic_favorite),
            Triple(Aba.AJUSTES, R.string.menu_ajustes, R.drawable.ic_settings)
        )

        for ((aba, texto, icone) in itens) {
            abas.addView(TextView(tela).apply {
                text = tela.getString(texto)
                tag = aba
                // Dez abas com a Apoie: tamanho e folga medidos para caber em 960 dp.
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f * k) // dip: segue a escala própria do menu, não o fontScale
                maxLines = 1
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(ContextCompat.getColorStateList(tela, R.color.texto_botao_ficha))
                // Apoie fica sempre em destaque, chamando atenção no menu.
                if (aba == Aba.APOIE) apoieChamativo(this, d) else setBackgroundResource(R.drawable.aba_menu)
                isFocusable = true
                isSelected = aba == atual
                // Extras só existe depois do código, e some de novo sem deixar vão.
                if (aba == Aba.EXTRAS) visibility = if (Unlock.unlocked) View.VISIBLE else View.GONE
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (1 * d).toInt() }
                when {
                    aba == Aba.AJUSTES -> Icones.so(this, icone, 1.4f * k)
                    icone != 0 && !ampliado -> Icones.inicio(this, icone)
                }
                setOnFocusChangeListener { _, foco -> if (foco) aoFocar?.invoke() }
                // Para os lados, o menu anda de aba em aba e para nas pontas:
                // no fim, a busca do sistema achava algo "à direita" lá embaixo.
                setOnKeyListener { v, codigo, evento ->
                    if (evento.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val passo = when (codigo) {
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        else -> return@setOnKeyListener false
                    }
                    var i = abas.indexOfChild(v) + passo
                    while (i in 0 until abas.childCount && abas.getChildAt(i).visibility != View.VISIBLE) i += passo
                    // Antes da primeira aba, o avatar do perfil.
                    if (i < 0) perfil.requestFocus() else abas.getChildAt(i)?.requestFocus()
                    true
                }
                setOnClickListener {
                    if (aba == Aba.INICIO && aoInicio != null) aoInicio() else ir(tela, aba, atual)
                }
            })
        }

        val relogio = TextView(tela).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f * k)
            setTextColor(ContextCompat.getColor(tela, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * d).toInt() }
        }
        barra.addView(relogio)
        val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        val handler = Handler(Looper.getMainLooper())
        val tique = object : Runnable {
            override fun run() {
                relogio.text = hora.format(Date())
                if (relogio.isAttachedToWindow || !tela.isFinishing) handler.postDelayed(this, 20_000)
            }
        }
        barra.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { handler.post(tique) }
            override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(tique) }
        })
        return barra
    }

    /** Foca a aba da tela atual (ou a primeira): é para onde VOLTAR leva na inicial. */
    fun focar(barra: View?, aba: Aba) {
        val abas = barra?.findViewById<LinearLayout>(R.id.menuGlobalAbas) ?: return
        (abas.findViewWithTag<View>(aba) ?: abas.getChildAt(0))?.requestFocus()
    }

    fun temFoco(barra: View?) = barra?.hasFocus() == true

    /** Mostra ou esconde o Extras conforme o código: o menu já montado não se refaz sozinho. */
    fun atualizar(barra: View?) {
        val abas = barra?.findViewById<LinearLayout>(R.id.menuGlobalAbas) ?: return
        abas.findViewWithTag<View>(Aba.EXTRAS)?.visibility = if (Unlock.unlocked) View.VISIBLE else View.GONE
        // Nome ou avatar do perfil pode ter mudado na tela de perfis.
        (barra?.findViewWithTag<View>(Aba.PERFIL) as? ImageView)?.let { mostrarPerfil(it) }
    }

    private fun mostrarPerfil(v: ImageView) {
        val perfil = Perfis.ativo()
        v.contentDescription = "Perfil ${perfil.nome}. Trocar de perfil"
        val lado = (28 * v.resources.displayMetrics.density).toInt()
        val av = PerfisActivity.avatarDrawable(v.context, perfil, lado)
        if (av != null) v.setImageDrawable(av) else v.setImageResource(R.mipmap.ic_launcher)
    }

    private fun ir(tela: Activity, aba: Aba, atual: Aba) {
        if (aba == atual) return
        val inicio = Intent(tela, EscolhaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val destino: Intent? = when (aba) {
            Aba.INICIO, Aba.NENHUMA -> null
            Aba.AO_VIVO -> Intent(tela, MainActivity::class.java)
            Aba.EVENTOS -> Intent(tela, EventosActivity::class.java)
            Aba.ON_DEMAND -> Intent(tela, OnDemandActivity::class.java)
            Aba.FAVORITOS -> GradeActivity.intent(tela, GradeActivity.FAVORITOS)
            Aba.RADIOS -> Intent(tela, RadiosActivity::class.java)
            Aba.EXTRAS -> GradeActivity.intent(tela, GradeActivity.EXTRAS)
            Aba.BUSCAR -> Intent(tela, BuscaActivity::class.java)
            Aba.AJUSTES -> Intent(tela, AjustesActivity::class.java)
            Aba.APOIE -> Intent(tela, DoacaoActivity::class.java)
            Aba.PERFIL -> Intent(tela, PerfisActivity::class.java)
        }
        // Perfis abre por cima da tela atual: VOLTAR devolve a ela.
        if (aba == Aba.PERFIL && destino != null) { tela.startActivity(destino); return }
        if (destino == null) tela.startActivity(inicio)
        else tela.startActivities(arrayOf(inicio, destino))
        tela.overridePendingTransition(0, 0)
    }

    /**
     * A aba Apoie chama atenção: a cor corre pelo arco-íris (4 s por volta) e
     * a aba pulsa de leve. Com foco fica branca, como as outras. A animação só
     * roda enquanto a aba está na tela.
     */
    private fun apoieChamativo(aba: TextView, d: Float) {
        val pilula = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 100 * d }
        val foco = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 100 * d; setColor(0xFFFFFFFF.toInt()) }
        aba.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), foco)
            addState(intArrayOf(), pilula)
        }
        val hsv = floatArrayOf(0f, 0.72f, 0.95f)
        val animador = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                hsv[0] = (330f + 360f * t) % 360f
                val a = android.graphics.Color.HSVToColor(hsv)
                hsv[0] = (hsv[0] + 50f) % 360f
                val b = android.graphics.Color.HSVToColor(hsv)
                pilula.colors = intArrayOf(a, b)
                pilula.orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                if (!aba.isFocused) {
                    val pulso = 1f + 0.05f * kotlin.math.sin(t * 2.0 * Math.PI * 2.5).toFloat()
                    aba.scaleX = pulso
                    aba.scaleY = pulso
                }
            }
        }
        aba.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { animador.start() }
            override fun onViewDetachedFromWindow(v: View) { animador.cancel(); v.scaleX = 1f; v.scaleY = 1f }
        })
        if (aba.isAttachedToWindow) animador.start()
    }
}

/**
 * Tela com o menu do topo e a imagem de fundo do app.
 *
 * Quem herda daqui chama `setContentView(layout)` como sempre: o menu entra
 * em cima do layout, sem nenhum XML repetido.
 */
@UnstableApi
abstract class TelaComMenu : AppCompatActivity() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(TamanhoDaTela.envolver(base))
    }


    protected open val aba: Aba = Aba.NENHUMA
    protected open fun aoFocarMenu() {}
    protected open val aoMenuInicio: (() -> Unit)? = null
    protected var barraMenu: View? = null
        private set
    private var vazioView: View? = null
    private var conteudoView: View? = null

    /**
     * Estado vazio da tela: [mensagem] em cima do conteúdo e um botão que abre
     * o diálogo de endereços. Sem dado nenhum, a tela continua navegável (o
     * menu do topo segue alcançável com a seta para cima).
     */
    protected fun mostrarVazio(mensagem: String) {
        val v = vazioView ?: return
        v.findViewById<TextView>(R.id.vazioTexto).text = mensagem
        if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        // O conteúdo (listas vazias, mas focáveis) por baixo não toma mais o
        // foco: antes a seta para baixo parava nele e o controle não chegava
        // aos botões de adicionar lista.
        (conteudoView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // O foco vai para o primeiro botão (a lista de baixo, vazia, o toma
        // quando a tela abre): sem isto o controle remoto não alcança nada.
        focarBotaoDoVazio()
        v.postDelayed({ focarBotaoDoVazio() }, 300)
    }

    protected fun vazioVisivel(): Boolean = vazioView?.visibility == View.VISIBLE

    /** Leva o foco ao primeiro botão do estado vazio, se ele está na tela. */
    protected fun focarBotaoDoVazio() {
        val v = vazioView ?: return
        if (v.visibility == View.VISIBLE && !v.hasFocus()) v.findViewById<View>(R.id.vazioBotao).requestFocus()
    }

    protected fun esconderVazio() {
        val v = vazioView ?: return
        if (v.visibility == View.VISIBLE) {
            v.visibility = View.GONE
        }
        (conteudoView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    /** Abre "Endereço das listas"; depois de salvar, a tela se refaz. */
    protected fun pedirEnderecos() {
        ListasDialogo.mostrar(this) { aoMudarListas() }
    }

    /** Tela vazia: adicionar uma lista M3U ou com usuário e senha. */
    protected fun pedirLista() {
        MinhasListasTela.adicionarPorLink(this) { aoMudarListas() }
    }

    /** Os endereços mudaram e os dados já foram pedidos de novo. */
    protected open fun aoMudarListas() { if (!isFinishing && !isDestroyed) recreate() }

    /**
     * Foca o que `achar` devolver assim que existir — listas desenham um quadro
     * depois de receberem os dados, e um único `post` às vezes chega cedo.
     */
    protected fun focarQuandoPronto(tentativas: Int = 20, achar: () -> View?) {
        val alvo = achar()
        if (alvo?.requestFocus() == true) return
        if (tentativas > 0) window.decorView.postDelayed({ focarQuandoPronto(tentativas - 1, achar) }, 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.drawable.fundo_app)
    }

    override fun onResume() {
        super.onResume()
        MenuGlobal.atualizar(barraMenu)
    }

    override fun setContentView(layoutResID: Int) {
        // Recorta cada parte no próprio espaço: a imagem larga da ficha, sem
        // isso, desenhava por cima do menu.
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val barra = MenuGlobal.criar(this, aba, { aoFocarMenu() }, aoMenuInicio)
        barraMenu = barra
        raiz.addView(barra)
        // O conteúdo e, por cima, o estado vazio (escondido até alguém pedir).
        val moldura = android.widget.FrameLayout(this)
        val conteudo = layoutInflater.inflate(layoutResID, moldura, false)
        moldura.addView(conteudo)
        conteudoView = conteudo
        val vazio = layoutInflater.inflate(R.layout.estado_vazio, moldura, false)
        moldura.addView(vazio)
        vazioView = vazio
        vazio.findViewById<TextView>(R.id.vazioBotao).apply {
            Icones.inicio(this, R.drawable.ic_link)
            setOnClickListener { pedirLista() }
        }
        vazio.findViewById<TextView>(R.id.vazioBotaoSaimo).apply {
            Icones.inicio(this, R.drawable.ic_settings)
            setOnClickListener { pedirEnderecos() }
        }
        vazio.findViewById<TextView>(R.id.vazioBotaoAddons)?.apply {
            Icones.inicio(this, R.drawable.ic_list)
            setOnClickListener { AddonsTela.abrir(this@TelaComMenu) }
        }
        raiz.addView(moldura, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        super.setContentView(raiz)
        // O sistema dá o primeiro foco ao primeiro focável da tela — que seria
        // a aba "Início". Enquanto o conteúdo não pega o foco, o menu fica de
        // fora; depois (ou em dois segundos, se o conteúdo não tiver nada), a
        // seta para cima chega nele normalmente.
        val abas = barra.findViewById<ViewGroup>(R.id.menuGlobalAbas)
        abas.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val liberar = Runnable { abas.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        raiz.viewTreeObserver.addOnGlobalFocusChangeListener { _, novo ->
            if (novo != null && abas.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) liberar.run()
        }
        raiz.postDelayed(liberar, 2_000)
    }
}
