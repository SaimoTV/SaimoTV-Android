package br.com.saimo.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sincronia do Saimo Connect: onde parou em filmes e episódios, títulos
 * favoritos e canais favoritos, entre a TV e o celular.
 *
 * Os dois lados mandam tudo ao conectar (`{"t":"dados"}`; "sync" já é o
 * pedido de estado da TV) e de novo quando algo
 * muda com o celular conectado. O formato é por título (o mesmo nos dois apps):
 *
 *   progresso:  {tipo:"f"|"s", titulo, ano, letra, temporada, episodio, pos, dur, em}
 *               pos e dur em milissegundos; em = quando foi visto (ms desde 1970)
 *   favTitulos: {tipo, titulo, ano, letra}
 *   favCanais:  nome do canal
 *   listas:     {canais, filmes, em, minhas: [{nome, link}]} — os dois endereços
 *               (em = quando foram escolhidos, ms) e as listas adicionadas por link
 *
 * Progresso: vale o mais recente. Favoritos: os dois lados ficam com a união
 * (desmarcar num aparelho não desmarca no outro). Endereços: vale o escolhido
 * por último; quem nunca escolheu (padrão ou vazio) fica com o que o outro
 * escolheu. Listas adicionadas: união pelo link (a que veio de arquivo fica
 * só no aparelho).
 */
object SincroniaConnect {

    private const val ARQUIVO = "progresso"

    /** Onde parou, títulos e canais favoritos de um perfil, no formato do pacote. */
    private fun dadosDoPerfil(context: Context, id: String): Triple<JSONArray, JSONArray, JSONArray> {
        val tudo = Perfis.prefs(context, ARQUIVO, id).all
        val progresso = JSONArray()
        for ((chave, valor) in tudo) {
            if (!(chave.startsWith("f|") || chave.startsWith("s|")) || chave.endsWith("|d") || chave.endsWith("|t")) continue
            val pos = valor as? Long ?: continue
            if (pos <= 0) continue
            val campos = chave.split("|")
            val serie = campos[0] == "s"
            val meta = (tudo["m|$chave"] as? String)?.split("\t")
            val titulo = meta?.getOrNull(0)?.takeIf { it.isNotBlank() } ?: campos.getOrNull(1) ?: continue
            progresso.put(JSONObject().apply {
                put("tipo", if (serie) "s" else "f")
                put("titulo", titulo)
                put("ano", meta?.getOrNull(3).orEmpty())
                put("letra", meta?.getOrNull(2).orEmpty())
                put("temporada", campos.getOrNull(2)?.toIntOrNull() ?: 0)
                put("episodio", campos.getOrNull(3)?.toIntOrNull() ?: 0)
                put("pos", pos)
                put("dur", tudo["$chave|d"] as? Long ?: 0L)
                put("em", tudo["$chave|t"] as? Long ?: 0L)
            })
        }
        val favTitulos = JSONArray()
        for (f in lerFavTitulos(context, id)) {
            favTitulos.put(JSONObject().apply {
                put("tipo", if (f.serie) "s" else "f")
                put("titulo", f.titulo)
                put("ano", f.ano)
                put("letra", f.letra)
            })
        }
        val favCanais = JSONArray()
        for (nome in lerFavCanais(context, id)) favCanais.put(nome)
        return Triple(progresso, favTitulos, favCanais)
    }

    // Favoritos de qualquer perfil, direto do arquivo dele (o VodFavoritos e o
    // Favorites só conhecem o perfil ativo).
    private const val ARQ_FAV_TITULOS = "vodfav"
    private const val ARQ_FAV_CANAIS = "saimo"

    private fun lerFavTitulos(context: Context, id: String): List<VodFavoritos.Item> =
        Perfis.prefs(context, ARQ_FAV_TITULOS, id).getString("lista", "").orEmpty().lineSequence().mapNotNull { linha ->
            val c = linha.split("\t")
            if (c.size < 3 || c[2].isBlank()) null else VodFavoritos.Item(c[2], c[0] == "s", c[1], c.getOrNull(3).orEmpty())
        }.toList()

    private fun gravarFavTitulos(context: Context, id: String, itens: List<VodFavoritos.Item>) {
        val texto = itens.joinToString("\n") { "${if (it.serie) "s" else "f"}\t${it.letra}\t${it.titulo}\t${it.ano}" }
        Perfis.prefs(context, ARQ_FAV_TITULOS, id).edit().putString("lista", texto).commit()
    }

    private fun lerFavCanais(context: Context, id: String): Set<String> =
        Perfis.prefs(context, ARQ_FAV_CANAIS, id).getStringSet("favorites", emptySet()).orEmpty().toSet()

