package br.com.saimo.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi

/**
 * O que fica por cima de todas as telas do app: o brilho do app e o tempo que
 * falta no timer.
 *
 * **Brilho do app** (0 a 100; 100 é o normal): um véu preto por cima do app,
 * não o brilho do aparelho. Em 0 a tela fica toda preta; aí qualquer botão do
 * controle traz o brilho de volta, devagar, até 100: quem não vê nada não
 * consegue achar o ajuste, então a volta não pode depender dele.
 *
 * **Timer**: enquanto ligado, "Fecha em 25 min" pequeno no canto superior direito.
 */
@UnstableApi
object Camada {

    private const val TAG_VEU = "saimo-veu"
    private const val TAG_TIMER = "saimo-timer"
    private const val CHAVE = "brilho_app"
    /** Abaixo disto a tela está praticamente preta: qualquer botão volta ao normal. */
    const val ESCURO = 5

    private val handler = Handler(Looper.getMainLooper())
    private var engolirSoltura: Int? = null
    private var animacao: ValueAnimator? = null

    /** 0 a 100. Global (vale para todos os perfis), guardado nos ajustes. */
    var brilho: Int
        get() = if (Preferencias.estaPronta()) Preferencias.inteiro(CHAVE, 100) else 100
        set(v) {
            val valor = v.coerceIn(0, 100)
            animacao?.cancel()
            Preferencias.definirInteiro(CHAVE, valor)
            aplicarVeu(valor.toFloat())
            Connect.notificarMudancaDeEstado()
        }

    val escuro: Boolean get() = brilho <= ESCURO

    /** Volta devagar (1,5 s) de onde estiver até 100. */
    fun voltarAoNormal() {
        val de = brilho.toFloat()
        Preferencias.definirInteiro(CHAVE, 100)
        animacao?.cancel()
        animacao = ValueAnimator.ofFloat(de, 100f).apply {
            duration = 1500
            addUpdateListener { aplicarVeu(it.animatedValue as Float) }
            start()
        }
        Connect.notificarMudancaDeEstado()
    }

    private fun aplicarVeu(valor: Float) {
        val tela = SaimoApp.atividadeAtual ?: Lembretes.atual ?: return
        veu(tela)?.let {
            it.alpha = 1f - valor / 100f
            it.visibility = if (valor >= 100f) View.GONE else View.VISIBLE
        }
        // Tela apagada é apagada: nem o selo do timer aparece.
        selo(tela)?.alpha = if (valor <= ESCURO) 0f else 1f
    }

    /** Põe o véu e o selo do timer na tela que acabou de aparecer. */
    fun instalar(tela: Activity) {
        val raiz = tela.window?.decorView as? ViewGroup ?: return
        if (raiz.findViewWithTag<View>(TAG_VEU) == null) {
            raiz.addView(View(tela).apply {
                tag = TAG_VEU
                setBackgroundColor(Color.BLACK)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (raiz.findViewWithTag<View>(TAG_TIMER) == null) {
            val d = tela.resources.displayMetrics.density
            raiz.addView(TextView(tela).apply {
                tag = TAG_TIMER
                textSize = 12f
                setTextColor(Color.argb(230, 255, 255, 255))
                setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
                background = GradientDrawable().apply { setColor(Color.argb(150, 0, 0, 0)); cornerRadius = 12 * d }
                isFocusable = false
                visibility = View.GONE
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply { topMargin = (10 * d).toInt(); marginEnd = (14 * d).toInt() })
        }
        // O véu e o selo sempre por cima do que a tela pôs depois.
        veu(tela)?.bringToFront()
        selo(tela)?.bringToFront()
        aplicarVeu(brilho.toFloat())
        atualizarTimer()
        interceptarTeclas(tela)
    }

    private fun veu(tela: Activity) = (tela.window?.decorView as? ViewGroup)?.findViewWithTag<View>(TAG_VEU)
    private fun selo(tela: Activity) = (tela.window?.decorView as? ViewGroup)?.findViewWithTag<TextView>(TAG_TIMER)

    private val tique = Runnable { atualizarTimer() }

    /** Mostra/atualiza "Fecha em N min" (o timer chama ao ligar e desligar). */
    fun atualizarTimer() {
        handler.removeCallbacks(tique)
        val tela = SaimoApp.atividadeAtual ?: Lembretes.atual ?: return
        val s = selo(tela) ?: return
        if (!TimerDeSono.ativo) { s.visibility = View.GONE; return }
        val ms = TimerDeSono.fimEm - System.currentTimeMillis()
        s.text = if (ms < 60_000) "Fecha em ${(ms / 1000).coerceAtLeast(0)} s" else "Fecha em ${TimerDeSono.restanteMin()} min"
        s.visibility = View.VISIBLE
        s.bringToFront()
        handler.postDelayed(tique, if (ms < 70_000) 1_000 else 15_000)
    }

    /**
     * Com a tela escura, qualquer botão (menos o de volume, que segue mexendo no
     * som) só traz o brilho de volta: não abre nem troca nada no escuro.
     */
    private fun interceptarTeclas(tela: Activity) {
        val janela = tela.window ?: return
        val atual = janela.callback ?: return
        if (atual is Interceptador) return
        janela.callback = Interceptador(atual)
    }

    private class Interceptador(private val original: Window.Callback) : Window.Callback by original {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val volume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            if (!volume && escuro) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    engolirSoltura = event.keyCode
                    voltarAoNormal()
                }
                return true
            }
            // A soltura do botão que acendeu a tela também não conta.
            if (event.action == KeyEvent.ACTION_UP && event.keyCode == engolirSoltura) {
                engolirSoltura = null
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == engolirSoltura) return true
            return original.dispatchKeyEvent(event)
        }
    }
}
