package br.com.saimo.tv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Procura versão nova (versao.json, em [URL_VERSAO]) e instala, com o aval de quem usa.
 *
 * O APK é instalado à mão, fora de loja, então não existe atualização
 * automática de fábrica: sem isto, uma correção só chega a quem lembra de
 * baixar o arquivo de novo — e num aparelho de TV isso praticamente não
 * acontece.
 *
 * A checagem é barata (um JSON de alguns KB): acontece na abertura e de hora
 * em hora com o app ligado, porque TV Box costuma ficar dias sem fechar o app.
 * Não incomoda por dois lados: "Depois" cala a versão até o app abrir de novo,
 * e a versão que a pessoa mandou pular não pergunta mais.
 */
@UnstableApi
object Atualizacao {

    /// O único endereço de atualização, fixo: não depende das listas do usuário.
    const val URL_VERSAO = "https://canais-saimo.pages.dev/versao.json"

    private const val ARQUIVO = "atualizacao"
    private const val PULADA = "pulada"
    private const val VISTO = "visto"
    private const val PENDENTE = "pendente"

    /// Versão a que a pessoa respondeu "Depois" nesta abertura do app.
    @Volatile
    private var adiada: String? = null

    data class Versao(val tag: String, val numero: String, val notas: String, val apk: String,
                      val bytes: Long = 0, val sha256: String? = null)

    /**
     * Devolve a versão nova quando há uma para oferecer, ou nulo.
     *
     * `manual` vem de quem pediu para checar: aí não há espera nem versão
     * pulada que valha. Uma atualização que a pessoa já aceitou e não terminou
     * conta como manual também — ver [marcarPendente].
     */
    suspend fun procurar(context: Context, manual: Boolean = false): Versao? =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
            val forcar = manual || prefs.getString(PENDENTE, null) != null
            prefs.edit().putLong(VISTO, System.currentTimeMillis()).apply()

