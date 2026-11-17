package br.com.saimo.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pede um ou dois códigos de 4 números (Kids: sair, editar, Ajustes; controle
 * parental: liberar e trocar a senha). [aoConfirmar] devolve a mensagem de
 * erro, ou null quando deu certo.
 */
object CaixaDeCodigo {

    fun pedir(
        tela: Activity,
        titulo: String,
        motivo: String,
        campos: List<String>,
        aoConfirmar: (List<String>) -> String?,
        aoCancelar: () -> Unit = {},
    ) {
        val d = tela.resources.displayMetrics.density
        val caixa = LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        caixa.addView(TextView(tela).apply { text = motivo; textSize = 15f; setTextColor(Color.argb(200, 255, 255, 255)) })
        val entradas = campos.map { dica ->
            EditText(tela).apply {
                hint = dica
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(InputFilter.LengthFilter(4))
                gravity = Gravity.CENTER
                textSize = 22f
                letterSpacing = 0.4f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.argb(110, 255, 255, 255))
                setBackgroundResource(R.drawable.bg_campo)
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                imeOptions = EditorInfo.IME_ACTION_DONE
            }.also {
                caixa.addView(it, LinearLayout.LayoutParams((220 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * d).toInt(); gravity = Gravity.CENTER_HORIZONTAL })
            }
        }
        val aviso = TextView(tela).apply { textSize = 14f; setTextColor(Color.rgb(255, 179, 179)) }
        caixa.addView(aviso)
        val dialogo = AlertDialog.Builder(tela, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(titulo)
            .setView(caixa)
            .setPositiveButton("Confirmar", null)
            .setNegativeButton("Cancelar") { _, _ -> aoCancelar() }
            .setOnCancelListener { aoCancelar() }
            .create()
        val confirmar = {
            val erro = aoConfirmar(entradas.map { it.text.toString().trim() })
            if (erro == null) dialogo.dismiss()
            else { aviso.text = erro; entradas.forEach { it.setText("") }; entradas.first().requestFocus() }
        }
        entradas.last().setOnEditorActionListener { _, _, _ -> confirmar(); true }
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirmar() }
            entradas.first().requestFocus()
        }
        dialogo.show()
    }

    /** Pede o código do perfil Kids [p] e, certo, roda [ok]. Perfil comum: roda direto. */
    fun doPerfil(tela: Activity, p: Perfis.Perfil, motivo: String, ok: () -> Unit) {
        if (!Perfis.pedeCodigo(p)) { ok(); return }
        pedir(tela, "Código do perfil ${p.nome}", motivo, listOf("Código"), { v ->
            if (Perfis.conferirCodigo(p, v[0])) { ok(); null } else "Código errado. Tente de novo."
        })
    }
}
