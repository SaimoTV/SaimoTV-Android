package br.com.saimo.tv

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * O leitor de .m3u do "Minhas listas" contra os gabaritos da especificação
 * (a saída do leitor de referência), campo a campo. O `edge` é pequeno e vive em
 * fixtures/; o `br` é uma lista de verdade e NUNCA entra no git: o teste lê da
 * pasta indicada em SAIMO_M3U_DIR e é ignorado se ela não existir.
 */
class LeitorM3uTest {

    private fun comparar(texto: String, golden: String, rotulo: String) {
        val esperado = JSONArray(golden)
        val obtido = LeitorM3u.ler(texto)
        assertEquals("$rotulo: canais", esperado.length(), obtido.size)
        var fontes = 0
        for (i in 0 until esperado.length()) {
            val e = esperado.getJSONObject(i)
            val c = obtido[i]
            assertEquals("$rotulo[$i] nome", e.getString("nome"), c.nome)
            assertEquals("$rotulo[$i] grupo", e.opt("grupo").nulo(), c.grupo)
            assertEquals("$rotulo[$i] logo", e.opt("logo").nulo(), c.logo)
            val ef = e.getJSONArray("fontes")
            assertEquals("$rotulo[$i] fontes", ef.length(), c.fontes.size)
            fontes += c.fontes.size
            for (j in 0 until ef.length()) {
                val f = ef.getJSONObject(j)
                val o = c.fontes[j]
                assertEquals("$rotulo[$i.$j] url", f.getString("url"), o.url)
                assertEquals("$rotulo[$i.$j] referer", f.opt("referer").nulo(), o.referer)
                assertEquals("$rotulo[$i.$j] ua", f.opt("ua").nulo(), o.ua)
                assertEquals("$rotulo[$i.$j] keyId", f.opt("keyId").nulo(), o.keyId)
                assertEquals("$rotulo[$i.$j] key", f.opt("key").nulo(), o.key)
                assertEquals("$rotulo[$i.$j] qualidade", f.opt("qualidade").nulo(), o.qualidade)
            }
        }
        println("$rotulo: ${obtido.size} canais, $fontes fontes, ${obtido.count { it.logo != null }} com logo, ${obtido.count { it.grupo != null }} com grupo")
    }

    private fun Any?.nulo(): String? = if (this == null || this == org.json.JSONObject.NULL) null else this as String

    @Test
    fun `gabarito edge`() {
        comparar(Fixtures.ler("m3u-edge.m3u"), Fixtures.ler("m3u-edge.golden.json"), "edge")
    }

    @Test
    fun `gabarito br`() {
        val pasta = File(System.getenv("SAIMO_M3U_DIR")
            ?: "/private/tmp/claude-501/-Volumes-SSD-1TB-DEV-Saimo-SaimoPlayer/c5ddbfb0-60d8-4a33-a367-79cbdbb1be68/scratchpad/m3u")
        val m3u = File(pasta, "br.m3u")
        val golden = File(pasta, "br.golden.json")
        assumeTrue(m3u.exists() && golden.exists())
        comparar(m3u.readText(), golden.readText(), "br")
        val canais = LeitorM3u.ler(m3u.readText())
        assertEquals(398, canais.size)
        assertEquals(398, canais.sumOf { it.fontes.size })
        assertEquals(393, canais.count { it.logo != null })
        assertEquals(398, canais.count { it.grupo != null })
    }

    @Test
    fun `lixo binario, linha de 1 MB e vazio nao lancam`() {
        assertTrue(LeitorM3u.ler("").isEmpty())
        assertTrue(LeitorM3u.ler("﻿").isEmpty())
        val rnd = java.util.Random(7)
        val bytes = ByteArray(200_000).also { rnd.nextBytes(it) }
        LeitorM3u.ler(String(bytes, Charsets.UTF_8))
        LeitorM3u.ler(String(bytes, Charsets.ISO_8859_1))
        val um = 1_000_000
        LeitorM3u.ler("#EXTM3U\n#EXTINF:-1," + "[".repeat(um) + "\nhttps://a/b\n")
        LeitorM3u.ler("#EXTINF:-1 tvg-name=\"" + "a".repeat(um) + "\n" + "x".repeat(um))
        LeitorM3u.ler("#EXTINF:-1," + "(".repeat(um / 2) + ")720p".repeat(10) + "\nhttps://a/b|" + "=&".repeat(um / 2))
        LeitorM3u.ler("h".repeat(um))
        LeitorM3u.ler("#EXTINF:-1,A\n" + "https://a/" + "z".repeat(um))
    }

    @Test
    fun `so http e https, sem esquema solto`() {
        val c = LeitorM3u.ler("#EXTM3U\n#EXTINF:-1,A\nrtmp://x/y\nhttps://ok/a\nudp://@1.2.3.4:1\n")
        assertEquals(1, c.size)
        assertEquals("https://ok/a", c[0].fontes[0].url)
        assertEquals("Canal 1", LeitorM3u.ler("http://solto/x.m3u8")[0].nome)
    }

    @Test
    fun `adulto e restrito, Adult Swim nao`() {
        assertTrue(LeitorM3u.ehRestrito("Adult", "Canal"))
        assertTrue(LeitorM3u.ehRestrito("XXX", "Canal"))
        assertTrue(LeitorM3u.ehRestrito(null, "Filmes +18"))
        assertTrue(LeitorM3u.ehRestrito("Erótico", "X"))
        assertFalse(LeitorM3u.ehRestrito("Cartoon", "Adult Swim"))
        assertFalse(LeitorM3u.ehRestrito("Adult Swim Brasil", "Adult Swim Brasil"))
        assertFalse(LeitorM3u.ehRestrito("Abertos", "Sexta Premiere"))
    }
}
