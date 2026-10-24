// ==========================================
// SEGUIDOR DE LÍNEA - RENGO
// CÓDIGO BASE GANADOR (SINTONIZABLE POR BLUETOOTH)
// ==========================================

#include <SoftwareSerial.h>
#include <EEPROM.h>

// --- PINES DE MOTORES (driver con dos pines de dirección por motor y habilitación) ---
const int PIN_MOTOR_IZQ_PWM = 3;
const int PIN_MOTOR_IZQ_B = 4;
const int PIN_MOTOR_IZQ_A = 5;
const int PIN_STBY = 6;          // en bajo el driver queda deshabilitado y no mueve nada
const int PIN_MOTOR_DER_A = 7;
const int PIN_MOTOR_DER_B = 8;
const int PIN_MOTOR_DER_PWM = 9;

// --- INVERSIÓN FÍSICA DE MOTORES ---
// No son constantes porque se invierten desde la app (comandos INVI/INVD) y se guardan en
// la EEPROM: así se corrige un motor cableado al revés sin recompilar.
bool invertirMotorIzq = false;
bool invertirMotorDer = false;

// --- MEJORAS QUE PERMITE EL DRIVER NUEVO (TB6612) ---
// Las dos cambian cómo responde el robot, así que se prenden y apagan desde la app
// (comandos FREN y PWMF) para poder comparar vueltas en la pista sin recompilar.
// Ojo: con cualquiera de las dos prendida hay que volver a sintonizar Kp y Kd.
bool frenoActivo = false;   // la rueda de adentro frena en vez de ir para atrás
bool pwmRapido = false;     // PWM de 31 kHz en lugar de los 490 Hz de fábrica

// --- PINES DE SENSORES ---
const int SENSOR_1 = A7;  // Extremo Izquierdo (-50 mm)
const int SENSOR_2 = A6;  // Medio Izquierdo   (-30 mm)
const int SENSOR_3 = A5;  // Centro Izquierdo  (-10 mm)
const int SENSOR_4 = A4;  // Centro Derecho    (+10 mm)
const int SENSOR_5 = A3;  // Medio Derecho     (+30 mm)
const int SENSOR_6 = A2;  // Extremo Derecho   (+50 mm)

// --- PERIFÉRICOS ---
const int PIN_LED = A1;   // el diagrama decía A0, pero en la placa quedó en A1
// Movido del 13 al 10: el 13 tiene el LED de la placa colgado con su resistencia a masa,
// que tira la tensión para abajo, así que con INPUT_PULLUP el pin se lee en bajo siempre
// (botón apretado) aunque no haya nadie tocándolo.
const int PIN_BOTON = 10;

// --- MÓDULO BLUETOOTH (HC-05 / HC-06 ZS-040) ---
const int PIN_BT_RX = 12;  // va al TXD del módulo
const int PIN_BT_TX = 11;  // va al RXD del módulo (con la resistencia)
SoftwareSerial BT(PIN_BT_RX, PIN_BT_TX);

// --- UMBRAL DE CORTE DIGITAL ---
const int UMBRAL = 500;

// --- PARÁMETROS DE VELOCIDAD Y PID ---
int velocidadBase = 135;          // Velocidad en recta (ir subiendo de a +5)
int velocidadCurva = 40;           // Piso mínimo de velocidad en curva
int velocidadMaxima = 255;

float FACTOR_FRENO_CURVA = 3.5;   // Multiplicador de desaceleración por error

float Kp = 2.4;                   // Ganancia Proporcional
float Ki = 0.0;                   // Ganancia Integral
float Kd = 3.0;                   // Ganancia Derivativa

int REVERSA_MAXIMA = -80;          // Reversa máxima permitida en pivote

int error = 0;
int ultimoError = 0;
float integral = 0;
int vBaseActual = 40;

// --- ESTADO DEL ROBOT ---
bool robotActivo = false;
int ultimoEstadoBoton = HIGH;

// --- BUFFER DE COMANDOS ---
char bufferBT[24];
byte largoBuffer = 0;

