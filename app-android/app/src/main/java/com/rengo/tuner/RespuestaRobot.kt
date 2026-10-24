package com.rengo.tuner

// Todo lo que contesta el robot por Bluetooth se interpreta acá y en ningún otro lado.
// Sin dependencias de Android a propósito: así se puede testear sin emulador.
object RespuestaRobot {

    data class Lectura(
        val valores: Map<String, String>,
        val andando: Boolean?,
        val invertidoIzq: Boolean?,
        val invertidoDer: Boolean?
    )

    // Los valores salen como texto crudo: cuántos decimales lleva cada parámetro lo sabe
    // la pantalla, no el robot.
    fun leer(linea: String): Lectura? {
        if (!linea.contains("$PRIMERA=")) return null

        val valores = mutableMapOf<String, String>()
        var andando: Boolean? = null
        var invertidoIzq: Boolean? = null
        var invertidoDer: Boolean? = null

        for (trozo in linea.trim().split(" ")) {
            val partes = trozo.split("=")
            if (partes.size != 2) continue

            val clave = partes[0]
            val valor = partes[1]
            when {
                // El robot informa también si está andando. Con un solo botón para arrancar
                // y parar, hay que creerle a él: si lo arrancaron con el botón del robot, la
                // app tiene que mostrar "Parar" igual.
                clave == "EST" -> andando = valor == "RUN"
                clave == "INVI" -> invertidoIzq = valor == "1"
                clave == "INVD" -> invertidoDer = valor == "1"
                clave in CLAVES -> valores[clave] = valor
            }
        }

        return Lectura(valores, andando, invertidoIzq, invertidoDer)
    }

    fun esConfirmacion(linea: String) = CONFIRMACION.matches(linea)

    fun esPong(linea: String) = linea.trim() == "PONG"

    private const val PRIMERA = "VEL"
    private val CLAVES = setOf(PRIMERA, "VMAX", "KP", "KI", "KD")

    // "OK KP=0.5000" es el eco de un cambio; "OK GUARDADO" no entra acá porque no lleva "=".
    private val CONFIRMACION = Regex("OK [A-Z]+=.*")
}