    fun pacote(context: Context): JSONObject {
        val (progresso, favTitulos, favCanais) = dadosDoPerfil(context, Perfis.PRINCIPAL)
        val perfis = JSONArray()
        for (p in Perfis.todos()) {
            val (pr, ft, fc) = if (p.id == Perfis.PRINCIPAL) Triple(progresso, favTitulos, favCanais) else dadosDoPerfil(context, p.id)
            perfis.put(JSONObject().put("id", p.id).put("nome", p.nome).put("avatar", p.avatar).put("em", p.em)
                .put("progresso", pr).put("favTitulos", ft).put("favCanais", fc)
                .apply { if (p.kids) { put("kids", true); put("codigo", p.codigo) } })
        }
        val removidos = JSONArray()
        for (r in Perfis.removidos()) removidos.put(JSONObject().put("id", r.id).put("em", r.em))
        return JSONObject().apply {
            put("t", "dados")
            // Os campos de sempre levam o perfil principal: apps sem perfis seguem funcionando.
            put("progresso", progresso)
            put("favTitulos", favTitulos)
            put("favCanais", favCanais)
            put("perfis", perfis)
            put("perfisRemovidos", removidos)
            put("listas", JSONObject().apply {
                put("canais", Enderecos.canais.orEmpty())
                put("filmes", Enderecos.filmes.orEmpty())
                put("em", Enderecos.escolhidosEm(context))
                put("minhas", JSONArray().apply {
                    for (l in MinhasListas.todas()) {
                        val link = l.link ?: continue
                        put(JSONObject().put("nome", l.nome).put("link", link))
                    }
                })
            })
        }.also { pacoteJson ->
            anexarAddonsEDebrid(
                pacote = pacoteJson,
                addons = AddonsStore.exportarJsonArray(),
                incluirDebrid = DebridStore.sincronizarConnectAtivo(),
                debridJson = if (DebridStore.sincronizarConnectAtivo()) DebridStore.exportarJson(true) else null,
            )
        }
    }

    /** Anexa `addons` e (apenas quando opt-in) `debrid` ao pacote `{t:"dados"}`. */
    fun anexarAddonsEDebrid(
        pacote: JSONObject,
        addons: JSONArray,
        incluirDebrid: Boolean,
        debridJson: JSONObject? = null,
    ): JSONObject {
        pacote.put("addons", addons)
        if (incluirDebrid && debridJson != null) {
            pacote.put("debrid", debridJson)
        }
        return pacote
    }

    /**
     * Aplica `addons` (somente se o campo vier como JSONArray) e `debrid` (se presente),
     * preservando addons locais quando o peer remoto enviar `{t:"dados"}` legado sem `"addons"`.
     */
    fun aplicarAddonsEDebrid(json: JSONObject) {
        val addonsRemotos = if (json.has("addons")) json.optJSONArray("addons") else null
        if (addonsRemotos != null) {
            AddonsStore.mesclarDoSync(addonsRemotos)
        }
        val debridObj = if (json.has("debrid")) json.optJSONObject("debrid") else null
        if (debridObj != null) {
            DebridStore.aplicarDoSync(debridObj)
        }
    }

    /** Os endereços e as listas adicionadas que vieram do celular. */
    private suspend fun aplicarListas(context: Context, l: JSONObject) {
        val em = l.optLong("em")
        val local = Enderecos.escolhidosEm(context)
        fun ler(chave: String): String? = l.optString(chave).takeIf { it.isNotBlank() }?.let { Enderecos.normalizar(it) }
        if (l.has("canais") && l.has("filmes")) {
            val canais = ler("canais")
            val filmes = ler("filmes")
            var c = Enderecos.canais
            var f = Enderecos.filmes
            if (em > local) {
                c = canais; f = filmes
            } else if (local == 0L) {
                // Aqui nunca se escolheu nada: fica o que o celular escolheu
                // (e um endereço que o celular não tem não apaga o daqui).
                if (canais != null) c = canais
                if (filmes != null) f = filmes
            }
            if (c != Enderecos.canais || f != Enderecos.filmes) {
                Enderecos.definir(context, c, f, if (em > local) em else System.currentTimeMillis())
                Listas.recarregar(context)
            }
        }
        val minhas = l.optJSONArray("minhas") ?: return
        var novas = 0
        for (i in 0 until minhas.length()) {
            val m = minhas.optJSONObject(i) ?: continue
            val link = m.optString("link").trim()
            if (!(link.startsWith("http://") || link.startsWith("https://"))) continue
            if (MinhasListas.todas().any { it.link == link }) continue
            runCatching { MinhasListas.adicionar(m.optString("nome"), link, MinhasListas.baixar(link)) }
                .onSuccess { novas++ }
        }
        if (novas > 0) runCatching { Remote.refresh(context, forcar = false) }
    }