// --- MEMORIA PERMANENTE ---
// Cambiada de 0xA5C3 a 0xA5C4 el 28/09/2026: la EEPROM había quedado con todo en 0 (se
// grabó desde la app mientras el robot no podía contestar y ella mostraba 0). Al no
// reconocer la firma vieja, el robot ignora eso y arranca con los valores de acá abajo.
// Cambiada de 0xA5C4 a 0xA5C5 el 23/10/2026: la struct creció con la inversión de motores,
// así que los datos viejos ya no coinciden campo por campo. Con la firma nueva se descartan
// en vez de leerse corridos y dar valores sin sentido.
// Cambiada de 0xA5C5 a 0xA5C6 el 24/10/2026: la struct volvió a crecer, ahora con el
// freno activo y el PWM rápido.
const uint16_t FIRMA_EEPROM = 0xA5C6;
const int DIR_EEPROM = 0;

struct Ajustes {
  uint16_t firma;
  int velocidadBase;
  int velocidadMaxima;
  float Kp;
  float Ki;
  float Kd;
  bool invertirMotorIzq;
  bool invertirMotorDer;
  bool frenoActivo;
  bool pwmRapido;
};

void setup() {
  pinMode(PIN_MOTOR_IZQ_PWM, OUTPUT);
  pinMode(PIN_MOTOR_IZQ_A, OUTPUT);
  pinMode(PIN_MOTOR_IZQ_B, OUTPUT);
  pinMode(PIN_MOTOR_DER_PWM, OUTPUT);
  pinMode(PIN_MOTOR_DER_A, OUTPUT);
  pinMode(PIN_MOTOR_DER_B, OUTPUT);
  pinMode(PIN_STBY, OUTPUT);
  pinMode(PIN_LED, OUTPUT);

  pinMode(PIN_BOTON, INPUT_PULLUP);
  // Arranca con lo que el pin tenga de verdad: si ya estuviera en bajo al encender, el
  // programa lo tomaria como un apreton y el robot largaria solo.
  ultimoEstadoBoton = digitalRead(PIN_BOTON);

  apagarMotores();
  digitalWrite(PIN_LED, LOW);

  cargarAjustes();
  aplicarFrecuenciaPwm();   // después de cargar: la frecuencia también viene de la EEPROM

  BT.begin(9600);
  BT.println(F("RENGO LISTO"));
  enviarValores();
}

// ==========================================
// 💾 MEMORIA PERMANENTE (EEPROM)
// ==========================================

void cargarAjustes() {
  Ajustes a;
  EEPROM.get(DIR_EEPROM, a);

  if (a.firma != FIRMA_EEPROM) return;   // EEPROM sin datos: quedan los valores del código

  velocidadBase = a.velocidadBase;
  velocidadMaxima = a.velocidadMaxima;
  Kp = a.Kp;
  Ki = a.Ki;
  Kd = a.Kd;
  invertirMotorIzq = a.invertirMotorIzq;
  invertirMotorDer = a.invertirMotorDer;
  frenoActivo = a.frenoActivo;
  pwmRapido = a.pwmRapido;
}

void guardarAjustes() {
  Ajustes a = { FIRMA_EEPROM, velocidadBase, velocidadMaxima, Kp, Ki, Kd,
                invertirMotorIzq, invertirMotorDer, frenoActivo, pwmRapido };
  EEPROM.put(DIR_EEPROM, a);
}

// ==========================================
// 📱 COMANDOS POR BLUETOOTH
// ==========================================

void enviarValores() {
  BT.print(F("VEL="));   BT.print(velocidadBase);
  BT.print(F(" VMAX=")); BT.print(velocidadMaxima);
  BT.print(F(" KP="));   BT.print(Kp, 3);
  BT.print(F(" KI="));   BT.print(Ki, 4);
  BT.print(F(" KD="));   BT.print(Kd, 3);
  BT.print(F(" EST="));  BT.print(robotActivo ? F("RUN") : F("STOP"));
  BT.print(F(" INVI=")); BT.print(invertirMotorIzq ? 1 : 0);
  BT.print(F(" INVD=")); BT.print(invertirMotorDer ? 1 : 0);
  BT.print(F(" FREN=")); BT.print(frenoActivo ? 1 : 0);
  BT.print(F(" PWMF=")); BT.println(pwmRapido ? 1 : 0);
}

