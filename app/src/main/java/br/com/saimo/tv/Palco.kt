package br.com.saimo.tv

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * O vídeo que abre espaço quando um menu entra na tela.
 *
 * Com a lista de canais à esquerda ou um painel à direita, o vídeo encolhe e
 * vai para o lado livre, inteiro e na proporção da tela — como numa TV de
 * fábrica — em vez de ficar escondido atrás do menu.
 *
 * A mudança é só de escala e posição, desenhada pela GPU: nada de refazer o
 * layout a cada quadro, que num TV Box fraco engasga a animação e o vídeo.
 *
 * As vistas são as camadas de vídeo da tela (player principal, segundo player
 * da multi tela, contornos). Todas ocupam a tela a partir do canto superior
 * esquerdo da raiz, então recebem a mesma transformação, com o pivô na origem
 * da raiz.
 */
class Palco(
    private val raiz: View,
    /// Os cantos arredondados, por cima do vídeo: aparecem só com ele encolhido.
    private val cantos: View? = null,
    private val camadas: () -> List<View>,
) {

    private val curva = DecelerateInterpolator(1.6f)

    /// Onde o vídeo está agora: nulo é a tela cheia.
    var encaixe: Encaixe? = null
        private set

    data class Encaixe(val esquerda: Float, val topo: Float, val escala: Float)

    /**
     * Cabe o vídeo no retângulo livre (em pixels da raiz), centrado nele.
     * Mantém a proporção da tela: o vídeo já vem ajustado dentro dela.
     */
    fun encaixar(livreEsquerda: Float, livreTopo: Float, livreDireita: Float, livreBaixo: Float, duracao: Long = 220) {
        val w = raiz.width.toFloat()
        val h = raiz.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val lw = (livreDireita - livreEsquerda).coerceAtLeast(1f)
        val lh = (livreBaixo - livreTopo).coerceAtLeast(1f)
        val escala = minOf(lw / w, lh / h, 1f)
        val esquerda = livreEsquerda + (lw - w * escala) / 2f
        val topo = livreTopo + (lh - h * escala) / 2f
        aplicar(Encaixe(esquerda, topo, escala), duracao)
    }

    /** De volta à tela cheia. */
    fun soltar(duracao: Long = 200) {
        if (encaixe == null) return
        aplicar(null, duracao)
    }

    private fun aplicar(novo: Encaixe?, duracao: Long) {
        if (novo == encaixe) return
        encaixe = novo
        val escala = novo?.escala ?: 1f
        for (v in camadas() + listOfNotNull(cantos)) {
            // Pivô na origem da raiz: a mesma conta vale para qualquer camada,
            // mesmo a que não começa no canto (o segundo player da multi tela).
            v.pivotX = -v.left.toFloat()
            v.pivotY = -v.top.toFloat()
            v.animate().cancel()
            v.animate()
                .scaleX(escala).scaleY(escala)
                .translationX(novo?.esquerda ?: 0f)
                .translationY(novo?.topo ?: 0f)
                .apply {
                    // Os cantos aparecem com o vídeo encolhido, e o raio
                    // depende da escala: redesenha a cada quadro.
                    if (v === cantos) alpha(if (novo == null) 0f else 1f).setUpdateListener { v.invalidate() }
                    else setUpdateListener(null)
                }
                .setInterpolator(curva)
                .setDuration(duracao)
                .start()
        }
    }
}
