# Rengo — Seguidor de línea

Robot seguidor de línea con Arduino Nano, sintonizable desde el celular por Bluetooth.

- [`rengo.ino`](rengo.ino) — firmware del Arduino Nano
- [`app-android/`](app-android) — app Android para ajustar los parámetros en pista

## Parámetros ajustables desde el celular

| Parámetro | Comando | Qué es |
|---|---|---|
| Velocidad base | `VEL` | velocidad en recta |
| Velocidad máxima | `VMAX` | techo de PWM |
| Kp | `KP` | corrección proporcional al error |
| Ki | `KI` | corrección acumulada (arranca en 0) |
| Kd | `KD` | freno ante cambios bruscos de error |

Se guardan en la EEPROM con el botón **Grabar en el robot**, así que sobreviven al apagado.
Al encender, el robot carga lo último guardado; si nunca guardaste, usa los valores del código.

## El módulo Bluetooth

Es un **BLE** (Bluetooth Low Energy), de la familia HM-10 / CC41-A, montado en una plaquita
ZS-040. Se anuncia con el nombre **BT05** y expone el servicio `FFE0` con la característica
`FFE1`, que se puede escribir y notificar: por ahí pasan los comandos y las respuestas.

Que sea BLE y no Bluetooth Classic tiene una consecuencia práctica: **no se empareja**. No lo
busques en los ajustes de Android, no va a funcionar — pide un PIN y falla con cualquiera. La
app se conecta directo por GATT. La plaquita ZS-040 se usa tanto para módulos BLE como para
HC-05 Classic, así que por la serigrafía no se distinguen.

### Cableado

| Módulo | Arduino Nano |
|---|---|
| VCC | 5V |
| GND | GND |
| TXD | pin 10 |
| RXD | pin 11 **con resistencia** |
| STATE / EN | sin conectar |

El módulo trabaja a 3.3V y el Nano saca 5V. El detalle que importa: el estado de reposo de un
UART es HIGH, así que el pin 11 queda a 5V **de forma permanente**, no solo mientras transmite.
Sin nada que limite la corriente, los diodos de protección del módulo conducen todo el tiempo y
el módulo se degrada de a poco.

Dos formas de resolverlo, de peor a mejor:

- **Una resistencia en serie** de 1kΩ a 10kΩ entre el pin 11 y el RXD. No baja el voltaje, pero
  limita la corriente a ~1 mA, que el módulo tolera sin problema.
- **Divisor de tensión**: dos resistencias iguales cualesquiera, una entre el pin 11 y el RXD, y
  otra entre el RXD y GND. Da 2.5V, bastante arriba del umbral del módulo para leer un `1`.

No se usan los pines 0 y 1 a propósito: son los del USB y entran en conflicto al cargar el programa.

### Pines ocupados

| Pin | Uso |
|---|---|
| 2 | LED |
| 3, 5 | motor izquierdo (dirección, PWM) |
| 6, 7 | motor derecho (PWM, dirección) |
| 9 | botón start/stop |
| 10, 11 | Bluetooth |
| A2–A7 | sensores 6 a 1 |

## Cargar el firmware

1. Abrir `rengo.ino` en el IDE de Arduino.
2. Placa: **Arduino Nano**. Si no carga, probar procesador *ATmega328P (Old Bootloader)*.
3. **Desconectar el módulo Bluetooth no es necesario**, pero sí conviene desenchufar la
   batería de los motores mientras cargás.

## Compilar la app

Con Android Studio: abrir la carpeta `app-android` y esperar el *Gradle sync*.

Sin Android Studio, hace falta un **JDK 17** y el SDK de Android (`platforms;android-34`
y `build-tools;34.0.0`, que se bajan con el `sdkmanager` de las command line tools):

```bash
cd app-android
./gradlew assembleDebug
```

El APK queda en `app-android/app/build/outputs/apk/debug/app-debug.apk`.
Si el SDK no está en la ruta por defecto, hay que crear un `app-android/local.properties`
con `sdk.dir=` apuntando a donde esté.

Después:

1. Instalar el APK en el celular (`adb install -r ...` o pasándolo a mano).
2. Abrir la app, **Conectar** y elegir **BT05** de la lista.

No hay que emparejar nada. Al tocar Conectar la app escanea 5 segundos y muestra lo que
encuentra, ordenado por potencia de señal — el módulo, que está al lado, queda arriba de todo.

## Cómo se usa la app

Tiene tres pestañas; sin conexión, las tres muestran el cartel para conectar.

- **Sintonía**: los cinco parámetros. Cada `+` / `−` manda el cambio al instante, y un valor
  escrito a mano se manda al salir del casillero o con "Listo". Eso cambia la RAM del robot, no
  la EEPROM: se puede tocar todo lo que haga falta sin gastarla. **Grabar en el robot** manda los
  cinco valores y los graba en la EEPROM (que aguanta ~100 mil escrituras, por eso es aparte).
  La velocidad base nunca queda por encima de la máxima: el firmware recorta cada rueda al tope,
  así que una base más alta no aceleraría nada.
