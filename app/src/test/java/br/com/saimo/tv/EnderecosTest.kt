package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A normalização do endereço digitado: a mesma regra em todos os apps Saimo. */
class EnderecosTest {

    private fun n(texto: String) = Enderecos.normalizar(texto)

    @Test
    fun `sem esquema vira https`() {
        assertEquals("https://exemplo.dev/", n("exemplo.dev"))
        assertEquals("https://exemplo.dev/", n("  exemplo.dev  "))
    }

    @Test
    fun `http e https ficam como estao`() {
        assertEquals("http://10.0.2.2:8101/", n("http://10.0.2.2:8101"))
        assertEquals("https://exemplo.dev/", n("https://exemplo.dev"))
        assertEquals("http://exemplo.dev/", n("HTTP://exemplo.dev"))
    }

    @Test
    fun `tira query e fragmento`() {
        assertEquals("https://exemplo.dev/", n("https://exemplo.dev/?a=1"))
        assertEquals("https://exemplo.dev/listas/", n("https://exemplo.dev/listas#topo"))
        assertEquals("https://exemplo.dev/", n("https://exemplo.dev?x=y#z"))
    }

    @Test
    fun `descarta o arquivo colado no fim`() {
        assertEquals("https://exemplo.dev/listas/", n("https://exemplo.dev/listas/catalogo.txt"))
        assertEquals("https://exemplo.dev/", n("https://exemplo.dev/indice.txt"))
        assertEquals("https://exemplo.dev/a/b/", n("exemplo.dev/a/b/x.json?v=2"))
    }

    @Test
    fun `pasta sem barra ganha barra e pasta com ponto no meio fica`() {
        assertEquals("https://exemplo.dev/listas/", n("https://exemplo.dev/listas"))
        assertEquals("https://exemplo.dev/v1.2/listas/", n("https://exemplo.dev/v1.2/listas"))
        assertEquals("https://exemplo.dev/v1.2/", n("https://exemplo.dev/v1.2/"))
    }

    @Test
    fun `vazio e invalido dao nulo`() {
        assertNull(n(""))
        assertNull(n("   "))
        assertNull(n("ftp://exemplo.dev"))
        assertNull(n("file:///etc/passwd"))
        assertNull(n("https://"))
        assertNull(n("https:///caminho"))
        assertNull(n("exemplo dev"))
        assertNull(n("https://exemplo.dev/uma pasta/"))
        assertNull(n("https://usuario@exemplo.dev/"))
    }

    @Test
    fun `e idempotente`() {
        val uma = n("exemplo.dev/listas/catalogo.txt")!!
        assertEquals(uma, n(uma))
    }

    @Test
    fun `os padroes ja estao normalizados`() {
        assertEquals(Enderecos.PADRAO_CANAIS, n(Enderecos.PADRAO_CANAIS))
        assertEquals(Enderecos.PADRAO_FILMES, n(Enderecos.PADRAO_FILMES))
    }
}
