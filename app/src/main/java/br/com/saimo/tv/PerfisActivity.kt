package br.com.saimo.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import android.graphics.BitmapFactory

/**
 * "Quem está assistindo?": escolher, gerenciar e editar perfis (até 10, com
 * avatar). Abre sozinha na partida com 2 ou mais perfis, pelo avatar do menu e
 * pelos Ajustes. Feita para o controle (setas e OK) e para o toque.
 */
class PerfisActivity : AppCompatActivity() {

    private enum class Modo { ESCOLHER, GERENCIAR, EDITAR }

    private var modo = Modo.ESCOLHER
    private var obrigatoria = false
    private var editando: Perfis.Perfil? = null
    private var avatar = Perfis.AVATARES.first()
    private var confirmarApagar = false
    /** Código do Kids ativo já digitado nesta tela (gerenciar e editar). */
    private var destrancado = false
    private var kids = false

    /** Roda [ok] direto, ou depois do código do perfil Kids [p]. */
    private fun comCodigo(p: Perfis.Perfil, motivo: String, ok: () -> Unit) {
        if (destrancado && p.id == Perfis.idAtivo()) { ok(); return }
        CaixaDeCodigo.doPerfil(this, p, motivo) { if (p.id == Perfis.idAtivo()) destrancado = true; ok() }
    }
    private lateinit var raiz: FrameLayout
    private var nomeDigitado: String? = null
    private val d by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        obrigatoria = intent.getBooleanExtra(EXTRA_OBRIGATORIA, false)
        modo = if (intent.getStringExtra(EXTRA_MODO) == "gerenciar") Modo.GERENCIAR else Modo.ESCOLHER
        raiz = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_escolha) }
        setContentView(raiz)
        desenhar()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            modo == Modo.EDITAR -> { modo = Modo.GERENCIAR; desenhar() }
            modo == Modo.GERENCIAR -> { modo = Modo.ESCOLHER; desenhar() }
            obrigatoria -> moveTaskToBack(true)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            @Suppress("DEPRECATION") onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun desenhar() {
        raiz.removeAllViews()
        if (modo == Modo.EDITAR) desenharEdicao() else desenharGrade()
    }

    // ---------- grade de perfis ----------

    private fun desenharGrade() {
        val gerenciar = modo == Modo.GERENCIAR
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            setPadding(dp(40), dp(36), dp(40), dp(28))
        }
        coluna.addView(titulo(if (gerenciar) "Gerenciar perfis" else "Quem está assistindo?"))
        if (gerenciar) coluna.addView(sub("Escolha um perfil para mudar o nome ou o avatar."))

        val perfis = Perfis.todos()
        val total = perfis.size + if (perfis.size < Perfis.MAX) 1 else 0
        val grade = GridLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            columnCount = minOf(total, 6)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(26) }
        }
        val atual = Perfis.idAtivo()
        var foco: View? = null
        for (p in perfis) {
            val item = tile(p.nome, p.id == atual, avatarView(p.nome, p.avatar, 96, escuro = gerenciar), gerenciar, p.kids) {
                if (gerenciar) comCodigo(p, "Para editar o perfil ${p.nome}, digite o código dele.") { editar(p) }
                else if (p.id != atual) comCodigo(Perfis.ativo(), "Para sair do perfil ${Perfis.ativo().nome}, digite o código.") { escolher(p) }
                else escolher(p)
            }
            if (p.id == atual) foco = item
            grade.addView(item)
        }
        if (perfis.size < Perfis.MAX) {
            val mais = TextView(this).apply {
                text = "+"
                gravity = Gravity.CENTER
                textSize = 44f
                setTextColor(Color.argb(210, 255, 255, 255))
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(2), Color.argb(110, 255, 255, 255), dp(8).toFloat(), dp(6).toFloat())
                    setColor(Color.argb(12, 255, 255, 255))
                }
            }
            grade.addView(tile("Adicionar perfil", false, mais, false) {
                comCodigo(Perfis.ativo(), "Para criar um perfil, digite o código deste perfil Kids.") { editar(null) }
            })
        }
        coluna.addView(grade)

        val acoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(20) }
        }
        if (gerenciar) {
            acoes.addView(botao("Pronto") { modo = Modo.ESCOLHER; desenhar() })
        } else {
            acoes.addView(botao("Gerenciar perfis") {
                comCodigo(Perfis.ativo(), "Para gerenciar os perfis, digite o código deste perfil Kids.") { modo = Modo.GERENCIAR; desenhar() }
            })
            acoes.addView(botao(if (Perfis.perguntarAoAbrir) "Ao abrir: perguntar o perfil" else "Ao abrir: entrar no último") {
                Perfis.perguntarAoAbrir = !Perfis.perguntarAoAbrir
                desenhar()
                raiz.post { raiz.findViewWithTag<View>("perguntar")?.requestFocus() }
            }.apply { tag = "perguntar" })
        }
        coluna.addView(acoes)
        coluna.addView(sub("Cada perfil tem os seus favoritos, o que está assistindo e os recentes. " +
            "As listas e os ajustes valem para todos. Até ${Perfis.MAX} perfis.").apply {
            textSize = 13f
            alpha = 0.7f
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(18)
        })

        raiz.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(FrameLayout(this@PerfisActivity).apply {
                addView(coluna, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val alvo = if (gerenciar) grade.getChildAt(0) else foco ?: grade.getChildAt(0)
        raiz.post { alvo?.requestFocus() }
    }

    private fun escolher(p: Perfis.Perfil) {
        val mudou = Perfis.entrar(this, p.id)
        if (mudou || obrigatoria) {
            // Recomeça pelo Início, já com os favoritos e o progresso do perfil escolhido.
            startActivity(Intent(this, EscolhaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_DEPOIS_DO_PERFIL, true))
        }
        finish()
    }

    // ---------- editar ----------

    private fun editar(p: Perfis.Perfil?) {
        editando = p
        avatar = p?.avatar?.takeIf { it in Perfis.AVATARES }
            ?: Perfis.AVATARES.firstOrNull { a -> Perfis.todos().none { it.avatar == a } } ?: Perfis.AVATARES.first()
        confirmarApagar = false
        kids = p?.kids == true
        nomeDigitado = p?.nome ?: ""
        modo = Modo.EDITAR
        desenhar()
    }

    private fun desenharEdicao() {
        val p = editando
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(28), dp(40), dp(24))
        }
        coluna.addView(titulo(if (p == null) "Novo perfil" else "Editar perfil"))

        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(18) }
        }
        val previa = FrameLayout(this)
        fun atualizarPrevia(nome: String) {
            previa.removeAllViews()
            previa.addView(avatarView(nome.ifBlank { "?" }, avatar, 84, false))
        }
        atualizarPrevia(nomeDigitado.orEmpty())
        topo.addView(previa, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(22) })
        val campo = EditText(this).apply {
            setText(nomeDigitado.orEmpty())
            hint = "Nome do perfil"
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(Perfis.MAX_NOME))
            textSize = 20f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(120, 255, 255, 255))
            setBackgroundResource(R.drawable.bg_campo)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { nomeDigitado = s?.toString() }
            })
        }
        topo.addView(campo, LinearLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT))
        coluna.addView(topo)
        coluna.addView(sub("Escolha um avatar").apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(16) })

        val grade = GridLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            columnCount = 11
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        var focoAvatar: View? = null
        for (a in Perfis.AVATARES) {
            val v = FrameLayout(this).apply {
                isFocusable = true
                isClickable = true
                foreground = moldura(14, marcado = a == avatar)
                addView(ImageView(this@PerfisActivity).apply {
                    setImageDrawable(redondo(a, 14))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                })
                setOnFocusChangeListener { v, f ->
                    // Foco: anel branco; escolhido: anel ciano.
                    (v as FrameLayout).foreground = if (f) moldura(14, true, branco = true) else moldura(14, a == avatar)
                    v.animate().scaleX(if (f) 1.14f else 1f).scaleY(if (f) 1.14f else 1f).setDuration(120).start()
                }
                setOnClickListener {
                    avatar = a
                    for (i in 0 until grade.childCount) {
                        val filho = grade.getChildAt(i) as FrameLayout
                        filho.foreground = if (filho.hasFocus() || filho.isFocused) moldura(14, true, branco = true) else moldura(14, Perfis.AVATARES[i] == a)
                    }
                    atualizarPrevia(campo.text.toString())
                }
            }
            if (a == avatar) focoAvatar = v
            grade.addView(v, GridLayout.LayoutParams().apply {
                width = dp(52); height = dp(52); setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        coluna.addView(grade)

        val aviso = sub("").apply { setTextColor(Color.rgb(255, 179, 179)) }
        var cod1: EditText? = null
        var cod2: EditText? = null
        if (p?.id != Perfis.PRINCIPAL) {
            coluna.addView(botao(if (kids) "Perfil Kids: ligado" else "Perfil Kids: desligado") {
                kids = !kids
                desenhar()
                raiz.post { raiz.findViewWithTag<View>("kids")?.requestFocus() }
            }.apply { tag = "kids"; (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) })
            if (kids) {
                coluna.addView(sub(if (p?.kids == true) "Só filmes e séries até 12 anos, nada adulto. Para trocar o código, preencha os dois campos."
                    else "Só filmes e séries até 12 anos, nada adulto. Escolha um código de 4 números para sair do Kids."))
                val linha = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(8) }
                }
                fun campoCodigo(dica: String) = EditText(this).apply {
                    hint = dica
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    filters = arrayOf(InputFilter.LengthFilter(4))
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.argb(120, 255, 255, 255))
                    setBackgroundResource(R.drawable.bg_campo)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                cod1 = campoCodigo("Código").also { linha.addView(it, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }) }
                cod2 = campoCodigo("Repita o código").also { linha.addView(it, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT)) }
                coluna.addView(linha)
            }
        }
        val acoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) }
        }
        acoes.addView(botao("Salvar") {
            val nome = campo.text.toString().trim()
            if (nome.isBlank()) { aviso.text = "Escreva um nome para o perfil."; campo.requestFocus(); return@botao }
            var kidsCodigo: String? = null
            if (kids) {
                val c1 = cod1?.text?.toString()?.trim().orEmpty()
                val c2 = cod2?.text?.toString()?.trim().orEmpty()
                val precisa = !(p?.kids == true && p.codigo.isNotBlank())
                if (c1.isNotEmpty() || c2.isNotEmpty() || precisa) {
                    if (!Regex("\\d{4}").matches(c1)) { aviso.text = "O código do Kids tem 4 números."; cod1?.requestFocus(); return@botao }
                    if (c1 != c2) { aviso.text = "Os dois códigos não são iguais."; cod2?.requestFocus(); return@botao }
                    kidsCodigo = c1
                }
            } else if (p?.kids == true) kidsCodigo = ""
            if (p == null) Perfis.criar(nome, avatar, kidsCodigo) else Perfis.editar(p.id, nome, avatar, kidsCodigo)
            modo = Modo.GERENCIAR
            desenhar()
        })
        acoes.addView(botao("Cancelar") { modo = Modo.GERENCIAR; desenhar() })
        if (p != null && p.id != Perfis.PRINCIPAL) {
            acoes.addView(botao(if (confirmarApagar) "Apagar mesmo (favoritos e progresso somem)" else "Apagar perfil") {
                if (!confirmarApagar) {
                    confirmarApagar = true
                    desenhar()
                    raiz.post { raiz.findViewWithTag<View>("apagar")?.requestFocus() }
                } else {
                    Perfis.apagar(p.id)
                    modo = Modo.GERENCIAR
                    desenhar()
                }
            }.apply { tag = "apagar"; setTextColor(Color.rgb(255, 179, 179)) })
        }
        coluna.addView(acoes)
        coluna.addView(aviso)

        raiz.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(FrameLayout(this@PerfisActivity).apply {
                addView(coluna, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val alvo = if (p == null) campo else focoAvatar
        raiz.post { alvo?.requestFocus() }
    }

    // ---------- peças ----------

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        textSize = 30f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Color.WHITE)
    }

    private fun sub(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.argb(170, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        maxWidth = dp(620)
    }

    private fun botao(t: String, acao: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 16f
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.botao_ficha)
        setTextColor(ContextCompat.getColorStateList(this@PerfisActivity, R.color.texto_botao_ficha))
        setPadding(dp(22), dp(10), dp(22), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(8), 0, dp(8), 0) }
        setOnClickListener { acao() }
    }

    private fun tile(nome: String, atual: Boolean, imagem: View, editando: Boolean, kids: Boolean = false, acao: () -> Unit): View {
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(14), dp(14), dp(6))
            // Largura fixa: "Adicionar perfil" cabe inteiro e a grade fica alinhada.
            minimumWidth = dp(150)
            clipChildren = false
            clipToPadding = false
        }
        val moldura = FrameLayout(this).apply {
            foreground = moldura(22, false)
            addView(imagem, FrameLayout.LayoutParams(dp(96), dp(96)))
            if (kids) addView(TextView(this@PerfisActivity).apply {
                text = "KIDS"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(42, 20, 0))
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(255, 180, 61)) }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
            if (editando) addView(ImageView(this@PerfisActivity).apply {
                setImageResource(R.drawable.ic_edit_perfil)
                setColorFilter(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }
        caixa.addView(moldura, LinearLayout.LayoutParams(dp(96), dp(96)))
        val rotulo = TextView(this).apply {
            text = nome
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(146)
            gravity = Gravity.CENTER
            setTextColor(Color.argb(200, 255, 255, 255))
            setPadding(0, dp(8), 0, 0)
            if (atual) typeface = Typeface.DEFAULT_BOLD
        }
        caixa.addView(rotulo)
        if (atual) caixa.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Color.rgb(79, 243, 224)) }
        }, LinearLayout.LayoutParams(dp(22), dp(3)).apply { topMargin = dp(5) })
        caixa.setOnFocusChangeListener { _, f ->
            moldura.foreground = moldura(22, f, branco = true)
            moldura.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(140).start()
            rotulo.setTextColor(if (f) Color.WHITE else Color.argb(200, 255, 255, 255))
        }
        caixa.setOnClickListener { acao() }
        return caixa
    }

    /** Contorno: ciano quando marcado, branco quando em foco. */
    private fun moldura(raioDp: Int, marcado: Boolean, branco: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(raioDp).toFloat()
        setColor(Color.TRANSPARENT)
        if (marcado) setStroke(dp(3), if (branco) Color.WHITE else Color.rgb(79, 243, 224))
    }

    private fun redondo(avatar: String, raioDp: Int): android.graphics.drawable.Drawable? {
        val res = Perfis.avatarRes(this, avatar)
        if (res == 0) return null
        val bmp = BitmapFactory.decodeResource(resources, res) ?: return null
        return RoundedBitmapDrawableFactory.create(resources, bmp).apply { cornerRadius = bmp.width * (if (raioDp <= 14) 0.27f else 0.24f) }
    }

    private fun avatarView(nome: String, avatar: String, tamanhoDp: Int, escuro: Boolean): View {
        val img = redondo(avatar, 22)
        if (img != null) return ImageView(this).apply {
            setImageDrawable(img)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (escuro) alpha = 0.5f
        }
        return TextView(this).apply {
            text = (nome.trim().firstOrNull() ?: '?').uppercaseChar().toString()
            gravity = Gravity.CENTER
            textSize = tamanhoDp * 0.18f
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(124, 140, 255), Color.rgb(26, 31, 92)))
                .apply { cornerRadius = dp(22).toFloat() }
        }
    }

    companion object {
        const val EXTRA_OBRIGATORIA = "obrigatoria"
        const val EXTRA_MODO = "modo"
        const val EXTRA_DEPOIS_DO_PERFIL = "depois_do_perfil"

        fun abrir(tela: Activity, gerenciar: Boolean = false) {
            tela.startActivity(Intent(tela, PerfisActivity::class.java).putExtra(EXTRA_MODO, if (gerenciar) "gerenciar" else "escolher"))
        }

        /** Avatar redondo pronto para pôr num botão (menu do topo, Ajustes). */
        fun avatarDrawable(context: Context, perfil: Perfis.Perfil, tamanhoPx: Int): android.graphics.drawable.Drawable? {
            val res = Perfis.avatarRes(context, perfil.avatar)
            if (res == 0) return null
            val bmp = BitmapFactory.decodeResource(context.resources, res) ?: return null
            val escalado = android.graphics.Bitmap.createScaledBitmap(bmp, tamanhoPx, tamanhoPx, true)
            return RoundedBitmapDrawableFactory.create(context.resources, escalado).apply {
                cornerRadius = tamanhoPx * 0.26f
                setBounds(0, 0, tamanhoPx, tamanhoPx)
            }
        }
    }
}