- **Modos**: configuraciones con nombre ("Pista rápida", "Curvas cerradas"…) guardadas **en el
  celular**, no en el robot. Tocar un modo manda sus valores al instante. Arriba de los parámetros
  se ve en qué modo estás, o "Personalizado" si tocaste algo.
- **Registro**: el historial de cambios, con fecha y hora. Tocar una línea vuelve a los valores
  de ese momento (manda solo los que difieren). Se guarda en el celular hasta 300 líneas.

**Arrancar** y **Parar** están siempre abajo. El botón de arriba a la derecha alterna tema
claro y oscuro; por defecto sigue al del celular.

Al salir y volver a la app se reengancha sola al último módulo usado, sin escanear.

"Conectado" quiere decir conectado **al módulo Bluetooth**. Que el Arduino reciba bien los
comandos solo se confirma cuando contesta: sus valores aparecen al conectar y Grabar pasa a
"El robot confirmó".

## Comandos por Bluetooth

Si querés probar sin la app, hace falta una terminal **BLE** (nRF Connect, o Serial Bluetooth
Terminal en modo BLE) apuntando a la característica `FFE1`. Una terminal Bluetooth Classic no
sirve, no va a ver el módulo:

```
VEL=135      VMAX=255      KP=2.4      KI=0.01      KD=3.0
SAVE         GET           START       STOP         LOAD
```

Acepta `VEL=135`, `vel 135` o `V135`. Contesta `OK ...` o `ERR COMANDO`.

## Para modificar la app

Hace falta lo mismo que para compilarla (ver arriba). Para probar en un celular: activar el
modo desarrollador y la depuración USB, enchufarlo y darle *Run* en Android Studio (o
`adb install -r` con el APK).

Está hecha en Kotlin con vistas clásicas de Android (layouts en XML y `findViewById`), sin
Compose ni librerías aparte de las de AndroidX y Material.

| Archivo | Qué tiene |
|---|---|
| `MainActivity.kt` | la pantalla y casi toda la lógica: parámetros, tope de velocidad, modos, pestañas, estado de la conexión, escaneo |
| `BluetoothLink.kt` | la conexión BLE con el módulo: conectar, mandar comandos con 80 ms entre uno y otro (el Arduino pierde bytes si llegan pegados) y rearmar las respuestas, que llegan de a 20 bytes |
| `Modos.kt` | los modos guardados, como JSON en `SharedPreferences` |
| `Historial.kt` | el historial: qué se anota, cómo se juntan los toques seguidos, el guardado |
| `Dialogos.kt` | los diálogos de confirmación y las hojas que suben desde abajo |
| `res/layout/` | las pantallas |
| `res/values/colors.xml` y `res/values-night/colors.xml` | colores del tema claro y del oscuro |
| `res/font/` | Raleway, Lato y JetBrains Mono, que van dentro del APK |

Algunas decisiones que conviene no deshacer sin pensarlo:

- **Nada toca la EEPROM salvo Grabar.** Ajustar valores solo cambia la RAM del robot.
- **Cambiar de tema no recrea la pantalla** (`configChanges="uiMode"` en el manifiesto): la
  rearma a mano, porque recrearla cortaría el Bluetooth.
- **Todo el proyecto está en castellano**: nombres, comentarios, textos y commits. Los
  comentarios explican *por qué* se hizo algo, no qué hace la línea.
- El diseño de referencia (colores, tamaños, tipografías) salió de un handoff con prototipo
  en HTML que no está en el repo.

Para repartir una versión nueva hay que subir `versionCode` en `app-android/app/build.gradle.kts`
(1, 2, 3…): si no sube, Android no la instala encima de la anterior.

## Pendiente / a tener en cuenta

- **Falta la resistencia en el RXD del módulo** (ver Cableado): hasta ponerla, el robot no puede
  contestar. La app no recibe sus valores (muestra todo en 0) y nada confirma que los comandos
  llegan. Ojo con **Grabar** mientras tanto: graba en la EEPROM los valores que muestra la app,
  aunque sean esos 0.
- **La app se reparte con el APK de depuración**, que está firmado con una clave que vive solo
  en la compu donde se compila. Si esa clave se pierde, las versiones nuevas no se pueden
  instalar encima: hay que desinstalar, y se pierden los modos y el historial de cada celular.
  Falta crear una clave de firma del club y guardarla con backup, fuera del repo.

- En la tabla de error, las condiciones `s2 && s3` y `s4 && s5` nunca se ejecutan porque los
  `else if (s3)` y `else if (s4)` van antes y las tapan. O sea, los errores ±20 y ±40 no ocurren
  nunca y el error salta de ±10 a ±30. Queda así a propósito por ahora: arreglarlo obliga a
  re-sintonizar Kp y Kd.
