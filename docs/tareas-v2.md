# Rengo v2 — tareas

Implementación del handoff `design_handoff_rengo_v2`. Cada historia dice qué archivos toca,
para que varias se puedan hacer a la vez sin pisarse. El contrato de nombres (ids, clases,
funciones) está fijado acá: si una historia lo cambia, rompe a las demás.

## Contrato compartido

Ids nuevos en `activity_main.xml`:

| id | qué es |
|---|---|
| `puntoLogo` | punto de estado pegado al logo |
| `textoEstado` | texto del estado en la cabecera |
| `nombreModulo` | "BT05" al lado del estado |
| `senal1` `senal2` `senal3` | barritas de señal |
| `vistaPerdida` | pantalla de conexión perdida |
| `nombrePerdida` | párrafo con el nombre del módulo |
| `cajaAndando` | recuadro "estaba andando" |
| `botonBuscarDeNuevo` `botonReconectar` | botones de esa pantalla |
| `bandaSilencio` | aviso de silencio sobre el contenido |
| `botonReintentarBanda` | su botón |
| `filaCrono` `puntoCrono` `textoCrono` | cronómetro |
| `pestanaMotores` `pestanaBotonMotores` | cuarta pestaña |
| `ruedaIzq` `flechaIzq` `estadoIzq` `normalIzq` `invertidoIzq` | columna del motor izquierdo |
| `ruedaDer` `flechaDer` `estadoDer` `normalDer` `invertidoDer` | columna del motor derecho |

Clases nuevas en `com.rengo.tuner`, sin dependencias de Android para poder testearlas:

```kotlin
class Cronometro(private val reloj: () -> Long = SystemClock::elapsedRealtime)
object RespuestaRobot
```

---

## H1 — Firmware: invertir motores y latido
**Como** quien sintoniza el robot, **quiero** invertir el sentido de cada motor desde la app
y que la app sepa si el robot sigue vivo, **para** no tener que recompilar ni adivinar.

- `GET` responde además `INVI=0|1 INVD=0|1`.
- `INVI=0|1` e `INVD=0|1` invierten ese motor al instante y lo graban en la EEPROM.
- `PING` responde `PONG`.
- La inversión se guarda junto al resto de los ajustes, con la firma ya existente.

Archivos: `rengo.ino`.
Tests: no hay automáticos (es hardware). Verificación en el robot: invertir desde la app,
apagar y prender, y confirmar que la inversión sobrevive.

## H2 — Cronómetro
**Como** quien prueba en pista, **quiero** ver cuánto duró la corrida, **para** comparar
sintonías sin cronómetro aparte.

- `Cronometro` con reloj inyectable: `arrancar()`, `parar(): Long?`, `transcurrido(): Long`,
  `corriendo: Boolean`, `ultima: Long?`.
- `formatoPantalla(ms): Pantalla` devuelve el texto `MM:SS.d` partido en tramo apagado,
  tramo vivo y décima.
- `formatoHistorial(ms): String` devuelve `12.4 s` o `1:05.3`.
- Minutos topeados en 99.

Archivos: `Cronometro.kt` (nuevo), `src/test/java/com/rengo/tuner/CronometroTest.kt` (nuevo),
`app/build.gradle.kts` (dependencia de JUnit).
Tests: 0 → `00:00.0` todo apagado; 12,4 s → apagado `00:`, vivo `12`, décima `.4`;
65,3 s → apagado `0`, vivo `1:05`, décima `.3`; 10 min → nada apagado; tope de 99 minutos;
formato de historial en los dos casos.

## H3 — Lectura de la respuesta del robot
**Como** desarrollador, **quiero** interpretar lo que manda el robot en un solo lugar
testeable, **para** no tener ese parseo mezclado con la pantalla.

- `RespuestaRobot.leer(linea): Lectura?` con `valores: Map<String,String>`, `andando: Boolean?`,
  `invertidoIzq: Boolean?`, `invertidoDer: Boolean?`.
- `esConfirmacion(linea)` y `esPong(linea)`.
- Tolera claves desconocidas, espacios de más y líneas incompletas.

Archivos: `RespuestaRobot.kt` (nuevo), `src/test/java/com/rengo/tuner/RespuestaRobotTest.kt`.
Tests: línea completa con `EST=RUN INVI=1 INVD=0`; línea vieja sin `INVI`/`INVD` (quedan
`null`); `EST=STOP`; línea sin `VEL=` devuelve `null`; `OK KP=0.5000` es confirmación;
`PONG` es pong y no confirmación.

## H4 — Historial con tiempo
**Como** quien revisa el historial, **quiero** ver cuánto duró cada corrida, **para**
encontrar la vuelta buena.

- Tipo nuevo `TIEMPO` con color `terminal_texto`.
- El tipo se guarda y se vuelve a leer sin romper historiales viejos.

Archivos: `Historial.kt`.
Tests: no hay automáticos (depende de vistas). Se verifica en el robot.

## H5 — Colores y formas nuevas
**Como** desarrollador, **quiero** los recursos gráficos que piden las pantallas nuevas.

- Colores: `chrome_alerta` #e3b341, `chrome_stop` #ff8a80, `barrita_apagada` #40FFFFFF,
  iguales en los dos temas.
- Formas: `punto_logo`, `banda_silencio`, `aviso_perdida`, `rueda`, `rueda_invertida`,
  `chasis_punteado`, `segmento_pista`, `segmento_normal`, `segmento_invertido`,
  `boton_crono`(no), según el handoff.

Archivos: `res/values/colors.xml`, `res/values-night/colors.xml`, `res/drawable/*.xml` nuevos.
Tests: no hay automáticos. Se verifica compilando y mirando la app.

## H6 — Pantallas nuevas en el layout
**Como** usuario, **quiero** la cabecera compacta, el cronómetro, la pantalla de conexión
perdida, la banda de silencio y la pestaña Motores.

- Cabecera de una fila con logo, punto de estado, RENGO, estado, módulo, barritas, tema y
  conexión. Se van el subtítulo y la segunda fila.
- Barra inferior vertical: fila del cronómetro y debajo el botón de marcha.
- `vistaPerdida`, `bandaSilencio`, `pestanaMotores` y el cuarto botón de pestaña.
- Sintonía compactada y `scrollbars="none"`.

Archivos: `res/layout/activity_main.xml`, `res/layout/fila_parametro.xml`.
Tests: no hay automáticos. Se verifica que compile y entre sin scroll en 412×892.

## H7 — Lógica en MainActivity
**Como** usuario, **quiero** que todo eso funcione: estados, reintentos, latido, cronómetro,
conexión perdida y motores.

Depende de H2 a H6. No es paralelizable: toca un solo archivo grande y es donde se integra
todo. La hago yo después de que entren las demás.

Archivos: `MainActivity.kt`.
Tests: verificación en el robot, con el detalle del handoff.
