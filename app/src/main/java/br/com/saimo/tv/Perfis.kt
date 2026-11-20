package br.com.saimo.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Perfis: cada um com os seus favoritos, onde parou, recentes, lembretes e
 * último canal; listas, fontes e ajustes são do aparelho todo. Contrato comum
 * dos apps em SaimoPlayer/docs/perfis.md.
 *
 * Armazenamento: cada dado de perfil mora num arquivo de preferências; o perfil
 * principal usa o arquivo de sempre (quem atualiza não perde nada) e os outros
 * o mesmo nome com `@<id>`. Quem guarda dado de perfil pede o arquivo por
 * [prefs] em vez de `getSharedPreferences` direto.
 */
object Perfis {

    const val PRINCIPAL = "principal"
    const val MAX = 10
    const val MAX_NOME = 20

    /** Ids de SaimoPlayer/docs/avatares/ids.txt, na ordem da grade (drawable `avatar_<id>`). */
    val AVATARES = listOf(
        "gato", "cachorro", "raposa", "urso", "panda", "coelho", "coruja", "pinguim", "sapo", "leao", "coala",
        "robo", "alien", "monstro", "dino", "polvo", "pintinho", "tigre", "astronauta", "abelha", "fantasma",
    )

    /** Os arquivos de preferências que são de cada perfil (apagados junto com ele). */
    val ARQUIVOS_DO_PERFIL = listOf("saimo", "vodfav", "progresso", "radios", "lembretes", "novelinhas", "ajustes", "remocoes")

    /** `kids`: perfil infantil (só títulos até 12 anos, nada adulto); `codigo`: SHA-256 de saimo-kids:<id>:<4 dígitos>. */
    data class Perfil(val id: String, val nome: String, val avatar: String, val em: Long,
                      val kids: Boolean = false, val codigo: String = "")
    data class Remocao(val id: String, val em: Long)

    private const val ARQUIVO = "perfis"
    private lateinit var app: Context
    @Volatile private var ativoCache: String? = null
    private val ouvintes = mutableListOf<() -> Unit>()

    fun iniciar(context: Context) {
        app = context.applicationContext
        // Último canal, canal anterior e buscas moravam nos ajustes: o principal continua lá.
        ativoCache = null
    }

    fun estaPronto() = ::app.isInitialized
    fun contexto(): Context = app

    private fun meus(): SharedPreferences = app.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    /** Nome do arquivo de um dado de perfil para o perfil [id] (o ativo, se omitido). */
    fun arquivo(nome: String, id: String = idAtivo()): String = if (id == PRINCIPAL) nome else "$nome@$id"

    fun prefs(context: Context, nome: String, id: String = idAtivo()): SharedPreferences =
        context.applicationContext.getSharedPreferences(arquivo(nome, id), Context.MODE_PRIVATE)

    // ---------- lista ----------

