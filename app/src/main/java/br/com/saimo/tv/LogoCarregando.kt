package br.com.saimo.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Logo que serve de carregamento: aparece apagada e em tons de cinza e vai
 * ganhando cor da esquerda para a direita enquanto o vídeo abre. Sem medida
 * real de progresso, a cor avança sozinha e desacelera perto do fim (0 a 92 %
 * em 12 s); quem esconde a view (o vídeo começou) leva a imagem já quase toda
 * colorida. Reinicia a cada vez que fica visível.
 */
class LogoCarregando @JvmOverloads constructor(
    context: Context,
    atributos: AttributeSet? = null,
) : View(context, atributos) {

    private var imagem: Drawable? = null
    private var progresso = 0f
    private var animador: ValueAnimator? = null
    private val cinza = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    fun definir(drawable: Drawable?) {
        imagem = drawable?.constantState?.newDrawable()?.mutate() ?: drawable?.mutate()
        invalidate()
    }

    override fun onVisibilityChanged(mudou: View, visibilidade: Int) {
        super.onVisibilityChanged(mudou, visibilidade)
        if (visibilidade == VISIBLE) iniciar() else parar()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) iniciar()
    }

    override fun onDetachedFromWindow() {
        parar()
        super.onDetachedFromWindow()
    }

    private fun iniciar() {
        parar()
        progresso = 0f
        animador = ValueAnimator.ofFloat(0f, 0.92f).apply {
            duration = 12_000
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { progresso = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun parar() {
        animador?.cancel()
        animador = null
    }

    override fun onDraw(canvas: Canvas) {
        val d = imagem ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        // Cabe inteira, centralizada, sem distorcer.
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val escala = minOf(w.toFloat() / iw, h.toFloat() / ih)
        val dw = (iw * escala).toInt()
        val dh = (ih * escala).toInt()
        val esq = (w - dw) / 2
        val topo = (h - dh) / 2
        d.setBounds(esq, topo, esq + dw, topo + dh)

        d.colorFilter = cinza
        d.alpha = 56
        d.draw(canvas)

        canvas.save()
        canvas.clipRect(esq.toFloat(), topo.toFloat(), esq + dw * progresso, (topo + dh).toFloat())
        d.colorFilter = null
        d.alpha = 255
        d.draw(canvas)
        canvas.restore()
    }
}
