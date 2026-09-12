// ==========================================
// SEGUIDOR DE LÍNEA - RENGO
// CÓDIGO BASE GANADOR (SINTONIZABLE POR BLUETOOTH)
// ==========================================

#include <SoftwareSerial.h>
#include <EEPROM.h>

// --- PINES DE MOTORES (Driver L298N) ---
const int PIN_MOTOR_IZQ_DIR = 3;
const int PIN_MOTOR_IZQ_PWM = 5;
const int PIN_MOTOR_DER_DIR = 7;
const int PIN_MOTOR_DER_PWM = 6;

// --- INVERSIÓN FÍSICA DE MOTORES ---
const bool INVERTIR_MOTOR_IZQ = true;
const bool INVERTIR_MOTOR_DER = false;

// --- PINES DE SENSORES ---
const int SENSOR_1 = A7;  // Extremo Izquierdo (-50 mm)
const int SENSOR_2 = A6;  // Medio Izquierdo   (-30 mm)
const int SENSOR_3 = A5;  // Centro Izquierdo  (-10 mm)
const int SENSOR_4 = A4;  // Centro Derecho    (+10 mm)
const int SENSOR_5 = A3;  // Medio Derecho     (+30 mm)
const int SENSOR_6 = A2;  // Extremo Derecho   (+50 mm)

// --- PERIFÉRICOS ---
const int PIN_LED = 2;
const int PIN_BOTON = 9;

// --- MÓDULO BLUETOOTH (HC-05 / HC-06 ZS-040) ---
const int PIN_BT_RX = 10;  // va al TXD del módulo
const int PIN_BT_TX = 11;  // va al RXD del módulo (con divisor de tensión)
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
const uint16_t FIRMA_EEPROM = 0xA5C3;
const int DIR_EEPROM = 0;

struct Ajustes {
  uint16_t firma;
  int velocidadBase;
  int velocidadMaxima;
  float Kp;
  float Ki;
  float Kd;
};

void setup() {
  pinMode(PIN_MOTOR_IZQ_DIR, OUTPUT);
  pinMode(PIN_MOTOR_IZQ_PWM, OUTPUT);
  pinMode(PIN_MOTOR_DER_DIR, OUTPUT);
  pinMode(PIN_MOTOR_DER_PWM, OUTPUT);
  pinMode(PIN_LED, OUTPUT);

  pinMode(PIN_BOTON, INPUT_PULLUP);

  apagarMotores();
  digitalWrite(PIN_LED, LOW);

  cargarAjustes();

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
}

void guardarAjustes() {
  Ajustes a = { FIRMA_EEPROM, velocidadBase, velocidadMaxima, Kp, Ki, Kd };
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
  BT.print(F(" EST="));  BT.println(robotActivo ? F("RUN") : F("STOP"));
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

void moverMotorIzq(int vel) {
  bool adelante = true;

  if (vel < 0) {
    adelante = false;
    vel = abs(vel);
  }

  if (INVERTIR_MOTOR_IZQ) adelante = !adelante;
  vel = constrain(vel, 0, velocidadMaxima);

  if (adelante) {
    digitalWrite(PIN_MOTOR_IZQ_DIR, LOW);
    analogWrite(PIN_MOTOR_IZQ_PWM, vel);
  } else {
    digitalWrite(PIN_MOTOR_IZQ_DIR, HIGH);
    analogWrite(PIN_MOTOR_IZQ_PWM, 255 - vel);
  }
}

void moverMotorDer(int vel) {
  bool adelante = true;

  if (vel < 0) {
    adelante = false;
    vel = abs(vel);
  }

  if (INVERTIR_MOTOR_DER) adelante = !adelante;
  vel = constrain(vel, 0, velocidadMaxima);

  if (adelante) {
    digitalWrite(PIN_MOTOR_DER_DIR, LOW);
    analogWrite(PIN_MOTOR_DER_PWM, vel);
  } else {
    digitalWrite(PIN_MOTOR_DER_DIR, HIGH);
    analogWrite(PIN_MOTOR_DER_PWM, 255 - vel);
  }
}

void apagarMotores() {
  digitalWrite(PIN_MOTOR_IZQ_DIR, LOW);
  analogWrite(PIN_MOTOR_IZQ_PWM, 0);
  digitalWrite(PIN_MOTOR_DER_DIR, LOW);
  analogWrite(PIN_MOTOR_DER_PWM, 0);
}

void arrancar() {
  robotActivo = true;
  ultimoError = 0;
  integral = 0;
  vBaseActual = velocidadCurva;
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
