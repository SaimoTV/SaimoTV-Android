package br.com.saimo.tv

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Tela de configuração de Addons Stremio e provedores Debrid na TV.
 * Mostra QR Code para configuração rápida pelo celular (`http://<ip>:8999/addons`)
 * além de gerenciamento completo pelo controle remoto.
 */
@UnstableApi
class AddonsTela : TelaComMenu() {

    override val aba = Aba.AJUSTES

    companion object {
        fun abrir(context: Context) {
            context.startActivity(Intent(context, AddonsTela::class.java))
        }
    }

    private sealed interface Linha
    private data class Secao(val nome: String) : Linha
    private data class Opcao(
        val chave: String,
        val nome: String,
        val valor: String,
        val acao: () -> Unit,
    ) : Linha

    private lateinit var lista: RecyclerView
    private val adaptador = Adaptador()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_addons)

        val ip = Connect.obterIpLocal().ifBlank { "127.0.0.1" }
        val urlLan = "http://$ip:${Connect.PORTA}/addons"
        findViewById<TextView>(R.id.addonsUrl).text = urlLan
        runCatching { QrCodeGerador.paraBitmap(urlLan, 368) }.getOrNull()?.let { bmp ->
            findViewById<ImageView>(R.id.addonsQr).setImageBitmap(bmp)
        }

        lista = findViewById(R.id.addonsLista)
        lista.layoutManager = LinearLayoutManager(this)
        lista.adapter = adaptador
        redesenhar()

        lifecycleScope.launch(semDerrubar) {
            combine(AddonsStore.versao, DebridStore.versao) { a, d -> a + d }.collect {
                redesenhar()
            }
        }

        focarQuandoPronto {
            lista.findViewHolderForAdapterPosition(0)?.itemView
                ?: lista.findViewHolderForAdapterPosition(1)?.itemView
        }
    }

    private fun redesenhar() {
        adaptador.linhas = montarLinhas()
    }

    private fun montarLinhas(): List<Linha> = buildList {
        add(
            Opcao(
                chave = "add_addon",
                nome = getString(R.string.addons_adicionar_url),
                valor = "manifest.json ou stremio://",
            ) { abrirDialogoAdicionarAddon() }
        )

        add(Secao(getString(R.string.addons_secao_instalados)))
        val instalados = AddonsStore.listar()
        if (instalados.isEmpty()) {
            add(
                Opcao(
                    chave = "addons_vazio",
                    nome = getString(R.string.addons_nenhum_titulo),
                    valor = getString(R.string.addons_nenhum_dica),
                ) { abrirDialogoAdicionarAddon() }
            )
        } else {
            instalados.forEachIndexed { idx, addon ->
                val estado = if (addon.habilitado) "Ativo" else "Desativado"
                val recursos = addon.manifesto.resources.joinToString(", ") { it.nome }.ifBlank { "catalog" }
                add(
                    Opcao(
                        chave = "addon_${addon.transportUrl}",
                        nome = "${idx + 1}. ${addon.nome} (v${addon.versao})",
                        valor = "$estado · $recursos",
                    ) { abrirMenuAddon(idx, instalados.size, addon) }
                )
            }
        }

        add(Secao(getString(R.string.addons_secao_debrid)))
        for (prov in DebridStore.Provedor.entries) {
            val cfg = DebridStore.configDe(prov)
            val status = when {
                cfg.pronto -> "Ativo (${mascararChave(cfg.apiKey)})"
                cfg.apiKey.isNotBlank() -> "Desativado (${mascararChave(cfg.apiKey)})"
                else -> "Não configurado"
            }
            add(
                Opcao(
                    chave = "debrid_${prov.codigo}",
                    nome = prov.nome,
                    valor = status,
                ) { abrirMenuDebrid(cfg) }
            )
        }
        add(
            Opcao(
                chave = "debrid_sync",
                nome = getString(R.string.addons_debrid_sync),
                valor = if (DebridStore.sincronizarConnectAtivo()) {
                    "Ligado (envia chaves ao parear)"
                } else {
                    "Desligado (padrão seguro)"
                },
            ) {
                val novo = !DebridStore.sincronizarConnectAtivo()
                DebridStore.definirSincronizarConnect(novo)
                redesenhar()
            }
        )
    }

    private fun abrirMenuAddon(
        indice: Int,
        total: Int,
        addon: AddonsStore.AddonInstalado,
    ) {
        val itens = mutableListOf(
            Painel.Item(if (addon.habilitado) "Desativar" else "Ativar") {
                AddonsStore.alternar(addon.transportUrl)
                redesenhar()
            },
            Painel.Item("Testar manifesto", addon.transportUrl) {
                testarAddon(addon.transportUrl)
            },
        )
        if (indice > 0) {
            itens += Painel.Item("Mover para cima") {
                AddonsStore.mover(addon.transportUrl, -1)
                redesenhar()
            }
        }
        if (indice < total - 1) {
            itens += Painel.Item("Mover para baixo") {
                AddonsStore.mover(addon.transportUrl, +1)
                redesenhar()
            }
        }
        itens += Painel.Item("Remover addon") {
            AddonsStore.remover(addon.transportUrl)
            Toast.makeText(this, "Addon removido: ${addon.nome}", Toast.LENGTH_SHORT).show()
            redesenhar()
        }
        Painel.mostrar(this, addon.nome, itens, subtitulo = addon.manifesto.description.ifBlank { null })
    }

    private fun abrirMenuDebrid(cfg: DebridStore.ConfigProvedor) {
        val prov = cfg.provedor
        val itens = mutableListOf<Painel.Item>()
        itens += Painel.Item(if (cfg.apiKey.isBlank()) "Configurar API Key" else "Alterar API Key", prov.dicaChave) {
            abrirDialogoChaveDebrid(cfg)
        }
        if (cfg.apiKey.isNotBlank()) {
            itens += Painel.Item(if (cfg.habilitado) "Desativar provedor" else "Ativar provedor") {
                DebridStore.definirProvedor(prov, cfg.apiKey, !cfg.habilitado)
                redesenhar()
            }
            itens += Painel.Item("Remover chave salva") {
                DebridStore.definirProvedor(prov, "", false)
                Toast.makeText(this, "Chave do ${prov.nome} removida.", Toast.LENGTH_SHORT).show()
                redesenhar()
            }
        }
        Painel.mostrar(this, prov.nome, itens)
    }

    private fun testarAddon(transportUrl: String) {
        Toast.makeText(this, "Testando manifesto…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(semDerrubar) {
            try {
                val res = AddonsStore.testar(transportUrl)
                Toast.makeText(
                    this@AddonsTela,
                    "Manifesto OK: ${res.nome} v${res.versao} (${res.manifesto.resources.size} recursos)",
                    Toast.LENGTH_LONG,
                ).show()
                redesenhar()
            } catch (e: StremioProtocol.AddonException) {
                Toast.makeText(this@AddonsTela, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun abrirDialogoAdicionarAddon() {
        abrirDialogoEntrada(
            titulo = getString(R.string.addons_adicionar_url),
            subtitulo = "Cole ou digite a URL do manifest.json (ou stremio://...). Você também pode usar o QR Code ao lado no celular.",
            dica = "https://addon.exemplo/manifest.json",
            valorInicial = "",
            mascarar = false,
        ) { texto, onResultado ->
            lifecycleScope.launch(semDerrubar) {
                try {
                    val instalado = AddonsStore.instalar(texto)
                    onResultado(null)
                    Toast.makeText(
                        this@AddonsTela,
                        "Addon instalado: ${instalado.nome} (v${instalado.versao})",
                        Toast.LENGTH_LONG,
                    ).show()
                    redesenhar()
                } catch (e: StremioProtocol.AddonException) {
                    onResultado(e.message ?: "Não foi possível validar o addon.")
                }
            }
        }
    }

    private fun abrirDialogoChaveDebrid(cfg: DebridStore.ConfigProvedor) {
        abrirDialogoEntrada(
            titulo = "API Key — ${cfg.provedor.nome}",
            subtitulo = "${cfg.provedor.dicaChave}. A chave é armazenada apenas neste aparelho.",
            dica = "Cole a API Key aqui",
            valorInicial = cfg.apiKey,
            mascarar = false,
        ) { texto, onResultado ->
            val limpa = texto.trim()
            if (limpa.isEmpty()) {
                onResultado("Informe uma chave válida ou use 'Remover chave salva'.")
                return@abrirDialogoEntrada
            }
            DebridStore.definirProvedor(cfg.provedor, limpa, true)
            onResultado(null)
            Toast.makeText(this, "${cfg.provedor.nome} salvo e ativado.", Toast.LENGTH_SHORT).show()
            redesenhar()
        }
    }

    private fun abrirDialogoEntrada(
        titulo: String,
        subtitulo: String,
        dica: String,
        valorInicial: String,
        mascarar: Boolean,
        aoConfirmar: (String, (erro: String?) -> Unit) -> Unit,
    ) {
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF131922.toInt())
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams((540 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        caixa.addView(TextView(this).apply {
            text = titulo
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        caixa.addView(TextView(this).apply {
            text = subtitulo
            textSize = 14f
            setTextColor(0xFFA8B4C0.toInt())
            setPadding(0, (8 * d).toInt(), 0, (14 * d).toInt())
        })
        val campo = EditText(this).apply {
            hint = dica
            setText(valorInicial)
            setSelection(text.length)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF6B7785.toInt())
            setBackgroundColor(0xFF0B0E14.toInt())
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            inputType = if (mascarar) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            isSingleLine = true
            TecladoSaimo.ligar(this)
        }
        caixa.addView(campo)

        val erroView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFF6B6B.toInt())
            setPadding(0, (10 * d).toInt(), 0, 0)
            visibility = View.GONE
        }
        caixa.addView(erroView)

        val prog = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        caixa.addView(prog)

        val botoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (18 * d).toInt(), 0, 0)
        }
        val btnCancelar = Button(this).apply {
            text = "Cancelar"
            setBackgroundResource(R.drawable.botao_player)
            setTextColor(0xFFFFFFFF.toInt())
        }
        val btnSalvar = Button(this).apply {
            text = "Salvar"
            setBackgroundResource(R.drawable.botao_player)
            setTextColor(0xFF4FE3D0.toInt())
        }
        botoes.addView(btnCancelar)
        botoes.addView(btnSalvar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (12 * d).toInt() })
        caixa.addView(botoes)

        val dialogo = Dialog(this)
        dialogo.setContentView(caixa)
        dialogo.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        Connect.textoAtivo = { novo ->
            campo.setText(novo)
            campo.setSelection(campo.text.length)
        }
        Connect.notificarAberturaDeTexto(titulo, valorInicial)

        var ocupado = false
        btnCancelar.setOnClickListener { dialogo.dismiss() }
        btnSalvar.setOnClickListener {
            if (ocupado) return@setOnClickListener
            ocupado = true
            prog.visibility = View.VISIBLE
            erroView.visibility = View.GONE
            aoConfirmar(campo.text.toString()) { erro ->
                ocupado = false
                prog.visibility = View.GONE
                if (erro == null) {
                    dialogo.dismiss()
                } else {
                    erroView.text = erro
                    erroView.visibility = View.VISIBLE
                    campo.requestFocus()
                }
            }
        }
        dialogo.setOnDismissListener {
            Connect.textoAtivo = null
            Connect.notificarFechamentoDeTexto()
        }
        dialogo.show()
        Vr.telaCheia(dialogo.window)
        campo.requestFocus()
    }

    private fun mascararChave(chave: String): String {
        val k = chave.trim()
        return if (k.length <= 4) "****" else "****${k.takeLast(4)}"
    }

    private inner class Adaptador : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var linhas: List<Linha> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        init { setHasStableIds(true) }

        override fun getItemId(position: Int) = linhas[position].let {
            when (it) {
                is Secao -> ("sec:" + it.nome).hashCode().toLong()
                is Opcao -> ("op:" + it.chave).hashCode().toLong()
            }
        }

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
                        text = linha.valor
                        visibility = View.VISIBLE
                    }
                    holder.itemView.setOnClickListener { linha.acao() }
                }
            }
        }

        override fun getItemCount() = linhas.size
    }
}
