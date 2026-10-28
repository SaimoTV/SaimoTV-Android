package br.com.saimo.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * "Endereço das listas" (Ajustes > Listas): onde a pessoa troca, desativa
 * (campo em branco) ou restaura o padrão dos endereços das duas listas. Nunca
 * aparece sozinho. Tudo
 * pelo controle: cima/baixo anda entre os campos e botões, OK num campo abre o
 * teclado e VOLTAR fecha. Digitar link no controle é penoso, então enquanto o
 * diálogo está aberto o celular conectado (Saimo Connect) recebe o pedido de
 * texto e o que ele escrever cai no campo em foco.
 */
object ListasDialogo {

    /**
     * @param aoMudar chamado depois de salvar, com os dados já pedidos de novo.
     */
    fun mostrar(activity: Activity, aoFechar: (() -> Unit)? = null,
                aoMudar: () -> Unit): Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val dialogo = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val vista = LayoutInflater.from(activity).inflate(R.layout.dialogo_listas, null)
        val texto = vista.findViewById<TextView>(R.id.listasTexto)
        val campoCanais = vista.findViewById<EditText>(R.id.listasCanais)
        val campoFilmes = vista.findViewById<EditText>(R.id.listasFilmes)
        val mensagem = vista.findViewById<TextView>(R.id.listasMensagem)
        val salvar = vista.findViewById<TextView>(R.id.listasSalvar)
        val tentar = vista.findViewById<TextView>(R.id.listasTentar)
        val mesmoAssim = vista.findViewById<TextView>(R.id.listasMesmoAssim)
        val fechar = vista.findViewById<TextView>(R.id.listasFechar)

        texto.text = activity.getString(R.string.listas_texto) + "\n" + activity.getString(R.string.listas_texto_ajustes)
        fechar.setText(R.string.listas_cancelar)
        val restaurar = vista.findViewById<TextView>(R.id.listasRestaurar)
        Icones.inicio(restaurar, R.drawable.ic_replay)
        restaurar.setOnClickListener {
            Enderecos.restaurarPadrao(activity.applicationContext)
            dialogo.dismiss()
            Toast.makeText(activity, R.string.listas_salvo, Toast.LENGTH_SHORT).show()
            val dono = activity as? LifecycleOwner
            if (dono == null) aoMudar() else dono.lifecycleScope.launch(semDerrubar) {
                Listas.recarregar(activity.applicationContext); aoMudar()
            }
        }
        Icones.inicio(salvar, R.drawable.ic_check)
        Icones.inicio(tentar, R.drawable.ic_replay)
        Icones.inicio(mesmoAssim, R.drawable.ic_check)
        Icones.inicio(fechar, R.drawable.ic_close)
        campoCanais.setText(Enderecos.canais.orEmpty())
        campoFilmes.setText(Enderecos.filmes.orEmpty())

        // O celular escreve no campo em que a pessoa está.
        var atual: EditText = campoCanais
        fun pedirTextoAoCelular(campo: EditText) {
            atual = campo
            Connect.notificarAberturaDeTexto(campo.hint?.toString().orEmpty(), campo.text.toString())
        }
        for (campo in listOf(campoCanais, campoFilmes)) {
            campo.setOnFocusChangeListener { _, foco -> if (foco) pedirTextoAoCelular(campo) }
            campo.setOnEditorActionListener { v, _, _ ->
                // Próximo/OK no teclado fecha o teclado e segue para o próximo item.
                v.clearFocus()
                v.focusSearch(View.FOCUS_DOWN)?.requestFocus()
                true
            }
        }
        Connect.textoAtivo = { novo ->
            atual.setText(novo)
            atual.setSelection(atual.text.length)
        }

        fun limparErro() {
            mensagem.visibility = View.GONE
            tentar.visibility = View.GONE
            mesmoAssim.visibility = View.GONE
            salvar.visibility = View.VISIBLE
        }

        // Normalizados e prontos para guardar; nulo em um campo = em branco.
        data class Escolha(val canais: String?, val filmes: String?)

