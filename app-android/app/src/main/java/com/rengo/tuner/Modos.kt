package com.rengo.tuner

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// Los modos viven en el celular: la EEPROM del robot guarda solo la configuración
// activa. Los valores se guardan ya formateados como los espera el Arduino, así
// comparar dos modos es comparar textos y no hay sorpresas con los decimales.
class Modos(private val preferencias: SharedPreferences) {

    fun nombres(): List<String> {
        val lista = leer()
        return (0 until lista.length()).map { lista.getJSONObject(it).getString(NOMBRE) }
    }

    fun valores(nombre: String): Map<String, String>? {
        val lista = leer()
        val indice = indiceDe(lista, nombre)
        if (indice < 0) return null

        val valores = lista.getJSONObject(indice).getJSONObject(VALORES)
        return valores.keys().asSequence().associateWith { valores.getString(it) }
    }

    fun guardar(nombre: String, valores: Map<String, String>) {
        val lista = leer()
        val modo = JSONObject()
            .put(NOMBRE, nombre)
            .put(VALORES, JSONObject(valores.toMap<String, Any>()))

        val indice = indiceDe(lista, nombre)
        if (indice >= 0) lista.put(indice, modo) else lista.put(modo)
        escribir(lista)
    }

    fun borrar(nombre: String) {
        val lista = leer()
        val indice = indiceDe(lista, nombre)
        if (indice < 0) return

        lista.remove(indice)
        escribir(lista)
    }

    fun buscarPorValores(actuales: Map<String, String>) = nombres().find { valores(it) == actuales }

    private fun indiceDe(lista: JSONArray, nombre: String): Int {
        for (i in 0 until lista.length()) {
            if (lista.getJSONObject(i).getString(NOMBRE) == nombre) return i
        }
        return -1
    }

    private fun leer(): JSONArray {
        val texto = preferencias.getString(CLAVE, null) ?: return JSONArray()
        return JSONArray(texto)
    }

    private fun escribir(lista: JSONArray) {
        preferencias.edit().putString(CLAVE, lista.toString()).apply()
    }

    private companion object {
        const val CLAVE = "modos"
        const val NOMBRE = "nombre"
        const val VALORES = "valores"
    }
}
