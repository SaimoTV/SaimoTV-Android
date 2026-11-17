package br.com.saimo.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Áudio e legendas do player para o Saimo Connect 2: a lista que o celular
 * mostra e a troca pelo índice. Mesma regra do painel da TV ([Faixas]): as
 * legendas externas (OpenSubtitles) ficam no painel da própria TV.
 */
object ConnectFaixas {

    private fun audios(p: ExoPlayer) = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    private fun legendas(p: ExoPlayer) = p.currentTracks.groups.filter { g ->
        g.type == C.TRACK_TYPE_TEXT && (0 until g.length).none { g.getTrackFormat(it).id == Legendas.ID_FAIXA }
    }

    fun json(context: Context, p: ExoPlayer?): JSONObject? {
        p ?: return null
        val a = audios(p)
        val l = legendas(p)
        if (a.size <= 1 && l.isEmpty()) return null
        val semLegenda = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || l.none { it.isSelected }
        return JSONObject()
            .put("audio", JSONArray().apply {
                for (g in a) {
                    val f = g.getTrackFormat(0)
                    put(JSONObject().put("rotulo", Faixas.idioma(context, f.language, f.label)).put("ativa", g.isSelected))
                }
            })
            .put("legendas", JSONArray().apply {
                for (g in l) {
                    val f = g.getTrackFormat(0)
                    put(JSONObject().put("rotulo", Faixas.idioma(context, f.language, f.label)).put("ativa", g.isSelected && !semLegenda))
                }
            })
            .put("semLegenda", semLegenda)
    }

    fun escolher(p: ExoPlayer?, tipo: String, indice: Int) {
        p ?: return
        if (tipo == "audio") {
            val g = audios(p).getOrNull(indice) ?: return
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
        } else if (tipo == "legenda") {
            if (indice < 0) {
                Preferencias.legendaIdioma = ""
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                return
            }
            val g = legendas(p).getOrNull(indice) ?: return
            Preferencias.legendaIdioma = g.getTrackFormat(0).language.orEmpty()
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
        }
    }
}
