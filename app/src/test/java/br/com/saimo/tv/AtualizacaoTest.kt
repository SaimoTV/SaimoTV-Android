package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtualizacaoTest {

    private val apk = "https://github.com/dono/repo/releases/download/v2.4.0/SaimoTV.apk"

    private fun json(url: String = apk, tag: String = "v2.4.0", sha: String = "ABC123") = """
        {"versao":"2.4.0","tag":"$tag","publicadoEm":"2026-10-19T00:00:00Z","notas":"texto",
         "arquivos":{
          "tvbox":{"nome":"SaimoTV.apk","url":"$url","bytes":4926733,"sha256":"$sha"},
          "celular":{"nome":"SaimoCell.apk","url":"https://evil.com/x.apk","bytes":0,"sha256":""}}}
    """.trimIndent()

    @Test
    fun `le a versao da tv box`() {
        val v = Atualizacao.interpretar(json())!!
        assertEquals("v2.4.0", v.tag)
        assertEquals("2.4.0", v.numero)
        assertEquals("texto", v.notas)
        assertEquals(apk, v.apk)
        assertEquals(4926733L, v.bytes)
        assertEquals("abc123", v.sha256)
    }

    @Test
    fun `sem tag usa o campo versao`() {
        val v = Atualizacao.interpretar(json().replace("\"tag\":\"v2.4.0\",", ""))
        assertEquals("2.4.0", v!!.numero)
    }

    @Test
    fun `json ruim ou incompleto vira sem atualizacao`() {
        assertNull(Atualizacao.interpretar(""))
        assertNull(Atualizacao.interpretar("<html>"))
        assertNull(Atualizacao.interpretar("{}"))
        assertNull(Atualizacao.interpretar("""{"versao":"2.4.0","arquivos":{}}"""))
    }

    @Test
    fun `url fora da lista e recusada`() {
        assertNull(Atualizacao.interpretar(json(url = "https://evil.com/a.apk")))
        assertNull(Atualizacao.interpretar(json(url = "http://github.com/a/b/releases/download/v1/x.apk")))
    }

    @Test
    fun `sha256 ausente e aceito`() {
        assertNull(Atualizacao.interpretar(json(sha = ""))!!.sha256)
    }

    @Test
    fun `lista de enderecos permitidos`() {
        assertTrue(Atualizacao.urlPermitida(apk))
        assertTrue(Atualizacao.urlPermitida("https://objects.githubusercontent.com/github-production/x?a=b"))
        assertTrue(Atualizacao.urlPermitida("https://release-assets.githubusercontent.com/x/y"))
        assertTrue(Atualizacao.urlPermitida("https://GitHub.com:443/a/b/releases/download/v1/x.apk"))
    }

    @Test
    fun `enderecos parecidos ou fora do padrao sao recusados`() {
        assertFalse(Atualizacao.urlPermitida("https://github.com.evil.com/a/b/releases/download/v1/x.apk"))
        assertFalse(Atualizacao.urlPermitida("https://evilgithub.com/a/b/releases/download/v1/x.apk"))
        assertFalse(Atualizacao.urlPermitida("https://github.com@evil.com/a/b/releases/download/v1/x.apk"))
        assertFalse(Atualizacao.urlPermitida("https://objects.githubusercontent.com.evil.com/x"))
        assertFalse(Atualizacao.urlPermitida("https://githubusercontent.com/x"))
        assertFalse(Atualizacao.urlPermitida("http://github.com/a/b/releases/download/v1/x.apk"))
        assertFalse(Atualizacao.urlPermitida("https://github.com/a/b/raw/main/x.apk"))
        assertFalse(Atualizacao.urlPermitida("https://github.com/a/releases/download/v1/x.apk"))
        assertFalse(Atualizacao.urlPermitida("https://github.com:8443/a/b/releases/download/v1/x.apk"))
        assertFalse(Atualizacao.urlPermitida("ftp://github.com/a/b/releases/download/v1/x.apk"))
        assertFalse(Atualizacao.urlPermitida(""))
        assertFalse(Atualizacao.urlPermitida("nao é url"))
    }

    @Test
    fun `sha256 confere ou recusa`() {
        val calc = Atualizacao.hexa(java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", calc)
        assertTrue(Atualizacao.hashConfere(calc.uppercase(), calc))
        assertTrue(Atualizacao.hashConfere(null, calc))
        assertTrue(Atualizacao.hashConfere("", calc))
        assertFalse(Atualizacao.hashConfere("00" + calc.drop(2), calc))
    }

    @Test
    fun `versoes se comparam por numero`() {
        assertTrue(Atualizacao.maisNova("2.10.0", "2.9.3"))
        assertFalse(Atualizacao.maisNova("2.3.2", "2.3.2"))
        assertNotNull(Atualizacao.numeroDaTag("v2.4.0"))
    }
}
