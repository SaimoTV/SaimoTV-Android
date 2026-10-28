package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O parser do catálogo, sobre um arquivo pequeno e inventado (fixtures/): o app
 * não traz lista nenhuma, então os testes também não dependem de uma.
 *
 * O que importa é que nada se perca entre o arquivo e o canal: um logo, referer
 * ou ClearKey perdido é um canal que deixa de funcionar para todo mundo.
 */
class RemoteTest {

    private val texto = Fixtures.ler("catalogo.txt")

    @Test
    fun `a lista restrita carrega os canais e nenhum se repete`() {
        val parsed = Remote.parse(Fixtures.ler("restritos.txt"))
        assertEquals(listOf("Restrito Um", "Restrito Dois"), parsed.map { it.name })
        assertEquals("nome repetido", parsed.size, parsed.map { it.name }.toSet().size)
        assertTrue("canal sem categoria", parsed.all { it.categoria == "Adulto" })
    }

    @Test
    fun `a lista carrega tudo o que o catalogo tem`() {
        val parsed = Remote.parse(texto)
        val blocks = texto.split(Regex("(?m)^canal: *")).drop(1)
        assertTrue("catálogo vazio", blocks.isNotEmpty())
        assertEquals(blocks.map { it.lineSequence().first().trim() }, parsed.map { it.name })
        fun field(block: String, name: String): String? = block.lineSequence()
            .map { it.trim() }.firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        for ((block, channel) in blocks.zip(parsed)) {
            assertEquals(field(block, "logo"), channel.logo)
            assertEquals(field(block, "categoria"), channel.categoria)
            val sources = block.split(Regex("(?m)^fonte: *")).drop(1)
            assertEquals("fontes de ${channel.name}", sources.size, channel.sources.size)
            for ((raw, source) in sources.zip(channel.sources)) {
                assertEquals(raw.lineSequence().first().trim(), source.url)
                assertEquals(field(raw, "referer"), source.referer)
                assertEquals(field(raw, "agente"), source.userAgent)
                val pair = field(raw, "chave")?.split(':')
                if (pair != null) {
                    assertEquals(2, pair.size)
                    assertEquals(pair[0].trim(), source.keyId)
                    assertEquals(pair[1].trim(), source.key)
                } else {
                    assertNull(source.keyId)
                    assertNull(source.key)
                }
            }
        }
    }

    @Test
    fun `canal sem logo continua sem logo, sem lista de reserva por tras`() {
        val semCategoria = Remote.parse(texto).first { it.name == "Canal Sem Categoria" }
        assertNull(semCategoria.logo)
        assertNull(semCategoria.categoria)
        assertEquals(Categorias.OUTROS, Categorias.de(semCategoria))
    }

    @Test
    fun `lista quebrada nao derruba o app`() {
        assertTrue(Remote.parse("").isEmpty())
        assertTrue(Remote.parse("qualquer coisa\nsem formato nenhum").isEmpty())
        // Um canal sem fonte não entra: seria uma linha que nunca toca.
        assertTrue(Remote.parse("canal: Fantasma\nlogo: https://x/y.png").isEmpty())
        // Chave sem par descarta a fonte em vez de tocar sem licença.
        val meio = Remote.parse("canal: X\nfonte: https://x/y.mpd\nchave: soaqui")
        assertTrue(meio.isEmpty())
    }

    @Test
    fun `formato antigo nao vira lista por engano`() {
        val antigo = """
            SAIMO TV — LISTA DE CANAIS
            71 canais · gerado em 11/08/2026

              1. A&E
                 https://exemplo/aie.m3u8
        """.trimIndent()
        assertTrue(Remote.parse(antigo).isEmpty())
    }

    @Test
    fun `sem nada carregado a lista comeca vazia`() {
        Remote.limpar()
        assertTrue(Remote.channels.isEmpty())
        assertTrue(Remote.restritos.isEmpty())
    }
}

/** O par do ClearKey tem de sobreviver inteiro até a licença. */
class ClearKeyTest {

    private val canais = Remote.parse(Fixtures.ler("catalogo.txt"))

    @Test
    fun `playlist txt do novo servidor e tratada como HLS`() {
        val source = Source("https://exemplo.test/ss/adultswim.txt")
        assertTrue(source.isHls)
        assertTrue(!source.isDash)
        assertTrue(Source("https://exemplo.test/cdn/hls/conteudo/master.txt").isHls)
    }

    @Test
    fun `toda fonte DASH com chave tem KID e chave utilizaveis`() {
        val dash = canais.flatMap { c -> c.sources.map { c.name to it } }.filter { it.second.isDash }
        val comChave = dash.filter { it.second.key != null }
        assertTrue("sem DASH com chave no fixture", comChave.isNotEmpty())
        for ((nome, source) in comChave) {
            val kid = source.keyId
            val key = source.key
            assertTrue("$nome: KID inválido ($kid)", kid != null && kid.matches(Regex("[0-9a-fA-F]{32}")))
            assertTrue("$nome: chave inválida ($key)", key != null && key.matches(Regex("[0-9a-fA-F]{32}")))
            assertTrue("$nome: KID igual à chave, par trocado", kid != key)
        }
    }
}

/** A lista publicada soma ao catálogo; não substitui. */
class MergeTest {

    private val base = Remote.parse(Fixtures.ler("catalogo.txt"))

    private val m3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="A&E" tvg-logo="https://exemplo.test/ae.png" group-title="X", A&E (ST)
        http://exemplo.test/ae-reserva.ts
        #EXTINF:-1 tvg-id="Canal Novo" tvg-logo="https://exemplo.test/novo.png" group-title="X", Canal Novo
        http://exemplo.test/novo.ts
    """.trimIndent()

    @Test
    fun `fontes publicadas entram como reserva, sem apagar as do catalogo`() {
        val merged = Remote.merge(base, Remote.parse(m3u))

        val ae = merged.first { it.name == "A&E" }
        val original = base.first { it.name == "A&E" }
        // As do catálogo continuam na frente, na mesma ordem.
        assertEquals(original.sources.map { it.url }, ae.sources.dropLast(1).map { it.url })
        assertEquals("http://exemplo.test/ae-reserva.ts", ae.sources.last().url)
        // E a chave do ClearKey não se perdeu no caminho.
        assertEquals(original.sources.map { it.key }, ae.sources.dropLast(1).map { it.key })

        // O que só existe na lista publicada entra como canal novo, no fim.
        assertEquals("Canal Novo", merged.last().name)
        assertEquals(base.size + 1, merged.size)
    }

    @Test
    fun `sem catalogo, so a lista publicada`() {
        val merged = Remote.merge(emptyList(), Remote.parse(m3u))
        assertEquals(listOf("A&E", "Canal Novo"), merged.map { it.name })
    }
}
