package com.rengo.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RespuestaRobotTest {

    @Test
    fun `lee la linea completa del firmware nuevo`() {
        val lectura = RespuestaRobot.leer(
            "VEL=135 VMAX=255 KP=2.400 KI=0.0000 KD=3.000 EST=RUN INVI=1 INVD=0"
        )

        assertNotNull(lectura)
        assertEquals(
            mapOf(
                "VEL" to "135",
                "VMAX" to "255",
                "KP" to "2.400",
                "KI" to "0.0000",
                "KD" to "3.000"
            ),
            lectura!!.valores
        )
        assertEquals(true, lectura.andando)
        assertEquals(true, lectura.invertidoIzq)
        assertEquals(false, lectura.invertidoDer)
    }

    // Los robots que todavía no tienen el firmware nuevo no mandan INVI/INVD: no se puede
    // confundir "no me lo dijo" con "no está invertido".
    @Test
    fun `el firmware viejo sin INVI ni INVD deja la inversion en null`() {
        val lectura = RespuestaRobot.leer("VEL=135 VMAX=255 KP=2.400 KI=0.0000 KD=3.000 EST=RUN")

        assertNotNull(lectura)
        assertEquals(true, lectura!!.andando)
        assertNull(lectura.invertidoIzq)
        assertNull(lectura.invertidoDer)
    }

    @Test
    fun `EST igual a STOP es que no esta andando`() {
        val lectura = RespuestaRobot.leer("VEL=135 VMAX=255 KP=2.400 EST=STOP INVI=0 INVD=1")

        assertNotNull(lectura)
        assertEquals(false, lectura!!.andando)
        assertEquals(false, lectura.invertidoIzq)
        assertEquals(true, lectura.invertidoDer)
    }

    @Test
    fun `sin EST el andando queda en null`() {
        val lectura = RespuestaRobot.leer("VEL=135 VMAX=255")

        assertNotNull(lectura)
        assertNull(lectura!!.andando)
    }

    @Test
    fun `una linea sin VEL no es un volcado de valores`() {
        assertNull(RespuestaRobot.leer("OK GUARDADO"))
        assertNull(RespuestaRobot.leer("ERR COMANDO"))
        assertNull(RespuestaRobot.leer("PONG"))
        assertNull(RespuestaRobot.leer(""))
    }

    @Test
    fun `tolera espacios de mas`() {
        val lectura = RespuestaRobot.leer("  VEL=135   VMAX=255  EST=RUN   ")

        assertNotNull(lectura)
        assertEquals(mapOf("VEL" to "135", "VMAX" to "255"), lectura!!.valores)
        assertEquals(true, lectura.andando)
    }

    @Test
    fun `ignora claves desconocidas y pares basura en el medio`() {
        val lectura = RespuestaRobot.leer("VEL=135 BASURA ZZZ=9 KP=1=2 VMAX=255 = KD=3.000")

        assertNotNull(lectura)
        assertEquals(mapOf("VEL" to "135", "VMAX" to "255", "KD" to "3.000"), lectura!!.valores)
    }

    @Test
    fun `el eco de un cambio es confirmacion`() {
        assertTrue(RespuestaRobot.esConfirmacion("OK KP=0.5000"))
        assertTrue(RespuestaRobot.esConfirmacion("OK INVI=1"))
    }

    // "OK GUARDADO" tiene que seguir cayendo en el historial, así que no es confirmación.
    @Test
    fun `OK GUARDADO no es confirmacion`() {
        assertFalse(RespuestaRobot.esConfirmacion("OK GUARDADO"))
        assertFalse(RespuestaRobot.esConfirmacion("ERR COMANDO"))
        assertFalse(RespuestaRobot.esConfirmacion("PONG"))
    }

    @Test
    fun `PONG es pong y nada mas`() {
        assertTrue(RespuestaRobot.esPong("PONG"))
        assertFalse(RespuestaRobot.esPong("OK GUARDADO"))
        assertFalse(RespuestaRobot.esPong("PONG=1"))
    }

    @Test
    fun `lee el freno activo y el PWM rapido`() {
        val lectura = RespuestaRobot.leer(
            "VEL=50 VMAX=200 KP=3.000 KI=0.0000 KD=2.000 EST=STOP INVI=0 INVD=0 FREN=1 PWMF=0"
        )

        assertNotNull(lectura)
        assertEquals(true, lectura!!.frenoActivo)
        assertEquals(false, lectura.pwmRapido)
    }

    // El firmware viejo no los manda: la app tiene que dejar lo que ya tenía en vez de
    // dar por hecho que están apagados.
    @Test
    fun `sin FREN ni PWMF quedan en nulo`() {
        val lectura = RespuestaRobot.leer("VEL=50 VMAX=200 KP=3.000 KI=0.0000 KD=2.000 EST=STOP")

        assertNotNull(lectura)
        assertNull(lectura!!.frenoActivo)
        assertNull(lectura.pwmRapido)
    }
}
