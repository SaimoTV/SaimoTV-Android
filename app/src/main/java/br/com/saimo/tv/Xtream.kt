package br.com.saimo.tv

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Lista com usuário e senha (o formato "Xtream Codes" que a maioria dos
 * provedores de IPTV usa).
 *
 * Uma conta Xtream é guardada como um link M3U comum
 * (`http://servidor:porta/get.php?username=…&password=…&type=m3u_plus`): assim
 * ela viaja pelo Saimo Connect e entra em "Minhas listas" como qualquer outra.
 * Quem cola esse link direto, ou digita servidor + usuário + senha, cai no
 * mesmo caminho: o app reconhece a conta e lê os canais pela API do servidor
 * (`player_api.php`), que é mais leve e traz as categorias. Se a API não
 * responde, vale o M3U do próprio `get.php`.
 *
 * A senha nunca é mostrada de volta nem entra em log ou em mensagem de erro.
 */
object Xtream {

    data class Conta(val base: String, val usuario: String, val senha: String) {
        val linkM3u: String get() = linkM3u(base, usuario, senha)
        fun api(acao: String? = null, extra: String = ""): String =
            "$base/player_api.php?username=${cod(usuario)}&password=${cod(senha)}" +
                (acao?.let { "&action=$it" } ?: "") + extra
        fun canal(id: String, ext: String) = "$base/live/${cod(usuario)}/${cod(senha)}/$id.$ext"
        fun filme(id: String, ext: String) = "$base/movie/${cod(usuario)}/${cod(senha)}/$id.$ext"
        fun episodio(id: String, ext: String) = "$base/series/${cod(usuario)}/${cod(senha)}/$id.$ext"
    }

