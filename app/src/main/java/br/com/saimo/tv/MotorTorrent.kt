package br.com.saimo.tv

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tocador de torrent embutido: toca fonte de addon que só tem `infoHash`, sem
 * depender de Debrid (como o Stremio faz).
 *
 * O motor é o `saimo-torrent` (Rust, librqbit), empacotado como
 * `libsaimotorrent.so` em jniLibs para o Android deixar executar. Ele sobe um
 * servidor só em 127.0.0.1 e o ExoPlayer toca
 * `http://127.0.0.1:<porta>/tocar?m=<magnet>&f=<arquivo>` como um vídeo comum:
 * baixa primeiro o trecho que o player pede (inclusive ao avançar) e o resto
 * depois. O cache fica em cacheDir/torrent, com limite pelo espaço livre, e é
 * apagado quando o player fecha.
 *
 * O processo morre junto com o app: quando a entrada padrão dele fecha, ele sai.
 */
object MotorTorrent {
    private const val TAG = "MotorTorrent"

    @Volatile private var processo: Process? = null
    @Volatile private var porta: Int = 0
    private var entrada: OutputStream? = null

    /** O motor usa funções do Android 7 (API 24); no 6, torrent continua pedindo Debrid. */
    fun disponivel(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 24 && binario(context).canExecute()

    private fun binario(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, "libsaimotorrent.so")

    /** Até um terço do espaço livre do cache, entre 512 MB e 8 GB. */
    private fun limiteMb(pasta: File): Long {
        val livre = runCatching { StatFs(pasta.path).availableBytes }.getOrDefault(2L shl 30)
        return (livre / 3 / (1024 * 1024)).coerceIn(512, 8192)
    }

    @Synchronized
    private fun iniciar(context: Context): Int {
        val atual = processo
        if (atual != null && porta > 0 && runCatching { atual.exitValue() }.isFailure) return porta
        val bin = binario(context)
        if (!bin.canExecute()) error("Motor de torrent indisponível neste aparelho.")
        val pasta = File(context.cacheDir, "torrent").apply { mkdirs() }
        val p = ProcessBuilder(
            bin.path, "--pasta", pasta.path, "--porta", "0", "--limite-mb", limiteMb(pasta).toString(),
        ).redirectErrorStream(true).start()
        val leitor = p.inputStream.bufferedReader()
        var lida: Int? = null
        // A primeira linha é "PRONTO <porta>"; o resto do log é descartado numa thread.
        val linha = leitor.readLine()
        if (linha != null && linha.startsWith("PRONTO ")) lida = linha.removePrefix("PRONTO ").trim().toIntOrNull()
        if (lida == null) {
            p.destroy()
            error("O motor de torrent não iniciou.")
        }
        Thread({ runCatching { leitor.forEachLine { Log.d(TAG, it) } } }, "motor-torrent-log").apply {
            isDaemon = true
            start()
        }
        processo = p
        entrada = p.outputStream
        porta = lida
        return lida
    }

    /** Monta o magnet com os rastreadores que o addon informou ("tracker:udp://..."). */
    private fun magnet(infoHash: String, rastreadores: List<String>, titulo: String?): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash.lowercase())
        if (!titulo.isNullOrBlank()) sb.append("&dn=").append(URLEncoder.encode(titulo, "UTF-8"))
        for (r in rastreadores) {
            val t = r.removePrefix("tracker:")
            if (t.startsWith("udp://") || t.startsWith("http://") || t.startsWith("https://")) {
                sb.append("&tr=").append(URLEncoder.encode(t, "UTF-8"))
            }
        }
        return sb.toString()
    }

    /**
     * Endereço local para o player. Sobe o motor se preciso; não espera o
     * torrent: quem espera os metadados é a primeira leitura do player.
     */
    suspend fun enderecoPara(
        context: Context,
        infoHash: String,
        fileIdx: Int?,
        rastreadores: List<String>,
        titulo: String?,
    ): String = withContext(Dispatchers.IO) {
        val p = iniciar(context.applicationContext)
        val m = URLEncoder.encode(magnet(infoHash, rastreadores, titulo), "UTF-8")
        val f = if (fileIdx != null && fileIdx >= 0) "&f=$fileIdx" else ""
        "http://127.0.0.1:$p/tocar?m=$m$f"
    }

    /** Apaga o que foi baixado (o player fechou). O motor continua no ar. */
    fun limpar() {
        val p = porta
        if (p <= 0 || processo == null) return
        Thread({
            runCatching {
                (URL("http://127.0.0.1:$p/parar").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 5000
                }.inputStream.use { it.readBytes() }
            }
        }, "motor-torrent-parar").apply { isDaemon = true; start() }
    }
}
