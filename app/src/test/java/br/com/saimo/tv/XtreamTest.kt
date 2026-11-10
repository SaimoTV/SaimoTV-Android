package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lista com usuário e senha (Xtream): reconhecer o link, normalizar o servidor, ler a API. Sem rede. */
class XtreamTest {

    private fun n(t: String) = Xtream.normalizarServidor(t)

    @Test
    fun `servidor sem esquema ganha http e perde barra e arquivo`() {
        assertEquals("http://exemplo.dev:8080", n("exemplo.dev:8080"))
        assertEquals("http://exemplo.dev:8080", n("  http://exemplo.dev:8080/  "))
        assertEquals("http://exemplo.dev:8080", n("http://exemplo.dev:8080/get.php"))
        assertEquals("https://exemplo.dev", n("HTTPS://EXEMPLO.dev/player_api.php"))
        assertEquals("http://exemplo.dev/painel", n("http://exemplo.dev/painel/"))
        assertEquals("http://10.0.2.2:8765", n("10.0.2.2:8765"))
    }

    @Test
    fun `servidor invalido`() {
        for (ruim in listOf("", "   ", "ftp://x.dev", "http://", "a b.dev", "http://x.dev:abc", "http://u@x.dev", "http://:80")) {
            assertNull("deveria recusar '$ruim'", n(ruim))
        }
    }

    @Test
    fun `reconhece o link de conta`() {
        val c = Xtream.deLink("http://exemplo.dev:8080/get.php?username=ana&password=s%40nha&type=m3u_plus&output=ts")
        assertNotNull(c)
        assertEquals("http://exemplo.dev:8080", c!!.base)
        assertEquals("ana", c.usuario)
        assertEquals("s@nha", c.senha)
        assertEquals("http://exemplo.dev:8080/live/ana/s%40nha/12.m3u8", c.canal("12", "m3u8"))
        assertEquals("http://exemplo.dev:8080/movie/ana/s%40nha/7.mp4", c.filme("7", "mp4"))
        assertEquals("http://exemplo.dev:8080/series/ana/s%40nha/9.mkv", c.episodio("9", "mkv"))
        // O link montado volta a ser reconhecido, com a mesma conta.
        assertEquals(c, Xtream.deLink(c.linkM3u))
    }

    @Test
    fun `link comum nao e conta`() {
        assertNull(Xtream.deLink("https://exemplo.dev/lista.m3u"))
        assertNull(Xtream.deLink("https://exemplo.dev/get.php?username=ana"))
        assertNull(Xtream.deLink("https://exemplo.dev/get.php?username=&password=x"))
        assertNull(Xtream.deLink("lixo"))
    }

    @Test
    fun `entrada da conta`() {
        assertEquals("", Xtream.verificarEntrada("""{"user_info":{"auth":1,"status":"Active","exp_date":"4102444800"}}"""))
        assertEquals("", Xtream.verificarEntrada("""{"user_info":{"auth":"1","status":"Active","exp_date":null}}"""))
        assertEquals("Usuário ou senha incorretos", Xtream.verificarEntrada("""{"user_info":{"auth":0}}"""))
        assertTrue(Xtream.verificarEntrada("""{"user_info":{"auth":1,"status":"Expired"}}""").contains("não está ativa"))
        assertEquals("Essa conta venceu", Xtream.verificarEntrada("""{"user_info":{"auth":1,"status":"Active","exp_date":"1000"}}"""))
        assertEquals(Xtream.SEM_API, Xtream.verificarEntrada("<html>painel</html>"))
        assertEquals(Xtream.SEM_API, Xtream.verificarEntrada("""{"outra":"coisa"}"""))
    }

    @Test
    fun `canais da API com categorias, duas fontes e nome repetido`() {
        val conta = Xtream.Conta("http://s.dev:80", "u", "p")
        val cats = """[{"category_id":"1","category_name":"Abertos"},{"category_id":"3","category_name":"Adultos +18"}]"""
        val streams = """[
            {"stream_id":10,"name":"Globo","stream_icon":"http://img/globo.png","category_id":"1"},
            {"stream_id":11,"name":"Globo","stream_icon":"","category_id":"1"},
            {"stream_id":12,"name":"Canal XXX","stream_icon":"","category_id":"3"},
            {"stream_id":13,"name":"Sem categoria","category_id":"99"},
            {"name":"Sem id"}
        ]"""
        val canais = Xtream.canaisDaApi(conta, streams, cats)
        assertEquals(listOf("Globo", "Canal XXX", "Sem categoria"), canais.map { it.nome })
        val globo = canais[0]
        assertEquals("Abertos", globo.grupo)
        assertEquals("http://img/globo.png", globo.logo)
        assertEquals(listOf("http://s.dev:80/live/u/p/10.m3u8", "http://s.dev:80/live/u/p/10.ts",
            "http://s.dev:80/live/u/p/11.m3u8", "http://s.dev:80/live/u/p/11.ts"), globo.fontes.map { it.url })
        assertTrue(LeitorM3u.ehRestrito(canais[1].grupo, canais[1].nome))
        assertNull(canais[2].grupo)
        assertTrue(Xtream.canaisDaApi(conta, "lixo", "[]").isEmpty())
    }
}
