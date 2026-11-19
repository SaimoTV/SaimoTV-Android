package br.com.saimo.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Primeira tela para quem ainda não entrou numa conta: à esquerda entrar ou
 * criar a conta, à direita seguir sem login. A conta é opcional. Quem escolhe
 * "sem login" não vê esta tela de novo; a Conta Saimo continua sendo a
 * primeira opção em Ajustes.
 */
object BoasVindas {
    private const val PREFS = "boas_vindas"
    private const val FEITO = "feito"

    fun precisa(context: Context): Boolean =
        !Conta.logado && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(FEITO, false)

    fun mostrar(activity: Activity, aoTerminar: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialogo = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialogo.setCancelable(false)

        fun texto(t: String, tam: Float, cor: Int = Color.WHITE, negrito: Boolean = false) = TextView(activity).apply {
            text = t; textSize = tam; setTextColor(cor); if (negrito) typeface = Typeface.DEFAULT_BOLD
        }
        fun botao(rotulo: String, acao: () -> Unit) = Button(activity).apply {
            text = rotulo; isAllCaps = false; textSize = 20f
            isFocusable = true
            setOnClickListener { acao() }
        }
        fun lado(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(32), dp(36), dp(32))
            background = GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = dp(20).toFloat() }
        }

        val raiz = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0B0E14.toInt())
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }

        val esquerda = lado().apply {
            addView(texto("Entrar ou criar conta", 30f, negrito = true))
            addView(texto("Por que criar uma conta?", 18f, negrito = true).apply { setPadding(0, dp(18), 0, dp(6)) })
            for (t in listOf(
                "• Perfis, favoritos, listas e addons guardados e em qualquer aparelho.",
                "• Continue de onde parou no celular, na TV e no computador.",
                "• Trocou de aparelho ou reinstalou? Nada se perde.",
            )) addView(texto(t, 16f, 0xFFA8B4C0.toInt()).apply { setPadding(0, dp(3), 0, dp(3)) })
        }
        val entrar = botao("Entrar ou criar conta") {
            ContaTela.abrir(activity) {
                if (Conta.logado) { dialogo.dismiss(); aoTerminar() }
            }
        }
        esquerda.addView(entrar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(22) })

        val direita = lado().apply {
            addView(texto("Entrar sem login", 30f, negrito = true))
            addView(texto("A conta não é obrigatória. Dá para usar o Saimo TV inteiro sem entrar: tudo fica guardado só neste aparelho.",
                17f, 0xFFA8B4C0.toInt()).apply { setPadding(0, dp(16), 0, dp(10)) })
            addView(texto("Crie a conta quando quiser, em Ajustes > Conta Saimo.", 17f, 0xFFA8B4C0.toInt()))
        }
        val semLogin = botao("Entrar sem login") {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(FEITO, true).apply()
            dialogo.dismiss()
            aoTerminar()
        }
        direita.addView(semLogin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(26) })

        raiz.addView(esquerda, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(20) })
        raiz.addView(direita, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dialogo.setContentView(raiz)
        dialogo.show()
        entrar.requestFocus()
    }
}
