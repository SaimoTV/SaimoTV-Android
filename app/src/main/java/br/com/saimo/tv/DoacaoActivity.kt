package br.com.saimo.tv

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Apoie o Saimo TV: por que doar, o QR code (o pagamento é feito no celular),
 * o link escrito e quem já apoiou, a lista publicada pelo site.
 */
@UnstableApi
class DoacaoActivity : TelaComMenu() {
    override val aba = Aba.APOIE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doacao)

        val destaque = getString(R.string.doacao_titulo_destaque)
        val titulo = getString(R.string.doacao_titulo, destaque)
        findViewById<TextView>(R.id.doacaoTitulo).text = SpannableStringBuilder(titulo).apply {
            val inicio = titulo.indexOf(destaque)
            setSpan(ForegroundColorSpan(0xFF4FE3D0.toInt()), inicio, inicio + destaque.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val vantagens = findViewById<LinearLayout>(R.id.doacaoVantagens)
        listOf(
            Triple(R.drawable.ic_block, R.string.doacao_v1_titulo, R.string.doacao_v1_texto),
            Triple(R.drawable.ic_bolt, R.string.doacao_v2_titulo, R.string.doacao_v2_texto),
            Triple(R.drawable.ic_favorite, R.string.doacao_v3_titulo, R.string.doacao_v3_texto),
        ).forEachIndexed { i, (icone, t, texto) -> vantagens.addView(vantagem(icone, t, texto, i > 0)) }

        // A tela não tem nada para escolher: o foco fica na aba Apoie do menu.
        MenuGlobal.focar(barraMenu, Aba.APOIE)

        lifecycleScope.launch(semDerrubar) {
            val nomes = withContext(Dispatchers.IO) { apoiadores() }
            if (nomes.isNotEmpty()) findViewById<TextView>(R.id.doacaoApoiadores).text = nomes.joinToString("   ·   ")
        }
    }

    private fun vantagem(icone: Int, titulo: Int, texto: Int, comMargem: Boolean): View {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.doacao_vantagem)
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (comMargem) marginStart = (10 * d).toInt() }
            addView(ImageView(this@DoacaoActivity).apply {
                setImageResource(icone)
                setColorFilter(0xFF4FE3D0.toInt())
                layoutParams = LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
            })
            addView(LinearLayout(this@DoacaoActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                addView(TextView(this@DoacaoActivity).apply {
                    setText(titulo); textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
                addView(TextView(this@DoacaoActivity).apply {
                    setText(texto); textSize = 10f; setTextColor(0xFF9AA4B8.toInt())
                })
            })
        }
    }

    /** Nomes de apoiadores.txt, na ordem publicada; vazio sem rede. */
    private fun apoiadores(): List<String> = runCatching {
        val pedido = okhttp3.Request.Builder().url(APOIADORES).build()
        Playback.client.newCall(pedido).execute().use { r ->
            if (!r.isSuccessful) return@use emptyList()
            r.body?.string().orEmpty().lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.substringBefore('\t').trim() }
                .filter(String::isNotEmpty)
                .take(40).toList()
        }
    }.getOrDefault(emptyList())

    companion object {
        const val LINK = "https://deflow.exchange/pt/qr/saimo-tv"
        private const val APOIADORES = "https://saimo-tv.pages.dev/apoiadores.txt"
    }
}
