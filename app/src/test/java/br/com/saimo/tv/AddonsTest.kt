package br.com.saimo.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AddonsTest {

    @Before
    fun limparAntes() {
        AddonsStore.limpar()
        DebridStore.Provedor.entries.forEach { DebridStore.definirProvedor(it, "", false) }
        DebridStore.definirSincronizarConnect(false)
    }

    @After
    fun limparDepois() {
        AddonsStore.limpar()
        DebridStore.Provedor.entries.forEach { DebridStore.definirProvedor(it, "", false) }
        DebridStore.definirSincronizarConnect(false)
    }

    // region --- URL normalization & encoding ---

    @Test
    fun `normaliza stremio scheme com config no path e preserva query`() {
        val url = StremioProtocol.normalizarUrl(
            "  stremio://torrentio.strem.fun/sort=quality/manifest.json?config=abc%3D1&lang=pt  "
        )
        assertEquals("https://torrentio.strem.fun/sort=quality", url.baseUrl)
        assertEquals("config=abc%3D1&lang=pt", url.query)
        assertEquals(
            "https://torrentio.strem.fun/sort=quality/manifest.json?config=abc%3D1&lang=pt",
            url.manifestUrl()
        )
        assertEquals(
            "https://torrentio.strem.fun/sort=quality/stream/movie/tt0133093.json?config=abc%3D1&lang=pt",
            url.resourceUrl("stream", "movie", "tt0133093")
        )
    }

    @Test
    fun `normaliza URL sem sufixo manifest e remove barras finais`() {
        val url = StremioProtocol.normalizarUrl("https://addon.exemplo.com/rota/cfg///")
        assertEquals("https://addon.exemplo.com/rota/cfg", url.baseUrl)
        assertEquals("", url.query)
        assertEquals("https://addon.exemplo.com/rota/cfg/manifest.json", url.manifestUrl())
    }

    @Test
    fun `codifica segmentos de catalogo com percent encoding sem plus`() {
        val url = StremioProtocol.normalizarUrl("https://cinemeta.strem.io/manifest.json")
        val busca = url.resourceUrl(
            recurso = "catalog",
            tipo = "series",
            id = "tmdb:1399",
            extra = mapOf("search" to "Guerra dos Tronos"),
        )
        assertEquals(
            "https://cinemeta.strem.io/catalog/series/tmdb:1399/search=Guerra%20dos%20Tronos.json",
            busca
        )
    }

    @Test(expected = StremioProtocol.AddonException::class)
    fun `rejeita esquema nao http`() {
        StremioProtocol.normalizarUrl("ftp://addon.exemplo.com/manifest.json")
    }

    // endregion

    // region --- Nuvio JS rejection & Manifest parsing ---

    @Test
    fun `aceita repositorio JSON com scrapers do Nuvio convertendo para manifesto Stremio`() {
        val nuvioRepo = """
            {
              "name": "Nuvio Community Scrapers",
              "version": "1.0",
              "scrapers": [
                { "id": "provider1", "name": "Provider 1", "url": "https://example.com/provider1.js" }
              ]
            }
        """.trimIndent()
        val m = StremioProtocol.interpretarManifesto(nuvioRepo)
        assertEquals("Nuvio Community Scrapers", m.name)
        assertTrue(m.resources.any { it.nome == "stream" })
    }

    @Test
    fun `rejeita codigo JavaScript bruto de scraper Nuvio`() {
        val js = "module.exports = { getStreams: async function(tmdbId) { return []; } };"
        val erro = runCatching { StremioProtocol.interpretarManifesto(js) }.exceptionOrNull()
        assertTrue(erro is StremioProtocol.AddonException)
        assertTrue(erro!!.message!!.contains("Nuvio"))
    }

    @Test
    fun `interpreta manifesto com resources string e objeto catalogs e behaviorHints`() {
        val json = """
            {
              "id": "org.saimo.teste",
              "name": "Addon Teste",
              "version": "2.4.1",
              "description": "Descricao",
              "logo": "https://example.com/logo.png",
              "types": ["movie", "series"],
              "idPrefixes": ["tt"],
              "resources": [
                "catalog",
                { "name": "stream", "types": ["movie", "series"], "idPrefixes": ["tt", "tmdb:"] },
                { "name": "subtitles", "types": ["movie"], "idPrefixes": ["tt"] }
              ],
              "catalogs": [
                {
                  "type": "movie",
                  "id": "populares",
                  "name": "Populares",
                  "extra": [
                    { "name": "search", "isRequired": false },
                    { "name": "genre", "isRequired": false, "options": ["Ação", "Drama"], "optionsLimit": 1 }
                  ]
                }
              ],
              "behaviorHints": {
                "configurable": true,
                "configurationRequired": false,
                "adult": false,
                "p2p": true
              }
            }
        """.trimIndent()

        val m = StremioProtocol.interpretarManifesto(json)
        assertEquals("org.saimo.teste", m.id)
        assertEquals("Addon Teste", m.name)
        assertEquals("2.4.1", m.version)
        assertEquals(3, m.resources.size)
        assertEquals(1, m.catalogs.size)
        assertTrue(m.catalogs[0].suportaBusca())
        assertTrue(m.catalogs[0].suportaGenero())
        assertTrue(m.behaviorHints.configurable)
        assertTrue(m.behaviorHints.p2p)
        assertTrue(m.suportaRecurso("stream", "movie", "tmdb:550"))
        assertTrue(m.suportaRecurso("subtitles", "movie", "tt0137523"))
        assertFalse(m.suportaRecurso("subtitles", "series", "tt0903747"))
        assertFalse(m.suportaRecurso("subtitles", "movie", "tmdb:550"))
    }

    // endregion

    // region --- Stream classification, headers & bingeGroup ---

    @Test
    fun `classifica streams parseia proxyHeaders request legendas inline e bingeGroup`() {
        val json = """
            {
              "streams": [
                {
                  "name": "Direct HD",
                  "title": "Dual Audio 1080p",
                  "url": "https://cdn.example.com/video.mp4",
                  "subtitles": [
                    { "id": "pt1", "lang": "pob", "url": "https://subs.example.com/pt.srt" }
                  ],
                  "behaviorHints": {
                    "notWebReady": true,
                    "bingeGroup": "grupo-hd-1",
                    "filename": "Movie.2025.1080p.mkv",
                    "videoSize": 2147483648,
                    "videoHash": "deadbeef1234",
                    "proxyHeaders": {
                      "request": {
                        "User-Agent": "CustomUA/1.0",
                        "Referer": "https://player.example.com/",
                        "Cookie": "sid=xyz"
                      }
                    }
                  }
                },
                {
                  "name": "Torrentio",
                  "title": "S01E01 4K HDR",
                  "infoHash": "ABCDEF1234567890ABCDEF1234567890ABCDEF12",
                  "fileIdx": 0,
                  "sources": ["tracker:udp://tracker.opentrackr.org:1337/announce"],
                  "behaviorHints": { "bingeGroup": "torrentio-4k" }
                },
                {
                  "name": "Site Oficial",
                  "externalUrl": "https://stream.oficial.example/watch"
                },
                {
                  "name": "Trailer",
                  "ytId": "dQw4w9WgXcQ"
                }
              ]
            }
        """.trimIndent()

        val streams = StremioProtocol.interpretarStreams(json)
        assertEquals(4, streams.size)

        val direto = streams[0]
        assertEquals(StremioProtocol.TipoStream.DIRETO, direto.tipo)
        assertEquals("grupo-hd-1", direto.behaviorHints.bingeGroup)
        assertEquals("Movie.2025.1080p.mkv", direto.behaviorHints.filename)
        assertEquals(2147483648L, direto.behaviorHints.videoSize)
        assertEquals("deadbeef1234", direto.behaviorHints.videoHash)
        assertTrue(direto.behaviorHints.notWebReady)
        assertEquals("CustomUA/1.0", direto.behaviorHints.requestHeaders["User-Agent"])
        assertEquals("https://player.example.com/", direto.behaviorHints.requestHeaders["Referer"])
        assertEquals("sid=xyz", direto.behaviorHints.requestHeaders["Cookie"])
        assertEquals(1, direto.subtitles.size)
        assertEquals("dub", StremioProtocol.inferirVersaoAudio(direto))
        assertTrue(direto.badges.contains("[Headers]"))
        assertTrue(direto.badges.contains("[Formato pesado]"))

        val torrent = streams[1]
        assertEquals(StremioProtocol.TipoStream.TORRENT_DEBRID, torrent.tipo)
        assertEquals("abcdef1234567890abcdef1234567890abcdef12", torrent.infoHash)
        assertEquals(0, torrent.fileIdx)
        assertTrue(torrent.badges.contains("[Torrent / Debrid]"))

        assertEquals(StremioProtocol.TipoStream.EXTERNO, streams[2].tipo)
        assertTrue(streams[2].badges.contains("[Externo]"))
        assertEquals(StremioProtocol.TipoStream.YOUTUBE, streams[3].tipo)
        assertTrue(streams[3].badges.contains("[YouTube]"))
    }

    @Test
    fun `seleciona proximo episodio priorizando bingeGroup`() {
        val opcoes = listOf(
            Titulos.Opcao("leg", "https://a.com/ep2.mp4", origem = "Addon", bingeGroup = "pack-a"),
            Titulos.Opcao("leg", "https://b.com/ep2.mp4", origem = "Addon", bingeGroup = "pack-b"),
            Titulos.Opcao("leg", "https://c.com/ep2.mp4", origem = "Addon", bingeGroup = "pack-c"),
        )
        assertEquals(2, Titulos.preferida(opcoes, servidor = "a.com", posicao = 0, bingeGroup = "pack-c"))
    }

    // endregion

    // region --- Debrid payload & response parsers ---

    @Test
    fun `monta magnet e seleciona maior video ou fileIdx`() {
        val magnet = DebridResolver.montarMagnet(
            infoHash = "ABC123",
            trackers = listOf("tracker:udp://t.example:80/announce"),
            titulo = "Meu Filme",
        )
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:abc123"))
        assertTrue(magnet.contains("dn=Meu%20Filme"))
        assertTrue(magnet.contains("tr=udp%3A%2F%2Ft.example%3A80%2Fannounce"))

        val arquivos = listOf(
            DebridResolver.ArquivoTorrent("0", "readme.txt", 9_999_999_999L),
            DebridResolver.ArquivoTorrent("1", "ep1.mkv", 500_000_000L),
            DebridResolver.ArquivoTorrent("2", "ep2.mp4", 900_000_000L),
        )
        assertEquals("2", DebridResolver.selecionarArquivoVideo(arquivos, fileIdx = null)?.id)
        assertEquals("1", DebridResolver.selecionarArquivoVideo(arquivos, fileIdx = 1)?.id)
        // fileIdx apontando para arquivo não-vídeo ignora texto e escolhe o maior vídeo
        assertEquals("2", DebridResolver.selecionarArquivoVideo(arquivos, fileIdx = 0)?.id)
    }

    @Test
    fun `interpreta respostas REST de TorBox RealDebrid AllDebrid e Premiumize`() {
        // TorBox
        val tbCache = JSONObject("""{"success":true,"data":{"deadbeef":{"name":"Filme"}}}""")
        assertTrue(DebridResolver.interpretarTorBoxCheckCached(tbCache, "deadbeef"))
        val tbCreate = JSONObject("""{"success":true,"data":{"torrent_id":42}}""")
        assertEquals("42", DebridResolver.interpretarTorBoxCreate(tbCreate))
        val tbLista = JSONObject("""{"success":true,"data":{"id":42,"download_present":true,"files":[{"id":7,"name":"Filme.1080p.mkv","size":1500}]}}""")
        val (tbPronto, tbArqs) = DebridResolver.interpretarTorBoxLista(tbLista, "42")
        assertTrue(tbPronto)
        assertEquals(1, tbArqs.size)
        assertEquals("7", tbArqs[0].id)
        val tbDl = JSONObject("""{"success":true,"data":"https://tb.cdn.example/filme.mkv"}""")
        assertEquals("https://tb.cdn.example/filme.mkv", DebridResolver.interpretarTorBoxRequestDl(tbDl))

        // Real-Debrid
        assertEquals("RD123", DebridResolver.interpretarRealDebridAdd(JSONObject("""{"id":"RD123"}""")))
        val rdInfo = DebridResolver.interpretarRealDebridInfo(
            JSONObject("""{"status":"downloaded","files":[{"id":1,"path":"/Filme.mp4","bytes":2000}],"links":["https://real-debrid.com/d/XYZ"]}""")
        )
        assertEquals("downloaded", rdInfo.status)
        assertEquals("1", rdInfo.arquivos[0].id)
        assertEquals("https://real-debrid.com/d/XYZ", rdInfo.links[0])
        assertEquals(
            "https://download.real-debrid.com/d/XYZ/Filme.mp4",
            DebridResolver.interpretarRealDebridUnrestrict(JSONObject("""{"download":"https://download.real-debrid.com/d/XYZ/Filme.mp4"}"""))
        )

        // AllDebrid
        assertEquals(
            "999",
            DebridResolver.interpretarAllDebridUpload(JSONObject("""{"status":"success","data":{"magnets":[{"id":999,"ready":true}]}}"""))
        )
        val (adPronto, adArqs) = DebridResolver.interpretarAllDebridStatus(
            JSONObject("""{"status":"success","data":{"magnets":{"id":999,"ready":true,"links":[{"link":"https://alldebrid.com/f/1","filename":"Ep.mkv","size":3000}]}}}""")
        )
        assertTrue(adPronto)
        assertEquals("https://alldebrid.com/f/1", adArqs[0].urlDireta)
        assertEquals(
            "https://cdn.alldebrid.com/dl/Ep.mkv",
            DebridResolver.interpretarAllDebridUnlock(JSONObject("""{"status":"success","data":{"link":"https://cdn.alldebrid.com/dl/Ep.mkv"}}"""))
        )

        // Premiumize
        val pmArqs = DebridResolver.interpretarPremiumizeDirectDl(
            JSONObject("""{"status":"success","content":[{"path":"Series/S01E01.mp4","size":4500,"link":"https://pm.cdn/ep1.mp4"}]}""")
        )
        assertEquals(1, pmArqs.size)
        assertEquals("https://pm.cdn/ep1.mp4", pmArqs[0].urlDireta)
    }

    // endregion

    // region --- Store, Debrid opt-in sync & SincroniaConnect backward compatibility ---

    @Test
    fun `comeca vazio e suporta adicionar mover alternar e remover`() {
        assertTrue(AddonsStore.listar().isEmpty())
        val m1 = StremioProtocol.interpretarManifesto("""{"id":"a1","name":"Addon 1","version":"1.0","resources":["stream"]}""")
        val m2 = StremioProtocol.interpretarManifesto("""{"id":"a2","name":"Addon 2","version":"1.0","resources":["catalog"],"catalogs":[{"type":"movie","id":"top","name":"Top"}]}""")
        val a1 = AddonsStore.AddonInstalado("a1", "https://a1.com/manifest.json", m1, true, 1L)
        val a2 = AddonsStore.AddonInstalado("a2", "https://a2.com/manifest.json", m2, true, 2L)

        AddonsStore.salvarOuAtualizar(a1)
        AddonsStore.salvarOuAtualizar(a2)
        assertEquals(listOf("a1", "a2"), AddonsStore.listar().map { it.id })

        assertTrue(AddonsStore.mover("a2", -1))
        assertEquals(listOf("a2", "a1"), AddonsStore.listar().map { it.id })

        AddonsStore.alternar("a1", false)
        assertEquals(listOf("a2"), AddonsStore.ativos().map { it.id })

        assertTrue(AddonsStore.remover("a1"))
        assertEquals(listOf("a2"), AddonsStore.listar().map { it.id })
    }

    @Test
    fun `sincroniaConnect preserva addons locais quando peer legado nao envia chave addons e respeita optin debrid`() {
        val mLocal = StremioProtocol.interpretarManifesto("""{"id":"local.addon","name":"Local","version":"1.0","resources":["stream"]}""")
        AddonsStore.salvarOuAtualizar(
            AddonsStore.AddonInstalado("local.addon", "https://local.example/manifest.json", mLocal, true, 10L)
        )
        DebridStore.definirProvedor(DebridStore.Provedor.TORBOX, "tb-secret-123", true)

        // Padrão: sincronizarConnect desligado NÃO anexa chaves Debrid ao pacote
        val pacoteSemOptIn = SincroniaConnect.anexarAddonsEDebrid(
            pacote = JSONObject().put("t", "dados"),
            addons = AddonsStore.exportarJsonArray(),
            incluirDebrid = DebridStore.sincronizarConnectAtivo(),
            debridJson = if (DebridStore.sincronizarConnectAtivo()) DebridStore.exportarJson(true) else null,
        )
        assertTrue(pacoteSemOptIn.has("addons"))
        assertFalse(pacoteSemOptIn.has("debrid"))

        // Opt-in ligado: anexa chaves Debrid
        DebridStore.definirSincronizarConnect(true)
        val pacoteComOptIn = SincroniaConnect.anexarAddonsEDebrid(
            pacote = JSONObject().put("t", "dados"),
            addons = AddonsStore.exportarJsonArray(),
            incluirDebrid = DebridStore.sincronizarConnectAtivo(),
            debridJson = DebridStore.exportarJson(true),
        )
        assertTrue(pacoteComOptIn.has("debrid"))

        // Peer legado sem campo "addons" NÃO apaga addon local
        SincroniaConnect.aplicarAddonsEDebrid(JSONObject().put("t", "dados").put("progresso", JSONArray()))
        assertEquals(1, AddonsStore.listar().size)
        assertEquals("local.addon", AddonsStore.listar()[0].id)

        // Peer novo enviando "addons" mescla mantendo o local e adicionando o remoto
        val mRemoto = StremioProtocol.interpretarManifesto("""{"id":"remoto.addon","name":"Remoto","version":"1.0","resources":["stream"]}""")
        val arrayRemoto = AddonsStore.paraJsonArray(
            listOf(AddonsStore.AddonInstalado("remoto.addon", "https://remoto.example/manifest.json", mRemoto, true, 20L))
        )
        SincroniaConnect.aplicarAddonsEDebrid(JSONObject().put("t", "dados").put("addons", arrayRemoto))
        assertEquals(setOf("local.addon", "remoto.addon"), AddonsStore.listar().map { it.id }.toSet())
    }

    // endregion

    // region --- QR Code generator ---

    @Test
    fun `gera matriz QR valida com padroes de busca intactos para URLs LAN`() {
        for (url in listOf(
            "http://192.168.1.50:8999/addons",
            "http://192.168.100.255:8999/addons?origem=tv_sala&v=2",
        )) {
            val matriz = QrCodeGerador.gerarMatriz(url)
            val n = matriz.tamanho
            assertTrue(n >= 21)
            assertEquals(1, n % 4)
            assertEquals(n * n, matriz.modulos.size)

            // Valida centros 3x3 dos 3 finder patterns (TL, TR, BL) e módulo escuro fixo
            for (dy in 2..4) {
                for (dx in 2..4) {
                    assertTrue(matriz.ativo(dx, dy))
                    assertTrue(matriz.ativo(n - 7 + dx, dy))
                    assertTrue(matriz.ativo(dx, n - 7 + dy))
                }
            }
            // Separador branco ao redor do finder TL
            assertFalse(matriz.ativo(7, 0))
            assertFalse(matriz.ativo(0, 7))
            // Módulo escuro fixo
            assertTrue(matriz.ativo(8, n - 8))
            assertNull(matriz.modulos.firstOrNull { false }.takeIf { false })
            val escuros = matriz.modulos.count { it }
            assertTrue(escuros > n && escuros < n * n - n)
        }
    }

    // endregion
}
