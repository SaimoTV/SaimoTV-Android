package br.com.saimo.tv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Fábrica de renderizadores do Saimo TV:
 * - Ativa fallback automático de decodificadores de hardware para software se a fonte falhar.
 * - Acopla o [NormalizadorAudioProcessor] diretamente ao AudioSink para nivelar o volume
 *   de canais baixos (ex: Cartoon) e altos (ex: Warner) em tempo real.
 */
@UnstableApi
class SaimoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(NormalizadorAudioProcessor()))
            .build()
    }
}
