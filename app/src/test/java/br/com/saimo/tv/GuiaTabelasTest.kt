package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** guia.txt: as tabelas por canal que não moram mais no código. */
class GuiaTabelasTest {

    private val dados = GuiaTabelas.parse(Fixtures.ler("guia.txt"))

    @Test
    fun `le cada tipo de linha`() {
        assertEquals("AAA", dados.meuguia["Canal Um"])
        assertEquals("CBR", dados.meuguia["canal-brasil"])
        assertEquals("canal-um", dados.guiadetv["canal um"])
        assertEquals("https://guia.exemplo.test/canal/dois", dados.guiadetv["canal dois"])
        assertEquals("trutv", dados.alias["adult swim"])
        assertEquals(listOf("https://feed1.exemplo.test/epg.xml", "https://feed2.exemplo.test/epg.xml"), dados.feeds)
        assertEquals("https://pluto.exemplo.test/br.xml", dados.pluto)
    }

    @Test
    fun `ignora comentario, linha quebrada, valor vazio e feed que nao e http`() {
        assertTrue("sem codigo" !in dados.meuguia)
        assertTrue(dados.feeds.none { it.startsWith("ftp") })
        assertEquals(3, dados.meuguia.size)
    }

    @Test
    fun `texto vazio ou sem formato nao quebra`() {
        assertTrue(GuiaTabelas.parse("").vazio)
        assertTrue(GuiaTabelas.parse("# so comentario\n\nqualquer coisa").vazio)
    }

    @Test
    fun `as tabelas alimentam as fontes do guia`() {
        GuiaTabelas.definir(dados)
        try {
            assertEquals("canal-um", GuiaDeTv.slug("Canal Um"))
            assertEquals("canal-um", GuiaDeTv.slug("Canal Um (S2)"))
            assertEquals(null, GuiaDeTv.slug("Canal Desconhecido"))
        } finally {
            GuiaTabelas.limpar()
        }
        // Sem o arquivo, nada casa: o app não traz tabela nenhuma.
        assertEquals(null, GuiaDeTv.slug("Canal Um"))
    }
}
