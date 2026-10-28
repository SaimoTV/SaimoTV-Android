package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A conta da amostra tem que bater com a do monitor (worker.js, `amostrado`). */
class AmostraTest {

    @Test
    fun `hash igual ao do servidor`() {
        // Valores tirados do JavaScript de referência, rodado no node.
        assertEquals(3670190157L, Amostra.fnv1a("79a275ad-b783-488c-8e18-2439ff3aed1c"))
        assertEquals(2926602047L, Amostra.fnv1a("db09f237-fffa-40c7-94c5-35eddcad6970"))
        assertEquals(2166136261L, Amostra.fnv1a(""))
    }

    @Test
    fun `id em maiusculas da o mesmo hash`() {
        assertEquals(3670190157L, Amostra.fnv1a("79A275AD-B783-488C-8E18-2439FF3AED1C"))
    }

    @Test
    fun `quem entra a 5 por cento`() {
        assertTrue(Amostra.dentro("79a275ad-b783-488c-8e18-2439ff3aed1c", 5.0))
        assertFalse(Amostra.dentro("db09f237-fffa-40c7-94c5-35eddcad6970", 5.0))
        // Percentual inválido cai no padrão de 5%.
        assertTrue(Amostra.dentro("79a275ad-b783-488c-8e18-2439ff3aed1c", Double.NaN))
        assertTrue(Amostra.dentro("db09f237-fffa-40c7-94c5-35eddcad6970", 100.0))
    }
}
