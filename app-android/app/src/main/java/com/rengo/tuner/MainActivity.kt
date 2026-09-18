package com.rengo.tuner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
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

private data class Hallazgo(
    val dispositivo: BluetoothDevice,
    val nombre: String?,
    val potencia: Int
)

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
    private lateinit var panelConectado: LinearLayout
    private lateinit var textoAyuda: TextView
    private lateinit var botonModo: Button

    private val preferencias by lazy { getSharedPreferences("rengo", MODE_PRIVATE) }
    private val modos by lazy { Modos(preferencias) }
    private var modoBase: String? = null
    private var buscando = false
    private var cortadoAMano = false

    private val pedirPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        if (resultados.values.all { it }) buscarDispositivos()
        else Toast.makeText(this, "Sin permiso de Bluetooth no puedo conectar", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textoEstado = findViewById(R.id.textoEstado)
        textoRegistro = findViewById(R.id.textoRegistro)
        botonConectar = findViewById(R.id.botonConectar)
        panelConectado = findViewById(R.id.panelConectado)
        textoAyuda = findViewById(R.id.textoAyuda)
        botonModo = findViewById(R.id.botonModo)

        enlace = BluetoothLink(
            contexto = this,
            alRecibirLinea = { linea ->
                registrar("< $linea")
                if (linea.contains("VEL=")) volcarValores(linea)
            },
            alCambiarEstado = { conectado, mensaje ->
                textoEstado.text = mensaje
                mostrarPanel(conectado)
                if (conectado) enlace.enviar("GET")
            }
        )

        armarFilas()
        refrescarModo()

        botonModo.setOnClickListener { elegirModo() }
        findViewById<Button>(R.id.botonGuardarModo).setOnClickListener { pedirNombreDeModo() }

        botonConectar.setOnClickListener {
            if (enlace.conectado) {
                cortadoAMano = true
                enlace.cerrar()
                textoEstado.text = "Desconectado"
                mostrarPanel(false)
            } else {
                verificarPermisoYConectar()
            }
        }

        findViewById<Button>(R.id.botonGuardar).setOnClickListener {
            enviarTodos()
            mandar("SAVE")
        }

        findViewById<Button>(R.id.botonStart).setOnClickListener { mandar("START") }
        findViewById<Button>(R.id.botonStop).setOnClickListener { mandar("STOP") }
    }

    override fun onResume() {
        super.onResume()
        reengancharse()
    }

    override fun onDestroy() {
        super.onDestroy()
        enlace.cerrar()
    }

    // ----- Interfaz -----

    private fun mostrarPanel(conectado: Boolean) {
        panelConectado.visibility = if (conectado) View.VISIBLE else View.GONE
        textoAyuda.visibility = if (conectado) View.GONE else View.VISIBLE
        botonConectar.text = if (conectado) "Desconectar" else "Conectar"
    }

    private fun armarFilas() {
        val contenedor = findViewById<LinearLayout>(R.id.contenedorParametros)
        val inflador = LayoutInflater.from(this)

        for (p in parametros) {
            val fila = inflador.inflate(R.layout.fila_parametro, contenedor, false)

            fila.findViewById<TextView>(R.id.nombreParametro).text = p.nombre

            val campo = fila.findViewById<EditText>(R.id.valorParametro)
            campo.setText(formatear(0.0, p.decimales))
            campos[p.clave] = campo

            // Ya no hay botón Aplicar: el valor escrito a mano se manda al salir del casillero.
            campo.setOnFocusChangeListener { _, tieneFoco -> if (!tieneFoco) aplicar(p) }
            campo.setOnEditorActionListener { _, _, _ ->
                campo.clearFocus()
                false
            }

            fila.findViewById<Button>(R.id.botonMenos).setOnClickListener { ajustar(p, -p.paso) }
            fila.findViewById<Button>(R.id.botonMas).setOnClickListener { ajustar(p, p.paso) }

            contenedor.addView(fila)
        }
    }

    private fun ajustar(p: Parametro, delta: Double) {
        campos[p.clave]?.setText(formatear((leerCampo(p) + delta).coerceIn(p.minimo, p.maximo), p.decimales))
        aplicar(p)
    }

    // Manda el valor al robot, que lo usa al instante pero no lo graba: la EEPROM
    // aguanta ~100 mil escrituras, así que solo se toca con Guardar.
    private fun aplicar(p: Parametro) {
        val valor = leerCampo(p).coerceIn(p.minimo, p.maximo)
        campos[p.clave]?.setText(formatear(valor, p.decimales))
        refrescarModo()

        if (!enlace.conectado) return
        mandar("${p.clave}=${formatear(valor, p.decimales)}")
    }

    private fun enviarTodos() {
        for (p in parametros) aplicar(p)
    }

    // ----- Modos guardados -----

    private fun valoresActuales() = parametros.associate { p ->
        p.clave to formatear(leerCampo(p).coerceIn(p.minimo, p.maximo), p.decimales)
    }

    // El estado se deduce de los valores: no hace falta recordar si el usuario tocó algo,
    // alcanza con comparar lo que hay en pantalla contra lo que tiene guardado el modo.
    private fun refrescarModo() {
        val actuales = valoresActuales()
        if (modoBase == null) modoBase = modos.buscarPorValores(actuales)

        val base = modoBase
        val guardados = base?.let { modos.valores(it) }

        botonModo.text = when {
            guardados == null -> "Personalizado"
            guardados == actuales -> base
            else -> "Personalizado (desde $base)"
        }
    }

    private fun cargarModo(nombre: String) {
        val valores = modos.valores(nombre) ?: return
        for (p in parametros) valores[p.clave]?.let { campos[p.clave]?.setText(it) }

        modoBase = nombre
        enviarTodos()
        refrescarModo()
    }

    private fun elegirModo() {
        val nombres = modos.nombres()
        if (nombres.isEmpty()) {
            Toast.makeText(this, "Todavía no guardaste ningún modo", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Cargar modo")
            .setItems(nombres.toTypedArray()) { _, indice -> cargarModo(nombres[indice]) }
            .setNegativeButton("Cancelar", null)
            .create()

        dialogo.show()
        dialogo.listView.setOnItemLongClickListener { _, _, indice, _ ->
            dialogo.dismiss()
            confirmarBorrado(nombres[indice])
            true
        }
    }

    private fun pedirNombreDeModo() {
        val vista = layoutInflater.inflate(R.layout.dialogo_guardar_modo, null)
        val campoNombre = vista.findViewById<EditText>(R.id.nombreModo)
        campoNombre.setText(modoBase ?: "")
        campoNombre.setSelection(campoNombre.text.length)

        val existentes = modos.nombres()
        vista.findViewById<TextView>(R.id.tituloExistentes).visibility =
            if (existentes.isEmpty()) View.GONE else View.VISIBLE

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Guardar modo")
            .setView(vista)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null)
            .create()

        // Tocar un nombre va derecho a sobrescribirlo. Copiarlo al casillero sería peor:
        // el casillero recorta a 20 caracteres, así que un nombre viejo más largo no
        // pisaría el modo original sino que crearía uno nuevo con el nombre cortado.
        val lista = vista.findViewById<LinearLayout>(R.id.listaExistentes)
        for (nombre in existentes) {
            val item = TextView(this)
            item.text = nombre
            item.textSize = 16f
            item.setPadding(0, 24, 0, 24)
            item.maxLines = 1
            item.ellipsize = TextUtils.TruncateAt.END
            item.setOnClickListener {
                dialogo.dismiss()
                confirmarYGuardar(nombre)
            }
            lista.addView(item)
        }

        // Sin esto el diálogo se cierra aunque el nombre esté vacío.
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nombre = campoNombre.text.toString().trim()
                if (nombre.isEmpty()) {
                    campoNombre.error = "Poné un nombre"
                    return@setOnClickListener
                }
                dialogo.dismiss()
                confirmarYGuardar(nombre)
            }
        }
        dialogo.show()
    }

    private fun confirmarYGuardar(nombre: String) {
        if (!modos.nombres().contains(nombre)) {
            guardarModo(nombre)
            return
        }

        AlertDialog.Builder(this)
            .setMessage("Ya existe \"$nombre\". ¿Sobrescribir?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sobrescribir") { _, _ -> guardarModo(nombre) }
            .show()
    }

    private fun guardarModo(nombre: String) {
        modos.guardar(nombre, valoresActuales())
        modoBase = nombre
        refrescarModo()
        Toast.makeText(this, "Modo \"$nombre\" guardado", Toast.LENGTH_SHORT).show()
    }

    private fun confirmarBorrado(nombre: String) {
        AlertDialog.Builder(this)
            .setMessage("¿Borrar el modo \"$nombre\"?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                modos.borrar(nombre)
                if (modoBase == nombre) modoBase = null
                refrescarModo()
            }
            .show()
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

        // Estos valores los puso el robot, no un modo que cargamos: que busque solo cuál es.
        modoBase = null
        refrescarModo()
    }

    private fun registrar(texto: String) {
        textoRegistro.text = "$texto\n${textoRegistro.text}".take(2000)
    }

    // ----- Bluetooth -----

    private fun permisosNecesarios() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun verificarPermisoYConectar() {
        val faltantes = permisosNecesarios().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltantes.isNotEmpty()) {
            pedirPermisos.launch(faltantes.toTypedArray())
            return
        }
        buscarDispositivos()
    }

    // El módulo es BLE, así que no se empareja: hay que escanear cada vez.
    @SuppressLint("MissingPermission")
    private fun buscarDispositivos() {
        val adaptador = obtenerAdaptador()
        if (adaptador == null || !adaptador.isEnabled) {
            Toast.makeText(this, "Prendé el Bluetooth del celular", Toast.LENGTH_LONG).show()
            return
        }

        val escaner = adaptador.bluetoothLeScanner ?: return
        val encontrados = LinkedHashMap<String, Hallazgo>()

        val escucha = object : ScanCallback() {
            override fun onScanResult(tipo: Int, resultado: ScanResult) {
                // El nombre del caché viene null si el dispositivo no está vinculado;
                // el del anuncio es el que sirve.
                val nombre = resultado.scanRecord?.deviceName ?: resultado.device.name
                encontrados[resultado.device.address] =
                    Hallazgo(resultado.device, nombre, resultado.rssi)
            }
        }

        buscando = true
        textoEstado.text = "Buscando módulos..."
        escaner.startScan(escucha)

        Handler(Looper.getMainLooper()).postDelayed({
            buscando = false
            escaner.stopScan(escucha)
            mostrarEncontrados(encontrados.values.sortedByDescending { it.potencia })
        }, DURACION_BUSQUEDA)
    }

    private fun conectarA(dispositivo: BluetoothDevice) {
        cortadoAMano = false
        preferencias.edit().putString(CLAVE_MODULO, dispositivo.address).apply()
        enlace.conectar(dispositivo)
    }

    // El enlace BLE se corta al dejar la app en segundo plano. Como ya sabemos qué módulo
    // eligió el usuario, alcanza con su MAC para volver a engancharse sin escanear.
    private fun reengancharse() {
        if (enlace.conectado || enlace.conectando || buscando || cortadoAMano) return

        val mac = preferencias.getString(CLAVE_MODULO, null) ?: return
        val adaptador = obtenerAdaptador() ?: return
        if (!adaptador.isEnabled) return

        val falta = permisosNecesarios().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (falta) return

        enlace.conectar(adaptador.getRemoteDevice(mac), "Reconectando...")
    }

    private fun mostrarEncontrados(hallazgos: List<Hallazgo>) {
        textoEstado.text = "Desconectado"

        if (hallazgos.isEmpty()) {
            Toast.makeText(
                this,
                "No encontré ningún módulo. Fijate que esté alimentado y parpadeando.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val etiquetas = hallazgos.map {
            "${it.nombre ?: "(sin nombre)"}\n${it.dispositivo.address}  ${it.potencia} dBm"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Elegí el módulo del robot")
            .setItems(etiquetas) { _, indice -> conectarA(hallazgos[indice].dispositivo) }
            .show()
    }

    private fun obtenerAdaptador(): BluetoothAdapter? {
        val servicio = getSystemService(BluetoothManager::class.java)
        return servicio?.adapter
    }

    private companion object {
        const val DURACION_BUSQUEDA = 5000L
        const val CLAVE_MODULO = "modulo"
    }
}
