package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Acessibilidade com prévia: cada OK muda só o rascunho e o exemplo à direita;
 * "Aplicar" leva para o app inteiro (e recria as telas abertas).
 */
class AcessibilidadeActivity : TelaComMenu() {

    override val aba = Aba.AJUSTES

    companion object {
        fun abrir(context: Context) = context.startActivity(Intent(context, AcessibilidadeActivity::class.java))
    }

    private lateinit var aplicado: TamanhoDaTela.Escolha
    private lateinit var rascunho: TamanhoDaTela.Escolha
    private lateinit var opcoes: LinearLayout
    private lateinit var previa: LinearLayout
    private val linhas = mutableListOf<Pair<View, () -> Unit>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_acessibilidade)
        aplicado = TamanhoDaTela.atual(this)
        rascunho = aplicado
        opcoes = findViewById(R.id.acessOpcoes)
        previa = findViewById(R.id.acessPrevia)

        linha("Tamanho do texto", { TamanhoDaTela.nomes[rascunho.nivel] + "  ·  OK troca" }) {
            rascunho = rascunho.copy(nivel = (rascunho.nivel + 1) % TamanhoDaTela.escalas.size)
        }
        if (TamanhoDaTela.negritoDisponivel) {
            linha("Texto em negrito", { simNao(rascunho.negrito) + "  ·  letras mais grossas" }) {
                rascunho = rascunho.copy(negrito = !rascunho.negrito)
            }
        }
        linha("Foco reforçado", { simNao(rascunho.focoForte) + "  ·  contorno amarelo onde o controle está" }) {
            rascunho = rascunho.copy(focoForte = !rascunho.focoForte)
        }
        linha("Aplicar", { if (rascunho == aplicado) "Nada mudou ainda" else "Leva estas escolhas para o app inteiro" }) {
            if (rascunho == aplicado) return@linha
            aplicado = rascunho
            TamanhoDaTela.aplicar(this, rascunho)
            Toast.makeText(this, "Acessibilidade aplicada", Toast.LENGTH_SHORT).show()
        }
        linha("Desfazer", { "Volta ao que está aplicado" }) { rascunho = aplicado }
        linha("Voltar ao padrão", { "Normal, sem negrito, foco comum" }) { rascunho = TamanhoDaTela.padrao }
        atualizar()
        focarQuandoPronto { linhas.firstOrNull()?.first }
    }

    private fun simNao(v: Boolean) = getString(if (v) R.string.ajustes_ligado else R.string.ajustes_desligado)

    private fun linha(nome: String, valor: () -> String, acao: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_painel, opcoes, false)
        v.findViewById<TextView>(R.id.itemTexto).text = nome
        v.setOnClickListener { acao(); atualizar() }
        opcoes.addView(v)
        linhas += v to { v.findViewById<TextView>(R.id.itemDetalhe).apply { visibility = View.VISIBLE; text = valor() } }
    }

    private fun atualizar() {
        linhas.forEach { it.second() }
        montarPrevia()
    }

    /** O exemplo à direita, desenhado com o rascunho (relativo ao que a tela já usa). */
    private fun montarPrevia() {
        previa.removeAllViews()
        val d = resources.displayMetrics.density
        // A tela já está na escala aplicada: a prévia compensa para mostrar a do rascunho.
        // Escala do sistema (a tela já vem multiplicada pela aplicada) vezes a do rascunho.
        val k = resources.configuration.fontScale / TamanhoDaTela.escalas[aplicado.nivel] * TamanhoDaTela.escalas[rascunho.nivel]
        val peso = if (rascunho.negrito) Typeface.BOLD else Typeface.NORMAL
        fun texto(t: String, sp: Float, cor: Int, negritoSempre: Boolean = false) = TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp * k)
            setTextColor(cor)
            setTypeface(Typeface.create("sans-serif", if (negritoSempre) Typeface.BOLD else peso))
        }
        previa.addView(texto("Prévia", 15f, 0xFF4FE3D0.toInt(), negritoSempre = true))
        previa.addView(texto("Filmes em alta", 24f, 0xFFFFFFFF.toInt(), negritoSempre = true).apply { setPadding(0, (8 * d).toInt(), 0, (12 * d).toInt()) })
        val cartoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("A Odisseia" to "2026 · Aventura", "Série do domingo" to "T2 · E5").forEachIndexed { i, (nome, sub) ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                if (i == 0) background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    if (rascunho.focoForte) setStroke((5 * d).toInt(), 0xFFFFD400.toInt()) else setStroke((3 * d).toInt(), 0xFF2DD4BF.toInt())
                }
            }
            c.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF8B5CF6.toInt(), 0xFF4FE3D0.toInt())).apply { cornerRadius = 10 * d }
            }, LinearLayout.LayoutParams((150 * d).toInt(), (90 * d).toInt()))
            c.addView(texto(nome, 18f, 0xFFFFFFFF.toInt()).apply { setPadding(0, (6 * d).toInt(), 0, 0); maxWidth = (150 * d).toInt() })
            c.addView(texto(sub, 14f, 0xB3FFFFFF.toInt()))
            cartoes.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (16 * d).toInt() })
        }
        previa.addView(cartoes)
        previa.addView(texto("OK abre · segure OK para favoritar · VOLTAR vai ao menu", 16f, 0xB3FFFFFF.toInt()).apply {
            setPadding(0, (14 * d).toInt(), 0, 0); gravity = Gravity.START
        })
    }
}
