package com.rengo.tuner

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

// El botón de acción se pinta según lo que hace: rojo si borra, amarillo si vuelve o
// sobrescribe, y el celeste del club para el resto.
enum class Tono(val fondo: Int, val tinta: Int) {
    MARCA(R.drawable.boton_marca, R.color.marca_tinta),
    ALERTA(R.drawable.boton_alerta, R.color.alerta_tinta),
    STOP(R.drawable.boton_stop, R.color.stop_tinta)
}

fun Activity.confirmar(
    titulo: String,
    mensaje: String,
    accion: String,
    tono: Tono,
    detalle: List<String> = emptyList(),
    alConfirmar: () -> Unit
) {
    val dialogo = AppCompatDialog(this, R.style.Rengo_Dialogo)
    val vista = layoutInflater.inflate(R.layout.dialogo, null)
    val tarjeta = vista.findViewById<View>(R.id.tarjetaDialogo)

    vista.findViewById<TextView>(R.id.tituloDialogo).text = titulo
    vista.findViewById<TextView>(R.id.mensajeDialogo).text = mensaje

    if (detalle.isNotEmpty()) {
        val caja = vista.findViewById<LinearLayout>(R.id.detalleDialogo)
        caja.visibility = View.VISIBLE
        for (linea in detalle) {
            val texto = TextView(this)
            texto.text = linea
            texto.textSize = 11f
            texto.typeface = ResourcesCompat.getFont(this, R.font.jetbrains_mono_medium)
            texto.setTextColor(ContextCompat.getColor(this, R.color.tinta))
            caja.addView(texto)
        }
    }

    val boton = vista.findViewById<TextView>(R.id.accionDialogo)
    boton.text = accion
    boton.setBackgroundResource(tono.fondo)
    boton.setTextColor(ContextCompat.getColor(this, tono.tinta))
    boton.setOnClickListener {
        dialogo.dismiss()
        alConfirmar()
    }
    vista.findViewById<View>(R.id.cancelarDialogo).setOnClickListener { dialogo.dismiss() }

    // La ventana ocupa todo el ancho para respetar los 24dp de margen del diseño; tocar
    // esos costados también tiene que cerrar, como tocar arriba o abajo.
    vista.setOnClickListener { dialogo.dismiss() }
    tarjeta.isClickable = true

    dialogo.setContentView(vista)
    dialogo.setCanceledOnTouchOutside(true)
    dialogo.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    dialogo.setOnShowListener { subir(tarjeta, DURACION_DIALOGO) }
    dialogo.show()
}

fun Activity.abrirHoja(layout: Int, armar: (vista: View, hoja: BottomSheetDialog) -> Unit): BottomSheetDialog {
    val hoja = BottomSheetDialog(this)
    val vista = layoutInflater.inflate(layout, null)
    armar(vista, hoja)
    hoja.setContentView(vista)

    // Siempre abierta del todo: la mitad de una hoja de escaneo no sirve para nada.
    hoja.behavior.skipCollapsed = true
    hoja.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    hoja.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    hoja.setOnShowListener { subir(vista, DURACION_HOJA) }
    hoja.show()
    return hoja
}

// Entrada de hojas y diálogos: suben 16dp mientras aparecen. El fundido del velo lo hace
// la animación de ventana del tema.
private fun subir(vista: View, duracion: Long) {
    vista.translationY = 16f * vista.resources.displayMetrics.density
    vista.alpha = 0f
    vista.animate()
        .translationY(0f)
        .alpha(1f)
        .setDuration(duracion)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

private const val DURACION_DIALOGO = 180L
private const val DURACION_HOJA = 220L