            val versao = buscar() ?: return@withContext null
            if (!forcar && prefs.getString(PULADA, null) == versao.tag) return@withContext null
            if (!forcar && adiada == versao.tag) return@withContext null
            if (!maisNova(versao.numero, BuildConfig.VERSION_NAME)) {
                esquecerPendente(context)
                return@withContext null
            }
            versao
        }

    fun pular(context: Context, versao: Versao) {
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
            .edit().putString(PULADA, versao.tag).remove(PENDENTE).apply()
    }

    /**
     * Lembra que a pessoa escolheu atualizar.
     *
     * Do Android 8 em diante, ligar "instalar apps desconhecidos" para o app
     * faz o sistema matar o processo na hora. Sem esta marca, quem voltava das
     * configurações abria o app de novo e só via a oferta seis horas depois —
     * parecia que o app tinha fechado sozinho e a atualização sumido.
     */
    fun adiar(versao: Versao) {
        adiada = versao.tag
    }

    fun marcarPendente(context: Context, versao: Versao) {
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
            .edit().putString(PENDENTE, versao.tag).apply()
    }

    fun temPendente(context: Context): Boolean =
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).getString(PENDENTE, null) != null

    fun esquecerPendente(context: Context) {
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
            .edit().remove(PENDENTE).apply()
    }

    /// Lê o versao.json publicado. Qualquer falha (rede, JSON ruim, endereço
    /// fora da lista permitida) vale como "sem atualização", nunca como erro.
    private fun buscar(): Versao? = runCatching {
        val pedido = Request.Builder()
            .url(URL_VERSAO)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", Playback.DEFAULT_USER_AGENT)
            .build()
        val corpo = Playback.client.newCall(pedido).execute().use { resposta ->
            if (!resposta.isSuccessful) return@runCatching null
            resposta.body?.string()
        } ?: return@runCatching null
        interpretar(corpo)
    }.getOrNull()

    /**
     * Lê o versao.json e devolve a versão da TV Box (`arquivos.tvbox`), ou nulo
     * se faltar algo, a tag não tiver cara de versão ou o endereço do APK não
     * for um download de release do GitHub.
     */
    fun interpretar(json: String): Versao? = runCatching {
        val raiz = JSONObject(json)
        val arquivo = raiz.optJSONObject("arquivos")?.optJSONObject("tvbox") ?: return@runCatching null
        val url = arquivo.optString("url")
        if (!urlPermitida(url)) return@runCatching null
        // A tag precisa ter cara de versão ("v1.2" ou "1.2.3"). Um "beta-v2"
        // não diz o que é mais novo que o quê, e comparar o número solto dele
        // com 1.0.0 ofereceria uma atualização para trás.
        val tag = raiz.optString("tag").ifBlank { "v" + raiz.optString("versao") }
        val numero = numeroDaTag(tag) ?: numeroDaTag(raiz.optString("versao")) ?: return@runCatching null
        val sha = arquivo.optString("sha256").trim().lowercase().ifEmpty { null }
        Versao(tag, numero, raiz.optString("notas"), url, arquivo.optLong("bytes", 0), sha)
    }.getOrNull()

    private val HOSTS_DE_REDIRECIONAMENTO = setOf(
        "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    /**
     * Só baixa APK de release do GitHub: `https://github.com/<dono>/<repo>/
     * releases/download/…`, ou os hosts para onde o GitHub redireciona o
     * download. Sem dono nem repositório fixos no app, esta lista é o que
     * impede um versao.json adulterado de apontar para outro lugar.
     */
    fun urlPermitida(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        if (uri.scheme != "https" || uri.userInfo != null) return@runCatching false
        if (uri.port != -1 && uri.port != 443) return@runCatching false
        val host = uri.host?.lowercase() ?: return@runCatching false
        val caminho = uri.rawPath ?: ""
        when (host) {
            "github.com" -> Regex("^/[^/]+/[^/]+/releases/download/[^/].*").matches(caminho)
            in HOSTS_DE_REDIRECIONAMENTO -> true
            else -> false
        }
    }.getOrDefault(false)

    /** sha256 em hexa minúsculo de um conjunto de bytes (para conferir o APK). */
    fun hexa(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** O hash do arquivo baixado bate com o publicado? Sem hash publicado, vale. */
    fun hashConfere(esperado: String?, calculado: String): Boolean =
        esperado.isNullOrBlank() || esperado.trim().equals(calculado, ignoreCase = true)

    /** "v1.2.3" e "1.2" viram "1.2.3" e "1.2"; qualquer outra coisa, nulo. */
    fun numeroDaTag(tag: String): String? =
        Regex("^[vV]?\\d+\\.\\d+(\\.\\d+)?$").find(tag)?.value?.trimStart('v', 'V')

    /**
     * Compara 1.10.0 com 1.9.3 pelo número de cada parte, não pelo texto —
     * como texto, "1.10" viria antes de "1.9".
     */
    fun maisNova(candidata: String, atual: String): Boolean {
        val a = candidata.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val b = atual.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Baixa o APK e entrega ao instalador do sistema.
     *
     * Vai pelo PackageInstaller, e não por um Intent de arquivo: assim não
     * precisa de FileProvider nem de permissão de leitura externa, e é o
     * próprio sistema quem mostra a confirmação — que num aparelho de TV é a
     * tela que o controle sabe operar.
     *
     * `aoAndar` recebe de 0 a 1 enquanto baixa; devolve a mensagem de erro, ou
     * nulo quando a instalação foi entregue ao sistema.
     */
    suspend fun instalar(context: Context, versao: Versao,
                         aoAndar: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val destino = File(context.cacheDir, "saimo-update.apk")
        if (!urlPermitida(versao.apk)) return@withContext "endereço do download não permitido"
        val resumo = java.security.MessageDigest.getInstance("SHA-256")
        runCatching {
            val pedido = Request.Builder().url(versao.apk)
                .header("User-Agent", Playback.DEFAULT_USER_AGENT).build()
            Playback.client.newCall(pedido).execute().use { resposta ->
                if (!resposta.isSuccessful) return@withContext "download recusado (${resposta.code})"
                // Depois dos redirecionamentos o arquivo tem de vir de um host permitido.
                if (!urlPermitida(resposta.request.url.toString())) {
                    return@withContext "download redirecionado para endereço não permitido"
                }
                val corpo = resposta.body ?: return@withContext "download vazio"
                val total = corpo.contentLength()
                destino.outputStream().use { saida ->
                    corpo.byteStream().use { entrada ->
                        val buffer = ByteArray(64 * 1024)
                        var lido = 0L
                        while (true) {
                            val n = entrada.read(buffer)
                            if (n <= 0) break
                            saida.write(buffer, 0, n)
                            resumo.update(buffer, 0, n)
                            lido += n
                            if (total > 0) aoAndar(lido.toFloat() / total)
                        }
                    }
                }
            }
        }.onFailure { return@withContext "falha ao baixar: ${it.message}" }

        if (!hashConfere(versao.sha256, hexa(resumo.digest()))) {
            destino.delete()
            return@withContext "arquivo baixado não confere com o publicado (sha256)"
        }

        runCatching {
            val instalador = context.packageManager.packageInstaller
            val parametros = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = instalador.createSession(parametros)
            instalador.openSession(id).use { sessao ->
                sessao.openWrite("saimo", 0, destino.length()).use { saida ->
                    destino.inputStream().use { it.copyTo(saida) }
                    sessao.fsync(saida)
                }
                // O Intent precisa ser explícito: do Android 14 em diante um
                // PendingIntent mutável com Intent implícito é recusado, e a
                // instalação morria antes de o sistema perguntar qualquer coisa.
                val aviso = PendingIntent.getBroadcast(
                    context, id,
                    Intent("br.com.saimo.tv.INSTALADO").setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                sessao.commit(aviso.intentSender)
            }
            // Entregue ao sistema: dali em diante quem decide é a tela dele.
            esquecerPendente(context)
        }.onFailure { return@withContext "falha ao instalar: ${it.message}" }

        null
    }
}
