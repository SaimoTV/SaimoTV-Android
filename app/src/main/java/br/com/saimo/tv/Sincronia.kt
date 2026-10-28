package br.com.saimo.tv

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A régua de tempo comum entre a TV e o celular que toca o som dela.
 *
 * O celular abre o mesmo canal por conta própria, e cada um carrega a lista do
 * canal num momento diferente: sem uma régua comum, os dois ficavam segundos
 * fora e iam escorregando. A régua é o instante do conteúdo:
 *
 * - canal com marca de horário (EXT-X-PROGRAM-DATE-TIME) ou DASH: a hora real
 *   do trecho no ar;
 * - canal HLS sem marca: o trecho que toca (número de sequência, o mesmo nos
 *   dois aparelhos) e quanto já tocou dele, convertidos para a régua contínua
 *   que o proxy da TV escreve na lista do celular ([Historico]);
 * - filme: a posição no filme.
 */
@UnstableApi
object Sincronia {

    /// Origem da hora sintética. Qualquer valor serve, desde que seja o mesmo
    /// no proxy e aqui.
    const val T0 = 1_600_000_000_000L

    /** O instante do conteúdo no ar, ou nulo quando não há régua (TS direto). */
    fun instante(player: Player): Long? {
        val linha = player.currentTimeline
        if (linha.isEmpty) return null
        val janela = Timeline.Window()
        linha.getWindow(player.currentMediaItemIndex, janela)
        val posicao = player.currentPosition
        val manifesto = player.currentManifest
        if (manifesto is HlsManifest) {
            val lista = manifesto.mediaPlaylist
            if (lista.hasProgramDateTime && janela.windowStartTimeMs != C.TIME_UNSET) {
                return janela.windowStartTimeMs + posicao
            }
            if (!lista.hasEndTag) {
                // Sem marca: o trecho que está tocando, levado para a régua
                // da lista que o celular recebe ([Historico]).
                val (seq, dentro) = trechoNaPosicao(player, lista, posicao) ?: return null
                Historico.instante(seq, dentro)?.let { return it }
                // Lista que o proxy não estende (partes LL-HLS, BYTERANGE):
                // ele escreve a régua antiga, contada pela duração declarada.
                val passo = (lista.targetDurationUs / 1000).coerceAtLeast(1)
                return T0 + lista.mediaSequence * passo + posicao
            }
            return posicao
        }
        if (janela.isLive) {
            return if (janela.windowStartTimeMs != C.TIME_UNSET) janela.windowStartTimeMs + posicao else null
        }
        return posicao
    }

    /// Duração de cada trecho já visto, por player: a TV costuma tocar atrás
    /// do começo da lista atual, e aí é preciso contar para trás.
    private val duracoes = java.util.WeakHashMap<Player, java.util.TreeMap<Long, Long>>()

    /** O número do trecho em `posicaoMs` (relativa à janela) e quanto já tocou dele. */
    private fun trechoNaPosicao(player: Player, lista: HlsMediaPlaylist, posicaoMs: Long): Pair<Long, Long>? {
        if (lista.segments.isEmpty()) return null
        val memoria = duracoes.getOrPut(player) { java.util.TreeMap() }
        lista.segments.forEachIndexed { i, t -> memoria[lista.mediaSequence + i] = t.durationUs / 1000 }
        while (memoria.size > 400) memoria.pollFirstEntry()
        if (posicaoMs >= 0) {
            lista.segments.forEachIndexed { i, t ->
                val inicio = t.relativeStartTimeUs / 1000
                if (posicaoMs < inicio + t.durationUs / 1000 || i == lista.segments.lastIndex) {
                    return (lista.mediaSequence + i) to (posicaoMs - inicio)
                }
            }
        }
        // Antes do começo da lista: volta trecho a trecho.
        val passo = (lista.targetDurationUs / 1000).coerceAtLeast(1)
        var seq = lista.mediaSequence
        var p = posicaoMs
        while (p < 0) {
            seq--
            p += memoria[seq] ?: passo
        }
        return seq to p
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** A marca de horário sintética do trecho de número `sequencia`. */
    fun marcaSintetica(sequencia: Long, passoMs: Long): String =
        synchronized(iso) { iso.format(java.util.Date(T0 + sequencia * passoMs)) }
}
