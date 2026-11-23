package br.com.saimo.tv

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * Acessibilidade: o tamanho do texto do app inteiro (Ajustes > Aparência).
 *
 * Mexe só na escala das letras (fontScale), por cima do que o sistema já usa:
 * as telas foram feitas para caber na TV, então os espaços continuam os mesmos
 * e só o texto cresce. Cada tela aplica ao abrir (attachBaseContext); trocar a
 * opção recria as telas abertas para valer na hora.
 */
object TamanhoDaTela {

    private const val ARQUIVO = "ajustes"
    private const val CHAVE = "tamanho_texto"

    /** Normal, Grande, Muito grande. */
    val escalas = floatArrayOf(1f, 1.15f, 1.3f)
    val nomes = listOf("Normal", "Grande", "Muito grande")

    fun nivel(context: Context): Int =
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).getInt(CHAVE, 0).coerceIn(0, escalas.lastIndex)

    fun definir(activity: Activity, nivel: Int) {
        activity.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).edit().putInt(CHAVE, nivel).commit()
        // As telas abertas por baixo também: voltar a elas já mostra no tamanho novo.
        SaimoApp.recriarTodas()
    }

    /** O contexto da tela com a escala escolhida (o mesmo, se for a normal). */
    fun envolver(base: Context): Context {
        val n = nivel(base)
        if (n == 0) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = config.fontScale * escalas[n]
        return base.createConfigurationContext(config)
    }
}
