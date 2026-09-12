package com.rengo.tuner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

private data class Parametro(
    val clave: String,
    val nombre: String,
    val paso: Double,
    val decimales: Int,
    val minimo: Double,
    val maximo: Double
)

class MainActivity : AppCompatActivity() {

    private val parametros = listOf(
        Parametro("VEL", "Velocidad base", 5.0, 0, 0.0, 255.0),
        Parametro("VMAX", "Velocidad máxima", 5.0, 0, 0.0, 255.0),
        Parametro("KP", "Kp — proporcional", 0.1, 2, 0.0, 50.0),
        Parametro("KI", "Ki — integral", 0.001, 3, 0.0, 50.0),
        Parametro("KD", "Kd — derivativo", 0.1, 2, 0.0, 50.0)
    )

    private val campos = HashMap<String, EditText>()

    private lateinit var enlace: BluetoothLink
    private lateinit var textoEstado: TextView
    private lateinit var textoRegistro: TextView
    private lateinit var botonConectar: Button

    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) elegirDispositivo()
        else Toast.makeText(this, "Sin permiso de Bluetooth no puedo conectar", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textoEstado = findViewById(R.id.textoEstado)
        textoRegistro = findViewById(R.id.textoRegistro)
        botonConectar = findViewById(R.id.botonConectar)

        enlace = BluetoothLink(
            alRecibirLinea = { linea ->
                registrar("< $linea")
                if (linea.contains("VEL=")) volcarValores(linea)
            },
            alCambiarEstado = { conectado, mensaje ->
                textoEstado.text = mensaje
                botonConectar.text = if (conectado) "Desconectar" else "Conectar"
                if (conectado) enlace.enviar("GET")
            }
        )

        armarFilas()

        botonConectar.setOnClickListener {
            if (enlace.conectado) {
                enlace.cerrar()
                textoEstado.text = "Desconectado"
                botonConectar.text = "Conectar"
            } else {
                verificarPermisoYConectar()
            }
        }

        findViewById<Button>(R.id.botonAplicar).setOnClickListener { enviarTodos() }

        findViewById<Button>(R.id.botonGuardar).setOnClickListener {
            enviarTodos()
            enlace.enviar("SAVE")
            registrar("> SAVE")
        }

        findViewById<Button>(R.id.botonLeer).setOnClickListener {
            enlace.enviar("GET")
            registrar("> GET")
        }

        findViewById<Button>(R.id.botonStart).setOnClickListener { mandar("START") }
        findViewById<Button>(R.id.botonStop).setOnClickListener { mandar("STOP") }
    }

    override fun onDestroy() {
        super.onDestroy()
        enlace.cerrar()
    }

    // ----- Interfaz -----

    private fun armarFilas() {
        val contenedor = findViewById<LinearLayout>(R.id.contenedorParametros)
        val inflador = LayoutInflater.from(this)

        for (p in parametros) {
            val fila = inflador.inflate(R.layout.fila_parametro, contenedor, false)

            fila.findViewById<TextView>(R.id.nombreParametro).text = p.nombre

            val campo = fila.findViewById<EditText>(R.id.valorParametro)
            campo.setText(formatear(0.0, p.decimales))
            campos[p.clave] = campo

            fila.findViewById<Button>(R.id.botonMenos).setOnClickListener { ajustar(p, -p.paso) }
            fila.findViewById<Button>(R.id.botonMas).setOnClickListener { ajustar(p, p.paso) }

            contenedor.addView(fila)
        }
    }

    private fun ajustar(p: Parametro, delta: Double) {
        val nuevo = (leerCampo(p) + delta).coerceIn(p.minimo, p.maximo)
        campos[p.clave]?.setText(formatear(nuevo, p.decimales))
        mandar("${p.clave}=${formatear(nuevo, p.decimales)}")
    }

    private fun enviarTodos() {
        for (p in parametros) {
            val valor = leerCampo(p).coerceIn(p.minimo, p.maximo)
            campos[p.clave]?.setText(formatear(valor, p.decimales))
            mandar("${p.clave}=${formatear(valor, p.decimales)}")
        }
    }

    private fun mandar(comando: String) {
        if (!enlace.conectado) {
            Toast.makeText(this, "Primero conectate al robot", Toast.LENGTH_SHORT).show()
            return
        }
        enlace.enviar(comando)
        registrar("> $comando")
    }

    private fun leerCampo(p: Parametro): Double {
        val texto = campos[p.clave]?.text?.toString()?.replace(',', '.') ?: ""
        return texto.toDoubleOrNull() ?: p.minimo
    }

    // El robot espera punto decimal, así que el formato nunca depende del idioma del celular.
    private fun formatear(valor: Double, decimales: Int) =
        String.format(Locale.US, "%.${decimales}f", valor)

    private fun volcarValores(linea: String) {
        for (trozo in linea.trim().split(" ")) {
            val partes = trozo.split("=")
            if (partes.size != 2) continue

            val p = parametros.find { it.clave == partes[0] } ?: continue
            val valor = partes[1].toDoubleOrNull() ?: continue
            campos[p.clave]?.setText(formatear(valor, p.decimales))
        }
    }

    private fun registrar(texto: String) {
        textoRegistro.text = "$texto\n${textoRegistro.text}".take(2000)
    }

    // ----- Bluetooth -----

    private fun verificarPermisoYConectar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permiso = Manifest.permission.BLUETOOTH_CONNECT
            if (ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED) {
                pedirPermiso.launch(permiso)
                return
            }
        }
        elegirDispositivo()
    }

    private fun elegirDispositivo() {
        val adaptador = obtenerAdaptador()
        if (adaptador == null || !adaptador.isEnabled) {
            Toast.makeText(this, "Prendé el Bluetooth del celular", Toast.LENGTH_LONG).show()
            return
        }

        val vinculados = try {
            adaptador.bondedDevices.toList()
        } catch (_: SecurityException) {
            emptyList()
        }

        if (vinculados.isEmpty()) {
            Toast.makeText(
                this,
                "No hay dispositivos vinculados. Emparejá el módulo desde los ajustes de Android (PIN 1234).",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val etiquetas = vinculados.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Elegí el módulo del robot")
            .setItems(etiquetas) { _, indice -> conectarA(vinculados[indice]) }
            .show()
    }

    private fun conectarA(dispositivo: BluetoothDevice) {
        try {
            enlace.conectar(dispositivo)
        } catch (_: SecurityException) {
            Toast.makeText(this, "Falta el permiso de Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    private fun obtenerAdaptador(): BluetoothAdapter? {
        val servicio = getSystemService(BluetoothManager::class.java)
        return servicio?.adapter
    }
}
