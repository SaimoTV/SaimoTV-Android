package br.com.saimo.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Processador de Áudio com Controle Automático de Ganho (AGC) e Nivelamento em Tempo Real.
 *
 * Mede continuamente a energia sonora (RMS) do fluxo de áudio PCM de 16 bits e ajusta o ganho:
 * - Fontes/canais baixos (como Cartoon Network) recebem amplificação suave de até +12 dB (4.0×).
 * - Fontes/canais altos (como Warner) sofrem atenuação automática de até -10 dB (0.3×).
 * - Ambos convergem para o mesmo padrão de diálogo televisivo (~ -18 dBFS / RMS 0.12).
 * - Uma rampa suave (interpolação de ganho) elimina estalos e transições bruscas.
 */
@UnstableApi
class NormalizadorAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val RMS_ALVO = 0.12f
        private const val GANHO_MIN = 0.28f  // -11 dB (reduz canais excessivamente altos)
        private const val GANHO_MAX = 4.00f  // +12 dB (amplifica canais muito baixos)
        private const val TAXA_SUAVIZACAO = 0.05f
    }

    private var ganhoAtual = 1.0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    @Suppress("DEPRECATION")
    @Deprecated("Overridden for compatibility across Media3 versions")
    override fun onFlush() {
        super.onFlush()
        ganhoAtual = 1.0f
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        ganhoAtual = 1.0f
    }

    override fun onReset() {
        ganhoAtual = 1.0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val remaining = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(remaining)

        if (!Preferencias.audioInteligente) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val numSamples = remaining / 2
            val startPos = inputBuffer.position()

            // 1. Calcular a energia RMS do buffer
            var sumSquares = 0.0
            for (i in 0 until numSamples) {
                val s = inputBuffer.short.toFloat() / 32768.0f
                sumSquares += (s * s)
            }
            inputBuffer.position(startPos)

            val rms = sqrt(sumSquares / numSamples).toFloat()

            // 2. Ajustar ganho com suavização contínua (ignora silêncio absoluto)
            if (rms > 0.003f) {
                val ganhoDesejado = (RMS_ALVO / rms).coerceIn(GANHO_MIN, GANHO_MAX)
                ganhoAtual += (ganhoDesejado - ganhoAtual) * TAXA_SUAVIZACAO
            }

            // 3. Aplicar o ganho e proteger contra clipping
            for (i in 0 until numSamples) {
                val sample = inputBuffer.short
                val amplificado = sample * ganhoAtual
                val limitado = amplificado.toInt().coerceIn(-32768, 32767).toShort()
                outputBuffer.putShort(limitado)
            }
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val numSamples = remaining / 4
            val startPos = inputBuffer.position()

            var sumSquares = 0.0
            for (i in 0 until numSamples) {
                val s = inputBuffer.float
                sumSquares += (s * s)
            }
            inputBuffer.position(startPos)

            val rms = sqrt(sumSquares / numSamples).toFloat()

            if (rms > 0.003f) {
                val ganhoDesejado = (RMS_ALVO / rms).coerceIn(GANHO_MIN, GANHO_MAX)
                ganhoAtual += (ganhoDesejado - ganhoAtual) * TAXA_SUAVIZACAO
            }

            for (i in 0 until numSamples) {
                val sample = inputBuffer.float
                val amplificado = (sample * ganhoAtual).coerceIn(-1.0f, 1.0f)
                outputBuffer.putFloat(amplificado)
            }
        }

        outputBuffer.flip()
    }
}
