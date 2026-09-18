package com.rengo.tuner

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
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
class Historial(
    private val panel: View,
    private val barra: View,
    private val scroll: ScrollView,
    private val lineas: LinearLayout,
    private val cedible: View,
    private val preferencias: SharedPreferences,
    private val alElegir: (hora: String, valores: Map<String, String>) -> Unit
) {

    enum class Tipo(val color: Int) {
        FECHA(R.color.terminal_evento),
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
        var momento: Long,
        val vista: TextView
    )

    private val contexto = panel.context
    private val entradas = ArrayList<Entrada>()
    private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val formatoDia = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val formatoFecha = SimpleDateFormat("EEEE dd/MM", Locale("es", "AR"))
    private var foto: Map<String, String>? = null

    // Como una terminal: si estás abajo, lo nuevo te sigue; si subiste a leer, no te mueve.
    private var pegadoAbajo = true

    init {
        panel.layoutParams.height = preferencias.getInt(CLAVE_ALTO, dp(ALTO_INICIAL_DP))
        permitirAgrandar()
        seguirLoUltimo()
        cargar()
    }

    fun iniciar(valores: Map<String, String>) {
        foto = valores
        agregar(Entrada(Tipo.INICIO, null, null, valores, ahora(), nuevaVista()))
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
        agregar(Entrada(tipo, titulo, antes, valores, ahora(), nuevaVista()))
    }

    fun evento(texto: String) {
        agregar(Entrada(Tipo.EVENTO, texto, null, null, ahora(), nuevaVista()))
    }

    fun respuesta(linea: String) {
        val tipo = if (linea.startsWith("ERR")) Tipo.ERROR else Tipo.RESPUESTA
        agregar(Entrada(tipo, "‹ $linea", null, null, ahora(), nuevaVista()))
    }

    // Arranca de cero pero con una línea de inicio, así siempre hay un punto al que volver.
    fun limpiar(valores: Map<String, String>) {
        entradas.clear()
        lineas.removeAllViews()
        iniciar(valores)
    }

    fun irAlFinal() {
        pegadoAbajo = true
        scroll.post { scroll.scrollTo(0, lineas.height) }
    }

    // Bajar al final "ya" no alcanza: el panel está oculto hasta que conecta y en ese
    // momento todavía no tiene alto, así que bajaba hasta 0. Escuchando cada vez que
    // cambia el tamaño, baja cuando Android ya lo midió.
    private fun seguirLoUltimo() {
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
            lineas.removeView(entrada.vista)
        } else {
            pintar(entrada)
        }

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
        guardar()
    }

    private fun sumar(entrada: Entrada, posicion: Int = entradas.size) {
        preparar(entrada)
        entradas.add(posicion, entrada)
        lineas.addView(entrada.vista, posicion)
    }

    private fun recortar() {
        if (entradas.size <= MAXIMO) return
        while (entradas.size > MAXIMO) lineas.removeView(entradas.removeAt(0).vista)

        // Si se fue el separador de arriba, las líneas más viejas quedarían sin fecha.
        val primera = entradas.first()
        if (primera.tipo != Tipo.FECHA) sumar(separador(primera.momento), 0)
    }

    private fun separador(momento: Long) =
        Entrada(Tipo.FECHA, "── ${formatoFecha.format(Date(momento))}", null, null, momento, nuevaVista())

    private fun preparar(entrada: Entrada) {
        pintar(entrada)
        if (entrada.despues == null) return

        entrada.vista.setBackgroundResource(R.drawable.toque_terminal)
        entrada.vista.setOnClickListener {
            alElegir(formatoHora.format(Date(entrada.momento)), entrada.despues!!)
        }
    }

    private fun pintar(entrada: Entrada) {
        if (entrada.tipo == Tipo.FECHA) {
            entrada.vista.text = entrada.titulo
            entrada.vista.setTextColor(color(entrada.tipo.color))
            return
        }

        val cuerpo = when (entrada.tipo) {
            Tipo.INICIO -> "inicio  " +
                entrada.despues!!.entries.joinToString(" ") { "${it.key}=${it.value}" }
            Tipo.EVENTO, Tipo.RESPUESTA, Tipo.ERROR -> entrada.titulo!!
            else -> listOfNotNull(entrada.titulo, diferencias(entrada.antes!!, entrada.despues!!))
                .joinToString("  ")
        }

        val texto = SpannableStringBuilder()
        texto.append(
            formatoHora.format(Date(entrada.momento)),
            ForegroundColorSpan(color(R.color.terminal_hora)),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        texto.append("  ")
        texto.append(cuerpo, ForegroundColorSpan(color(entrada.tipo.color)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        entrada.vista.text = texto
    }

    private fun nuevaVista() = TextView(contexto).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun cambiadas(antes: Map<String, String>, despues: Map<String, String>) =
        despues.keys.filter { antes[it] != despues[it] }.toSet()

    private fun diferencias(antes: Map<String, String>, despues: Map<String, String>) =
        cambiadas(antes, despues).joinToString("   ") { "$it ${antes[it]} → ${despues[it]}" }

    private fun mismoDia(a: Long, b: Long) =
        formatoDia.format(Date(a)) == formatoDia.format(Date(b))

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
                sumar(
                    Entrada(
                        tipo = Tipo.valueOf(json.getString(TIPO)),
                        titulo = json.optString(TITULO).ifEmpty { null },
                        antes = json.optJSONObject(ANTES)?.let { aMapa(it) },
                        despues = json.optJSONObject(DESPUES)?.let { aMapa(it) },
                        momento = json.getLong(MOMENTO),
                        vista = nuevaVista()
                    )
                )
            }
        } catch (e: JSONException) {
            entradas.clear()
            lineas.removeAllViews()
        } catch (e: IllegalArgumentException) {
            entradas.clear()
            lineas.removeAllViews()
        }
    }

    private fun aMapa(json: JSONObject) =
        json.keys().asSequence().associateWith { json.getString(it) }

    // ----- Tamaño -----

    // Arrastrar la barra hacia arriba agranda el historial a costa de la lista de
    // parámetros, que siempre conserva un mínimo para que no desaparezca.
    @SuppressLint("ClickableViewAccessibility")
    private fun permitirAgrandar() {
        var altoInicial = 0
        var yInicial = 0f
        var altoMaximo = 0

        barra.setOnTouchListener { _, evento ->
            when (evento.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    altoInicial = panel.height
                    // Se usa la posición en pantalla y no la relativa a la barra, porque la
                    // barra se mueve mientras la arrastrás y el alto temblaría.
                    yInicial = evento.rawY
                    altoMaximo = maxOf(
                        altoInicial + cedible.height - dp(MINIMO_CEDIBLE_DP),
                        dp(ALTO_MINIMO_DP)
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val alto = altoInicial - (evento.rawY - yInicial).toInt()
                    panel.layoutParams.height = alto.coerceIn(dp(ALTO_MINIMO_DP), altoMaximo)
                    panel.requestLayout()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    preferencias.edit().putInt(CLAVE_ALTO, panel.layoutParams.height).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun ahora() = System.currentTimeMillis()

    private fun color(id: Int) = ContextCompat.getColor(contexto, id)

    private fun dp(valor: Int) = (valor * contexto.resources.displayMetrics.density).toInt()

    private companion object {
        const val CLAVE_ALTO = "alto_historial"
        const val CLAVE_HISTORIAL = "historial"
        const val TIPO = "tipo"
        const val TITULO = "titulo"
        const val ANTES = "antes"
        const val DESPUES = "despues"
        const val MOMENTO = "momento"
        const val JUNTAR_MS = 3000L
        const val MAXIMO = 300
        const val ALTO_INICIAL_DP = 160
        const val ALTO_MINIMO_DP = 72
        const val MINIMO_CEDIBLE_DP = 64
    }
}