void ejecutarComando(char *txt) {
  char clave[8];
  byte n = 0;
  byte i = 0;

  while (txt[i] == ' ') i++;
  while (isAlpha(txt[i]) && n < sizeof(clave) - 1) {
    clave[n++] = toupper(txt[i++]);
  }
  clave[n] = '\0';

  while (txt[i] == ' ' || txt[i] == '=' || txt[i] == ':') i++;
  bool hayValor = (txt[i] != '\0');
  float valor = atof(&txt[i]);

  // --- Acciones sin valor ---
  if (!strcmp(clave, "SAVE") || !strcmp(clave, "S")) {
    guardarAjustes();
    BT.println(F("OK GUARDADO"));
    return;
  }
  if (!strcmp(clave, "GET") || !strcmp(clave, "G")) {
    enviarValores();
    return;
  }
  if (!strcmp(clave, "LOAD")) {
    cargarAjustes();
    BT.println(F("OK CARGADO"));
    enviarValores();
    return;
  }
  if (!strcmp(clave, "PING")) {
    BT.println(F("PONG"));
    return;
  }
  if (!strcmp(clave, "START")) {
    arrancar();
    BT.println(F("OK RUN"));
    return;
  }
  if (!strcmp(clave, "STOP") || !strcmp(clave, "X")) {
    robotActivo = false;
    apagarMotores();
    digitalWrite(PIN_LED, LOW);
    BT.println(F("OK STOP"));
    return;
  }

  if (!hayValor) {
    BT.println(F("ERR COMANDO"));
    return;
  }

  // --- Inversión de motores ---
  // Se graban en el acto y no con SAVE: se cambian una vez cada tanto, al armar la placa,
  // así que no hay riesgo de gastar la EEPROM y la inversión sobrevive al apagado.
  if (!strcmp(clave, "INVI") || !strcmp(clave, "INVD")) {
    bool invertido = (valor != 0);
    if (clave[3] == 'I') {
      invertirMotorIzq = invertido;
    } else {
      invertirMotorDer = invertido;
    }
    guardarAjustes();

    BT.print(F("OK "));
    BT.print(clave);
    BT.print('=');
    BT.println(invertido ? 1 : 0);
    return;
  }

  // --- Mejoras del driver nuevo ---
  // Como la inversión, se graban en el acto: así se puede probar una vuelta con el freno
  // activo, apagar el robot, volver a encenderlo y seguir probando lo mismo.
  if (!strcmp(clave, "FREN") || !strcmp(clave, "PWMF")) {
    bool activo = (valor != 0);
    if (clave[0] == 'F') {
      frenoActivo = activo;
    } else {
      pwmRapido = activo;
      aplicarFrecuenciaPwm();
    }
    guardarAjustes();

    BT.print(F("OK "));
    BT.print(clave);
    BT.print('=');
    BT.println(activo ? 1 : 0);
    return;
  }

  // --- Asignación de parámetros ---
  if (!strcmp(clave, "VEL") || !strcmp(clave, "V")) {
    velocidadBase = constrain((int)valor, 0, 255);
  } else if (!strcmp(clave, "VMAX") || !strcmp(clave, "M")) {
    velocidadMaxima = constrain((int)valor, 0, 255);
  } else if (!strcmp(clave, "KP") || !strcmp(clave, "P")) {
    Kp = constrain(valor, 0.0, 50.0);
  } else if (!strcmp(clave, "KI") || !strcmp(clave, "I")) {
    Ki = constrain(valor, 0.0, 50.0);
    integral = 0;
  } else if (!strcmp(clave, "KD") || !strcmp(clave, "D")) {
    Kd = constrain(valor, 0.0, 50.0);
  } else {
    BT.println(F("ERR COMANDO"));
    return;
  }

  BT.print(F("OK "));
  BT.print(clave);
  BT.print('=');
  BT.println(valor, 4);
}

