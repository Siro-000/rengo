package com.rengo.tuner

import android.Manifest
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.ChipGroup
import java.util.Locale

private data class Hallazgo(
    val dispositivo: BluetoothDevice,
    val nombre: String?,
    val potencia: Int
)

private data class Parametro(
    val clave: String,
    val nombre: String,
    val descripcion: String,
    val paso: Double,
    val decimales: Int,
    val minimo: Double,
    val maximo: Double
)

private enum class Estado { DESCONECTADO, BUSCANDO, CONECTANDO, CONECTADO }

private enum class Pestana { SINTONIA, MODOS, REGISTRO }

class MainActivity : AppCompatActivity() {

    private val parametros = listOf(
        Parametro("VEL", "Velocidad base", "PWM en recta", 5.0, 0, 0.0, 255.0),
        Parametro("VMAX", "Velocidad máxima", "techo de PWM", 5.0, 0, 0.0, 255.0),
        Parametro("KP", "Kp · proporcional", "según el error", 0.1, 2, 0.0, 50.0),
        Parametro("KI", "Ki · integral", "error acumulado", 0.001, 3, 0.0, 50.0),
        Parametro("KD", "Kd · derivativo", "cambios bruscos", 0.1, 2, 0.0, 50.0)
    )

    private val campos = HashMap<String, EditText>()
    private val rellenos = HashMap<String, View>()

    private lateinit var enlace: BluetoothLink
    private lateinit var historial: Historial

    // Vistas: se vuelven a buscar cada vez que se arma la pantalla (ver armarPantalla).
    private lateinit var puntoEstado: View
    private lateinit var tituloEstado: TextView
    private lateinit var detalleEstado: TextView
    private lateinit var botonConexion: TextView
    private lateinit var botonConectarGrande: TextView
    private lateinit var barrido: View
    private lateinit var tramoBarrido: View
    private lateinit var iconoTema: View
    private lateinit var vistaDesconectado: View
    private lateinit var pestanas: Map<Pestana, View>
    private lateinit var botonesPestana: Map<Pestana, View>
    private lateinit var nombreModo: TextView
    private lateinit var tituloModos: TextView
    private lateinit var listaModos: LinearLayout
    private lateinit var vacioModos: View
    private lateinit var aviso: TextView
    private lateinit var botonArrancar: TextView
    private lateinit var haloArrancar: View
    private lateinit var botonGrabar: View
    private lateinit var iconoGrabar: TextView
    private lateinit var textoGrabar: TextView
    private lateinit var etiquetaGrabar: TextView

    private val preferencias by lazy { getSharedPreferences("rengo", MODE_PRIVATE) }
    private val modos by lazy { Modos(preferencias) }
    private val principal = Handler(Looper.getMainLooper())

    private var estado = Estado.DESCONECTADO
    private var tituloConectando = TITULO_CONECTANDO
    private var detalle = DETALLE_DESCONECTADO
    private var pestana = Pestana.SINTONIA
    private var corriendo = false
    private var modoBase: String? = null
    private var cortadoAMano = false
    private var nocheAplicada = 0
    private var girarIconoTema = false

    // El escaneo en curso queda a mano para poder cancelarlo desde el botón.
    private var escaner: BluetoothLeScanner? = null
    private var escucha: ScanCallback? = null
    private val encontrados = LinkedHashMap<String, Hallazgo>()
    private val finDeBusqueda = Runnable { terminarBusqueda(mostrarResultados = true) }

    // Solo se conoce la señal si se conectó desde el escaneo; al reengancharse por MAC no.
    private var potenciaConectado: Int? = null

    private var pulsoEstado: Animator? = null
    private var animacionBarrido: Animator? = null
    private var latidoArrancar: Animator? = null
    private val restaurarGrabar = Runnable { pintarGrabar(hecho = false) }
    private val ocultarAviso = Runnable {
        aviso.animate().alpha(0f).setDuration(150).withEndAction { aviso.visibility = View.GONE }.start()
    }

