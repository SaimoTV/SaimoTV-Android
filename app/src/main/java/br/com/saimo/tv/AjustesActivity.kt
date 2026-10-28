package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.content.ActivityNotFoundException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ajustes: cada linha mostra o valor atual e OK troca.
 *
 * Tudo aqui tem um padrão bom — ninguém precisa passar por esta tela para o
 * app funcionar bem. Ela existe para quem quer legenda maior, prefere
 * legendado, quer abrir direto no último canal, ou precisa do número do
 * aparelho para pedir ajuda.
 */
@UnstableApi
class AjustesActivity : TelaComMenu() {

    override val aba = Aba.AJUSTES


    companion object {
        fun abrir(context: Context) = context.startActivity(Intent(context, AjustesActivity::class.java))
    }

    private sealed interface Linha
    private data class Secao(val nome: String) : Linha
    private data class Opcao(val nome: String, val valor: () -> String, val acao: () -> Unit) : Linha

    private lateinit var lista: RecyclerView
    private val adaptador = Adaptador()
    private val atualizacao by lazy { OfertaDeAtualizacao(this) { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() } }

    /// Minhas listas: lista a atualizar pelo arquivo (nulo = lista nova).
    private var alvoArquivo: String? = null
    private val seletorDeArquivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        MinhasListasTela.aoEscolherArquivo(this, uri, alvoArquivo, ::pedirArquivo) { redesenhar() }
    }

    private val permissaoDePastas = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) MinhasListasTela.mostrarArquivos(this, alvoArquivo, ::pedirArquivo) { redesenhar() }
        else Toast.makeText(this, "Sem permissão para ver os arquivos. Use o link da lista.", Toast.LENGTH_LONG).show()
    }

    /**
     * Seletor de arquivos do sistema (SAF). Muitas TVs trazem só um "stub" no
     * lugar (a pessoa veria um aviso em inglês) ou nenhum app: aí a escolha é
     * feita numa lista própria dos .m3u de Download/Documents.
     */
    private fun pedirArquivo(alvo: String?) {
        alvoArquivo = alvo
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val dono = intent.resolveActivity(packageManager)?.packageName
        if (dono == null || dono == "com.android.tv.frameworkpackagestubs") { listarPastas(); return }
        try {
            seletorDeArquivo.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            listarPastas()
        }
    }

    private fun listarPastas() {
        val precisa = android.os.Build.VERSION.SDK_INT in 23..32 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (precisa) permissaoDePastas.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        else MinhasListasTela.mostrarArquivos(this, alvoArquivo, ::pedirArquivo) { redesenhar() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ajustes)
        findViewById<TextView>(R.id.ajustesSobre).text = getString(R.string.ajustes_sobre,
            BuildConfig.VERSION_NAME, Preferencias.idCurto(this))
        lista = findViewById(R.id.ajustesLista)
        lista.layoutManager = LinearLayoutManager(this)
        lista.adapter = adaptador
        adaptador.linhas = linhas()
        focarQuandoPronto { lista.findViewHolderForAdapterPosition(1)?.itemView }
    }

    private fun simNao(v: Boolean) = getString(if (v) R.string.ajustes_ligado else R.string.ajustes_desligado)

    private fun rotuloUpscaling(modo: String) = getString(
        when (modo) {
            Upscaling.NITIDEZ_HD -> R.string.ajustes_upscaling_hd
            Upscaling.SUPER_4K -> R.string.ajustes_upscaling_4k
            else -> R.string.ajustes_upscaling_off
        }
    )

    private fun rotuloZeroDelay(modo: String) = when (modo) {
        "equilibrado" -> "Equilibrado (Recomendado)"
        "agressivo" -> "Agressivo (Ultra Ao Vivo)"
        else -> "Desligado"
    }

    private fun linhas(): List<Linha> = listOf(
        Secao(getString(R.string.ajustes_reproducao)),
        Opcao(getString(R.string.ajustes_versao), { Titulos.rotulo(Preferencias.versao) }) {
            escolher(getString(R.string.ajustes_versao), listOf("dub", "leg"), Preferencias.versao,
                { Titulos.rotulo(it) }) { Preferencias.versao = it }
        },
        Opcao(getString(R.string.ajustes_proximo), { simNao(Preferencias.proximoAutomatico) }) {
            Preferencias.proximoAutomatico = !Preferencias.proximoAutomatico; redesenhar()
        },
        Opcao(getString(R.string.ajustes_pular), { simNao(Preferencias.pularAutomatico) }) {
            Preferencias.pularAutomatico = !Preferencias.pularAutomatico; redesenhar()
        },
        Opcao(getString(R.string.player_tamanho_legenda),
            { resources.getStringArray(R.array.tamanhos_legenda)[Preferencias.legenda.coerceIn(0, 3)] }) {
            val nomes = resources.getStringArray(R.array.tamanhos_legenda)
            escolher(getString(R.string.player_tamanho_legenda), nomes.indices.toList(), Preferencias.legenda,
                { nomes[it] }) { Preferencias.legenda = it }
        },
        Opcao(getString(R.string.ajustes_legenda_fundo), { simNao(Preferencias.legendaFundo) }) {
            Preferencias.legendaFundo = !Preferencias.legendaFundo; redesenhar()
        },
        Opcao(getString(R.string.ajustes_economia), { simNao(Preferencias.economia) }) {
            Preferencias.economia = !Preferencias.economia; redesenhar()
        },
        Opcao(getString(R.string.ajustes_audio_inteligente), { simNao(Preferencias.audioInteligente) }) {
            Preferencias.audioInteligente = !Preferencias.audioInteligente
            AudioInteligente.atualizarTodos()
            redesenhar()
        },
        Opcao("Zero Delay (Menor Atraso)", { rotuloZeroDelay(Preferencias.zeroDelay) }) {
            escolher(
                "Zero Delay (Menor Atraso)",
                listOf("off", "equilibrado", "agressivo"),
                Preferencias.zeroDelay,
                { rotuloZeroDelay(it) }
            ) { Preferencias.zeroDelay = it }
        },
        Opcao(getString(R.string.ajustes_upscaling), { rotuloUpscaling(Preferencias.upscaling) }) {
            escolher(
                getString(R.string.ajustes_upscaling),
                Upscaling.modos,
                Preferencias.upscaling,
                { rotuloUpscaling(it) }
            ) { Preferencias.upscaling = it }
        },

        Secao(getString(R.string.ajustes_geral)),
        Opcao(getString(R.string.ajustes_listas), { Listas.resumo(this) }) {
            ListasDialogo.mostrar(this) { redesenhar() }
        },
        Opcao(getString(R.string.ajustes_minhas_listas), { MinhasListas.resumo() }) {
            MinhasListasTela.mostrar(this, ::pedirArquivo) { redesenhar() }
        },
        Opcao(getString(R.string.ajustes_abrir_em), {
            getString(if (Preferencias.abrirEm == "canal") R.string.ajustes_abrir_canal else R.string.ajustes_abrir_inicio)
        }) {
            escolher(getString(R.string.ajustes_abrir_em), listOf("inicio", "canal"), Preferencias.abrirEm,
                { getString(if (it == "canal") R.string.ajustes_abrir_canal else R.string.ajustes_abrir_inicio) }) {
                Preferencias.abrirEm = it
            }
        },
        Opcao(getString(R.string.ajustes_timer), {
            if (TimerDeSono.ativo) getString(R.string.ajustes_timer_em, TimerDeSono.restanteMin())
            else getString(R.string.ajustes_desligado)
        }) {
            escolher(getString(R.string.ajustes_timer), listOf(0, 30, 60, 90, 120, 180), -1,
                { if (it == 0) getString(R.string.ajustes_desligado) else getString(R.string.ajustes_minutos, it) }) {
                TimerDeSono.ligar(it)
            }
        },

        Opcao(getString(R.string.ajustes_connect), { getString(R.string.ajustes_connect_n, Connect.quantosAutorizados()) }) {
            Painel.mostrar(this, getString(R.string.ajustes_connect), listOf(
                Painel.Item("Endereço e PIN", "${Connect.obterIpLocal()} · PIN ${Connect.pinAtual}") {},
                Painel.Item("Conexão na rede local", if (Connect.ehAutoAprovarAtivo(this)) "Direta (recomendado)" else "Exigir PIN", fecha = false) {
                    Connect.definirAutoAprovar(this, !Connect.ehAutoAprovarAtivo(this))
                    redesenhar()
                },
                Painel.Item(getString(R.string.ajustes_connect_esquecer)) {
                    Connect.esquecerTodos(this)
                    Toast.makeText(this, R.string.ajustes_connect_esquecido, Toast.LENGTH_LONG).show()
                    redesenhar()
                }
            ))
        },

        Secao(getString(R.string.ajustes_sistema)),
        Opcao(getString(R.string.ajustes_atualizar), { "v${BuildConfig.VERSION_NAME}" }) { procurarVersao() },
        Opcao(getString(R.string.ajustes_recarregar), { getString(R.string.ajustes_recarregar_dica) }) { recarregar() },
        Opcao(getString(R.string.ajustes_telemetria), { simNao(Preferencias.telemetria) }) {
            Preferencias.telemetria = !Preferencias.telemetria; redesenhar()
        },
    )

    private fun <T> escolher(titulo: String, valores: List<T>, atual: T, nome: (T) -> String, aoEscolher: (T) -> Unit) {
        Painel.mostrar(this, titulo, valores.map { v ->
            Painel.Item(nome(v), marcado = v == atual) { aoEscolher(v); redesenhar() }
        })
    }

    /** Linha que tinha o foco: depois de trocar um valor, ele volta para ela. */
    private var posicaoEmFoco = RecyclerView.NO_POSITION

    // Sem isto o foco caía no "Buscar" do topo depois de cada OK: a lista
    // recriava as linhas e a que estava focada sumia.
    private fun redesenhar() {
        val antes = lista.focusedChild?.let { lista.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION } ?: posicaoEmFoco
        adaptador.notifyDataSetChanged()
        if (antes != RecyclerView.NO_POSITION) {
            lista.post { lista.findViewHolderForAdapterPosition(antes)?.itemView?.requestFocus() }
        }
    }

    private fun procurarVersao() {
        lifecycleScope.launch(semDerrubar) {
            val versao = Atualizacao.procurar(this@AjustesActivity, manual = true)
            if (versao == null) {
                Toast.makeText(this@AjustesActivity, R.string.ajustes_em_dia, Toast.LENGTH_LONG).show()
            } else {
                atualizacao.ofertar()
            }
        }
    }

    /** Apaga o catálogo e as capas guardadas: a próxima abertura baixa tudo de novo. */
    private fun recarregar() {
        lifecycleScope.launch(semDerrubar) {
            withContext(Dispatchers.IO) {
                File(filesDir, "vod").deleteRecursively()
                runCatching { coil3.SingletonImageLoader.get(this@AjustesActivity).diskCache?.clear() }
                Remote.refresh(this@AjustesActivity, forcar = true)
            }
            runCatching { coil3.SingletonImageLoader.get(this@AjustesActivity).memoryCache?.clear() }
            Toast.makeText(this@AjustesActivity, R.string.ajustes_recarregado, Toast.LENGTH_LONG).show()
        }
    }

    private inner class Adaptador : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var linhas: List<Linha> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        // Ids estáveis: ao redesenhar, a mesma linha continua sendo a mesma
        // vista, e o foco do controle não se perde.
        init { setHasStableIds(true) }

        override fun getItemId(position: Int) = position.toLong()

        override fun getItemViewType(position: Int) = if (linhas[position] is Secao) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.item_ajuste_secao else R.layout.item_painel
            val vista = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return object : RecyclerView.ViewHolder(vista) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val linha = linhas[position]) {
                is Secao -> (holder.itemView as TextView).text = linha.nome
                is Opcao -> {
                    holder.itemView.findViewById<TextView>(R.id.itemTexto).text = linha.nome
                    holder.itemView.findViewById<TextView>(R.id.itemDetalhe).apply {
                        text = linha.valor(); visibility = View.VISIBLE
                    }
                    holder.itemView.setOnClickListener { linha.acao() }
                    holder.itemView.setOnFocusChangeListener { _, tem ->
                        if (tem) posicaoEmFoco = holder.bindingAdapterPosition
                    }
                }
            }
        }

        override fun getItemCount() = linhas.size
    }
}