        fun gravar(escolha: Escolha) {
            val apagouCanais = Enderecos.canais != null && escolha.canais == null
            val apagouFilmes = Enderecos.filmes != null && escolha.filmes == null
            Enderecos.definir(activity.applicationContext, escolha.canais, escolha.filmes)
            dialogo.dismiss()
            if (apagouCanais || apagouFilmes) Toast.makeText(activity, R.string.listas_apagado, Toast.LENGTH_SHORT).show()
            if (escolha.canais != null || escolha.filmes != null) {
                Toast.makeText(activity, R.string.listas_salvo, Toast.LENGTH_SHORT).show()
            }
            val dono = activity as? LifecycleOwner
            if (dono == null) { aoMudar(); return }
            dono.lifecycleScope.launch(semDerrubar) {
                Listas.recarregar(activity.applicationContext)
                aoMudar()
            }
        }

        var verificando = false

        fun verificar(testar: Escolha, aGravar: Escolha) {
            val dono = activity as? LifecycleOwner ?: return
            verificando = true
            limparErro()
            mensagem.setTextColor(0xFFA8B4C0.toInt())
            mensagem.text = activity.getString(R.string.listas_verificando)
            mensagem.visibility = View.VISIBLE
            dono.lifecycleScope.launch(semDerrubar) {
                val canaisOk = async { testar.canais?.let { Enderecos.testar(it, "catalogo.txt") } ?: true }
                val filmesOk = async { testar.filmes?.let { Enderecos.testar(it, "indice.txt") } ?: true }
                val ok = canaisOk.await() && filmesOk.await()
                verificando = false
                if (!dialogo.isShowing) return@launch
                if (ok) { gravar(aGravar); return@launch }
                mensagem.setTextColor(activity.getColor(R.color.live))
                mensagem.text = activity.getString(R.string.listas_erro)
                tentar.visibility = View.VISIBLE
                mesmoAssim.visibility = View.VISIBLE
                tentar.setOnClickListener { verificar(testar, aGravar) }
                mesmoAssim.setOnClickListener { gravar(aGravar) }
                tentar.requestFocus()
            }
        }

        salvar.setOnClickListener {
            if (verificando) return@setOnClickListener
            val tCanais = campoCanais.text.toString()
            val tFilmes = campoFilmes.text.toString()
            val canais = if (tCanais.isBlank()) null else Enderecos.normalizar(tCanais)
            val filmes = if (tFilmes.isBlank()) null else Enderecos.normalizar(tFilmes)
            if ((tCanais.isNotBlank() && canais == null) || (tFilmes.isNotBlank() && filmes == null)) {
                limparErro()
                mensagem.setTextColor(activity.getColor(R.color.live))
                mensagem.text = activity.getString(R.string.listas_invalido)
                mensagem.visibility = View.VISIBLE
                (if (tCanais.isNotBlank() && canais == null) campoCanais else campoFilmes).requestFocus()
                return@setOnClickListener
            }
            if (canais != null) campoCanais.setText(canais)
            if (filmes != null) campoFilmes.setText(filmes)
            if (canais == null && filmes == null) {
                gravar(Escolha(null, null))
                return@setOnClickListener
            }
            // Só testa o que mudou: apagar ou deixar como está não precisa de rede.
            val novo = Escolha(canais, filmes)
            val teste = Escolha(canais?.takeIf { it != Enderecos.canais }, filmes?.takeIf { it != Enderecos.filmes })
            if (teste.canais == null && teste.filmes == null) gravar(novo) else verificar(teste, novo)
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
            // O teclado só abre quando a pessoa aperta OK num campo.
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialogo.setOnDismissListener {
            Connect.textoAtivo = null
            Connect.notificarFechamentoDeTexto()
            aoFechar?.invoke()
        }
        dialogo.show()
        Vr.telaCheia(dialogo.window)
        campoCanais.requestFocus()
        return dialogo
    }
}

/** Ações de dados das listas, sem tela. */
object Listas {

    /**
     * Pede de novo o que depende dos endereços atuais. Sem endereço não sai
     * nenhum pedido. Falhas são silenciosas: as telas mostram o estado vazio.
     */
    suspend fun recarregar(context: android.content.Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (Enderecos.canais != null) {
            runCatching { Remote.refresh(context, forcar = true) }
            runCatching { Radios.baixar(context) }
            runCatching { GuiaTabelas.atualizar(context) }
        }
        if (Enderecos.filmes != null) runCatching { Vod.indice(context) }
    }

    fun resumo(context: android.content.Context): String {
        fun situacao(v: String?) = context.getString(if (v != null) R.string.listas_informado else R.string.listas_em_branco)
        return context.getString(R.string.ajustes_listas_resumo, situacao(Enderecos.canais), situacao(Enderecos.filmes))
    }
}
