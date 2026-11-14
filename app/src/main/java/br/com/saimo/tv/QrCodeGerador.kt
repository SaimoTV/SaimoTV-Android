package br.com.saimo.tv

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.charset.StandardCharsets

/**
 * Gerador clean-room de QR Code (ISO/IEC 18004, versões 1 a 10, modo byte, EC M)
 * em Kotlin puro, sem dependências externas, usado para exibir a URL de configuração
 * de Addons e Debrid na TV para leitura pelo celular.
 */
object QrCodeGerador {

    data class Matriz(val tamanho: Int, val modulos: BooleanArray) {
        fun ativo(x: Int, y: Int): Boolean =
            x in 0 until tamanho && y in 0 until tamanho && modulos[y * tamanho + x]
    }

    private data class SpecVersao(
        val versao: Int,
        val capacidadeBytes: Int,
        val ecPorBloco: Int,
        val blocosG1: Int,
        val dadosG1: Int,
        val blocosG2: Int = 0,
        val dadosG2: Int = 0,
    )

    // Tabela ISO/IEC 18004 (nível de correção M, versões 1..10)
    private val TABELA_M = listOf(
        SpecVersao(1, 14, 10, 1, 16),
        SpecVersao(2, 26, 16, 1, 28),
        SpecVersao(3, 42, 26, 1, 44),
        SpecVersao(4, 62, 18, 2, 32),
        SpecVersao(5, 84, 24, 2, 43),
        SpecVersao(6, 106, 16, 4, 27),
        SpecVersao(7, 122, 18, 4, 31),
        SpecVersao(8, 152, 22, 2, 38, 2, 39),
        SpecVersao(9, 180, 22, 3, 36, 2, 37),
        SpecVersao(10, 213, 26, 4, 43, 1, 44),
    )

