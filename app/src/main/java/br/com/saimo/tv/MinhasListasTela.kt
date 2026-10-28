package br.com.saimo.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Ajustes > Minhas listas. Um painel lateral (o mesmo das outras escolhas) com
 * "Adicionar por link", "Escolher arquivo" e uma linha por lista; OK numa lista
 * abre Atualizar / Ativar-Desativar / Remover. O link é digitado num diálogo
 * igual ao do "Endereço das listas": OK no campo abre o teclado e o celular
 * conectado (Saimo Connect) escreve no campo em foco. Nunca mostra o link.
 */
object MinhasListasTela {

    /**
     * @param escolherArquivo abre o seletor do sistema; o argumento é o id da
     *   lista a atualizar (nulo = lista nova). O resultado volta por [aoEscolherArquivo].
     * @param aoMudar chamado quando a lista de listas mudou (para redesenhar o Ajustes).
     */
    fun mostrar(activity: Activity, escolherArquivo: (String?) -> Unit, aoMudar: () -> Unit) {
        val itens = mutableListOf(
            Painel.Item("Adicionar por link", icone = R.drawable.ic_link) { mostrarLink(activity, escolherArquivo, aoMudar) },
            Painel.Item("Escolher arquivo", "Uma lista .m3u guardada neste aparelho", icone = R.drawable.ic_arquivo) { escolherArquivo(null) },
        )
        for (l in MinhasListas.todas()) {
            itens += Painel.Item(l.nome, detalheDe(l), icone = R.drawable.ic_list) { abrirLista(activity, l, escolherArquivo, aoMudar) }
        }
        Painel.mostrar(activity, activity.getString(R.string.ajustes_minhas_listas), itens,
            subtitulo = activity.getString(R.string.ml_texto))
    }

    private fun detalheDe(l: MinhasListas.Lista): String {
        val n = if (l.canais == 1) "1 canal" else "${l.canais} canais"
        return "$n · ${MinhasListas.quando(l)}" + if (l.ativa) "" else " · desativada"
    }

    private fun reabrir(activity: Activity, escolherArquivo: (String?) -> Unit, aoMudar: () -> Unit) {
        aoMudar()
        mostrar(activity, escolherArquivo, aoMudar)
    }

    private fun abrirLista(activity: Activity, l: MinhasListas.Lista, escolherArquivo: (String?) -> Unit, aoMudar: () -> Unit) {
        Painel.mostrar(activity, l.nome, listOf(
            Painel.Item("Atualizar", if (l.link == null) "Escolha o arquivo de novo" else null, icone = R.drawable.ic_replay) {
                if (l.link == null) { escolherArquivo(l.id); return@Item }
                Toast.makeText(activity, "Atualizando a lista…", Toast.LENGTH_SHORT).show()
                val dono = activity as? LifecycleOwner ?: return@Item
                dono.lifecycleScope.launch(semDerrubar) {
                    val erro = MinhasListas.atualizar(l.id)
                    Toast.makeText(activity, erro ?: "Lista atualizada", Toast.LENGTH_LONG).show()
                    if (!activity.isFinishing && !activity.isDestroyed) reabrir(activity, escolherArquivo, aoMudar)
                }
            },
            Painel.Item(if (l.ativa) "Desativar" else "Ativar", icone = R.drawable.ic_check) {
                MinhasListas.alternar(l.id); reabrir(activity, escolherArquivo, aoMudar)
            },
            Painel.Item("Remover", icone = R.drawable.ic_delete) {
                Painel.mostrar(activity, "Remover \"${l.nome}\"?", listOf(
                    Painel.Item("Sim, remover", "O link e os canais desta lista são apagados", icone = R.drawable.ic_delete) {
                        MinhasListas.remover(l.id); reabrir(activity, escolherArquivo, aoMudar)
                    },
                    Painel.Item("Não", icone = R.drawable.ic_close) { abrirLista(activity, l, escolherArquivo, aoMudar) },
                ))
            },
        ), subtitulo = detalheDe(l))
    }

    /** Resultado do seletor de arquivos do sistema. [alvo] = lista a atualizar, ou nulo para criar. */
    fun aoEscolherArquivo(activity: Activity, uri: Uri?, alvo: String?, escolherArquivo: (String?) -> Unit, aoMudar: () -> Unit) {
        if (uri == null) { reabrir(activity, escolherArquivo, aoMudar); return }
        val dono = activity as? LifecycleOwner ?: return
        Toast.makeText(activity, "Lendo o arquivo…", Toast.LENGTH_SHORT).show()
        dono.lifecycleScope.launch(semDerrubar) {
            val msg = try {
                val canais = MinhasListas.lerArquivo(uri)
                if (alvo != null) MinhasListas.substituir(alvo, canais) else MinhasListas.adicionar(null, null, canais)
                "Lista com ${canais.size} canais"
            } catch (e: MinhasListas.Erro) {
                e.message ?: "Não consegui abrir esse arquivo"
            }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            if (!activity.isFinishing && !activity.isDestroyed) reabrir(activity, escolherArquivo, aoMudar)
        }
    }

