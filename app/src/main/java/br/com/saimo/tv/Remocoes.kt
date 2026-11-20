package br.com.saimo.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registro do que foi removido ("Continuar assistindo", favoritos, canais
 * favoritos, Minhas listas), para a sincronia (Conta Saimo e Saimo Connect) não
 * trazer de volta. Contrato em SaimoPlayer/docs/perfis.md: cada remoção é
 * `{…, em}` e um item só fica se for mais novo que ela (sem `em` conta como 0).
 * As mesmas regras do servidor (SaimoConta/src/juntar.ts).
 *
 * Por perfil (arquivo "remocoes"): prog e fav ({tipo, titulo, ano, em}; ano
 * vazio vale para qualquer ano), canal ({nome, em}), favEm e canalEm (quando
 * cada favorito entrou). Minhas listas (do aparelho): rem ({link, em}) e em por link.
 */
object Remocoes {

    private const val ARQUIVO = "remocoes"
    private const val CHAVE = "json"
    private const val MAX = 300
    private const val VIDA = 180L * 24 * 3600_000

    class Registro(
        val prog: MutableList<JSONObject> = mutableListOf(),
        val fav: MutableList<JSONObject> = mutableListOf(),
        val canal: MutableList<JSONObject> = mutableListOf(),
        val favEm: MutableMap<String, Long> = mutableMapOf(),
        val canalEm: MutableMap<String, Long> = mutableMapOf(),
    )

