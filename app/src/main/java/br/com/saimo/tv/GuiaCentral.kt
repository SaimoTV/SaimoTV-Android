package br.com.saimo.tv

import android.util.JsonReader
import okhttp3.Request

/**
 * Guia de programação já montado no servidor (epg.json do endereço dos canais,
 * gerado às 11h e às 18h). Um arquivo só no lugar de uma página do guiadetv ou
 * do meuguia por canal, que eram dezenas de MB baixados e lidos no TV Box a
 * cada atualização do guia.
 *
 * Chaves iguais às do guia.txt: `g` = slug do guiadetv, `m` = código do meuguia.
 * Cada programa: `[inicio, fim, título, extra]` em segundos (extra = sinopse no
 * `g`, gênero no `m`). Em aparelho de pouca memória vem a versão leve (sem
 * sinopse, 30 h).
 */
object GuiaCentral {

    class Dados(val guiadetv: Map<String, List<Programme>>, val meuguia: Map<String, List<Programme>>)

    /** null quando o endereço não publica o arquivo (lista antiga): o app baixa página por página. */
    fun carregar(from: Long, to: Long): Dados? {
        val base = Enderecos.canais ?: return null
        val arquivo = if (Aparelho.poucaMemoria) "epg-leve.json" else "epg.json"
        return runCatching {
            val pedido = Request.Builder().url(base + arquivo)
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .build()
            Playback.client.newCall(pedido).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val corpo = r.body ?: return@use null
                // Lido em fluxo: o documento inteiro como String seria um pico de memória à toa.
                JsonReader(corpo.charStream()).use { ler(it, from, to) }
            }
        }.getOrNull()
    }

    private fun ler(json: JsonReader, from: Long, to: Long): Dados {
        var g: Map<String, List<Programme>> = emptyMap()
        var m: Map<String, List<Programme>> = emptyMap()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "g" -> g = grade(json, from, to, sinopse = true)
                "m" -> m = grade(json, from, to, sinopse = false)
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Dados(g, m)
    }

    private fun grade(json: JsonReader, from: Long, to: Long, sinopse: Boolean): Map<String, List<Programme>> {
        val saida = HashMap<String, List<Programme>>()
        json.beginObject()
        while (json.hasNext()) {
            val chave = json.nextName()
            val lista = ArrayList<Programme>()
            json.beginArray()
            while (json.hasNext()) {
                json.beginArray()
                val ini = json.nextLong() * 1000
                val fim = json.nextLong() * 1000
                val titulo = json.nextString()
                val extra = if (json.hasNext()) json.nextString() else ""
                while (json.hasNext()) json.skipValue()
                json.endArray()
                if (fim > from && ini < to) {
                    lista.add(
                        Programme(
                            title = titulo,
                            category = if (sinopse) "" else extra,
                            start = ini,
                            stop = fim,
                            description = if (sinopse) extra.ifBlank { null } else null,
                        )
                    )
                }
            }
            json.endArray()
            if (lista.isNotEmpty()) saida[chave] = lista
        }
        json.endObject()
        return saida
    }
}
