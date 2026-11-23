package br.com.saimo.tv

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/**
 * Acessibilidade (Ajustes > Aparência e acessibilidade > Acessibilidade).
 *
 * - Tamanho do texto: multiplica a escala de letras (fontScale) por cima da do
 *   sistema. As telas foram feitas para caber na TV: os espaços ficam, só o
 *   texto cresce. O menu do topo fica fixo para sempre caber.
 * - Texto em negrito: `fontWeightAdjustment` (Android 12 ou mais novo).
 * - Foco reforçado: um contorno amarelo grosso, desenhado por cima de tudo,
 *   acompanha o que está em foco em qualquer tela.
 *
 * Cada tela aplica ao abrir (attachBaseContext); "Aplicar" recria as abertas.
 */
object TamanhoDaTela {

    private const val ARQUIVO = "ajustes"
    private const val CHAVE = "tamanho_texto"
    private const val CHAVE_NEGRITO = "acess_negrito"
    private const val CHAVE_FOCO = "acess_foco_forte"

    /** Normal, Grande, Muito grande, Enorme. */
    val escalas = floatArrayOf(1f, 1.15f, 1.3f, 1.45f)
    val nomes = listOf("Normal", "Grande", "Muito grande", "Enorme")

    /** Negrito para todo o app só existe do Android 12 em diante. */
    val negritoDisponivel get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    data class Escolha(val nivel: Int, val negrito: Boolean, val focoForte: Boolean)

    val padrao = Escolha(0, negrito = false, focoForte = false)

    private fun prefs(c: Context) = c.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun atual(c: Context) = Escolha(
        prefs(c).getInt(CHAVE, 0).coerceIn(0, escalas.lastIndex),
        prefs(c).getBoolean(CHAVE_NEGRITO, false),
        prefs(c).getBoolean(CHAVE_FOCO, false),
    )

    fun nivel(context: Context): Int = atual(context).nivel

    fun aplicar(activity: Activity, e: Escolha) {
        prefs(activity).edit().putInt(CHAVE, e.nivel).putBoolean(CHAVE_NEGRITO, e.negrito)
            .putBoolean(CHAVE_FOCO, e.focoForte).commit()
        // As telas abertas por baixo também: voltar a elas já mostra do jeito novo.
        SaimoApp.recriarTodas()
    }

    /** O contexto da tela com a escala e o negrito escolhidos (o mesmo, se nada mudou). */
    fun envolver(base: Context): Context {
        val e = atual(base)
        val negrito = e.negrito && negritoDisponivel
        if (e.nivel == 0 && !negrito) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = config.fontScale * escalas[e.nivel]
        if (negrito && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) config.fontWeightAdjustment = 300
        return base.createConfigurationContext(config)
    }

    private const val TAG_FOCO = "saimo.foco.forte"

    /** Põe (ou tira) o contorno de foco reforçado na tela que acabou de aparecer. */
    fun instalarFoco(tela: Activity) {
        val raiz = tela.window?.decorView as? ViewGroup ?: return
        val existente = raiz.findViewWithTag<ContornoDeFoco>(TAG_FOCO)
        if (!atual(tela).focoForte) { existente?.let { raiz.removeView(it) }; return }
        val contorno = existente ?: ContornoDeFoco(tela).also {
            it.tag = TAG_FOCO
            raiz.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        contorno.bringToFront()
    }

    /** Desenha o contorno sobre a vista em foco; acompanha a rolagem a cada quadro. */
    private class ContornoDeFoco(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5 * d; color = 0xFFFFD400.toInt()
        }
        private val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 11 * d; color = 0xD9000000.toInt()
        }
        private val area = Rect()
        private val ultimo = Rect()
        private val caixa = RectF()
        private val aoDesenhar = ViewTreeObserver.OnPreDrawListener {
            val foco = rootView?.findFocus()
            if (foco == null || foco === this) area.setEmpty() else {
                foco.getGlobalVisibleRect(area)
                val minha = Rect(); getGlobalVisibleRect(minha)
                area.offset(-minha.left, -minha.top)
            }
            if (area != ultimo) { ultimo.set(area); invalidate() }
            true
        }

        init {
            isClickable = false; isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(aoDesenhar) }
        override fun onDetachedFromWindow() { viewTreeObserver.removeOnPreDrawListener(aoDesenhar); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            if (ultimo.isEmpty) return
            val folga = 4 * d
            caixa.set(ultimo.left - folga, ultimo.top - folga, ultimo.right + folga, ultimo.bottom + folga)
            canvas.drawRoundRect(caixa, 14 * d, 14 * d, sombra)
            canvas.drawRoundRect(caixa, 14 * d, 14 * d, tinta)
        }
    }
}
