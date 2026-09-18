package com.rengo.tuner

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Historial con pinta de terminal: lo más nuevo abajo, una línea por cambio. Cada línea
// que toca valores guarda cómo quedaron los cinco, así volver a un punto es copiar esa
// foto en vez de deshacer los pasos de a uno. Se guarda en el celular para que no se
// pierda si Android cierra la app, y para poder volver a lo que anduvo bien otro día.
//
// Los datos viven acá y las vistas se arman aparte con mostrarEn(): al cambiar de tema
// la pantalla se vuelve a armar y el historial se redibuja sin perder nada.
class Historial(
    private val preferencias: SharedPreferences,
    private val alElegir: (hora: String, valores: Map<String, String>) -> Unit
) {

    enum class Tipo(val color: Int) {
        FECHA(R.color.terminal_fecha),
        INICIO(R.color.terminal_evento),
        CAMBIO(R.color.terminal_texto),
        MODO(R.color.terminal_modo),
        VUELTA(R.color.terminal_vuelta),
        ROBOT(R.color.terminal_robot),
        EVENTO(R.color.terminal_evento),
        RESPUESTA(R.color.terminal_robot),
        ERROR(R.color.terminal_error)
    }

    private class Entrada(
        val tipo: Tipo,
        val titulo: String?,
        val antes: Map<String, String>?,
        var despues: Map<String, String>?,
        var momento: Long
    ) {
        var vista: View? = null
    }

    private val entradas = ArrayList<Entrada>()
    private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val formatoDia = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val formatoFecha = SimpleDateFormat("EEEE dd/MM", Locale("es", "AR"))
    private var foto: Map<String, String>? = null

    private var scroll: ScrollView? = null
    private var lineas: LinearLayout? = null
    private var punto: View? = null

    // Como una terminal: si estás abajo, lo nuevo te sigue; si subiste a leer, no te mueve.
    private var pegadoAbajo = true

    init {
        cargar()
    }

    fun mostrarEn(scroll: ScrollView, lineas: LinearLayout, punto: View) {
        this.scroll = scroll
        this.lineas = lineas
        this.punto = punto
        pegadoAbajo = true
        seguirLoUltimo(scroll, lineas)

        lineas.removeAllViews()
        for (entrada in entradas) {
            entrada.vista = crearVista(entrada, lineas)
            lineas.addView(entrada.vista)
        }
        pintarPunto()
    }

    fun iniciar(valores: Map<String, String>) {
        foto = valores
        agregar(Entrada(Tipo.INICIO, null, null, valores, ahora()))
    }

    // Anota solo si algo cambió de verdad: salir de un casillero sin tocarlo o reenviar
    // todo con Grabar no ensucia el historial.
    fun anotar(valores: Map<String, String>, tipo: Tipo, titulo: String? = null) {
        val antes = foto
        if (antes == null) {
            iniciar(valores)
            return
        }
        if (antes == valores) return
        foto = valores

        val ultima = entradas.lastOrNull { it.despues != null }
        if (tipo == Tipo.CAMBIO && ultima != null && sePuedeJuntar(ultima, antes, valores)) {
            juntar(ultima, valores)
            return
        }
        agregar(Entrada(tipo, titulo, antes, valores, ahora()))
    }

    fun evento(texto: String) {
        agregar(Entrada(Tipo.EVENTO, texto, null, null, ahora()))
    }

    fun respuesta(linea: String) {
        val tipo = if (linea.startsWith("ERR")) Tipo.ERROR else Tipo.RESPUESTA
        agregar(Entrada(tipo, "‹ $linea", null, null, ahora()))
    }

    // Arranca de cero pero con una línea de inicio, así siempre hay un punto al que volver.
    fun limpiar(valores: Map<String, String>) {
        entradas.clear()
        lineas?.removeAllViews()
        iniciar(valores)
    }

    fun irAlFinal() {
        pegadoAbajo = true
        val s = scroll ?: return
        val l = lineas ?: return
        s.post { s.scrollTo(0, l.height) }
    }

    // Bajar al final "ya" no alcanza: la pestaña está oculta hasta que la elegís y en ese
    // momento todavía no tiene alto, así que bajaba hasta 0. Escuchando cada vez que
    // cambia el tamaño, baja cuando Android ya lo midió.
    private fun seguirLoUltimo(scroll: ScrollView, lineas: LinearLayout) {
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            pegadoAbajo = !scroll.canScrollVertically(1)
        }
        val alCambiarTamano = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (pegadoAbajo) scroll.scrollTo(0, lineas.height)
        }
        lineas.addOnLayoutChangeListener(alCambiarTamano)
        scroll.addOnLayoutChangeListener(alCambiarTamano)
    }

    // ----- Líneas -----

    // Apretar + diez veces seguidas es un solo ajuste: sin esto el historial se llena de
    // pasitos de a 0.1 y encontrar un punto al que volver se vuelve imposible.
    private fun sePuedeJuntar(
        ultima: Entrada,
        antes: Map<String, String>,
        valores: Map<String, String>
    ): Boolean {
        if (ultima.tipo != Tipo.CAMBIO || ahora() - ultima.momento > JUNTAR_MS) return false
        return cambiadas(ultima.antes!!, ultima.despues!!) == cambiadas(antes, valores)
    }

    private fun juntar(entrada: Entrada, valores: Map<String, String>) {
        entrada.despues = valores
        entrada.momento = ahora()

        // Fue y volvió: el ajuste se anuló solo y no queda nada que recordar.
        if (entrada.antes == valores) {
            entradas.remove(entrada)
            lineas?.removeView(entrada.vista)
        } else {
            entrada.vista?.let { pintar(entrada, it) }
        }

        pintarPunto()
        guardar()
    }

    private fun agregar(entrada: Entrada) {
        // Las líneas solo tienen hora: sin un separador por día, con el historial guardado
        // no habría forma de saber si un 10:53 es de hoy o de la semana pasada.
        val anterior = entradas.lastOrNull()
        if (anterior == null || !mismoDia(anterior.momento, entrada.momento)) {
            sumar(separador(entrada.momento))
        }
        sumar(entrada)
        recortar()
        pintarPunto()
        guardar()
    }

    private fun sumar(entrada: Entrada, posicion: Int = entradas.size) {
        entradas.add(posicion, entrada)
        val l = lineas ?: return
        entrada.vista = crearVista(entrada, l)
        l.addView(entrada.vista, posicion)
    }

    private fun recortar() {
        if (entradas.size <= MAXIMO) return
        while (entradas.size > MAXIMO) lineas?.removeView(entradas.removeAt(0).vista)

        // Si se fue el separador de arriba, las líneas más viejas quedarían sin fecha.
        val primera = entradas.first()
        if (primera.tipo != Tipo.FECHA) sumar(separador(primera.momento), 0)
    }

    private fun separador(momento: Long) =
        Entrada(Tipo.FECHA, "── ${formatoFecha.format(Date(momento))}", null, null, momento)

    private fun crearVista(entrada: Entrada, lineas: LinearLayout): View {
        val vista = LayoutInflater.from(lineas.context).inflate(R.layout.fila_historial, lineas, false)
        pintar(entrada, vista)
        if (entrada.despues != null) {
            vista.setBackgroundResource(R.drawable.terminal_fila)
            vista.setOnClickListener {
                alElegir(formatoHora.format(Date(entrada.momento)), entrada.despues!!)
            }
        }
        return vista
    }

    private fun pintar(entrada: Entrada, vista: View) {
        val cuerpo = when (entrada.tipo) {
            Tipo.INICIO -> "inicio  " +
                entrada.despues!!.entries.joinToString(" ") { "${it.key}=${it.value}" }
            Tipo.FECHA, Tipo.EVENTO, Tipo.RESPUESTA, Tipo.ERROR -> entrada.titulo!!
            else -> listOfNotNull(entrada.titulo, diferencias(entrada.antes!!, entrada.despues!!))
                .joinToString("  ")
        }

        vista.findViewById<TextView>(R.id.horaLinea).text =
            if (entrada.tipo == Tipo.FECHA) "" else formatoHora.format(Date(entrada.momento))
        val texto = vista.findViewById<TextView>(R.id.cuerpoLinea)
        texto.text = cuerpo
        texto.setTextColor(ContextCompat.getColor(vista.context, entrada.tipo.color))
    }

    // El punto de la barra toma el color de lo último que pasó, para ver de un vistazo
    // si lo último fue un cambio tuyo, algo del robot o un error.
    private fun pintarPunto() {
        val p = punto ?: return
        val color = when (entradas.lastOrNull()?.tipo) {
            null -> R.color.terminal_evento
            Tipo.FECHA, Tipo.INICIO, Tipo.EVENTO -> R.color.terminal_neutro
            else -> entradas.last().tipo.color
        }
        p.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(p.context, color))
    }

    private fun cambiadas(antes: Map<String, String>, despues: Map<String, String>) =
        despues.keys.filter { antes[it] != despues[it] }.toSet()

    private fun diferencias(antes: Map<String, String>, despues: Map<String, String>) =
        cambiadas(antes, despues).joinToString("   ") { "$it ${antes[it]} → ${despues[it]}" }

    private fun mismoDia(a: Long, b: Long) =
        formatoDia.format(Date(a)) == formatoDia.format(Date(b))

    private fun ahora() = System.currentTimeMillis()

    // ----- Guardado -----

    private fun guardar() {
        val lista = JSONArray()
        for (entrada in entradas) {
            val json = JSONObject()
                .put(TIPO, entrada.tipo.name)
                .put(MOMENTO, entrada.momento)
            entrada.titulo?.let { json.put(TITULO, it) }
            entrada.antes?.let { json.put(ANTES, JSONObject(it.toMap<String, Any>())) }
            entrada.despues?.let { json.put(DESPUES, JSONObject(it.toMap<String, Any>())) }
            lista.put(json)
        }
        preferencias.edit().putString(CLAVE_HISTORIAL, lista.toString()).apply()
    }

    private fun cargar() {
        val texto = preferencias.getString(CLAVE_HISTORIAL, null) ?: return

        // Si lo guardado no se puede leer (por ejemplo, de una versión vieja de la app),
        // se arranca vacío en vez de cerrarse.
        try {
            val lista = JSONArray(texto)
            for (i in 0 until lista.length()) {
                val json = lista.getJSONObject(i)
                entradas.add(
                    Entrada(
                        tipo = Tipo.valueOf(json.getString(TIPO)),
                        titulo = json.optString(TITULO).ifEmpty { null },
                        antes = json.optJSONObject(ANTES)?.let { aMapa(it) },
                        despues = json.optJSONObject(DESPUES)?.let { aMapa(it) },
                        momento = json.getLong(MOMENTO)
                    )
                )
            }
        } catch (e: JSONException) {
            entradas.clear()
        } catch (e: IllegalArgumentException) {
            entradas.clear()
        }
    }

    private fun aMapa(json: JSONObject) =
        json.keys().asSequence().associateWith { json.getString(it) }

    private companion object {
        const val CLAVE_HISTORIAL = "historial"
        const val TIPO = "tipo"
        const val TITULO = "titulo"
        const val ANTES = "antes"
        const val DESPUES = "despues"
        const val MOMENTO = "momento"
        const val JUNTAR_MS = 3000L
        const val MAXIMO = 300
    }
}