    private val CENTROS_ALINHAMENTO = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
    )

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var v = 1
        for (i in 0 until 255) {
            GF_EXP[i] = v
            GF_LOG[v] = i
            v = v shl 1
            if (v and 0x100 != 0) v = v xor 0x11D
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    /** Gera a matriz booleana (`true` = módulo escuro) do QR Code para [texto]. */
    fun gerarMatriz(texto: String): Matriz {
        val bytes = texto.toByteArray(StandardCharsets.UTF_8)
        val spec = TABELA_M.firstOrNull { bytes.size <= it.capacidadeBytes }
            ?: throw IllegalArgumentException("Texto muito longo para QR Code (${bytes.size} bytes)")

        val fluxoBits = montarDadosBitStream(bytes, spec)
        val codewordsFinais = montarCodewordsComEc(fluxoBits, spec)

        val n = 17 + 4 * spec.versao
        val funcao = BooleanArray(n * n)
        val base = BooleanArray(n * n)

        desenharPadroesFuncao(n, spec.versao, funcao, base)
        preencherBitsDados(n, funcao, base, codewordsFinais)

        var melhorMascara = 0
        var melhorPontuacao = Int.MAX_VALUE
        var melhorGrid = BooleanArray(0)

        for (mascara in 0..7) {
            val candidato = base.copyOf()
            aplicarMascara(n, funcao, candidato, mascara)
            desenharInfoFormato(n, candidato, mascara)
            val pontos = calcularPenalidade(n, candidato)
            if (pontos < melhorPontuacao) {
                melhorPontuacao = pontos
                melhorMascara = mascara
                melhorGrid = candidato
            }
        }
        if (melhorGrid.isEmpty()) {
            val fallback = base.copyOf()
            aplicarMascara(n, funcao, fallback, melhorMascara)
            desenharInfoFormato(n, fallback, melhorMascara)
            melhorGrid = fallback
        }

        return Matriz(tamanho = n, modulos = melhorGrid)
    }

    /** Renderiza o QR Code como [Bitmap] pronto para exibição em `ImageView` na TV. */
    fun paraBitmap(texto: String, tamanhoPx: Int = 520, margemModulos: Int = 4): Bitmap {
        val matriz = gerarMatriz(texto)
        val total = matriz.tamanho + margemModulos * 2
        val bmp = Bitmap.createBitmap(tamanhoPx, tamanhoPx, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(tamanhoPx * tamanhoPx) { Color.WHITE }

        for (py in 0 until tamanhoPx) {
            val my = (py * total) / tamanhoPx - margemModulos
            if (my !in 0 until matriz.tamanho) continue
            val rowOffset = py * tamanhoPx
            for (px in 0 until tamanhoPx) {
                val mx = (px * total) / tamanhoPx - margemModulos
                if (mx in 0 until matriz.tamanho && matriz.ativo(mx, my)) {
                    pixels[rowOffset + px] = Color.BLACK
                }
            }
        }
        bmp.setPixels(pixels, 0, tamanhoPx, 0, 0, tamanhoPx, tamanhoPx)
        return bmp
    }

    private fun montarDadosBitStream(bytes: ByteArray, spec: SpecVersao): IntArray {
        val bits = ArrayList<Int>(bytes.size * 8 + 32)
        fun pushBits(valor: Int, qtd: Int) {
            for (i in qtd - 1 downTo 0) bits.add((valor ushr i) and 1)
        }

        val totalDadosBytes = spec.blocosG1 * spec.dadosG1 + spec.blocosG2 * spec.dadosG2
        val totalDadosBits = totalDadosBytes * 8

        pushBits(0b0100, 4) // Modo Byte
        pushBits(bytes.size, if (spec.versao <= 9) 8 else 16)
        for (b in bytes) pushBits(b.toInt() and 0xFF, 8)

        val terminador = minOf(4, totalDadosBits - bits.size)
        repeat(terminador) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)

        val bytesDados = IntArray(totalDadosBytes)
        for (i in 0 until bits.size / 8) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bits[i * 8 + b]
            bytesDados[i] = v
        }
        val pad = intArrayOf(0xEC, 0x11)
        var p = 0
        for (i in bits.size / 8 until totalDadosBytes) {
            bytesDados[i] = pad[p++ and 1]
        }
        return bytesDados
    }

    private fun montarCodewordsComEc(dados: IntArray, spec: SpecVersao): ByteArray {
        val totalBlocos = spec.blocosG1 + spec.blocosG2
        val blocosDados = ArrayList<IntArray>(totalBlocos)
        val blocosEc = ArrayList<IntArray>(totalBlocos)
        val gerador = gerarPolinomioRs(spec.ecPorBloco)

        var pos = 0
        repeat(spec.blocosG1) {
            val fatia = dados.copyOfRange(pos, pos + spec.dadosG1)
            pos += spec.dadosG1
            blocosDados.add(fatia)
            blocosEc.add(calcularEc(fatia, gerador, spec.ecPorBloco))
        }
        repeat(spec.blocosG2) {
            val fatia = dados.copyOfRange(pos, pos + spec.dadosG2)
            pos += spec.dadosG2
            blocosDados.add(fatia)
            blocosEc.add(calcularEc(fatia, gerador, spec.ecPorBloco))
        }

        val totalSaida = dados.size + totalBlocos * spec.ecPorBloco
        val saida = ByteArray(totalSaida)
        var idx = 0
        val maxDados = maxOf(spec.dadosG1, spec.dadosG2)
        for (col in 0 until maxDados) {
            for (b in blocosDados) {
                if (col < b.size) saida[idx++] = b[col].toByte()
            }
        }
        for (col in 0 until spec.ecPorBloco) {
            for (ec in blocosEc) {
                saida[idx++] = ec[col].toByte()
            }
        }
        return saida
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else GF_EXP[(GF_LOG[a] + GF_LOG[b]) % 255]

    private fun gerarPolinomioRs(grau: Int): IntArray {
        var poly = IntArray(grau + 1)
        poly[grau] = 1
        var raiz = 1
        for (i in 0 until grau) {
            for (j in 0 until poly.size) {
                poly[j] = gfMul(poly[j], raiz)
                if (j + 1 < poly.size) poly[j] = poly[j] xor poly[j + 1]
            }
            raiz = gfMul(raiz, 2)
        }
        return poly
    }

    private fun calcularEc(dados: IntArray, gerador: IntArray, ecLen: Int): IntArray {
        val rem = IntArray(ecLen)
        for (b in dados) {
            val fator = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            for (i in 0 until ecLen) {
                rem[i] = rem[i] xor gfMul(gerador[ecLen - 1 - i], fator)
            }
        }
        return rem
    }

    private fun desenharPadroesFuncao(n: Int, versao: Int, funcao: BooleanArray, grid: BooleanArray) {
        fun marcar(x: Int, y: Int, escuro: Boolean) {
            if (x in 0 until n && y in 0 until n) {
                val i = y * n + x
                funcao[i] = true
                grid[i] = escuro
            }
        }

        fun finder(ox: Int, oy: Int) {
            for (dy in -1..7) {
                for (dx in -1..7) {
                    val x = ox + dx
                    val y = oy + dy
                    if (x !in 0 until n || y !in 0 until n) continue
                    val escuro = (dx in 0..6 && dy in 0..6) && (
                        dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)
                    )
                    marcar(x, y, escuro)
                }
            }
        }

        finder(0, 0)
        finder(n - 7, 0)
        finder(0, n - 7)

        for (i in 8 until n - 8) {
            val escuro = i % 2 == 0
            marcar(i, 6, escuro)
            marcar(6, i, escuro)
        }

        val centros = CENTROS_ALINHAMENTO[versao - 1]
        for (cy in centros) {
            for (cx in centros) {
                if (funcao[cy * n + cx]) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        marcar(cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }

        // Reserva área das 2 cópias de informação de formato + módulo escuro fixo
        for (i in 0..8) {
            if (i != 6) {
                funcao[8 * n + i] = true
                funcao[i * n + 8] = true
            }
        }
        for (i in n - 8 until n) {
            funcao[8 * n + i] = true
            funcao[i * n + 8] = true
        }
        marcar(8, n - 8, true)

        // Informação de versão (V >= 7)
        if (versao >= 7) {
            var rem = versao shl 12
            for (b in 17 downTo 12) {
                if ((rem ushr b) and 1 != 0) rem = rem xor (0x1F25 shl (b - 12))
            }
            val verBits = (versao shl 12) or rem
            for (i in 0 until 18) {
                val escuro = ((verBits ushr i) and 1) != 0
                val a = n - 11 + (i % 3)
                val b = i / 3
                marcar(a, b, escuro)
                marcar(b, a, escuro)
            }
        }
    }

    private fun preencherBitsDados(n: Int, funcao: BooleanArray, grid: BooleanArray, dados: ByteArray) {
        var bitIdx = 0
        val totalBits = dados.size * 8
        var dirSubindo = true
        var colDireita = n - 1
        while (colDireita >= 1) {
            if (colDireita == 6) colDireita--
            for (passo in 0 until n) {
                val y = if (dirSubindo) n - 1 - passo else passo
                for (dx in 0..1) {
                    val x = colDireita - dx
                    val i = y * n + x
                    if (!funcao[i]) {
                        val escuro = if (bitIdx < totalBits) {
                            ((dados[bitIdx ushr 3].toInt() ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else false
                        grid[i] = escuro
                        bitIdx++
                    }
                }
            }
            dirSubindo = !dirSubindo
            colDireita -= 2
        }
    }

    private fun aplicarMascara(n: Int, funcao: BooleanArray, grid: BooleanArray, mascara: Int) {
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                if (funcao[i]) continue
                val inverter = when (mascara) {
                    0 -> (y + x) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (y + x) % 3 == 0
                    4 -> ((y / 2) + (x / 3)) % 2 == 0
                    5 -> ((y * x) % 2) + ((y * x) % 3) == 0
                    6 -> (((y * x) % 2) + ((y * x) % 3)) % 2 == 0
                    else -> (((y + x) % 2) + ((y * x) % 3)) % 2 == 0
                }
                if (inverter) grid[i] = !grid[i]
            }
        }
    }

    private fun desenharInfoFormato(n: Int, grid: BooleanArray, mascara: Int) {
        // EC M = 0b00
        val data = mascara and 0x7
        var rem = data shl 10
        for (b in 14 downTo 10) {
            if ((rem ushr b) and 1 != 0) rem = rem xor (0x537 shl (b - 10))
        }
        val fmt = ((data shl 10) or rem) xor 0x5412

        for (k in 0..14) {
            val escuro = ((fmt ushr k) and 1) != 0
            // Cópia 1 (ao redor do finder superior esquerdo)
            when {
                k < 6 -> grid[k * n + 8] = escuro
                k < 8 -> grid[(k + 1) * n + 8] = escuro
                k == 8 -> grid[8 * n + 7] = escuro
                else -> grid[8 * n + (14 - k)] = escuro
            }
            // Cópia 2 (ao redor dos finders superior direito e inferior esquerdo)
            if (k < 8) {
                grid[8 * n + (n - 1 - k)] = escuro
            } else {
                grid[(n - 15 + k) * n + 8] = escuro
            }
        }
    }

    private fun calcularPenalidade(n: Int, grid: BooleanArray): Int {
        var pontos = 0
        // Regra 1: sequências >= 5 da mesma cor em linhas e colunas
        for (y in 0 until n) {
            var corrLine = 0
            var ultimaLine = false
            var corrCol = 0
            var ultimaCol = false
            for (x in 0 until n) {
                val vLine = grid[y * n + x]
                if (x == 0 || vLine != ultimaLine) {
                    if (corrLine >= 5) pontos += 3 + (corrLine - 5)
                    corrLine = 1
                    ultimaLine = vLine
                } else corrLine++

                val vCol = grid[x * n + y]
                if (x == 0 || vCol != ultimaCol) {
                    if (corrCol >= 5) pontos += 3 + (corrCol - 5)
                    corrCol = 1
                    ultimaCol = vCol
                } else corrCol++
            }
            if (corrLine >= 5) pontos += 3 + (corrLine - 5)
            if (corrCol >= 5) pontos += 3 + (corrCol - 5)
        }
        // Regra 2: blocos 2x2 uniformes
        for (y in 0 until n - 1) {
            for (x in 0 until n - 1) {
                val c = grid[y * n + x]
                if (c == grid[y * n + x + 1] && c == grid[(y + 1) * n + x] && c == grid[(y + 1) * n + x + 1]) {
                    pontos += 3
                }
            }
        }
        // Regra 3: padrões semelhantes a finder (1:1:3:1:1 com 4 claros)
        val p1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val p2 = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        for (y in 0 until n) {
            for (x in 0..n - 11) {
                if ((0 until 11).all { grid[y * n + x + it] == p1[it] } ||
                    (0 until 11).all { grid[y * n + x + it] == p2[it] }
                ) pontos += 40
                if ((0 until 11).all { grid[(x + it) * n + y] == p1[it] } ||
                    (0 until 11).all { grid[(x + it) * n + y] == p2[it] }
                ) pontos += 40
            }
        }
        // Regra 4: proporção de módulos escuros vs 50%
        val escuros = grid.count { it }
        val pct = (escuros * 100) / (n * n)
        val desvio = kotlin.math.abs(pct - 50) / 5
        pontos += desvio * 10
        return pontos
    }
}