    private fun lista(a: JSONArray?): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += it }
        return out
    }

    private fun mapa(o: JSONObject?): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        if (o != null) for (k in o.keys()) out[k] = o.optLong(k)
        return out
    }

    private fun tipo(o: JSONObject) = if (o.optString("tipo") == "s") "s" else "f"
    fun chaveTitulo(tipo: String, titulo: String, ano: String) = "$tipo|$titulo|$ano"
    private fun chaveRemTitulo(o: JSONObject) = o.optString("titulo").takeIf { it.isNotBlank() }?.let { chaveTitulo(tipo(o), it, o.optString("ano")) }

    /** Junta pela chave (vale o `em` maior); as velhas demais saem. */
    private fun juntarPor(a: List<JSONObject>, b: List<JSONObject>, chave: (JSONObject) -> String?): MutableList<JSONObject> {
        val m = LinkedHashMap<String, JSONObject>()
        val limite = System.currentTimeMillis() - VIDA
        for (r in a + b) {
            val k = chave(r) ?: continue
            val em = r.optLong("em")
            if (em <= 0 || em < limite) continue
            val atual = m[k]
            if (atual == null || em > atual.optLong("em")) m[k] = r
        }
        return m.values.sortedByDescending { it.optLong("em") }.take(MAX).toMutableList()
    }

    fun tituloRemovido(rem: List<JSONObject>, tipo: String, titulo: String, ano: String, emItem: Long): Boolean =
        rem.any {
            tipo(it) == tipo && it.optString("titulo") == titulo &&
                (it.optString("ano").isBlank() || ano.isBlank() || it.optString("ano") == ano) && it.optLong("em") >= emItem
        }

    fun canalRemovido(r: Registro, nome: String, emItem: Long = r.canalEm[nome] ?: 0L): Boolean =
        r.canal.any { it.optString("nome") == nome && it.optLong("em") >= emItem }

    @Synchronized
    fun ler(context: Context, id: String): Registro {
        val o = runCatching { JSONObject(Perfis.prefs(context, ARQUIVO, id).getString(CHAVE, "{}") ?: "{}") }.getOrElse { JSONObject() }
        return Registro(lista(o.optJSONArray("prog")), lista(o.optJSONArray("fav")), lista(o.optJSONArray("canal")),
            mapa(o.optJSONObject("favEm")), mapa(o.optJSONObject("canalEm")))
    }

    @Synchronized
    fun gravar(context: Context, id: String, r: Registro) {
        val o = JSONObject()
            .put("prog", JSONArray(juntarPor(r.prog, emptyList(), ::chaveRemTitulo)))
            .put("fav", JSONArray(juntarPor(r.fav, emptyList(), ::chaveRemTitulo)))
            .put("canal", JSONArray(juntarPor(r.canal, emptyList()) { it.optString("nome").takeIf { n -> n.isNotBlank() } }))
            .put("favEm", JSONObject(r.favEm as Map<*, *>))
            .put("canalEm", JSONObject(r.canalEm as Map<*, *>))
        Perfis.prefs(context, ARQUIVO, id).edit().putString(CHAVE, o.toString()).commit()
    }

    /** Junta ao registro do perfil as remoções que vieram de fora (perfil do pacote). */
    fun juntar(context: Context, id: String, chegou: JSONObject): Registro {
        val r = ler(context, id)
        val canalEm = r.canalEm
        chegou.optJSONObject("favCanaisEm")?.let { m -> for (k in m.keys()) if (m.optLong(k) > (canalEm[k] ?: 0L)) canalEm[k] = m.optLong(k) }
        chegou.optJSONArray("favTitulos")?.let { a ->
            for (f in lista(a)) {
                val k = chaveTitulo(tipo(f), f.optString("titulo"), f.optString("ano"))
                if (f.optLong("em") > (r.favEm[k] ?: 0L)) r.favEm[k] = f.optLong("em")
            }
        }
        val novo = Registro(
            juntarPor(r.prog, lista(chegou.optJSONArray("progressoRemovido")), ::chaveRemTitulo),
            juntarPor(r.fav, lista(chegou.optJSONArray("favTitulosRemovidos")), ::chaveRemTitulo),
            juntarPor(r.canal, lista(chegou.optJSONArray("favCanaisRemovidos"))) { it.optString("nome").takeIf { n -> n.isNotBlank() } },
            r.favEm, canalEm,
        )
        gravar(context, id, novo)
        return novo
    }

    /** As remoções no perfil do pacote. */
    fun anexar(perfil: JSONObject, r: Registro) {
        if (r.prog.isNotEmpty()) perfil.put("progressoRemovido", JSONArray(r.prog))
        if (r.fav.isNotEmpty()) perfil.put("favTitulosRemovidos", JSONArray(r.fav))
        if (r.canal.isNotEmpty()) perfil.put("favCanaisRemovidos", JSONArray(r.canal))
        if (r.canalEm.isNotEmpty()) perfil.put("favCanaisEm", JSONObject(r.canalEm as Map<*, *>))
    }

    private fun titulo(tipo: String, titulo: String, ano: String) =
        JSONObject().put("tipo", tipo).put("titulo", titulo).put("ano", ano).put("em", System.currentTimeMillis())

    /** Tirou do "Continuar assistindo" (perfil ativo). */
    fun anotarContinuar(context: Context, serie: Boolean, titulo: String, ano: String) {
        if (titulo.isBlank()) return
        val id = Perfis.idAtivo()
        val r = ler(context, id)
        r.prog += titulo(if (serie) "s" else "f", titulo, ano)
        gravar(context, id, r)
    }

    /** Desfavoritou (removido) ou favoritou um título (perfil ativo). */
    fun anotarFavorito(context: Context, serie: Boolean, titulo: String, ano: String, removido: Boolean) {
        val id = Perfis.idAtivo()
        val r = ler(context, id)
        val tipo = if (serie) "s" else "f"
        val k = chaveTitulo(tipo, titulo, ano)
        if (removido) { r.fav += titulo(tipo, titulo, ano); r.favEm.remove(k) }
        else { r.fav.removeAll { chaveRemTitulo(it) == k }; r.favEm[k] = System.currentTimeMillis() }
        gravar(context, id, r)
    }

    /** Desfavoritou (removido) ou favoritou um canal (perfil ativo). */
    fun anotarCanal(context: Context, nome: String, removido: Boolean) {
        val id = Perfis.idAtivo()
        val r = ler(context, id)
        if (removido) { r.canal += JSONObject().put("nome", nome).put("em", System.currentTimeMillis()); r.canalEm.remove(nome) }
        else { r.canal.removeAll { it.optString("nome") == nome }; r.canalEm[nome] = System.currentTimeMillis() }
        gravar(context, id, r)
    }

    // ---------- Minhas listas (do aparelho) ----------

    class RegistroListas(val rem: MutableList<JSONObject>, val em: MutableMap<String, Long>)

    private fun prefsListas(context: Context) = context.getSharedPreferences("remocoes_listas", Context.MODE_PRIVATE)

    @Synchronized
    fun lerListas(context: Context): RegistroListas {
        val o = runCatching { JSONObject(prefsListas(context).getString(CHAVE, "{}") ?: "{}") }.getOrElse { JSONObject() }
        return RegistroListas(lista(o.optJSONArray("rem")), mapa(o.optJSONObject("em")))
    }

    @Synchronized
    fun gravarListas(context: Context, r: RegistroListas) {
        val o = JSONObject()
            .put("rem", JSONArray(juntarPor(r.rem, emptyList()) { it.optString("link").trim().takeIf { l -> l.isNotBlank() } }))
            .put("em", JSONObject(r.em as Map<*, *>))
        prefsListas(context).edit().putString(CHAVE, o.toString()).commit()
    }

    fun juntarListas(context: Context, chegou: JSONArray?): RegistroListas {
        val r = lerListas(context)
        val novo = RegistroListas(juntarPor(r.rem, lista(chegou)) { it.optString("link").trim().takeIf { l -> l.isNotBlank() } }, r.em)
        gravarListas(context, novo)
        return novo
    }

    fun listaRemovida(r: RegistroListas, link: String, emItem: Long = r.em[link] ?: 0L): Boolean =
        r.rem.any { it.optString("link").trim() == link && it.optLong("em") >= emItem }

    fun anotarLista(context: Context, link: String?, removida: Boolean) {
        if (link.isNullOrBlank()) return
        val r = lerListas(context)
        if (removida) { r.rem += JSONObject().put("link", link).put("em", System.currentTimeMillis()); r.em.remove(link) }
        else { r.rem.removeAll { it.optString("link").trim() == link }; r.em[link] = System.currentTimeMillis() }
        gravarListas(context, r)
    }
}