void leerBluetooth() {
  while (BT.available()) {
    char c = BT.read();

    if (c == '\n' || c == '\r' || c == ';') {
      if (largoBuffer > 0) {
        bufferBT[largoBuffer] = '\0';
        largoBuffer = 0;
        ejecutarComando(bufferBT);
      }
    } else if (largoBuffer < sizeof(bufferBT) - 1) {
      bufferBT[largoBuffer++] = c;
    }
  }
}

// ==========================================
// ⚙️ CONTROL DE MOTORES
// ==========================================

// Con este driver, el sentido lo deciden dos pines por motor (A y B) y el PWM es directo:
// 0 es quieto y 255 es a fondo. En el driver anterior, para ir hacia atrás había que
// mandar 255 - velocidad, que confundía bastante.
void moverMotor(int pinA, int pinB, int pinPWM, int vel, bool invertido) {
  // Con el freno activo, a la rueda de adentro se le pide frenar en vez de girar para atrás:
  // los dos pines de sentido en alto cortocircuitan el motor y la rueda se planta. Es el
  // freno del TB6612, que el puente viejo no tenía. Frena igual para los dos lados, así que
  // no hace falta mirar si el motor está invertido.
  if (frenoActivo && vel <= 0) {
    digitalWrite(pinA, HIGH);
    digitalWrite(pinB, HIGH);
    analogWrite(pinPWM, 255);
    return;
  }

  bool adelante = vel >= 0;
  vel = constrain(abs(vel), 0, velocidadMaxima);
  if (invertido) adelante = !adelante;

  digitalWrite(pinA, adelante ? HIGH : LOW);
  digitalWrite(pinB, adelante ? LOW : HIGH);
  analogWrite(pinPWM, vel);
}

// El PWM de fábrica del Nano va a unos 490 Hz: lo bastante lento como para que el motor lo
// escuche (el chillido agudo andando despacio) y como para que a poca velocidad avance a
// tirones, porque entre pulso y pulso llega a frenarse. A 31 kHz los pulsos van tan seguidos
// que el motor recibe algo parecido a una tensión continua: queda más suave y con más fuerza
// abajo. El Timer1 maneja el pin 9 y el Timer2 el pin 3; al Timer0 no se lo toca porque de él
// dependen millis() y delay(), que el resto del programa usa para medir el tiempo.
void aplicarFrecuenciaPwm() {
  if (pwmRapido) {
    TCCR1B = (TCCR1B & 0b11111000) | 0x01;   // divisor 1 -> 31372 Hz
    TCCR2B = (TCCR2B & 0b11111000) | 0x01;   // divisor 1 -> 31372 Hz
  } else {
    TCCR1B = (TCCR1B & 0b11111000) | 0x03;   // divisor 64, lo de fábrica
    TCCR2B = (TCCR2B & 0b11111000) | 0x04;   // divisor 64 (en el Timer2 el código es otro)
  }
}

void moverMotorIzq(int vel) {
  moverMotor(PIN_MOTOR_IZQ_A, PIN_MOTOR_IZQ_B, PIN_MOTOR_IZQ_PWM, vel, invertirMotorIzq);
}

void moverMotorDer(int vel) {
  moverMotor(PIN_MOTOR_DER_A, PIN_MOTOR_DER_B, PIN_MOTOR_DER_PWM, vel, invertirMotorDer);
}

// Además de soltar los motores (los dos pines de sentido en bajo, sin frenarlos de golpe),
// baja el STBY: con el driver deshabilitado las ruedas no pueden girar ni por un error.
void apagarMotores() {
  digitalWrite(PIN_MOTOR_IZQ_A, LOW);
  digitalWrite(PIN_MOTOR_IZQ_B, LOW);
  analogWrite(PIN_MOTOR_IZQ_PWM, 0);
  digitalWrite(PIN_MOTOR_DER_A, LOW);
  digitalWrite(PIN_MOTOR_DER_B, LOW);
  analogWrite(PIN_MOTOR_DER_PWM, 0);
  digitalWrite(PIN_STBY, LOW);
}

