package com.rengo.tuner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

class BluetoothLink(
    private val contexto: Context,
    private val alRecibirLinea: (String) -> Unit,
    private val alCambiarEstado: (Boolean, String) -> Unit
) {

    private val principal = Handler(Looper.getMainLooper())
    private val cola = ArrayDeque<String>()
    private val linea = StringBuilder()

    private var gatt: BluetoothGatt? = null
    private var canal: BluetoothGattCharacteristic? = null
    private var nombre = ""
    private var escribiendo = false

    var conectado = false
        private set

    @SuppressLint("MissingPermission")
    fun conectar(dispositivo: BluetoothDevice) {
        cerrar()
        nombre = dispositivo.name ?: dispositivo.address
        alCambiarEstado(false, "Conectando...")
        gatt = dispositivo.connectGatt(contexto, false, respuestas, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun cerrar() {
        gatt?.close()
        gatt = null
        canal = null
        conectado = false
        escribiendo = false
        cola.clear()
        linea.setLength(0)
    }

    fun enviar(comando: String) {
        principal.post {
            cola.addLast(comando)
            procesarCola()
        }
    }

    private val respuestas = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, estado: Int) {
            if (estado == BluetoothProfile.STATE_CONNECTED) {
                principal.post { alCambiarEstado(false, "Buscando servicios...") }
                g.discoverServices()
            } else {
                principal.post {
                    val estaba = conectado
                    cerrar()
                    alCambiarEstado(false, if (estaba) "Se cortó la conexión" else "No se pudo conectar")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(ETIQUETA, g.services.joinToString(" | ") { s ->
                "${s.uuid} -> " + s.characteristics.joinToString { "${it.uuid}(${it.properties})" }
            })

            val encontrado = buscarCanal(g)
            if (encontrado == null) {
                principal.post {
                    cerrar()
                    alCambiarEstado(false, "El módulo no expone un canal de datos")
                }
                return
            }

            canal = encontrado
            g.setCharacteristicNotification(encontrado, true)

            val descriptor = encontrado.getDescriptor(UUID_NOTIFICACIONES)
            if (descriptor == null) marcarConectado() else activarNotificaciones(g, descriptor)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            marcarConectado()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            principal.postDelayed({
                escribiendo = false
                procesarCola()
            }, ESPERA_ENTRE_COMANDOS)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            valor: ByteArray
        ) {
            procesarBytes(valor)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            procesarBytes(c.value ?: return)
        }
    }

    // Los clones del HM-10 casi siempre usan FFE1, pero no todos. Si no está, sirve
    // cualquier característica que se pueda escribir y además notifique.
    private fun buscarCanal(g: BluetoothGatt): BluetoothGattCharacteristic? {
        val todas = g.services.flatMap { it.characteristics }
        return todas.find { it.uuid == UUID_DATOS }
            ?: todas.find { seEscribe(it) && notifica(it) }
    }

    private fun seEscribe(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    private fun notifica(c: BluetoothGattCharacteristic) =
        c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

    private fun marcarConectado() {
        principal.post {
            conectado = true
            alCambiarEstado(true, "Conectado a $nombre")
        }
    }

    @SuppressLint("MissingPermission")
    private fun activarNotificaciones(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        val valor = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, valor)
        } else {
            @Suppress("DEPRECATION")
            d.value = valor
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    // El Arduino usa SoftwareSerial, que pierde bytes si le llegan comandos pegados.
    // El espacio entre envíos le da tiempo a procesar cada línea.
    @SuppressLint("MissingPermission")
    private fun procesarCola() {
        if (escribiendo || !conectado) return
        val g = gatt ?: return
        val c = canal ?: return
        val comando = cola.removeFirstOrNull() ?: return

        escribiendo = true
        val datos = (comando + "\n").toByteArray()
        val tipo = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, datos, tipo)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = tipo
            @Suppress("DEPRECATION")
            c.value = datos
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    // BLE entrega de a 20 bytes, así que una respuesta larga llega partida en varios paquetes.
    private fun procesarBytes(datos: ByteArray) {
        principal.post {
            for (b in datos) {
                val c = b.toInt().toChar()
                if (c == '\n' || c == '\r') {
                    if (linea.isNotEmpty()) {
                        val texto = linea.toString()
                        linea.setLength(0)
                        alRecibirLinea(texto)
                    }
                } else {
                    linea.append(c)
                }
            }
        }
    }

    private companion object {
        const val ETIQUETA = "RengoBLE"
        const val ESPERA_ENTRE_COMANDOS = 80L
        val UUID_DATOS: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val UUID_NOTIFICACIONES: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
