package br.com.saimo.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Os cantos arredondados do vídeo encolhido.
 *
 * O vídeo vem numa superfície própria (SurfaceView), que não aceita recorte
 * arredondado. Esta vista fica por cima dele, do mesmo tamanho, e pinta só os
 * quatro cantos na cor do fundo — o efeito é o de um vídeo de bordas
 * redondas. Ela recebe a mesma escala do vídeo, então o raio é dividido pela
 * escala para chegar à tela sempre do mesmo tamanho.
 */
class Cantos(context: Context, cor: Int, private val raioDp: Float = 18f) : View(context) {

    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cor }
    private val caminho = Path()
    private val caixa = RectF()

    init {
        // Não recebe foco nem toque: é só desenho.
        isFocusable = false
        isClickable = false
        alpha = 0f
    }

    override fun onDraw(canvas: Canvas) {
        val escala = scaleX.coerceAtLeast(0.05f)
        val raio = raioDp * resources.displayMetrics.density / escala
        caixa.set(0f, 0f, width.toFloat(), height.toFloat())
        caminho.rewind()
        // Depois do rewind: ele volta o preenchimento ao padrão, e aí o meio
        // (o vídeo) também era pintado.
        caminho.fillType = Path.FillType.EVEN_ODD
        caminho.addRect(caixa, Path.Direction.CW)
        caminho.addRoundRect(caixa, raio, raio, Path.Direction.CW)
        canvas.drawPath(caminho, tinta)
    }
}
