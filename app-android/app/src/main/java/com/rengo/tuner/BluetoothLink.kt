package com.rengo.tuner

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

class BluetoothLink(
    private val alRecibirLinea: (String) -> Unit,
    private val alCambiarEstado: (Boolean, String) -> Unit
) {

    private val principal = Handler(Looper.getMainLooper())
    private val cola = LinkedBlockingQueue<String>()

    private var socket: BluetoothSocket? = null
    private var hiloLector: Thread? = null
    private var hiloEscritor: Thread? = null

    val conectado: Boolean
        get() = socket?.isConnected == true

    fun conectar(dispositivo: BluetoothDevice) {
        cerrar()
        principal.post { alCambiarEstado(false, "Conectando...") }

        Thread {
            try {
                val nuevo = dispositivo.createRfcommSocketToServiceRecord(UUID_SPP)
                nuevo.connect()
                socket = nuevo
                cola.clear()
                arrancarLector(nuevo)
                arrancarEscritor(nuevo)
                principal.post {
                    alCambiarEstado(true, "Conectado a ${dispositivo.name ?: dispositivo.address}")
                }
            } catch (e: Exception) {
                cerrar()
                principal.post { alCambiarEstado(false, "Error: ${e.message}") }
            }
        }.start()
    }

    // El Arduino usa SoftwareSerial, que pierde bytes si le llegan comandos pegados.
    // El espacio entre envíos le da tiempo a procesar cada línea.
    private fun arrancarEscritor(activo: BluetoothSocket) {
        hiloEscritor = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val comando = cola.take()
                    activo.outputStream.write((comando + "\n").toByteArray())
                    activo.outputStream.flush()
                    Thread.sleep(ESPERA_ENTRE_COMANDOS)
                }
            } catch (_: InterruptedException) {
            } catch (e: IOException) {
                principal.post { alCambiarEstado(false, "Se cortó la conexión") }
            }
        }.also { it.start() }
    }

    private fun arrancarLector(activo: BluetoothSocket) {
        hiloLector = Thread {
            val linea = StringBuilder()
            val buffer = ByteArray(64)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val leidos = activo.inputStream.read(buffer)
                    if (leidos <= 0) break

                    for (i in 0 until leidos) {
                        val c = buffer[i].toInt().toChar()
                        if (c == '\n' || c == '\r') {
                            if (linea.isNotEmpty()) {
                                val texto = linea.toString()
                                linea.setLength(0)
                                principal.post { alRecibirLinea(texto) }
                            }
                        } else {
                            linea.append(c)
                        }
                    }
                }
            } catch (_: IOException) {
                principal.post { alCambiarEstado(false, "Se cortó la conexión") }
            }
        }.also { it.start() }
    }

    fun enviar(comando: String) {
        if (conectado) cola.offer(comando)
    }

    fun cerrar() {
        hiloLector?.interrupt()
        hiloEscritor?.interrupt()
        hiloLector = null
        hiloEscritor = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private companion object {
        val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val ESPERA_ENTRE_COMANDOS = 80L
    }
}