    fun todos(): List<Perfil> {
        val lista = mutableListOf<Perfil>()
        runCatching {
            val a = JSONArray(meus().getString("lista", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                if (lista.any { it.id == id }) continue
                lista += Perfil(id, o.optString("nome").ifBlank { "Perfil" }, o.optString("avatar"), o.optLong("em"),
                    o.optBoolean("kids") && id != PRINCIPAL, o.optString("codigo"))
            }
        }
        val principal = lista.firstOrNull { it.id == PRINCIPAL } ?: Perfil(PRINCIPAL, "Principal", "gato", 0L)
        return (listOf(principal) + lista.filter { it.id != PRINCIPAL }).take(MAX)
    }

    private fun gravar(lista: List<Perfil>) {
        val a = JSONArray()
        for (p in lista) a.put(JSONObject().put("id", p.id).put("nome", p.nome).put("avatar", p.avatar).put("em", p.em).apply {
            if (p.kids) { put("kids", true); put("codigo", p.codigo) }
        })
        meus().edit().putString("lista", a.toString()).apply()
        avisar()
    }

    fun removidos(): List<Remocao> = runCatching {
        val a = JSONArray(meus().getString("removidos", "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Remocao(it.optString("id"), it.optLong("em")) } }
            .filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun guardarRemocao(r: Remocao) {
        val lista = (listOf(r) + removidos().filter { it.id != r.id }).sortedByDescending { it.em }.take(50)
        val a = JSONArray()
        for (x in lista) a.put(JSONObject().put("id", x.id).put("em", x.em))
        meus().edit().putString("removidos", a.toString()).apply()
    }

    fun idAtivo(): String {
        ativoCache?.let { return it }
        if (!::app.isInitialized) return PRINCIPAL
        val id = meus().getString("ativo", PRINCIPAL) ?: PRINCIPAL
        val valido = if (id == PRINCIPAL || todos().any { it.id == id }) id else PRINCIPAL
        ativoCache = valido
        return valido
    }

    fun ativo(): Perfil = todos().firstOrNull { it.id == idAtivo() } ?: todos().first()

    private fun limpar(nome: String) = nome.replace(Regex("\\s+"), " ").trim().take(MAX_NOME)

    private fun novoId(): String {
        val letras = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return "p" + (1..9).map { letras[r.nextInt(letras.length)] }.joinToString("")
    }

    // ---------- Kids ----------

    fun hashCodigo(id: String, codigo: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("saimo-kids:$id:$codigo".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Sair deste perfil, editá-lo ou abrir os Ajustes nele pede o código? */
    fun pedeCodigo(p: Perfil) = p.kids && p.codigo.isNotBlank()

    fun conferirCodigo(p: Perfil, codigo: String) = !pedeCodigo(p) || hashCodigo(p.id, codigo) == p.codigo

    /** O perfil ativo é Kids? */
    fun kidsAtivo(): Boolean = runCatching { ativo().kids }.getOrDefault(false)

    /** `kidsCodigo`: 4 dígitos liga o Kids com esse código; "" desliga; null mantém. */
    fun criar(nome: String, avatar: String, kidsCodigo: String? = null): Perfil? {
        val lista = todos()
        val limpo = limpar(nome)
        if (limpo.isBlank() || lista.size >= MAX) return null
        val id = novoId()
        val kids = kidsCodigo != null && Regex("\\d{4}").matches(kidsCodigo)
        val p = Perfil(id, limpo, avatar, System.currentTimeMillis(), kids, if (kids) hashCodigo(id, kidsCodigo!!) else "")
        gravar(lista + p)
        Connect.avisarMudancaLocal(app, 1000)
        return p
    }

    fun editar(id: String, nome: String, avatar: String, kidsCodigo: String? = null) {
        val limpo = limpar(nome)
        if (limpo.isBlank()) return
        gravar(todos().map {
            if (it.id != id) it else {
                val base = it.copy(nome = limpo, avatar = avatar, em = System.currentTimeMillis())
                when {
                    kidsCodigo == null -> base
                    kidsCodigo.isEmpty() -> base.copy(kids = false, codigo = "")
                    id != PRINCIPAL && Regex("\\d{4}").matches(kidsCodigo) -> base.copy(kids = true, codigo = hashCodigo(id, kidsCodigo))
                    else -> base
                }
            }
        })
        Connect.avisarMudancaLocal(app, 1000)
    }

    /** Apaga o perfil e os dados dele. O principal não sai. */
    fun apagar(id: String) {
        if (id == PRINCIPAL) return
        apagarLocal(id)
        guardarRemocao(Remocao(id, System.currentTimeMillis()))
        Connect.avisarMudancaLocal(app, 1000)
    }

    private fun apagarLocal(id: String) {
        for (nome in ARQUIVOS_DO_PERFIL) {
            val arquivo = arquivo(nome, id)
            app.getSharedPreferences(arquivo, Context.MODE_PRIVATE).edit().clear().commit()
            runCatching { app.deleteSharedPreferences(arquivo) }
        }
        gravar(todos().filter { it.id != id })
        if (idAtivo() == id) definirAtivo(PRINCIPAL)
    }

    // ---------- escolher ----------

    var perguntarAoAbrir: Boolean
        get() = meus().getBoolean("perguntar", true)
        set(v) { meus().edit().putBoolean("perguntar", v).apply() }

    /** Nesta execução do app o perfil já foi escolhido. */
    @Volatile var escolhidoNestaSessao = false

    fun deveEscolherAoAbrir(): Boolean = !escolhidoNestaSessao && perguntarAoAbrir && todos().size >= 2

    private fun definirAtivo(id: String) {
        meus().edit().putString("ativo", id).commit()
        ativoCache = id
    }

    /**
     * Troca o perfil: grava, joga fora o que estava na memória do anterior e
     * avisa quem precisa recarregar. Devolve se mudou.
     */
    fun entrar(context: Context, id: String): Boolean {
        escolhidoNestaSessao = true
        if (id == idAtivo()) return false
        definirAtivo(id)
        // Trocou de perfil: o conteúdo adulto volta a ficar trancado.
        Unlock.bloquear()
        Favorites.load(context)
        VodFavoritos.esquecer()
        avisar()
        return true
    }

    // ---------- avatares ----------

    fun avatarRes(context: Context, avatar: String): Int =
        if (avatar in AVATARES) context.resources.getIdentifier("avatar_$avatar", "drawable", context.packageName) else 0

    // ---------- avisos ----------

    fun assinar(f: () -> Unit) { synchronized(ouvintes) { ouvintes += f } }
    fun cancelar(f: () -> Unit) { synchronized(ouvintes) { ouvintes -= f } }
    private fun avisar() { synchronized(ouvintes) { ouvintes.toList() }.forEach { runCatching { it() } } }

    // ---------- sincronia (Saimo Connect) ----------

    /**
     * Junta a lista de perfis que veio de outro aparelho: remoções primeiro,
     * depois cria os que faltam (havendo vaga) e fica com nome e avatar mais
     * recentes. Devolve os ids que existem aqui depois disso.
     */
    fun mesclar(remotos: List<Perfil>, remocoes: List<Remocao>): Set<String> {
        val locaisRemovidos = removidos().associateBy { it.id }
        for (r in remocoes) {
            if (r.id == PRINCIPAL) continue
            val local = todos().firstOrNull { it.id == r.id }
            if (local != null && local.em <= r.em) apagarLocal(r.id)
            if ((locaisRemovidos[r.id]?.em ?: 0L) < r.em) guardarRemocao(r)
        }
        var lista = todos()
        val removidosAgora = removidos().associateBy { it.id }
        for (p in remotos) {
            if (p.id.isBlank()) continue
            val remocao = removidosAgora[p.id]
            if (remocao != null && remocao.em >= p.em) continue
            val local = lista.firstOrNull { it.id == p.id }
            lista = when {
                local == null && lista.size < MAX && p.nome.isNotBlank() ->
                    lista + p.copy(nome = limpar(p.nome), kids = p.kids && p.id != PRINCIPAL)
                local != null && p.em > local.em && p.nome.isNotBlank() ->
                    lista.map { if (it.id == p.id) it.copy(nome = limpar(p.nome), avatar = p.avatar, em = p.em,
                        kids = p.kids && p.id != PRINCIPAL, codigo = p.codigo) else it }
                else -> lista
            }
        }
        if (lista != todos()) gravar(lista)
        return lista.map { it.id }.toSet()
    }
}
