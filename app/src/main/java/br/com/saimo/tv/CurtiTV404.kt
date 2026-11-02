package br.com.saimo.tv

// ============================================================
// EXTRACTOR 404 - CURTITV NO SAIMO TV (canais ao vivo com sessao)
// Criado por 404 - assinatura permanente, nao remover
// ============================================================
// SEM OFUSCACAO (decisao documentada na secao 6 do relatorio):
// a base e o endpoint sao publicos (site aberto), o stream_id
// vem do catalogo.txt em texto puro e a logica nao guarda segredo.
// Renovacao por EXPIRES: renova faltando 10 min, 3 tentativas
// com 5s de espera; video NAO derruba enquanto o token antigo vale.
// Lock serializa pedidos (MultiTela abre 2 players e nao pode
// pedir 2 tokens em paralelo).
// ============================================================

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

object CurtiTV404 {

    const val ASSINATURA = "Criado por 404 - assinatura permanente, nao remover"
    const val BASE = "https://curtitvonline.com.br"
    private const val PREFS = "curtitv404"
    private const val KEY_COOKIES = "cookies"
    private const val MARGEM_RENOVACAO_S = 600L // renova faltando 10 min
    private const val TENTATIVAS = 3
    private const val ESPERA_RETRY_MS = 5_000L

    private val lock = Any() // serializa pedidos de token (MultiTela)

    data class Sessao(val gatewayUrl: String, val expiraEm: Long)

    fun cookies(context: Context): String? =
        context.getSharedPreferences(PREFS, 0).getString(KEY_COOKIES, null)

    fun invalidar(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit()
            .remove(KEY_COOKIES).apply()
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Exception) {}
    }

    fun extrairStreamId(url: String): String {
        return if (url.startsWith("curtitv://", ignoreCase = true)) {
            url.substring(10).substringBefore('?').substringBefore('/')
        } else if (url.contains("stream=")) {
            url.substringAfter("stream=").substringBefore('&').substringBefore('.')
        } else if (url.contains("canal=")) {
            url.substringAfter("canal=").substringBefore('&').substringBefore('.')
        } else {
            url.filter { it.isDigit() }
        }
    }

    /**
     * Obtém cookies reais via HTTP direto usando OkHttp (rápido e sem precisar de WebView na TV Box).
     * Abre site.php e ativa a sessão com site_api.php?type=live.
     */
    private fun obterCookiesHttp(context: Context): String? {
        try {
            val reqSite = Request.Builder()
                .url("$BASE/site.php")
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .header("Referer", "$BASE/site.php")
                .build()

            var cookieSite: String? = null
            Playback.client.newCall(reqSite).execute().use { res ->
                val setCookies = res.headers("Set-Cookie")
                for (sc in setCookies) {
                    if (sc.contains("CURTITV_SITE=")) {
                        val v = sc.substringAfter("CURTITV_SITE=").substringBefore(';')
                        cookieSite = "CURTITV_SITE=$v"
                        break
                    }
                }
            }

            if (cookieSite == null) return null

            // Ativa a sessão no backend da CurtiTV
            val reqAtivar = Request.Builder()
                .url("$BASE/site_api.php?type=live&offset=0&featured=1")
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .header("Referer", "$BASE/site.php")
                .header("Cookie", cookieSite!!)
                .build()

            Playback.client.newCall(reqAtivar).execute().close()

            context.getSharedPreferences(PREFS, 0).edit()
                .putString(KEY_COOKIES, cookieSite).apply()

            return cookieSite
        } catch (_: Exception) {
            return null
        }
    }

    /** Captura cookies reais via WebView invisível conforme o guia v9.6. */
    @SuppressLint("SetJavaScriptEnabled")
    fun capturarCookies(
        activity: android.app.Activity,
        aoConcluir: (String?) -> Unit = {}
    ) {
        capturarCookiesViaWebView(activity, aoConcluir)
    }

    /** Captura cookies reais via WebView invisível (fallback se HTTP falhar). */
    @SuppressLint("SetJavaScriptEnabled")
    fun capturarCookiesViaWebView(
        activity: android.app.Activity,
        aoConcluir: (String?) -> Unit
    ) {
        try {
            val wv = WebView(activity)
            wv.layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.flush()
                    val c = cm.getCookie(BASE)
                    if (c != null && c.contains("CURTITV")) {
                        activity.getSharedPreferences(PREFS, 0).edit()
                            .putString(KEY_COOKIES, c).apply()
                        aoConcluir(c)
                    } else aoConcluir(null)
                }
            }
            activity.addContentView(wv, wv.layoutParams)
            wv.loadUrl("$BASE/site.php")
        } catch (_: Exception) {
            aoConcluir(null)
        }
    }

    /** cookies + stream_id -> gateway URL com expires. Serializado por lock. */
    suspend fun gateway(context: Context, streamId: String): Sessao? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                var c = cookies(context)
                if (c.isNullOrEmpty()) {
                    c = obterCookiesHttp(context)
                }
                if (c.isNullOrEmpty()) return@withContext null

                fun pedirToken(cookie: String): Sessao? {
                    val req = Request.Builder()
                        .url("$BASE/hls_token.php?stream=$streamId&type=live")
                        .header("Cookie", cookie)
                        .header("Referer", "$BASE/site.php")
                        .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                        .build()

                    return try {
                        Playback.client.newCall(req).execute().use { r ->
                            if (!r.isSuccessful) return null
                            val corpo = r.body?.string().orEmpty()
                            val j = JSONObject(corpo)
                            if (!j.optBoolean("ok", false)) return null
                            val gu = j.optString("url")
                            if (gu.isEmpty()) return null
                            val gw = if (gu.startsWith("http")) gu else "$BASE/" + gu.removePrefix("/")
                            Sessao(gw, j.optLong("expires", 0L))
                        }
                    } catch (_: Exception) {
                        null
                    }
                }

                var sessao = pedirToken(c)
                if (sessao == null) {
                    // Cookie pode ter expirado; renova e tenta mais uma vez
                    invalidar(context)
                    val novoCookie = obterCookiesHttp(context)
                    if (novoCookie != null) {
                        sessao = pedirToken(novoCookie)
                    }
                }
                sessao
            }
        }

    fun faltaRenovar(s: Sessao?): Boolean =
        s != null && s.expiraEm > 0 &&
            s.expiraEm <= System.currentTimeMillis() / 1000 + MARGEM_RENOVACAO_S

    /** Renova com retry: 3 tentativas, 5s entre elas. Null se falhar tudo. */
    suspend fun renovar(context: Context, streamId: String): Sessao? =
        withContext(Dispatchers.IO) {
            for (t in 1..TENTATIVAS) {
                val s = gateway(context, streamId)
                if (s != null) return@withContext s
                try {
                    Thread.sleep(ESPERA_RETRY_MS)
                } catch (_: Exception) {}
            }
            null
        }
}
