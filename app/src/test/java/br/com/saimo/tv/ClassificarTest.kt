package br.com.saimo.tv

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** Classificar contra o gabarito comum (SaimoPlayer/docs/exemplos/listas). */
class ClassificarTest {

    private fun atributo(linha: String, nome: String): String =
        Regex("$nome=\"([^\"]*)\"").find(linha)?.groupValues?.get(1)?.trim().orEmpty()

    @Test
    fun gabarito() {
        val texto = Fixtures.ler("classificar.m3u")
        val esperado = JSONArray(Fixtures.ler("classificar.esperado.json"))
        var pend = ""
        var i = 0
        for (bruta in texto.lines()) {
            val linha = bruta.trim()
            if (linha.startsWith("#EXTINF")) { pend = linha; continue }
            if (linha.isEmpty() || linha.startsWith("#")) continue
            val nome = (pend.substringAfterLast("\",", "").ifEmpty { pend.substringAfter(",", "") }).trim()
            val grupo = atributo(pend, "group-title")
            val declarado = atributo(pend, "tvg-type").ifEmpty { atributo(pend, "type") }
            val obtido = mutableMapOf<String, Any>("nome" to nome)
            when (val t = Classificar.tipo(linha, nome, grupo, declarado)) {
                Classificar.Tipo.Canal -> obtido["tipo"] = "canal"
                Classificar.Tipo.Filme -> Classificar.tituloDoFilme(nome, grupo).let {
                    obtido += mapOf("tipo" to "filme", "titulo" to it.titulo, "ano" to it.ano, "versao" to it.versao)
                }
                is Classificar.Tipo.Episodio -> Classificar.tituloDaSerie(nome, t.marca.inicio, grupo).let {
                    obtido += mapOf("tipo" to "episodio", "titulo" to it.titulo, "ano" to it.ano, "versao" to it.versao,
                        "temporada" to t.marca.temporada, "episodio" to t.marca.episodio)
                }
            }
            val e = esperado.getJSONObject(i)
            val esperadoMapa = e.keys().asSequence().associateWith { e.get(it) }
            assertEquals("item $i", esperadoMapa.mapValues { it.value.toString() }, obtido.mapValues { it.value.toString() })
            i++
        }
        assertEquals(esperado.length(), i)
    }
}