    /** Junta o que veio do celular ao que a TV tem. */
    suspend fun aplicar(context: Context, json: JSONObject) {
        val lista = json.optJSONArray("perfis")
        if (lista == null) {
            // App sem perfis do outro lado: os campos de sempre são do perfil principal.
            aplicarDoPerfil(context, Perfis.PRINCIPAL, json)
        } else {
            val remotos = mutableListOf<Pair<Perfis.Perfil, JSONObject>>()
            for (i in 0 until lista.length()) {
                val o = lista.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                remotos += Perfis.Perfil(id, o.optString("nome"), o.optString("avatar"), o.optLong("em"),
                    o.optBoolean("kids"), o.optString("codigo")) to o
            }
            val remocoes = mutableListOf<Perfis.Remocao>()
            json.optJSONArray("perfisRemovidos")?.let { r ->
                for (i in 0 until r.length()) r.optJSONObject(i)?.let { remocoes += Perfis.Remocao(it.optString("id"), it.optLong("em")) }
            }
            val existem = Perfis.mesclar(remotos.map { it.first }, remocoes)
            for ((p, o) in remotos) if (p.id in existem) aplicarDoPerfil(context, p.id, o)
        }
        // O que está na memória é do perfil ativo: relê depois de juntar.
        Favorites.load(context)
        VodFavoritos.esquecer()

        json.optJSONObject("listas")?.let { runCatching { aplicarListas(context, it) } }
        runCatching { aplicarAddonsEDebrid(json) }
    }
    /** Junta onde parou e os favoritos de um perfil vindos do outro aparelho. */
    private fun aplicarDoPerfil(context: Context, id: String, json: JSONObject) {
        val prefs = Perfis.prefs(context, ARQUIVO, id)
        val editor = prefs.edit()
        val lista = json.optJSONArray("progresso") ?: JSONArray()
        for (i in 0 until lista.length()) {
            val p = lista.optJSONObject(i) ?: continue
            val titulo = p.optString("titulo").takeIf { it.isNotBlank() } ?: continue
            val serie = p.optString("tipo") == "s"
            val ano = p.optString("ano")
            val letra = p.optString("letra")
            val temporada = p.optInt("temporada")
            val episodio = p.optInt("episodio")
            val pos = p.optLong("pos")
            val em = p.optLong("em")
            if (pos <= 0 || (serie && (temporada <= 0 || episodio <= 0))) continue
            // A série é guardada com o ano no nome, como no resto do app.
            val nome = if (serie && ano.isNotBlank()) "$titulo ($ano)" else titulo
            val chave = if (serie) Progresso.chaveEpisodio(nome, temporada, episodio) else Progresso.chaveFilme(nome)
            if (prefs.getLong("$chave|t", 0L) >= em) continue
            editor.putLong(chave, pos)
                .putLong("$chave|d", p.optLong("dur"))
                .putLong("$chave|t", em)
            val meta = listOf(titulo, if (serie) "s" else "f", letra, ano, "", "0").joinToString("\t")
            if (!prefs.contains("m|$chave")) editor.putString("m|$chave", meta)
            if (serie && prefs.getLong("u|$nome|t", 0L) < em) {
                editor.putString("u|$nome", "$meta\t$temporada\t$episodio").putLong("u|$nome|t", em)
            }
        }
        editor.apply()

        val titulos = json.optJSONArray("favTitulos") ?: JSONArray()
        val atuais = lerFavTitulos(context, id).toMutableList()
        var mudou = false
        for (i in 0 until titulos.length()) {
            val f = titulos.optJSONObject(i) ?: continue
            val titulo = f.optString("titulo").takeIf { it.isNotBlank() } ?: continue
            val serie = f.optString("tipo") == "s"
            val ano = f.optString("ano")
            if (atuais.none { it.titulo == titulo && it.serie == serie && it.ano == ano }) {
                atuais.add(0, VodFavoritos.Item(titulo, serie, f.optString("letra"), ano))
                mudou = true
            }
        }
        if (mudou) gravarFavTitulos(context, id, atuais)

        val canais = json.optJSONArray("favCanais") ?: JSONArray()
        val favs = lerFavCanais(context, id).toMutableSet()
        val antes = favs.size
        for (i in 0 until canais.length()) canais.optString(i).takeIf { it.isNotBlank() }?.let { favs += it }
        if (favs.size != antes) Perfis.prefs(context, ARQ_FAV_CANAIS, id).edit().putStringSet("favorites", HashSet(favs)).commit()
    }

}
