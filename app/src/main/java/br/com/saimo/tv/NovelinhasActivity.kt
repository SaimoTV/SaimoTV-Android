package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Novelinhas: as minisséries em pé da RedeFlix, em fileiras como o On Demand
 * — continuar assistindo, dubladas, legendadas e uma fileira por tema. Abrir
 * uma toca no [NovelinhaPlayerActivity], com o vídeo em pé no meio da TV.
 */
@UnstableApi
class NovelinhasActivity : TelaComMenu() {

    override val aba: Aba get() = Aba.ON_DEMAND

    companion object {
        fun abrir(context: Context) = context.startActivity(Intent(context, NovelinhasActivity::class.java))
    }

    private lateinit var filas: RecyclerView
    private val adaptador by lazy {
        Inicio.Adapter(escopo = lifecycleScope, capaDe = { _, _ -> null }, aoFocar = { destaque.mostrar(it) })
    }
    private lateinit var destaque: Destaque
    private var montou = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pagina)
        filas = findViewById(R.id.inicioFilas)
        filas.layoutManager = Inicio.Filas(this)
        filas.adapter = adaptador
        destaque = Destaque(this, lifecycleScope, findViewById<ImageView>(R.id.inicioFundo),
            findViewById(R.id.inicioTitulo), findViewById(R.id.inicioMeta), findViewById(R.id.inicioSinopse)) { it.aoEscolher() }
        findViewById<TextView>(R.id.paginaNome).visibility = android.view.View.GONE
        destaque.padrao(getString(R.string.vod_novelinhas), getString(R.string.ondemand_novelinhas_sobre))
        lifecycleScope.launch(semDerrubar) { montar() }
    }

    override fun onRestart() {
        super.onRestart()
        // "Continuar" muda depois de assistir.
        lifecycleScope.launch(semDerrubar) { montar() }
    }

    private suspend fun montar() {
        val lista = Novelinhas.carregar(this)
        if (lista.isEmpty()) {
            mostrarVazio(getString(R.string.novelinhas_vazio))
            return
        }
        esconderVazio()
        val filasNovas = mutableListOf<Inicio.Fila>()
        val porId = lista.associateBy { it.id }
        val continuar = Novelinhas.recentes(this).mapNotNull { porId[it] }.take(20)
        if (continuar.isNotEmpty()) filasNovas += Inicio.Fila(getString(R.string.vod_continuar), continuar.map { cartao(it) })
        filasNovas += Inicio.Fila(getString(R.string.novelinhas_dublado) + "s", lista.filter { it.dublada }.take(40).map { cartao(it) })
        val temas = lista.flatMap { it.generos }.groupingBy { it }.eachCount()
            .filter { it.value >= 6 }.entries.sortedByDescending { it.value }.take(12).map { it.key }
        for (tema in temas) {
            filasNovas += Inicio.Fila(tema, lista.filter { tema in it.generos }.take(40).map { cartao(it) })
        }
        filasNovas += Inicio.Fila(getString(R.string.novelinhas_legendado) + "s", lista.filter { !it.dublada }.take(60).map { cartao(it) })
        adaptador.trocar(filasNovas.filter { it.cartoes.isNotEmpty() })
        if (!montou) {
            montou = true
            focarQuandoPronto {
                filas.findViewHolderForAdapterPosition(0)?.itemView
                    ?.findViewById<RecyclerView>(R.id.filaCapas)?.getChildAt(0)
            }
        }
    }

    private fun cartao(n: Novelinhas.Novelinha): Inicio.Cartao {
        val parada = Novelinhas.parada(this, n.id)
        val audio = getString(if (n.dublada) R.string.novelinhas_dublado else R.string.novelinhas_legendado)
        return Inicio.Cartao(
            titulo = n.titulo, capa = n.capa, inicial = n.titulo.take(1).uppercase(),
            progresso = parada?.let { it.first.toFloat() / n.episodios },
            meta = (parada?.let { getString(R.string.novelinhas_parou, it.first, n.episodios) }
                ?: getString(R.string.novelinhas_episodios, n.episodios)) + " · " + audio,
            sinopse = n.sinopse,
        ) { NovelinhaPlayerActivity.abrir(this, n.id) }
    }
}
