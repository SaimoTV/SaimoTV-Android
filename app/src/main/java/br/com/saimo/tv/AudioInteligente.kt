package br.com.saimo.tv

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Áudio Inteligente (Normalização Dinâmica de Volume / Automatic Gain Control).
 *
 * O processamento dinâmico real é realizado diretamente no pipeline PCM de áudio
 * através do [NormalizadorAudioProcessor] conectado via [SaimoRenderersFactory]
 * no player principal, no VOD e em todas as instâncias do MultiTela.
 */
@UnstableApi
object AudioInteligente {

    fun ativo(): Boolean = Preferencias.audioInteligente

    fun aplicar(player: ExoPlayer) {
        // O processador NormalizadorAudioProcessor já atua diretamente no AudioSink
    }

    fun atualizarTodos() {
        // NormalizadorAudioProcessor consulta Preferencias.audioInteligente em tempo real
    }

    fun liberar(player: ExoPlayer) {
        // Recursos gerenciados pelo ciclo de vida do player
    }
}
