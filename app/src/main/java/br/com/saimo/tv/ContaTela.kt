package br.com.saimo.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ajustes > Conta Saimo na TV. Sem conta: o QR (e o código curto) para entrar
 * pelo celular, que já está logado; a tela pergunta ao servidor a cada 5 s.
 * Com conta: e-mail, última sincronização, sincronizar agora e sair.
 */
object ContaTela {

    fun abrir(activity: Activity, aoMudar: () -> Unit) {
        if (Conta.logado) mostrarLogado(activity, aoMudar) else mostrarQr(activity, aoMudar)
    }

    private fun quando(ms: Long): String {
        if (ms == 0L) return "ainda não sincronizou"
        val min = (System.currentTimeMillis() - ms) / 60_000
        return when {
            min < 1 -> "sincronizado agora"
            min < 60 -> "sincronizado há $min min"
            min < 24 * 60 -> "sincronizado há ${min / 60} h"
            else -> "sincronizado há ${min / (24 * 60)} d"
        }
    }

    fun resumo(): String = if (!Conta.logado) "Entrar pelo celular (QR)" else {
        val e = Conta.estado
        "${e.email ?: "Conta Saimo"} · ${e.erro ?: quando(e.sincronizadaEm)}"
    }

    private fun mostrarLogado(activity: Activity, aoMudar: () -> Unit) {
        val e = Conta.estado
        Painel.mostrar(activity, "Conta Saimo", listOf(
            Painel.Item(e.email ?: "Conta Saimo", e.erro ?: quando(e.sincronizadaEm)) {},
            Painel.Item("Sincronizar agora", "Perfis, onde parou, favoritos, listas e addons") {
                (activity as? LifecycleOwner)?.lifecycleScope?.launch(semDerrubar) {
                    Conta.sincronizar(forcar = true)
                    aoMudar()
                    android.widget.Toast.makeText(activity, Conta.estado.erro ?: "Sincronizado", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            Painel.Item("Sair da conta", "Os dados continuam nesta TV") {
                (activity as? LifecycleOwner)?.lifecycleScope?.launch(semDerrubar) { Conta.sair(); aoMudar() }
            },
        ))
    }

    private fun mostrarQr(activity: Activity, aoMudar: () -> Unit) {
        val dono = activity as? LifecycleOwner ?: return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialogo = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val raiz = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(36), dp(40), dp(36))
            background = GradientDrawable().apply { setColor(0xF2101826.toInt()); cornerRadius = dp(20).toFloat() }
        }
        val qr = ImageView(activity).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        raiz.addView(qr, LinearLayout.LayoutParams(dp(260), dp(260)))
        val coluna = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(36), 0, 0, 0) }
        fun texto(t: String, tam: Float, cor: Int = Color.WHITE, negrito: Boolean = false) = TextView(activity).apply {
            text = t; textSize = tam; setTextColor(cor); if (negrito) typeface = Typeface.DEFAULT_BOLD
        }
        coluna.addView(texto("Entrar na Conta Saimo", 26f, negrito = true))
        coluna.addView(texto(
            "No celular com o Saimo e a sua conta, leia o QR com a câmera. Ou abra Ajustes > Conta Saimo > Entrar numa TV e digite o código:",
            16f, 0xFFA8B4C0.toInt()).apply { setPadding(0, dp(10), 0, dp(14)) })
        val codigo = texto("…", 40f, 0xFF2DD4BF.toInt(), negrito = true).apply { letterSpacing = 0.12f }
        coluna.addView(codigo)
        val situacao = texto("Pedindo um código…", 15f, 0xFFA8B4C0.toInt()).apply { setPadding(0, dp(14), 0, 0) }
        coluna.addView(situacao)
        coluna.addView(texto("Sem conta, tudo continua só nesta TV. Voltar fecha.", 13f, 0xFF6B7280.toInt()).apply { setPadding(0, dp(18), 0, 0) })
        raiz.addView(coluna, LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogo.setContentView(raiz, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogo.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        var tarefa: Job? = null
        dialogo.setOnDismissListener { tarefa?.cancel() }
        tarefa = dono.lifecycleScope.launch(semDerrubar) {
            while (isActive) {
                val pedido = try {
                    Conta.pedirCodigoDaTv()
                } catch (e: Exception) {
                    situacao.text = e.message ?: "Sem conexão: tentando de novo…"
                    delay(10_000); continue
                }
                codigo.text = pedido.codigo
                qr.setImageBitmap(runCatching { QrCodeGerador.paraBitmap(pedido.url, dp(240)) }.getOrNull())
                situacao.text = "Esperando o celular…"
                // Até vencer (10 min); vencido, pede outro código sozinho.
                while (isActive && System.currentTimeMillis() < pedido.expiraEm) {
                    delay(pedido.intervaloMs)
                    val r = runCatching { Conta.conferirAprovacao(pedido) }.getOrElse { false }
                    if (r == true) {
                        situacao.text = "Pronto! Sincronizando…"
                        delay(1200)
                        dialogo.dismiss()
                        aoMudar()
                        android.widget.Toast.makeText(activity, "Entrou na conta ${Conta.estado.email.orEmpty()}", android.widget.Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    if (r == null) break
                }
            }
        }
        dialogo.show()
    }
}
