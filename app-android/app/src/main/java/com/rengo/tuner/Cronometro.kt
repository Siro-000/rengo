package com.rengo.tuner

import android.os.SystemClock
import java.util.Locale

// Cronómetro de corrida. El reloj entra por parámetro para poder probarlo en la JVM sin
// Android: en la app es elapsedRealtime(), que no salta si alguien cambia la hora del celular.
//
// El formato de pantalla sale partido en tres porque la fila del cronómetro pinta cada tramo
// distinto (ceros apagados, números vivos y la décima más chica), y así la decisión de dónde
// corta cada tramo queda acá, probada, en vez de mezclada con los Spannable de la pantalla.
class Cronometro(private val reloj: () -> Long = { SystemClock.elapsedRealtime() }) {

    data class Pantalla(val apagado: String, val vivo: String, val decima: String)

    var corriendo = false
        private set

    // Última corrida completa: null mientras no haya ninguna medida (o si se descartó la
    // primera por un corte de conexión).
    var ultima: Long? = null
        private set

    private var inicio: Long? = null

    fun arrancar() {
        inicio = reloj()
        corriendo = true
    }

    // Devuelve null si no había arranque: pasa cuando el robot ya venía andando al conectar
    // (EST=RUN) y entonces no hay nada que medir.
    fun parar(): Long? {
        val desde = inicio
        corriendo = false
        inicio = null
        if (desde == null) return null

        val duracion = reloj() - desde
        ultima = duracion
        return duracion
    }

    // Si se corta la conexión con el robot andando, la corrida no vale: el STOP nunca llegó
    // y no se sabe cuánto duró de verdad. Se tira sin tocar la corrida anterior.
    fun descartar() {
        corriendo = false
        inicio = null
    }

    fun transcurrido(): Long {
        val desde = inicio ?: return ultima ?: 0L
        return reloj() - desde
    }

    companion object {
        // Tope del handoff: con los minutos topeados en 99, el reloj se clava en 99:59.9 en
        // vez de dar la vuelta y mostrar un tiempo chico que no es el que corrió.
        private const val TOPE_MS = 99L * 60_000L + 59_900L

        fun formatoPantalla(ms: Long): Pantalla {
            val decimas = ms.coerceIn(0L, TOPE_MS) / 100
            val reloj = String.format(Locale.US, "%02d:%02d", decimas / 600, decimas / 10 % 60)

            // Los ceros y los ":" de adelante van apagados hasta el primer dígito que dice algo.
            // Con 00:00 no queda ninguno encendido, y así se pinta el "sin corrida" del handoff.
            val apagado = reloj.takeWhile { it == '0' || it == ':' }
            return Pantalla(apagado, reloj.substring(apagado.length), ".${decimas % 10}")
        }

        fun formatoHistorial(ms: Long): String {
            val decimas = ms.coerceAtLeast(0L) / 100
            val minutos = decimas / 600
            val segundos = decimas / 10 % 60
            return if (minutos == 0L) String.format(Locale.US, "%d.%d s", segundos, decimas % 10)
            else String.format(Locale.US, "%d:%02d.%d", minutos, segundos, decimas % 10)
        }
    }
}
