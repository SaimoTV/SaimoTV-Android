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

    fun pacote(context: Context): JSONObject {
        val tudo = context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).all
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
        for (f in VodFavoritos.lista(context)) {
            favTitulos.put(JSONObject().apply {
                put("tipo", if (f.serie) "s" else "f")
                put("titulo", f.titulo)
                put("ano", f.ano)
                put("letra", f.letra)
            })
        }
        val favCanais = JSONArray()
        Favorites.load(context)
        for (nome in Favorites.todos()) favCanais.put(nome)
        return JSONObject().apply {
            put("t", "dados")
            put("progresso", progresso)
            put("favTitulos", favTitulos)
            put("favCanais", favCanais)
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
        val prefs = context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
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
        for (i in 0 until titulos.length()) {
            val f = titulos.optJSONObject(i) ?: continue
            val titulo = f.optString("titulo").takeIf { it.isNotBlank() } ?: continue
            val serie = f.optString("tipo") == "s"
            val ano = f.optString("ano")
            if (!VodFavoritos.contem(context, titulo, serie, ano)) {
                VodFavoritos.alternar(context, VodFavoritos.Item(titulo, serie, f.optString("letra"), ano))
            }
        }

        val canais = json.optJSONArray("favCanais") ?: JSONArray()
        Favorites.load(context)
        for (i in 0 until canais.length()) {
            val nome = canais.optString(i).takeIf { it.isNotBlank() } ?: continue
            if (!Favorites.contains(nome)) Favorites.toggle(context, nome)
        }

        json.optJSONObject("listas")?.let { runCatching { aplicarListas(context, it) } }
    }
}
