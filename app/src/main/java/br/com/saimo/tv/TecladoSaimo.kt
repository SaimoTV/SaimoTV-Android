package br.com.saimo.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * O teclado do Saimo para os campos de texto (link da lista, usuário, senha,
 * nome, código).
 *
 * Cada TV Box traz um teclado do sistema diferente: em alguns (Xiaomi, por
 * exemplo) o campo abre um teclado que não digita letras, ou abre em tela cheia
 * com o texto branco no fundo branco. Este é igual em todo aparelho, escuro e
 * feito para o controle remoto. "Teclado do aparelho" continua à mão, e o Saimo
 * Connect digita pelo celular como sempre.
 */
object TecladoSaimo {

    private const val CIANO = 0xFF2DD4BF.toInt()

    /**
     * Liga o teclado do Saimo a um campo: OK (ou toque) abre o teclado; o do
     * sistema só aparece se a pessoa escolher "Teclado do aparelho".
     */
    fun ligar(campo: EditText) {
        campo.showSoftInputOnFocus = false
        // Se o teclado do aparelho for usado, que ele não abra em tela cheia
        // (é a tela cheia que deixa o texto branco sobre fundo branco).
        campo.imeOptions = campo.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        campo.isCursorVisible = true
        campo.setOnClickListener { abrir(campo) }
        campo.setOnKeyListener { _, codigo, evento ->
            if (evento.action == KeyEvent.ACTION_UP &&
                (codigo == KeyEvent.KEYCODE_DPAD_CENTER || codigo == KeyEvent.KEYCODE_ENTER || codigo == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                abrir(campo); true
            } else codigo == KeyEvent.KEYCODE_DPAD_CENTER || codigo == KeyEvent.KEYCODE_ENTER
        }
    }

    /** Liga o teclado a todos os campos de texto dentro de [raiz]. */
    fun ligarTodos(raiz: View) {
        if (raiz is EditText) { ligar(raiz); return }
        if (raiz is ViewGroup) for (i in 0 until raiz.childCount) ligarTodos(raiz.getChildAt(i))
    }

    private fun fundo(cor: Int, raio: Float, borda: Int = 0, corBorda: Int = 0) = GradientDrawable().apply {
        setColor(cor); cornerRadius = raio
        if (borda > 0) setStroke(borda, corBorda)
    }

    private fun senha(campo: EditText): Boolean {
        val v = campo.inputType and InputType.TYPE_MASK_VARIATION
        val c = campo.inputType and InputType.TYPE_MASK_CLASS
        return (c == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (c == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun numerico(campo: EditText) = (campo.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER

    fun abrir(campo: EditText) {
        val activity = campo.context as? Activity ?: (campo.context as? android.content.ContextWrapper)?.baseContext as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val texto = StringBuilder(campo.text?.toString().orEmpty())
        val esconder = senha(campo)
        var mostrar = false
        var maiusculas = false
        var simbolos = false
        val so = numerico(campo)
        val limite = (campo.filters.firstOrNull { it is android.text.InputFilter.LengthFilter } as? android.text.InputFilter.LengthFilter)?.max ?: 2000

        val dialogo = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val raiz = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = fundo(0xF2101826.toInt(), dp(20).toFloat())
        }
        val titulo = TextView(activity).apply {
            text = campo.hint ?: ""
            setTextColor(CIANO); textSize = 14f
        }
        val visor = TextView(activity).apply {
            setTextColor(Color.WHITE); textSize = 20f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = fundo(0xFF1F2937.toInt(), dp(12).toFloat())
            minWidth = dp(720); maxWidth = dp(900)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        fun atualizarVisor() {
            val t = if (esconder && !mostrar) "•".repeat(texto.length) else texto.toString()
            visor.text = if (t.isEmpty()) "" else "$t|"
            visor.hint = campo.hint
            visor.setHintTextColor(0xFF6B7280.toInt())
        }
        raiz.addView(titulo)
        raiz.addView(visor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val grade = GridLayout(activity).apply { columnCount = 10 }
        raiz.addView(grade, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        fun tecla(rotulo: String, colunas: Int = 1, destaque: Boolean = false, acao: () -> Unit) = TextView(activity).apply {
            text = rotulo
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (rotulo.length > 3) 14f else 18f
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), fundo(if (destaque) CIANO else 0xFF374151.toInt(), dp(10).toFloat(), dp(3), Color.WHITE))
                addState(intArrayOf(), fundo(if (destaque) 0xFF0F766E.toInt() else 0xFF1F2937.toInt(), dp(10).toFloat()))
            }
            setOnClickListener { acao() }
            layoutParams = GridLayout.LayoutParams().apply {
                width = dp(64) * colunas + dp(6) * (colunas - 1)
                height = dp(44)
                setMargins(dp(3), dp(3), dp(3), dp(3))
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, colunas)
            }
        }

        fun digitar(s: String) {
            if (texto.length + s.length > limite) return
            texto.append(s); atualizarVisor()
        }

        lateinit var montar: () -> Unit
        fun confirmar() {
            campo.setText(texto.toString())
            campo.setSelection(campo.text.length)
            dialogo.dismiss()
            // Como o "Próximo" do teclado: vai para o campo de baixo.
            campo.focusSearch(View.FOCUS_DOWN)?.requestFocus()
        }
        montar = {
            val foco = grade.findFocus()?.let { grade.indexOfChild(it) } ?: 0
            grade.removeAllViews()
            val linhas: List<String> = when {
                so -> listOf("1234567890")
                simbolos -> listOf("1234567890", "./:-_@?=&%", "#+!~*,;()[", "]{}$'\"<>\\|")
                else -> listOf("1234567890", "qwertyuiop", "asdfghjkl@", "zxcvbnm./:")
            }
            for (l in linhas) for (c in l) {
                val s = if (maiusculas && c.isLetter()) c.uppercase() else c.toString()
                grade.addView(tecla(s) { digitar(s) })
            }
            if (!so) {
                grade.addView(tecla(if (maiusculas) "abc" else "ABC", 2) { maiusculas = !maiusculas; montar() })
                grade.addView(tecla(if (simbolos) "abc" else "#+=", 2) { simbolos = !simbolos; montar() })
                grade.addView(tecla("Espaço", 3) { digitar(" ") })
                grade.addView(tecla("-", 1) { digitar("-") })
                grade.addView(tecla("_", 1) { digitar("_") })
                grade.addView(tecla(".com", 1) { digitar(".com") })
                grade.addView(tecla("http://", 2) { digitar("http://") })
                grade.addView(tecla("https://", 2) { digitar("https://") })
                grade.addView(tecla(".m3u", 2) { digitar(".m3u") })
                grade.addView(tecla(".br", 2) { digitar(".br") })
                grade.addView(tecla("www.", 2) { digitar("www.") })
            }
            grade.addView(tecla("Apagar", 2) { if (texto.isNotEmpty()) { texto.setLength(texto.length - 1); atualizarVisor() } })
            grade.addView(tecla("Limpar", 2) { texto.setLength(0); atualizarVisor() })
            if (esconder) grade.addView(tecla(if (mostrar) "Ocultar" else "Mostrar", 2) { mostrar = !mostrar; atualizarVisor(); montar() })
            grade.addView(tecla("Teclado do aparelho", 3) {
                campo.setText(texto.toString())
                campo.setSelection(campo.text.length)
                dialogo.dismiss()
                campo.showSoftInputOnFocus = true
                campo.requestFocus()
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(campo, InputMethodManager.SHOW_IMPLICIT)
            })
            grade.addView(tecla("OK", 2, destaque = true) { confirmar() })
            grade.getChildAt(foco.coerceIn(0, grade.childCount - 1))?.requestFocus()
        }
        montar()
        atualizarVisor()

        val dica = TextView(activity).apply {
            text = "Dica: pelo Saimo Connect dá para digitar no celular."
            setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
        }
        raiz.addView(dica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        dialogo.setContentView(raiz)
        dialogo.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
        }
        dialogo.setOnKeyListener { _, codigo, evento ->
            // Teclas de um teclado físico ou do controle com números também digitam.
            if (evento.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                codigo == KeyEvent.KEYCODE_DEL -> { if (texto.isNotEmpty()) { texto.setLength(texto.length - 1); atualizarVisor() }; true }
                codigo in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && grade.findFocus() == null -> { digitar(('0' + (codigo - KeyEvent.KEYCODE_0)).toString()); true }
                evento.unicodeChar in 33..126 && codigo !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER -> {
                    digitar(evento.unicodeChar.toChar().toString()); true
                }
                else -> false
            }
        }
        dialogo.show()
    }
}
