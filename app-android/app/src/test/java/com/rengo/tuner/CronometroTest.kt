package com.rengo.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// El reloj es una variable que mueve el test: así las corridas duran lo que uno quiere y
// no hay que esperar ni depender de Android.
class CronometroTest {

    private var ahora = 0L
    private val crono = Cronometro { ahora }

    @Test
    fun `mide lo que duro la corrida`() {
        crono.arrancar()
        assertTrue(crono.corriendo)

        ahora = 12_400
        assertEquals(12_400L, crono.transcurrido())
        assertEquals(12_400L, crono.parar())
        assertFalse(crono.corriendo)
    }

    @Test
    fun `parar sin arrancar no mide nada`() {
        assertNull(crono.parar())
        assertNull(crono.ultima)
    }

    @Test
    fun `la ultima corrida se guarda al parar`() {
        crono.arrancar()
        ahora = 9_000
        crono.parar()
        assertEquals(9_000L, crono.ultima)
    }

    @Test
    fun `descartar no guarda la corrida`() {
        crono.arrancar()
        ahora = 5_000
        crono.parar()

        crono.arrancar()
        ahora = 20_000
        crono.descartar()

        assertFalse(crono.corriendo)
        assertEquals(5_000L, crono.ultima)
    }

    // ----- Formato de pantalla (ejemplos del handoff) -----

    @Test
    fun `sin corrida medida todo va apagado`() {
        val pantalla = Cronometro.formatoPantalla(0)
        assertEquals("00:00", pantalla.apagado)
        assertEquals("", pantalla.vivo)
        assertEquals(".0", pantalla.decima)
    }

    @Test
    fun `doce segundos y pico apaga los minutos`() {
        val pantalla = Cronometro.formatoPantalla(12_400)
        assertEquals("00:", pantalla.apagado)
        assertEquals("12", pantalla.vivo)
        assertEquals(".4", pantalla.decima)
    }

    @Test
    fun `pasado el minuto queda un solo cero apagado`() {
        val pantalla = Cronometro.formatoPantalla(65_300)
        assertEquals("0", pantalla.apagado)
        assertEquals("1:05", pantalla.vivo)
        assertEquals(".3", pantalla.decima)
    }

    @Test
    fun `con dos digitos de minutos no hay nada apagado`() {
        val pantalla = Cronometro.formatoPantalla(600_000)
        assertEquals("", pantalla.apagado)
        assertEquals("10:00", pantalla.vivo)
        assertEquals(".0", pantalla.decima)
    }

    @Test
    fun `los minutos se topean en noventa y nueve`() {
        val pantalla = Cronometro.formatoPantalla(120L * 60_000)
        assertEquals("", pantalla.apagado)
        assertEquals("99:59", pantalla.vivo)
        assertEquals(".9", pantalla.decima)
    }

    // ----- Formato de historial -----

    @Test
    fun `el historial muestra segundos si no llega al minuto`() {
        assertEquals("12.4 s", Cronometro.formatoHistorial(12_400))
    }

    @Test
    fun `el historial muestra minutos si pasa del minuto`() {
        assertEquals("1:05.3", Cronometro.formatoHistorial(65_300))
    }
}