    private fun cod(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun linkM3u(base: String, usuario: String, senha: String) =
        "$base/get.php?username=${cod(usuario)}&password=${cod(senha)}&type=m3u_plus&output=ts"

    /**
     * Servidor digitado -> base sem barra no fim (`http://host:porta`, com o
     * caminho do painel, se houver). Sem esquema ganha `http://` (a quase
     * totalidade dos painéis não tem https). Nulo se não parece um endereço.
     */
    fun normalizarServidor(texto: String): String? {
        var t = texto.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (!t.contains("://")) t = "http://$t"
        val m = Regex("^(https?)://([^/?#]+)([^?#]*)", RegexOption.IGNORE_CASE).find(t) ?: return null
        val esquema = m.groupValues[1].lowercase(Locale.ROOT)
        val host = m.groupValues[2]
        if (!Regex("^(?:[A-Za-z0-9]([A-Za-z0-9.\\-]*[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]+\\])(?::\\d{1,5})?$").matches(host)) return null
        var caminho = m.groupValues[3]
        for (arquivo in listOf("/get.php", "/player_api.php", "/panel_api.php", "/c", "/c/")) {
            if (caminho.equals(arquivo, true)) caminho = ""
        }
        caminho = caminho.trimEnd('/')
        return "$esquema://${host.lowercase(Locale.ROOT)}$caminho"
    }

    /** Reconhece o link de uma conta Xtream (`get.php` ou `player_api.php` com usuário e senha). */
    fun deLink(link: String): Conta? {
        val t = link.trim()
        val m = Regex("^(https?://[^/?#]+(?:/[^?#]*?)?)/(?:get|player_api)\\.php\\?(.*)$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val consulta = m.groupValues[2].substringBefore('#')
        val params = consulta.split('&').mapNotNull { par ->
            val i = par.indexOf('=')
            if (i < 0) null else par.substring(0, i).lowercase(Locale.ROOT) to runCatching {
                URLDecoder.decode(par.substring(i + 1), "UTF-8")
            }.getOrDefault(par.substring(i + 1))
        }.toMap()
        val u = params["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val s = params["password"]?.takeIf { it.isNotEmpty() } ?: return null
        val base = normalizarServidor(m.groupValues[1]) ?: return null
        return Conta(base, u, s)
    }

    // MARK: - Resposta da API (sem rede: testável)

    private fun texto(o: JSONObject, k: String): String = o.opt(k)?.let {
        if (it == JSONObject.NULL) "" else it.toString().trim()
    }.orEmpty()

    /** O servidor não falou o idioma Xtream (sem `player_api.php`): resta tentar o M3U. */
    const val SEM_API = "\u0000sem-api"

    /** Resultado do `player_api.php` sem ação: `""` se a conta vale; [SEM_API]; ou o motivo de recusa. */
    fun verificarEntrada(json: String): String {
        val raiz = runCatching { JSONObject(json) }.getOrNull() ?: return SEM_API
        val info = raiz.optJSONObject("user_info") ?: return SEM_API
        val auth = texto(info, "auth")
        if (auth != "1" && !auth.equals("true", true)) return "Usuário ou senha incorretos"
        val status = texto(info, "status")
        if (status.isNotEmpty() && !status.equals("Active", true)) return "Essa conta não está ativa ($status)"
        val exp = texto(info, "exp_date").toLongOrNull()
        if (exp != null && exp in 1..(System.currentTimeMillis() / 1000)) return "Essa conta venceu"
        return ""
    }

    private fun categorias(json: String): Map<String, String> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).associate {
            val o = a.getJSONObject(it)
            texto(o, "category_id") to texto(o, "category_name")
        }
    }.getOrDefault(emptyMap())

    /** `get_live_streams` + categorias -> canais, com `.m3u8` e `.ts` como duas fontes. */
    fun canaisDaApi(conta: Conta, streams: String, categorias: String): List<LeitorM3u.CanalM3u> {
        val cats = categorias(categorias)
        val a = runCatching { JSONArray(streams) }.getOrNull() ?: return emptyList()
        val porNome = LinkedHashMap<String, LeitorM3u.CanalM3u>()
        for (i in 0 until a.length()) {
            if (porNome.size >= LeitorM3u.LIMITE_CANAIS) break
            val o = a.optJSONObject(i) ?: continue
            val id = texto(o, "stream_id").ifEmpty { continue }
            var nome = texto(o, "name")
            if (nome.isEmpty()) nome = "Canal ${porNome.size + 1}"
            val grupo = cats[texto(o, "category_id")]?.ifEmpty { null }
            val logo = texto(o, "stream_icon").takeIf { it.startsWith("http", true) }
            val fontes = listOf(
                LeitorM3u.FonteM3u(conta.canal(id, "m3u8")),
                LeitorM3u.FonteM3u(conta.canal(id, "ts")),
            )
            val k = LeitorM3u.chave(nome)
            val antigo = porNome[k]
            porNome[k] = if (antigo == null) LeitorM3u.CanalM3u(nome, grupo, logo, fontes)
            else antigo.copy(grupo = antigo.grupo ?: grupo, logo = antigo.logo ?: logo, fontes = antigo.fontes + fontes)
        }
        return porNome.values.toList()
    }

    // MARK: - Rede

    private val cliente by lazy {
        Playback.client.newBuilder().callTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private fun buscar(url: String): String {
        val pedido = Request.Builder().url(url).header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build()
        return cliente.newCall(pedido).execute().use { r ->
            if (!r.isSuccessful) throw MinhasListas.Erro("O servidor recusou o pedido (${r.code})")
            r.body.string()
        }
    }

    /** Entra na conta e devolve os canais. Erros em português, sem o link nem a senha. */
    fun canais(conta: Conta): List<LeitorM3u.CanalM3u> {
        val motivo = try { verificarEntrada(buscar(conta.api())) } catch (e: MinhasListas.Erro) { SEM_API }
            catch (e: Exception) { throw MinhasListas.Erro("Não consegui falar com o servidor") }
        if (motivo == SEM_API) return canaisDoM3u(conta)
        if (motivo.isNotEmpty()) throw MinhasListas.Erro(motivo)
        val canais = try {
            canaisDaApi(conta, buscar(conta.api("get_live_streams")), runCatching { buscar(conta.api("get_live_categories")) }.getOrDefault("[]"))
        } catch (e: Exception) { return canaisDoM3u(conta) }
        return canais.ifEmpty { canaisDoM3u(conta) }
    }

    /** Reserva: o servidor sem API (ou sem canais ao vivo nela) ainda serve o M3U. */
    private fun canaisDoM3u(conta: Conta): List<LeitorM3u.CanalM3u> {
        val texto = try { buscar(conta.linkM3u) } catch (e: MinhasListas.Erro) { throw e }
            catch (e: Exception) { throw MinhasListas.Erro("Não consegui falar com o servidor") }
        val canais = LeitorM3u.ler(texto)
        if (canais.isEmpty()) throw MinhasListas.Erro("Essa conta não tem canais ao vivo")
        return canais
    }
}