void arrancar() {
  robotActivo = true;
  ultimoError = 0;
  integral = 0;
  vBaseActual = velocidadCurva;
  digitalWrite(PIN_STBY, HIGH);   // habilita el driver: recién ahora las ruedas pueden girar
}

// ==========================================
// 🔄 BUCLE PRINCIPAL
// ==========================================

void loop() {

  // 0. COMANDOS DEL CELULAR
  leerBluetooth();

  // 1. BOTÓN START/STOP
  int lecturaBoton = digitalRead(PIN_BOTON);
  if (ultimoEstadoBoton == HIGH && lecturaBoton == LOW) {
    delay(50);
    if (digitalRead(PIN_BOTON) == LOW) {
      if (robotActivo) {
        robotActivo = false;
        apagarMotores();
        digitalWrite(PIN_LED, LOW);
      } else {
        arrancar();
      }
    }
  }
  ultimoEstadoBoton = lecturaBoton;

  // 2. CONTROL PID
  if (robotActivo) {
    digitalWrite(PIN_LED, HIGH);

    bool s1 = analogRead(SENSOR_1) > UMBRAL;
    bool s2 = analogRead(SENSOR_2) > UMBRAL;
    bool s3 = analogRead(SENSOR_3) > UMBRAL;
    bool s4 = analogRead(SENSOR_4) > UMBRAL;
    bool s5 = analogRead(SENSOR_5) > UMBRAL;
    bool s6 = analogRead(SENSOR_6) > UMBRAL;

    // --- TABLA DE ERROR ---
    if (s3 && s4)       { error = 0; }
    else if (s3)        { error = -10; }
    else if (s2 && s3)  { error = -20; }
    else if (s2)        { error = -30; }
    else if (s1 && s2)  { error = -40; }
    else if (s1)        { error = -50; }
    else if (s4)        { error = 10; }
    else if (s4 && s5)  { error = 20; }
    else if (s5)        { error = 30; }
    else if (s5 && s6)  { error = 40; }
    else if (s6)        { error = 50; }
    else {
      error = (ultimoError < 0) ? -60 : 60;
    }

    // --- CÁLCULO PID ---
    int P = error;
    int D = error - ultimoError;

    D = constrain(D, -30, 30);

    if (error != -60 && error != 60) {
      ultimoError = error;
    }

    // El tope del acumulador evita que la integral sature la salida
    if (Ki > 0) {
      integral += error;
      float tope = 255.0 / Ki;
      integral = constrain(integral, -tope, tope);
    } else {
      integral = 0;
    }

    int ajuste = (Kp * P) + (Ki * integral) + (Kd * D);

    // --- FRENO PROPORCIONAL DINÁMICO ---
    int vDeseada = velocidadBase - (abs(error) * FACTOR_FRENO_CURVA);
    if (vDeseada < velocidadCurva) {
      vDeseada = velocidadCurva;
    }

    // RAMPA DE ACELERACIÓN EN RECTA
    if (vDeseada < vBaseActual) {
      vBaseActual = vDeseada;           // Freno instantáneo al detectar desvío
    } else {
      vBaseActual += 2;                 // Aceleración gradual
      if (vBaseActual > velocidadBase) {
        vBaseActual = velocidadBase;
      }
    }

    int velIzq = vBaseActual + ajuste;
    int velDer = vBaseActual - ajuste;

    velIzq = constrain(velIzq, REVERSA_MAXIMA, velocidadMaxima);
    velDer = constrain(velDer, REVERSA_MAXIMA, velocidadMaxima);

    moverMotorIzq(velIzq);
    moverMotorDer(velDer);

  } else {
    apagarMotores();
    digitalWrite(PIN_LED, LOW);
  }
}