    /** Sem seletor do sistema: lista os .m3u de Download/Documents para escolher com o controle. */
    fun mostrarArquivos(activity: Activity, alvo: String?, escolherArquivo: (String?) -> Unit, aoMudar: () -> Unit) {
        val arquivos = MinhasListas.arquivosNaPasta()
        if (arquivos.isEmpty()) {
            Toast.makeText(activity, "Não encontrei arquivos .m3u na pasta Download. Use o link da lista.", Toast.LENGTH_LONG).show()
            reabrir(activity, escolherArquivo, aoMudar)
            return
        }
        Painel.mostrar(activity, "Escolher arquivo", arquivos.map { f ->
            Painel.Item(f.name, "${maxOf(1L, f.length() / 1024)} KB", icone = R.drawable.ic_arquivo) {
                val dono = activity as? LifecycleOwner ?: return@Item
                Toast.makeText(activity, "Lendo o arquivo…", Toast.LENGTH_SHORT).show()
                dono.lifecycleScope.launch(semDerrubar) {
                    val msg = try {
                        val canais = MinhasListas.lerArquivo(f)
                        if (alvo != null) MinhasListas.substituir(alvo, canais) else MinhasListas.adicionar(null, null, canais)
                        "Lista com ${canais.size} canais"
                    } catch (e: MinhasListas.Erro) {
                        e.message ?: "Não consegui abrir esse arquivo"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    if (!activity.isFinishing && !activity.isDestroyed) reabrir(activity, escolherArquivo, aoMudar)
                }
            }
        }, subtitulo = "Arquivos .m3u, .m3u8 e .m3 das pastas Download e Documents") { }
    }

    private fun mostrarLink(activity: Activity, escolherArquivo: (String?) -> Unit, aoMudar: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialogo = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val vista = LayoutInflater.from(activity).inflate(R.layout.dialogo_minha_lista, null)
        val campoLink = vista.findViewById<EditText>(R.id.mlLink)
        val campoNome = vista.findViewById<EditText>(R.id.mlNome)
        val mensagem = vista.findViewById<TextView>(R.id.mlMensagem)
        val progresso = vista.findViewById<ProgressBar>(R.id.mlProgresso)
        val adicionar = vista.findViewById<TextView>(R.id.mlAdicionar)
        val fechar = vista.findViewById<TextView>(R.id.mlFechar)
        Icones.inicio(adicionar, R.drawable.ic_check)
        Icones.inicio(fechar, R.drawable.ic_close)

        // O celular escreve no campo em que a pessoa está.
        var atual: EditText = campoLink
        for (campo in listOf(campoLink, campoNome)) {
            campo.setOnFocusChangeListener { _, foco ->
                if (foco) {
                    atual = campo
                    Connect.notificarAberturaDeTexto(campo.hint?.toString().orEmpty(), campo.text.toString())
                }
            }
            campo.setOnEditorActionListener { v, _, _ ->
                v.clearFocus()
                v.focusSearch(View.FOCUS_DOWN)?.requestFocus()
                true
            }
        }
        Connect.textoAtivo = { novo -> atual.setText(novo); atual.setSelection(atual.text.length) }

        fun erro(texto: String) {
            progresso.visibility = View.GONE
            mensagem.setTextColor(activity.getColor(R.color.live))
            mensagem.text = texto
            mensagem.visibility = View.VISIBLE
        }

        var ocupado = false
        adicionar.setOnClickListener {
            if (ocupado) return@setOnClickListener
            val link = campoLink.text.toString().trim()
            val dono = activity as? LifecycleOwner ?: return@setOnClickListener
            ocupado = true
            mensagem.visibility = View.GONE
            progresso.visibility = View.VISIBLE
            mensagem.setTextColor(0xFFA8B4C0.toInt())
            mensagem.text = activity.getString(R.string.ml_baixando)
            mensagem.visibility = View.VISIBLE
            dono.lifecycleScope.launch(semDerrubar) {
                try {
                    val canais = MinhasListas.baixar(link)
                    MinhasListas.adicionar(campoNome.text.toString(), link, canais)
                    Toast.makeText(activity, "Lista adicionada: ${canais.size} canais", Toast.LENGTH_LONG).show()
                    dialogo.dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed) reabrir(activity, escolherArquivo, aoMudar)
                } catch (e: MinhasListas.Erro) {
                    erro(e.message ?: "Não consegui baixar essa lista")
                    campoLink.requestFocus()
                } finally {
                    ocupado = false
                }
            }
        }
        fechar.setOnClickListener { dialogo.dismiss() }

        dialogo.setContentView(vista, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogo.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialogo.setOnDismissListener {
            Connect.textoAtivo = null
            Connect.notificarFechamentoDeTexto()
        }
        dialogo.show()
        Vr.telaCheia(dialogo.window)
        campoLink.requestFocus()
    }
}