    private val pedirPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        if (resultados.values.all { it }) buscarDispositivos()
        else avisar("Sin permiso de Bluetooth no puedo conectar")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super: si el tema elegido difiere del sistema, se aplica sin recrear.
        AppCompatDelegate.setDefaultNightMode(
            preferencias.getInt(CLAVE_TEMA, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        super.onCreate(savedInstanceState)

        enlace = BluetoothLink(
            contexto = this,
            alRecibirLinea = { linea ->
                when {
                    linea.contains("VEL=") -> volcarValores(linea)
                    linea.startsWith("OK GUARDADO") -> {
                        historial.respuesta(linea)
                        mostrarGrabado("El robot confirmó", confirmado = true)
                    }
                    // "OK KP=0.5000": el cambio ya está anotado, repetirlo solo mete ruido.
                    CONFIRMACION.matches(linea) -> Unit
                    else -> historial.respuesta(linea)
                }
            },
            alCambiarEstado = { conectado, mensaje -> alCambiarEstado(conectado, mensaje) }
        )
        historial = Historial(preferencias) { hora, valores -> ofrecerVolverA(hora, valores) }

        armarPantalla()
        historial.iniciar(valoresActuales())
        refrescarModo()
    }

    override fun onResume() {
        super.onResume()
        reengancharse()
    }

    override fun onDestroy() {
        super.onDestroy()
        principal.removeCallbacksAndMessages(null)
        detenerEscaneo()
        enlace.cerrar()
    }

    // El manifiesto declara uiMode, así que cambiar de tema no recrea la actividad: la
    // recreación cerraría el Bluetooth. En su lugar se vuelve a armar la pantalla con los
    // colores nuevos, conservando los valores.
    override fun onConfigurationChanged(nueva: Configuration) {
        super.onConfigurationChanged(nueva)
        if ((nueva.uiMode and Configuration.UI_MODE_NIGHT_MASK) == nocheAplicada) return

        currentFocus?.clearFocus()
        val valores = valoresActuales()
        armarPantalla()
        for (p in parametros) {
            valores[p.clave]?.let { campos[p.clave]?.setText(it) }
            // Sin animar: la barra tiene que aparecer donde estaba, no crecer desde cero.
            rellenos[p.clave]?.animate()?.cancel()
            rellenos[p.clave]?.scaleX = fraccion(p)
        }
        refrescarModo()
    }

    // ----- Armado de la pantalla -----

    private fun armarPantalla() {
        detenerAnimaciones()
        setContentView(R.layout.activity_main)
        nocheAplicada = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        puntoEstado = findViewById(R.id.puntoEstado)
        tituloEstado = findViewById(R.id.tituloEstado)
        detalleEstado = findViewById(R.id.detalleEstado)
        botonConexion = findViewById(R.id.botonConexion)
        botonConectarGrande = findViewById(R.id.botonConectarGrande)
        barrido = findViewById(R.id.barrido)
        tramoBarrido = findViewById(R.id.tramoBarrido)
        iconoTema = findViewById(R.id.iconoTema)
        vistaDesconectado = findViewById(R.id.vistaDesconectado)
        nombreModo = findViewById(R.id.nombreModo)
        tituloModos = findViewById(R.id.tituloModos)
        listaModos = findViewById(R.id.listaModos)
        vacioModos = findViewById(R.id.vacioModos)
        aviso = findViewById(R.id.aviso)
        botonArrancar = findViewById(R.id.botonArrancar)
        haloArrancar = findViewById(R.id.haloArrancar)
        botonGrabar = findViewById(R.id.botonGrabar)
        iconoGrabar = findViewById(R.id.iconoGrabar)
        textoGrabar = findViewById(R.id.textoGrabar)
        etiquetaGrabar = findViewById(R.id.etiquetaGrabar)

        // El halo copia el tamaño del botón. Con match_parent en el layout, al medirse
        // dentro de una barra de alto libre se estiraba hasta el fondo de la pantalla y
        // empujaba las pestañas afuera.
        botonArrancar.addOnLayoutChangeListener { boton, _, _, _, _, _, _, _, _ ->
            val medidas = haloArrancar.layoutParams
            if (medidas.width == boton.width && medidas.height == boton.height) return@addOnLayoutChangeListener
            medidas.width = boton.width
            medidas.height = boton.height
            haloArrancar.post { haloArrancar.requestLayout() }
        }

        pestanas = mapOf(
            Pestana.SINTONIA to findViewById(R.id.pestanaSintonia),
            Pestana.MODOS to findViewById(R.id.pestanaModos),
            Pestana.REGISTRO to findViewById(R.id.pestanaRegistro)
        )
        botonesPestana = mapOf(
            Pestana.SINTONIA to findViewById(R.id.pestanaBotonSintonia),
            Pestana.MODOS to findViewById(R.id.pestanaBotonModos),
            Pestana.REGISTRO to findViewById(R.id.pestanaBotonRegistro)
        )
        val etiquetas = mapOf(Pestana.SINTONIA to "Sintonía", Pestana.MODOS to "Modos", Pestana.REGISTRO to "Registro")
        for ((p, boton) in botonesPestana) {
            boton.findViewById<TextView>(R.id.etiqueta).text = etiquetas.getValue(p)
            boton.setOnClickListener { irA(p) }
        }

        armarFilas()

        findViewById<View>(R.id.botonTema).setOnClickListener { alternarTema() }
        botonConexion.setOnClickListener { alternarConexion() }
        botonConectarGrande.setOnClickListener { alternarConexion() }
        findViewById<View>(R.id.filaModo).setOnClickListener { irA(Pestana.MODOS) }
        findViewById<View>(R.id.botonGuardarModo).setOnClickListener { abrirGuardarModo() }
        findViewById<View>(R.id.botonLimpiarHistorial).setOnClickListener { confirmarLimpiarHistorial() }

        botonGrabar.setOnClickListener {
            enviarTodos()
            if (!mandar("SAVE")) return@setOnClickListener
            historial.evento("grabado en el robot")
            mostrarGrabado("Mandado a grabar", confirmado = false)
        }
        botonArrancar.setOnClickListener {
            if (!mandar("START")) return@setOnClickListener
            corriendo = true
            historial.evento("▶ arrancar")
            pintarArrancar()
        }
        findViewById<View>(R.id.botonParar).setOnClickListener {
            if (!mandar("STOP")) return@setOnClickListener
            corriendo = false
            historial.evento("■ parar")
            pintarArrancar()
        }

        historial.mostrarEn(
            findViewById(R.id.scrollHistorial),
            findViewById(R.id.lineasHistorial),
            findViewById(R.id.puntoHistorial)
        )

        pintarIconoTema()
        pintarEstado()
        mostrarContenido(animar = false)
        pintarArrancar()
    }

    private fun armarFilas() {
        campos.clear()
        rellenos.clear()
        val contenedor = findViewById<LinearLayout>(R.id.contenedorParametros)

        for (p in parametros) {
            val fila = layoutInflater.inflate(R.layout.fila_parametro, contenedor, false)

            fila.findViewById<TextView>(R.id.nombreParametro).text = p.nombre
            fila.findViewById<TextView>(R.id.claveParametro).text = p.clave
            fila.findViewById<TextView>(R.id.descripcionParametro).text =
                "${p.descripcion} · ±${formatear(p.paso, p.decimales)}"

            // Velocidades en el celeste del club; las constantes del PID en el azul de apoyo.
            val relleno = fila.findViewById<View>(R.id.rellenoBarra)
            relleno.setBackgroundResource(
                if (p.clave == "VEL" || p.clave == "VMAX") R.drawable.barra_marca else R.drawable.barra_apoyo
            )
            relleno.pivotX = 0f
            rellenos[p.clave] = relleno

            val campo = fila.findViewById<EditText>(R.id.valorParametro)
            campo.setText(formatear(0.0, p.decimales))
            campos[p.clave] = campo
            relleno.scaleX = fraccion(p)
            campo.doAfterTextChanged { moverBarra(p) }

            // El valor escrito a mano se manda al salir del casillero o con "Listo".
            campo.setOnFocusChangeListener { _, tieneFoco ->
                if (tieneFoco) return@setOnFocusChangeListener
                aplicar(p)
                anotarCambio()
            }
            campo.setOnEditorActionListener { _, _, _ ->
                campo.clearFocus()
                false
            }

            fila.findViewById<View>(R.id.botonMenos).setOnClickListener { ajustar(p, -p.paso) }
            fila.findViewById<View>(R.id.botonMas).setOnClickListener { ajustar(p, p.paso) }

            contenedor.addView(fila)
        }
    }

    // ----- Pestañas y contenido -----

    private fun irA(destino: Pestana) {
        pestana = destino
        mostrarContenido()
    }

    // Sin conexión las pestañas siguen ahí, pero el contenido es siempre el cartel.
    private fun mostrarContenido(animar: Boolean = true) {
        val visible = if (estado == Estado.CONECTADO) pestanas.getValue(pestana) else vistaDesconectado
        for (v in pestanas.values + vistaDesconectado) {
            if (v !== visible) v.visibility = View.GONE
        }
        if (visible.visibility != View.VISIBLE) {
            visible.visibility = View.VISIBLE
            if (animar) {
                visible.alpha = 0f
                visible.animate().alpha(1f).setDuration(DURACION_PESTANA).start()
            }
        }

        for ((p, boton) in botonesPestana) {
            val activa = p == pestana
            boton.findViewById<View>(R.id.indicador).animate()
                .alpha(if (activa) 1f else 0f)
                .setDuration(if (animar) DURACION_PESTANA else 0)
                .start()
            boton.findViewById<TextView>(R.id.etiqueta)
                .setTextColor(color(if (activa) R.color.chrome_tinta else R.color.chrome_detalle))
        }
    }

    // ----- Tema -----

    private fun esNoche() = nocheAplicada == Configuration.UI_MODE_NIGHT_YES

    // Por defecto sigue al sistema; tocar el botón fuerza el otro modo y lo recuerda.
    private fun alternarTema() {
        val nuevo = if (esNoche()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        preferencias.edit().putInt(CLAVE_TEMA, nuevo).apply()
        girarIconoTema = true
        AppCompatDelegate.setDefaultNightMode(nuevo)
    }

    private fun pintarIconoTema() {
        val destino = if (esNoche()) 0f else 180f
        if (!girarIconoTema) {
            iconoTema.rotation = destino
            return
        }
        girarIconoTema = false
        iconoTema.rotation = destino - 180f
        iconoTema.animate().rotation(destino).setDuration(350).start()
    }

    // ----- Estado de la conexión -----

    private fun pintarEstado() {
        val colorPunto = when (estado) {
            Estado.CONECTADO -> R.color.marca
            Estado.DESCONECTADO -> R.color.punto_desconectado
            else -> R.color.alerta
        }
        puntoEstado.backgroundTintList = ColorStateList.valueOf(color(colorPunto))

        tituloEstado.text = when (estado) {
            Estado.DESCONECTADO -> "Desconectado"
            Estado.BUSCANDO -> "Buscando módulos…"
            Estado.CONECTANDO -> tituloConectando
            Estado.CONECTADO -> "Conectado"
        }
        detalleEstado.text = detalle

        val textoBoton = when (estado) {
            Estado.CONECTADO -> "Desconectar"
            Estado.DESCONECTADO -> "Conectar"
            else -> "Cancelar"
        }
        botonConexion.text = textoBoton
        botonConectarGrande.text = textoBoton
        if (estado == Estado.CONECTADO) {
            botonConexion.setBackgroundResource(R.drawable.boton_desconectar)
            botonConexion.setTextColor(color(R.color.chrome_boton_tinta))
        } else {
            botonConexion.setBackgroundResource(R.drawable.boton_conectar)
            botonConexion.setTextColor(color(R.color.marca_tinta))
        }

        val ocupado = estado == Estado.BUSCANDO || estado == Estado.CONECTANDO
        if (ocupado) latirPunto() else {
            pulsoEstado?.cancel()
            pulsoEstado = null
            puntoEstado.alpha = 1f
            puntoEstado.scaleX = 1f
            puntoEstado.scaleY = 1f
        }
        if (ocupado) barrer() else {
            animacionBarrido?.cancel()
            animacionBarrido = null
            barrido.visibility = View.GONE
        }
    }

    private fun alternarConexion() {
        when (estado) {
            Estado.DESCONECTADO -> verificarPermisoYConectar()
            Estado.BUSCANDO -> terminarBusqueda(mostrarResultados = false)
            Estado.CONECTANDO -> {
                cortadoAMano = true
                enlace.cerrar()
                pasarADesconectado(DETALLE_DESCONECTADO)
            }
            Estado.CONECTADO -> {
                cortadoAMano = true
                enlace.cerrar()
                historial.evento("── Desconectado")
                pasarADesconectado(DETALLE_DESCONECTADO)
            }
        }
    }

    private fun alCambiarEstado(conectado: Boolean, mensaje: String) {
        when {
            conectado -> {
                estado = Estado.CONECTADO
                detalle = detalleConectado(mensaje)
                historial.evento("── $mensaje")
                enlace.enviar("GET")
            }
            enlace.conectando -> {
                estado = Estado.CONECTANDO
                tituloConectando = if (mensaje.startsWith("Reconectando")) "Reconectando…" else TITULO_CONECTANDO
                detalle = if (mensaje.startsWith("Buscando servicios")) {
                    "buscando servicios · característica FFE1"
                } else {
                    "abriendo GATT · característica FFE1"
                }
            }
            else -> {
                // Un aviso de progreso que llegó después de cancelar a mano: ya no importa.
                if (estado == Estado.DESCONECTADO) return
                if (estado == Estado.CONECTADO) historial.evento("── $mensaje")
                pasarADesconectado(mensaje.replaceFirstChar { it.lowercase() })
                return
            }
        }
        pintarEstado()
        mostrarContenido()
        pintarArrancar()
    }

    private fun pasarADesconectado(motivo: String) {
        estado = Estado.DESCONECTADO
        detalle = motivo
        corriendo = false
        pintarEstado()
        mostrarContenido()
        pintarArrancar()
    }

    private fun detalleConectado(mensaje: String): String {
        val nombre = preferencias.getString(CLAVE_NOMBRE, null) ?: mensaje.removePrefix("Conectado a ")
        val mac = preferencias.getString(CLAVE_MODULO, null)
        return listOfNotNull(nombre, mac, potenciaConectado?.let { "$it dBm" }).joinToString(" · ")
    }

    // ----- Arrancar -----

    private fun pintarArrancar() {
        val conectado = estado == Estado.CONECTADO
        botonArrancar.setBackgroundResource(
            when {
                corriendo -> R.drawable.arrancar_corriendo
                conectado -> R.drawable.arrancar_quieto
                else -> R.drawable.arrancar_quieto_desconectado
            }
        )
        botonArrancar.setTextColor(
            color(
                when {
                    corriendo -> R.color.marca_tinta
                    conectado -> R.color.tinta
                    else -> R.color.tinta3
                }
            )
        )
        if (corriendo) latirArrancar() else {
            latidoArrancar?.cancel()
            latidoArrancar = null
            haloArrancar.alpha = 0f
        }
    }

    // ----- Grabar -----

    // Grabar no cambia nada en pantalla (los valores ya estaban), así que el botón mismo
    // avisa: se pinta y el celular vibra, para notarlo aunque estés mirando el robot.
    // El ✓ queda reservado para cuando el robot contesta OK GUARDADO: al mandar la orden
    // va una flecha, porque sin respuesta no hay forma de saber si la EEPROM se escribió.
    private fun mostrarGrabado(texto: String, confirmado: Boolean) {
        principal.removeCallbacks(restaurarGrabar)
        pintarGrabar(hecho = true)
        iconoGrabar.text = if (confirmado) "✓" else "→"
        textoGrabar.text = texto

        val vibracion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        botonGrabar.performHapticFeedback(vibracion)
        botonGrabar.scaleX = 0.97f
        botonGrabar.scaleY = 0.97f
        botonGrabar.animate().scaleX(1f).scaleY(1f).setDuration(180).start()

        principal.postDelayed(restaurarGrabar, DURACION_GRABADO)
    }

    private fun pintarGrabar(hecho: Boolean) {
        botonGrabar.setBackgroundResource(if (hecho) R.drawable.boton_grabar_hecho else R.drawable.boton_grabar)
        iconoGrabar.setBackgroundResource(if (hecho) R.drawable.icono_grabar_hecho else R.drawable.icono_grabar)
        if (!hecho) iconoGrabar.text = "↓"
        iconoGrabar.setTextColor(color(if (hecho) R.color.marca else R.color.marca_tinta))
        textoGrabar.setTextColor(color(if (hecho) R.color.marca_tinta else R.color.tinta))
        etiquetaGrabar.setTextColor(color(if (hecho) R.color.marca_tinta else R.color.tinta2))
        if (!hecho) textoGrabar.text = "Grabar en el robot"
    }

    // ----- Animaciones -----

    // Punto de estado: late mientras busca o conecta.
    private fun latirPunto() {
        if (pulsoEstado != null) return
        pulsoEstado = ObjectAnimator.ofPropertyValuesHolder(
            puntoEstado,
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.25f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.7f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.7f, 1f)
        ).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    // Barra de escaneo: un tramo del 40% que cruza de punta a punta.
    private fun barrer() {
        barrido.visibility = View.VISIBLE
        if (animacionBarrido != null) return
        barrido.post {
            if (barrido.visibility != View.VISIBLE || animacionBarrido != null) return@post
            val ancho = barrido.width * 0.4f
            tramoBarrido.layoutParams.width = ancho.toInt()
            tramoBarrido.requestLayout()
            animacionBarrido = ObjectAnimator.ofFloat(tramoBarrido, View.TRANSLATION_X, -0.6f * ancho, 2.6f * ancho).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    // Halo de Arrancar mientras el robot anda: se abre 10dp y se apaga en el 70% del
    // ciclo, y en el resto vuelve a cerrarse, como el keyframe del diseño.
    private fun latirArrancar() {
        if (latidoArrancar != null) return
        val crecer = dp(10f)
        val frenada = DecelerateInterpolator()
        latidoArrancar = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedValue as Float
                val apertura = if (f < 0.7f) frenada.getInterpolation(f / 0.7f)
                else 1f - frenada.getInterpolation((f - 0.7f) / 0.3f)
                val ancho = haloArrancar.width.coerceAtLeast(1)
                val alto = haloArrancar.height.coerceAtLeast(1)
                haloArrancar.scaleX = 1f + 2 * crecer * apertura / ancho
                haloArrancar.scaleY = 1f + 2 * crecer * apertura / alto
                haloArrancar.alpha = 1f - apertura
            }
            start()
        }
    }

    private fun detenerAnimaciones() {
        pulsoEstado?.cancel()
        animacionBarrido?.cancel()
        latidoArrancar?.cancel()
        pulsoEstado = null
        animacionBarrido = null
        latidoArrancar = null
        principal.removeCallbacks(ocultarAviso)
        principal.removeCallbacks(restaurarGrabar)
    }

    // ----- Aviso breve -----

    // Cada aviso pisa al anterior: si no, apretar + diez veces contra el tope deja diez
    // carteles encolados apareciendo de a uno mucho después de soltar el botón.
    private fun avisar(texto: String) {
        principal.removeCallbacks(ocultarAviso)
        aviso.animate().cancel()
        aviso.text = texto
        aviso.visibility = View.VISIBLE
        aviso.alpha = 0f
        aviso.translationY = dp(16f)
        aviso.animate().alpha(1f).translationY(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).start()
        principal.postDelayed(ocultarAviso, DURACION_AVISO)
    }

    // ----- Parámetros -----

    private fun ajustar(p: Parametro, delta: Double) {
        fijarCampo(p, leerCampo(p) + delta)
        aplicar(p)
        anotarCambio()
    }

    // Se anota acá y no en aplicar(), porque aplicar() también corre al cargar un modo o
    // al volver en el historial, y esos van como una sola línea con su propio título.
    private fun anotarCambio() {
        historial.anotar(valoresActuales(), Historial.Tipo.CAMBIO)
    }

    // Manda el valor al robot, que lo usa al instante pero no lo graba: la EEPROM
    // aguanta ~100 mil escrituras, así que solo se toca con Grabar.
    private fun aplicar(p: Parametro) {
        fijarCampo(p, leerCampo(p))
        val arrastrado = respetarTope(p)
        refrescarModo()

        if (!enlace.conectado) return
        mandar("${p.clave}=${textoDe(p)}")
        if (arrastrado != null) mandar("${arrastrado.clave}=${textoDe(arrastrado)}")
    }

    private fun fijarCampo(p: Parametro, valor: Double) {
        campos[p.clave]?.setText(formatear(valor.coerceIn(p.minimo, p.maximo), p.decimales))
    }

    private fun textoDe(p: Parametro) = formatear(leerCampo(p), p.decimales)

    private fun fraccion(p: Parametro) = (leerCampo(p) / p.maximo).coerceIn(0.02, 1.0).toFloat()

    private fun moverBarra(p: Parametro) {
        rellenos[p.clave]?.animate()?.scaleX(fraccion(p))?.setDuration(200)?.start()
    }

    // El firmware recorta cada rueda a la velocidad máxima, así que una base más alta no
    // acelera: solo deja el número mintiendo. Se baja la base, nunca se sube el tope, para
    // no tocar un límite que el usuario eligió a propósito. Devuelve el parámetro que
    // hubo que arrastrar, si es que no fue el que se tocó.
    private fun respetarTope(cambiado: Parametro): Parametro? {
        val base = parametros.first { it.clave == "VEL" }
        val tope = parametros.first { it.clave == "VMAX" }
        if (cambiado != base && cambiado != tope) return null
        if (leerCampo(base) <= leerCampo(tope)) return null

        fijarCampo(base, leerCampo(tope))
        val limite = textoDe(tope)
        val arrastrado = if (cambiado == base) null else base

        avisar(
            if (arrastrado == null) "La velocidad base no puede pasar la máxima ($limite)"
            else "Bajé la velocidad base a $limite para que no pase la máxima"
        )
        return arrastrado
    }

    private fun enviarTodos() {
        for (p in parametros) aplicar(p)
    }

    private fun valoresActuales() = parametros.associate { p ->
        p.clave to formatear(leerCampo(p).coerceIn(p.minimo, p.maximo), p.decimales)
    }

    private fun resumen(valores: Map<String, String>) =
        parametros.joinToString("  ") { "${it.clave} ${valores[it.clave]}" }

    private fun mandar(comando: String): Boolean {
        if (!enlace.conectado) {
            avisar("Primero conectate al robot")
            return false
        }
        enlace.enviar(comando)
        return true
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
        historial.anotar(valoresActuales(), Historial.Tipo.ROBOT, "robot")
    }

    // ----- Modos guardados -----

    // El estado se deduce de los valores: no hace falta recordar si el usuario tocó algo,
    // alcanza con comparar lo que hay en pantalla contra lo que tiene guardado el modo.
    private fun refrescarModo() {
        val actuales = valoresActuales()
        if (modoBase == null) modoBase = modos.buscarPorValores(actuales)

        val base = modoBase
        val guardados = base?.let { modos.valores(it) }

        nombreModo.text = when {
            guardados == null -> "Personalizado"
            guardados == actuales -> base
            else -> "Personalizado (desde $base)"
        }
        refrescarListaModos(actuales)
    }

    private fun refrescarListaModos(actuales: Map<String, String>) {
        val nombres = modos.nombres()
        tituloModos.text = "GUARDADOS EN EL CELULAR · ${nombres.size} ${if (nombres.size == 1) "modo" else "modos"}"
        vacioModos.visibility = if (nombres.isEmpty()) View.VISIBLE else View.GONE

        listaModos.removeAllViews()
        for (nombre in nombres) {
            val valores = modos.valores(nombre) ?: continue
            val activo = valores == actuales

            val fila = layoutInflater.inflate(R.layout.fila_modo, listaModos, false)
            fila.setBackgroundResource(if (activo) R.drawable.modo_fila_activa else R.drawable.modo_fila)
            fila.clipToOutline = true
            fila.findViewById<TextView>(R.id.nombreModoGuardado).text = nombre
            fila.findViewById<View>(R.id.chipActivo).visibility = if (activo) View.VISIBLE else View.GONE
            fila.findViewById<TextView>(R.id.resumenModo).text = resumen(valores)
            fila.findViewById<View>(R.id.cargarModo).setOnClickListener { cargarModo(nombre) }
            fila.findViewById<View>(R.id.borrarModo).setOnClickListener { confirmarBorrado(nombre) }
            listaModos.addView(fila)
        }
    }

    private fun cargarModo(nombre: String) {
        val valores = modos.valores(nombre) ?: return
        modoBase = nombre
        if (valores == valoresActuales()) {
            refrescarModo()
            avisar("Ya estás en ese punto")
            return
        }

        for (p in parametros) valores[p.clave]?.let { campos[p.clave]?.setText(it) }
        enviarTodos()
        refrescarModo()
        historial.anotar(valoresActuales(), Historial.Tipo.MODO, "modo \"$nombre\"")
        irA(Pestana.SINTONIA)
    }

    private fun abrirGuardarModo() {
        abrirHoja(R.layout.hoja_guardar_modo) { vista, hoja ->
            val campo = vista.findViewById<EditText>(R.id.nombreNuevoModo)
            campo.setText(modoBase ?: "")
            campo.setSelection(campo.text.length)

            vista.findViewById<TextView>(R.id.resumenActual).text = resumen(valoresActuales())

            val existentes = modos.nombres()
            val chips = vista.findViewById<ChipGroup>(R.id.existentes)
            if (existentes.isEmpty()) {
                vista.findViewById<View>(R.id.tituloExistentes).visibility = View.GONE
                chips.visibility = View.GONE
            }
            // Tocar un nombre va derecho a sobrescribirlo, previa confirmación.
            for (nombre in existentes) {
                val chip = TextView(this)
                chip.text = nombre
                chip.textSize = 12f
                chip.typeface = ResourcesCompat.getFont(this, R.font.raleway_semibold)
                chip.setTextColor(color(R.color.tinta))
                chip.setBackgroundResource(R.drawable.chip_modo)
                chip.setPadding(dp(12f).toInt(), dp(9f).toInt(), dp(12f).toInt(), dp(9f).toInt())
                chip.maxLines = 1
                chip.ellipsize = TextUtils.TruncateAt.END
                chip.setOnClickListener {
                    hoja.dismiss()
                    pedirGuardar(nombre)
                }
                chips.addView(chip)
            }

            val guardar = vista.findViewById<View>(R.id.confirmarGuardado)
            guardar.setOnClickListener {
                val nombre = campo.text.toString().trim()
                if (nombre.isEmpty()) {
                    campo.error = "Poné un nombre"
                    return@setOnClickListener
                }
                hoja.dismiss()
                pedirGuardar(nombre)
            }
            campo.setOnEditorActionListener { _, _, _ ->
                guardar.performClick()
                true
            }
            vista.findViewById<View>(R.id.cancelarGuardado).setOnClickListener { hoja.dismiss() }
        }
    }

    private fun pedirGuardar(nombre: String) {
        if (!modos.nombres().contains(nombre)) {
            guardarModo(nombre)
            return
        }
        confirmar(
            titulo = "Ya existe \"$nombre\"",
            mensaje = "¿Querés sobrescribirlo con los valores de ahora?",
            accion = "Sobrescribir",
            tono = Tono.ALERTA
        ) { guardarModo(nombre) }
    }

    private fun guardarModo(nombre: String) {
        modos.guardar(nombre, valoresActuales())
        modoBase = nombre
        refrescarModo()
        avisar("Modo \"$nombre\" guardado")
    }

    private fun confirmarBorrado(nombre: String) {
        confirmar(
            titulo = "Borrar modo",
            mensaje = "¿Borrar \"$nombre\"? Se va del celular, el robot no se toca.",
            accion = "Borrar",
            tono = Tono.STOP
        ) {
            modos.borrar(nombre)
            if (modoBase == nombre) modoBase = null
            refrescarModo()
        }
    }

    // ----- Historial -----

    // Muestra el camino inverso antes de hacerlo: tocar una línea sin querer mientras
    // scrolleás no debería cambiarle cinco valores al robot sin avisar.
    private fun ofrecerVolverA(hora: String, destino: Map<String, String>) {
        val actuales = valoresActuales()
        val camino = parametros.filter { actuales[it.clave] != destino[it.clave] }
        if (camino.isEmpty()) {
            avisar("Ya estás en ese punto")
            return
        }

        confirmar(
            titulo = "Volver a las $hora",
            mensaje = "Se mandan solo los valores que cambian.",
            accion = "Volver",
            tono = Tono.ALERTA,
            detalle = camino.map { "${it.clave}  ${actuales[it.clave]} → ${destino[it.clave]}" }
        ) {
            volverA(hora, destino, camino)
            irA(Pestana.SINTONIA)
        }
    }

    // Se mandan solo los valores que difieren: al robot le importa dónde termina, no
    // los pasos intermedios, y cada comando cuesta 80 ms de Bluetooth.
    private fun volverA(hora: String, destino: Map<String, String>, camino: List<Parametro>) {
        // Primero todos los casilleros y después aplicar, así el tope de velocidad se
        // compara contra los valores nuevos y no contra una mezcla de viejos y nuevos.
        for (p in camino) destino[p.clave]?.let { campos[p.clave]?.setText(it) }
        for (p in camino) aplicar(p)

        modoBase = null
        refrescarModo()
        historial.anotar(valoresActuales(), Historial.Tipo.VUELTA, "↶ a las $hora")
    }

    private fun confirmarLimpiarHistorial() {
        confirmar(
            titulo = "Limpiar historial",
            mensaje = "¿Borrar todo el historial? No se puede deshacer.",
            accion = "Borrar",
            tono = Tono.STOP
        ) { historial.limpiar(valoresActuales()) }
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
            avisar("Prendé el Bluetooth del celular")
            return
        }
        val lector = adaptador.bluetoothLeScanner ?: return

        encontrados.clear()
        val oyente = object : ScanCallback() {
            override fun onScanResult(tipo: Int, resultado: ScanResult) {
                // El nombre del caché viene null si el dispositivo no está vinculado;
                // el del anuncio es el que sirve.
                val nombre = resultado.scanRecord?.deviceName ?: resultado.device.name
                encontrados[resultado.device.address] =
                    Hallazgo(resultado.device, nombre, resultado.rssi)
            }
        }

        escaner = lector
        escucha = oyente
        estado = Estado.BUSCANDO
        detalle = "escaneando BLE 5 s · no hace falta emparejar"
        pintarEstado()
        lector.startScan(oyente)
        principal.postDelayed(finDeBusqueda, DURACION_BUSQUEDA)
    }

    private fun terminarBusqueda(mostrarResultados: Boolean) {
        detenerEscaneo()
        pasarADesconectado(DETALLE_DESCONECTADO)
        if (mostrarResultados) mostrarEncontrados(encontrados.values.sortedByDescending { it.potencia })
    }

    @SuppressLint("MissingPermission")
    private fun detenerEscaneo() {
        principal.removeCallbacks(finDeBusqueda)
        val oyente = escucha ?: return
        escaner?.stopScan(oyente)
        escaner = null
        escucha = null
    }

    private fun mostrarEncontrados(hallazgos: List<Hallazgo>) {
        if (hallazgos.isEmpty()) {
            avisar("No encontré ningún módulo. Fijate que esté alimentado y parpadeando.")
            return
        }

        abrirHoja(R.layout.hoja_escaneo) { vista, hoja ->
            val lista = vista.findViewById<LinearLayout>(R.id.listaHallazgos)
            hallazgos.forEachIndexed { indice, h ->
                val fila = layoutInflater.inflate(R.layout.fila_hallazgo, lista, false)
                // El primero es el de mejor señal: casi siempre es el robot que tenés al lado.
                fila.setBackgroundResource(if (indice == 0) R.drawable.hallazgo_primero else R.drawable.hallazgo)
                fila.findViewById<TextView>(R.id.nombreHallazgo).text = h.nombre ?: "(sin nombre)"
                fila.findViewById<TextView>(R.id.detalleHallazgo).text =
                    "${h.dispositivo.address} · ${h.potencia} dBm"

                val umbrales = listOf(R.id.barrita1 to -95, R.id.barrita2 to -80, R.id.barrita3 to -60)
                for ((id, umbral) in umbrales) {
                    val encendida = h.potencia > umbral
                    fila.findViewById<View>(id).backgroundTintList =
                        ColorStateList.valueOf(color(if (encendida) R.color.marca else R.color.borde))
                }

                fila.setOnClickListener {
                    hoja.dismiss()
                    conectarA(h)
                }
                lista.addView(fila)
            }
            vista.findViewById<View>(R.id.cancelarEscaneo).setOnClickListener { hoja.dismiss() }
        }
    }

    private fun conectarA(hallazgo: Hallazgo) {
        cortadoAMano = false
        potenciaConectado = hallazgo.potencia
        preferencias.edit()
            .putString(CLAVE_MODULO, hallazgo.dispositivo.address)
            .putString(CLAVE_NOMBRE, hallazgo.nombre)
            .apply()
        enlace.conectar(hallazgo.dispositivo)
    }

    // El enlace BLE se corta al dejar la app en segundo plano. Como ya sabemos qué módulo
    // eligió el usuario, alcanza con su MAC para volver a engancharse sin escanear.
    private fun reengancharse() {
        if (enlace.conectado || enlace.conectando || estado == Estado.BUSCANDO || cortadoAMano) return

        val mac = preferencias.getString(CLAVE_MODULO, null) ?: return
        val adaptador = obtenerAdaptador() ?: return
        if (!adaptador.isEnabled) return

        val falta = permisosNecesarios().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (falta) return

        potenciaConectado = null
        enlace.conectar(adaptador.getRemoteDevice(mac), "Reconectando...")
    }

    private fun obtenerAdaptador(): BluetoothAdapter? {
        val servicio = getSystemService(BluetoothManager::class.java)
        return servicio?.adapter
    }

    // ----- Utilidades -----

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun dp(valor: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valor, resources.displayMetrics)

    private companion object {
        const val DURACION_BUSQUEDA = 5000L
        const val DURACION_PESTANA = 180L
        const val DURACION_AVISO = 2400L
        const val DURACION_GRABADO = 2000L
        const val CLAVE_MODULO = "modulo"
        const val CLAVE_NOMBRE = "nombre_modulo"
        const val CLAVE_TEMA = "tema"
        const val TITULO_CONECTANDO = "Conectando…"
        const val DETALLE_DESCONECTADO = "el robot tiene que estar prendido"
        val CONFIRMACION = Regex("OK [A-Z]+=.*")
    }
}
