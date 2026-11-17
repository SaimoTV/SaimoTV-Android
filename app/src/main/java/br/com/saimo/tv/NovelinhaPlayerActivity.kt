package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/**
 * Player das novelinhas na TV (e no celular, quando o app roda nele).
 *
 * Os episódios entram todos numa lista só do ExoPlayer: no fim de um, o
 * seguinte começa sozinho e já vinha sendo carregado. O vídeo é em pé
 * (720x1280), então fica centralizado com faixas pretas dos lados. Setas
 * cima e baixo (ou canal +/-) trocam de episódio; OK pausa; voltar sai.
 * Onde a pessoa parou fica guardado (episódio e segundo).
 */
@UnstableApi
class NovelinhaPlayerActivity : ComponentActivity() {

    companion object {
        private const val ID = "novelinha.id"
        fun abrir(context: Context, id: String) =
            context.startActivity(Intent(context, NovelinhaPlayerActivity::class.java).putExtra(ID, id))
    }

    private var player: ExoPlayer? = null
    private lateinit var novelinha: Novelinhas.Novelinha
    private lateinit var titulo: TextView
    private lateinit var episodio: TextView
    private lateinit var dica: TextView
    private lateinit var barra: ProgressBar
    private lateinit var topo: View
    private val relogio = Handler(Looper.getMainLooper())
    private val esconder = Runnable { topo.animate().alpha(0f).setDuration(400).start() }
    private val acompanhar = object : Runnable {
        override fun run() {
            player?.let { p ->
                val d = p.duration
                if (d > 0) barra.progress = (p.currentPosition * 1000 / d).toInt()
                Novelinhas.guardar(this@NovelinhaPlayerActivity, novelinha.id, p.currentMediaItemIndex + 1, p.currentPosition)
            }
            relogio.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = intent.getStringExtra(ID).orEmpty()
        lifecycleScope.launch(semDerrubar) {
            Novelinhas.carregar(this@NovelinhaPlayerActivity)
            val n = Novelinhas.porId(id)
            if (n == null) { finish(); return@launch }
            novelinha = n
            montarTela()
            tocar()
        }
    }

    private fun montarTela() {
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        raiz.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = resources.displayMetrics.density
        topo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((32 * d).toInt(), (24 * d).toInt(), (32 * d).toInt(), (24 * d).toInt())
            setBackgroundColor(0x99000000.toInt())
        }
        titulo = TextView(this).apply { setTextColor(Color.WHITE); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        episodio = TextView(this).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 15f }
        dica = TextView(this).apply { setTextColor(0x99FFFFFF.toInt()); textSize = 13f; setText(R.string.novelinhas_dica) }
        (topo as LinearLayout).apply { addView(titulo); addView(episodio); addView(dica) }
        raiz.addView(topo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFF7EB6.toInt())
        }
        raiz.addView(barra, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * d).toInt(), Gravity.BOTTOM))
        setContentView(raiz)
        titulo.text = novelinha.titulo

        val p = ExoPlayer.Builder(this).build()
        player = p
        video.player = p
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = mostrarEpisodio()
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) Novelinhas.guardar(this@NovelinhaPlayerActivity, novelinha.id, novelinha.episodios, 0)
            }
        })
    }

    private fun tocar() {
        val p = player ?: return
        val itens = (1..novelinha.episodios).map { MediaItem.fromUri(Novelinhas.url(novelinha, it)) }
        val parada = Novelinhas.parada(this, novelinha.id)
        val inicio = ((parada?.first ?: 1) - 1).coerceIn(0, novelinha.episodios - 1)
        p.setMediaItems(itens, inicio, parada?.second ?: 0L)
        p.prepare()
        p.play()
        mostrarEpisodio()
        relogio.post(acompanhar)
    }

    private fun mostrarEpisodio() {
        val p = player ?: return
        val audio = getString(if (novelinha.dublada) R.string.novelinhas_dublado else R.string.novelinhas_legendado)
        episodio.text = getString(R.string.novelinhas_episodio, p.currentMediaItemIndex + 1, novelinha.episodios, audio)
        topo.animate().cancel()
        topo.alpha = 1f
        relogio.removeCallbacks(esconder)
        relogio.postDelayed(esconder, 4000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (p.hasNextMediaItem()) p.seekToNextMediaItem()
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> {
                if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (p.isPlaying) p.pause() else p.play()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> p.seekTo(p.currentPosition + 10_000)
            KeyEvent.KEYCODE_DPAD_LEFT -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
            else -> return super.onKeyDown(keyCode, event)
        }
        mostrarEpisodio()
        return true
    }

    override fun onStop() {
        super.onStop()
        if (::novelinha.isInitialized) player?.let { Novelinhas.guardar(this, novelinha.id, it.currentMediaItemIndex + 1, it.currentPosition) }
        player?.pause()
    }

    override fun onDestroy() {
        relogio.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
