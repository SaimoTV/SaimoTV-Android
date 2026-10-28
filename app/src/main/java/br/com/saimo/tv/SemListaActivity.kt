package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi

/**
 * A tela de uma aba cujos dados dependem de uma lista que não existe (sem
 * endereço, ou endereço que não carregou): o menu do topo continua à mão e o
 * estado vazio explica o que fazer.
 *
 * O Ao vivo, que é uma tela de vídeo, vem para cá em vez de abrir o player sem
 * canal. Quando os endereços passam a funcionar, ela se troca pela tela de verdade.
 */
@UnstableApi
class SemListaActivity : TelaComMenu() {

    override val aba: Aba get() = runCatching { Aba.valueOf(intent.getStringExtra(EXTRA_ABA).orEmpty()) }
        .getOrDefault(Aba.NENHUMA)

    companion object {
        private const val EXTRA_ABA = "aba"

        fun abrir(context: Context, aba: Aba) = context.startActivity(intent(context, aba))

        fun intent(context: Context, aba: Aba) =
            Intent(context, SemListaActivity::class.java).putExtra(EXTRA_ABA, aba.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sem_lista)
        mostrarVazio(getString(
            if (aba == Aba.AO_VIVO && Enderecos.canais != null) R.string.vazio_falhou else R.string.vazio_canais))
    }

    override fun aoMudarListas() {
        if (Remote.channels.isNotEmpty() && aba == Aba.AO_VIVO) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            mostrarVazio(getString(R.string.vazio_falhou))
        }
    }
}
