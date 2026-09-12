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

Se guardan en la EEPROM con el botón **Guardar**, así que sobreviven al apagado.
Al encender, el robot carga lo último guardado; si nunca guardaste, usa los valores del código.

## Cableado del módulo Bluetooth (HC-05 / HC-06 ZS-040)

| Módulo | Arduino Nano |
|---|---|
| VCC | 5V |
| GND | GND |
| TXD | pin 10 |
| RXD | pin 11 **con divisor de tensión** |
| STATE / EN | sin conectar |

El divisor de tensión son dos resistencias: **1kΩ** entre el pin 11 y el RXD, y **2kΩ** entre
el RXD y GND. El módulo trabaja a 3.3V (lo dice impreso en la plaquita) y el Nano saca 5V,
así que sin el divisor se le acorta la vida al módulo.

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

1. Abrir la carpeta `app-android` con Android Studio y esperar el *Gradle sync*.
2. Emparejar el módulo desde los ajustes de Bluetooth de Android (PIN **1234** o **0000**).
3. Instalar la app en el celular, abrirla, **Conectar** y elegir el módulo de la lista.

La app lee los valores actuales del robot al conectarse. Cada `+` / `−` manda el cambio
al instante, **Aplicar** manda los cinco juntos y **Guardar** los manda y los graba en la EEPROM.

## Comandos por Bluetooth

Si querés probar sin la app, con cualquier terminal Bluetooth (9600 baudios):

```
VEL=135      VMAX=255      KP=2.4      KI=0.01      KD=3.0
SAVE         GET           START       STOP         LOAD
```

Acepta `VEL=135`, `vel 135` o `V135`. Contesta `OK ...` o `ERR COMANDO`.

## Pendiente / a tener en cuenta

- En la tabla de error, las condiciones `s2 && s3` y `s4 && s5` nunca se ejecutan porque los
  `else if (s3)` y `else if (s4)` van antes y las tapan. O sea, los errores ±20 y ±40 no ocurren
  nunca y el error salta de ±10 a ±30. Queda así a propósito por ahora: arreglarlo obliga a
  re-sintonizar Kp y Kd.
