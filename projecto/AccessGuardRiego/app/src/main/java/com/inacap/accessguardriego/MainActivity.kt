package com.inacap.accessguardriego

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

data class EventoHistorial(
    val hora: String = "",
    val usuario: String = "",
    val accion: String = ""
)

enum class TipoMensaje {
    NINGUNO, ROJO, NARANJA, VERDE, AZUL
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRiegoIoT()
                }
            }
        }
    }
}

@Composable
fun AppRiegoIoT() {
    val auth = remember { FirebaseAuth.getInstance() }
    var usuarioAutenticado by remember { mutableStateOf(auth.currentUser?.email) }

    if (usuarioAutenticado == null) {
        PantallaLoginOT(
            auth = auth,
            onLoginExitoso = { email -> usuarioAutenticado = email }
        )
    } else {
        PantallaDashboardRiego(
            usuario = usuarioAutenticado ?: "operador@inacap.cl",
            onCerrarSesion = {
                auth.signOut()
                usuarioAutenticado = null
            }
        )
    }
}

@Composable
fun AppAccessGuardRiego() {
    AppRiegoIoT()
}

@Composable
fun PantallaLoginOT(
    auth: FirebaseAuth,
    onLoginExitoso: (String) -> Unit
) {
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var mensajeAlerta by remember { mutableStateOf<String?>(null) }
    var tipoAlerta by remember { mutableStateOf(TipoMensaje.NINGUNO) }
    var intentosFallidos by remember { mutableIntStateOf(0) }
    var procesando by remember { mutableStateOf(false) }

    // Estados para flujo de verificación obligatoria por correo
    var mostrarVerificacionPendiente by remember { mutableStateOf(false) }
    var correoPendiente by remember { mutableStateOf("") }
    var verificandoCorreo by remember { mutableStateOf(false) }
    var reenviandoCorreo by remember { mutableStateOf(false) }
    var enviandoReset by remember { mutableStateOf(false) }

    val credencialesGenericas = remember {
        setOf("admin", "1234", "0000", "1111", "password")
    }

    val estaBloqueado = intentosFallidos >= 3

    fun esFormatoCorreoValido(email: String): Boolean {
        return email.contains("@") &&
                email.contains(".") &&
                email.indexOf('@') < email.lastIndexOf('.') &&
                !email.startsWith("@") &&
                !email.endsWith(".")
    }

    fun esCorreoGenerico(email: String): Boolean {
        val parteUsuario = email.substringBefore("@")
        return credencialesGenericas.contains(email) ||
                credencialesGenericas.contains(parteUsuario) ||
                email.contains("admin")
    }

    fun validarPoliticaPassword(password: String): List<String> {
        val errores = mutableListOf<String>()
        if (password.length < 8) {
            errores.add("Mínimo 8 caracteres (actual: ${password.length})")
        }
        if (!password.any { it.isUpperCase() }) {
            errores.add("Al menos 1 letra mayúscula (A-Z)")
        }
        if (!password.any { it.isDigit() }) {
            errores.add("Al menos 1 número (0-9)")
        }
        val caracteresEspeciales = ".,!@#$%^&*_-".toSet()
        if (!password.any { it in caracteresEspeciales }) {
            errores.add("Al menos 1 carácter especial o punto (.,!@#$%^&*_-)")
        }
        if (credencialesGenericas.contains(password.lowercase())) {
            errores.add("No se permiten contraseñas genéricas por defecto (admin, 1234, etc.)")
        }
        return errores
    }

    fun ejecutarRecuperacionPassword() {
        val correoLimpio = correo.trim().lowercase()
        if (correoLimpio.isEmpty()) {
            tipoAlerta = TipoMensaje.ROJO
            mensajeAlerta = "Ingresa tu correo electrónico arriba para enviarte el enlace de recuperación."
            return
        }
        if (!esFormatoCorreoValido(correoLimpio)) {
            tipoAlerta = TipoMensaje.ROJO
            mensajeAlerta = "El correo ingresado no tiene un formato válido (ej. operador@inacap.cl)."
            return
        }

        enviandoReset = true
        auth.sendPasswordResetEmail(correoLimpio)
            .addOnCompleteListener { task ->
                enviandoReset = false
                if (task.isSuccessful) {
                    intentosFallidos = 0
                    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val horaActual = sdf.format(Date())
                    val evento = EventoHistorial(
                        hora = horaActual,
                        usuario = correoLimpio,
                        accion = "Solicitud de restablecimiento de contraseña: $correoLimpio"
                    )
                    FirebaseDatabase.getInstance().getReference("riego/historial").push().setValue(evento)

                    tipoAlerta = TipoMensaje.VERDE
                    mensajeAlerta = "Enviamos un enlace a $correoLimpio para cambiar tu contraseña (revisa también la carpeta Spam). Cámbiala desde el enlace e inicia sesión aquí."
                } else {
                    tipoAlerta = TipoMensaje.ROJO
                    val errorMsg = task.exception?.localizedMessage ?: "Error al enviar correo de recuperación."
                    mensajeAlerta = errorMsg
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🛡️", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "AccessGuard Riego OT",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Acceso Seguro a Control Industrial • ISO/IEC 27400",
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = correo,
            onValueChange = {
                correo = it
                if (mensajeAlerta != null && !estaBloqueado) {
                    mensajeAlerta = null
                    tipoAlerta = TipoMensaje.NINGUNO
                }
            },
            label = { Text("Correo Electrónico (OT Operator)") },
            singleLine = true,
            enabled = !procesando,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = clave,
            onValueChange = {
                clave = it
                if (mensajeAlerta != null && !estaBloqueado) {
                    mensajeAlerta = null
                    tipoAlerta = TipoMensaje.NINGUNO
                }
            },
            label = { Text("Contraseña") },
            singleLine = true,
            enabled = !procesando,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Tarjeta de alerta / confirmación dinámica
        if (mensajeAlerta != null && tipoAlerta != TipoMensaje.NINGUNO) {
            val (bgColor, textColor, titulo) = when (tipoAlerta) {
                TipoMensaje.ROJO -> Triple(
                    Color(0xFFFFEBEE),
                    Color(0xFFB71C1C),
                    if (estaBloqueado) "⛔ SISTEMA BLOQUEADO (ISO/IEC 27400)" else "🚨 ALERTA DE SEGURIDAD (ISO/IEC 27400)"
                )
                TipoMensaje.NARANJA -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), "⚠️ INTENTO FALLIDO")
                TipoMensaje.VERDE -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), "✅ OPERACIÓN EXITOSA")
                TipoMensaje.AZUL -> Triple(Color(0xFFE1F5FE), Color(0xFF01579B), "📨 INFORMACIÓN")
                else -> Triple(Color(0xFFFFEBEE), Color(0xFFB71C1C), "AVISO")
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = bgColor),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = titulo,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = mensajeAlerta!!,
                        color = textColor,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Tarjeta de Verificación de Correo Requerida
        if (mostrarVerificacionPendiente) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "✉️ Verificación de Correo Requerida",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Te enviamos un enlace de validación a $correoPendiente. Revisa tu bandeja de entrada o Spam, haz clic en el enlace y luego presiona confirmar.",
                        fontSize = 13.sp,
                        color = Color(0xFF5D4037)
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Botón 1: "Ya verifiqué mi correo (Ingresar)"
                    Button(
                        onClick = {
                            verificandoCorreo = true
                            val currentUser = auth.currentUser
                            if (currentUser == null) {
                                val correoLimpio = correo.trim().lowercase()
                                val claveLimpia = clave.trim()
                                if (correoLimpio.isNotEmpty() && claveLimpia.isNotEmpty()) {
                                    auth.signInWithEmailAndPassword(correoLimpio, claveLimpia)
                                        .addOnCompleteListener { task ->
                                            if (task.isSuccessful) {
                                                val user = task.result?.user
                                                user?.reload()?.addOnCompleteListener {
                                                    verificandoCorreo = false
                                                    if (user.isEmailVerified) {
                                                        intentosFallidos = 0
                                                        mostrarVerificacionPendiente = false
                                                        tipoAlerta = TipoMensaje.NINGUNO
                                                        mensajeAlerta = null

                                                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                                        val horaActual = sdf.format(Date())
                                                        val evento = EventoHistorial(
                                                            hora = horaActual,
                                                            usuario = correoLimpio,
                                                            accion = "Usuario verificado e ingreso: $correoLimpio"
                                                        )
                                                        FirebaseDatabase.getInstance().getReference("riego/historial").push().setValue(evento)

                                                        onLoginExitoso(correoLimpio)
                                                    } else {
                                                        tipoAlerta = TipoMensaje.ROJO
                                                        mensajeAlerta = "Tu correo aún no ha sido verificado. Por favor abre el enlace que te enviamos y vuelve a presionar este botón."
                                                    }
                                                }
                                            } else {
                                                verificandoCorreo = false
                                                tipoAlerta = TipoMensaje.ROJO
                                                mensajeAlerta = "Ingresa tu correo y contraseña en los campos de arriba para comprobar la verificación."
                                            }
                                        }
                                } else {
                                    verificandoCorreo = false
                                    tipoAlerta = TipoMensaje.ROJO
                                    mensajeAlerta = "Ingresa tu correo y contraseña en los campos de arriba para comprobar la verificación."
                                }
                            } else {
                                currentUser.reload().addOnCompleteListener { reloadTask ->
                                    verificandoCorreo = false
                                    if (currentUser.isEmailVerified) {
                                        val userEmail = currentUser.email ?: correoPendiente
                                        intentosFallidos = 0
                                        mostrarVerificacionPendiente = false
                                        tipoAlerta = TipoMensaje.NINGUNO
                                        mensajeAlerta = null

                                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                        val horaActual = sdf.format(Date())
                                        val evento = EventoHistorial(
                                            hora = horaActual,
                                            usuario = userEmail,
                                            accion = "Usuario verificado e ingreso: $userEmail"
                                        )
                                        FirebaseDatabase.getInstance().getReference("riego/historial").push().setValue(evento)

                                        onLoginExitoso(userEmail)
                                    } else {
                                        tipoAlerta = TipoMensaje.ROJO
                                        mensajeAlerta = "Tu correo aún no ha sido verificado. Por favor abre el enlace que te enviamos a $correoPendiente y vuelve a presionar este botón."
                                    }
                                }
                            }
                        },
                        enabled = !verificandoCorreo && !reenviandoCorreo,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (verificandoCorreo) "Comprobando verificación..." else "Ya verifiqué mi correo (Ingresar)",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Botón 2: "Reenviar correo de validación"
                    OutlinedButton(
                        onClick = {
                            reenviandoCorreo = true
                            val currentUser = auth.currentUser
                            if (currentUser != null) {
                                currentUser.sendEmailVerification()
                                    .addOnCompleteListener { task ->
                                        reenviandoCorreo = false
                                        if (task.isSuccessful) {
                                            tipoAlerta = TipoMensaje.VERDE
                                            mensajeAlerta = "Correo de validación reenviado a $correoPendiente. Revisa tu bandeja de entrada o Spam."
                                        } else {
                                            tipoAlerta = TipoMensaje.ROJO
                                            mensajeAlerta = task.exception?.localizedMessage ?: "Error al reenviar correo de validación."
                                        }
                                    }
                            } else {
                                val correoLimpio = correo.trim().lowercase()
                                val claveLimpia = clave.trim()
                                if (correoLimpio.isNotEmpty() && claveLimpia.isNotEmpty()) {
                                    auth.signInWithEmailAndPassword(correoLimpio, claveLimpia)
                                        .addOnCompleteListener { signInTask ->
                                            if (signInTask.isSuccessful) {
                                                signInTask.result?.user?.sendEmailVerification()
                                                    ?.addOnCompleteListener { sendTask ->
                                                        reenviandoCorreo = false
                                                        if (sendTask.isSuccessful) {
                                                            tipoAlerta = TipoMensaje.VERDE
                                                            mensajeAlerta = "Correo de validación reenviado a $correoLimpio. Revisa tu bandeja de entrada o Spam."
                                                        } else {
                                                            tipoAlerta = TipoMensaje.ROJO
                                                            mensajeAlerta = sendTask.exception?.localizedMessage ?: "Error al reenviar correo de validación."
                                                        }
                                                    }
                                            } else {
                                                reenviandoCorreo = false
                                                tipoAlerta = TipoMensaje.ROJO
                                                mensajeAlerta = "Ingresa tu correo y contraseña arriba para reenviar la verificación."
                                            }
                                        }
                                } else {
                                    reenviandoCorreo = false
                                    tipoAlerta = TipoMensaje.ROJO
                                    mensajeAlerta = "Ingresa tu correo y contraseña arriba para reenviar la verificación."
                                }
                            }
                        },
                        enabled = !reenviandoCorreo && !verificandoCorreo,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (reenviandoCorreo) "Reenviando..." else "Reenviar correo de validación",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Botón Iniciar Sesión Segura
        Button(
            onClick = {
                val correoLimpio = correo.trim().lowercase()
                val claveLimpia = clave.trim()

                if (correoLimpio.isEmpty() || claveLimpia.isEmpty()) {
                    tipoAlerta = TipoMensaje.ROJO
                    mensajeAlerta = "Debe ingresar el correo y la contraseña."
                    return@Button
                }

                // Bloqueo explícito si se ingresan credenciales genéricas (admin, 1234, 0000, 1111, password)
                if (esCorreoGenerico(correoLimpio) || credencialesGenericas.contains(claveLimpia.lowercase())) {
                    intentosFallidos++
                    tipoAlerta = TipoMensaje.ROJO
                    if (intentosFallidos >= 3) {
                        mensajeAlerta = "PROHIBIDO (ISO/IEC 27400): Credencial genérica detectada. Ha alcanzado 3 intentos fallidos. Sistema bloqueado. Utilice la recuperación por correo."
                    } else {
                        mensajeAlerta = "PROHIBIDO (ISO/IEC 27400): Credencial genérica o por defecto prohibida ('$correoLimpio' / '$claveLimpia'). Intento $intentosFallidos de 3."
                    }
                    return@Button
                }

                procesando = true
                auth.signInWithEmailAndPassword(correoLimpio, claveLimpia)
                    .addOnCompleteListener { task ->
                        procesando = false
                        if (task.isSuccessful) {
                            val user = task.result?.user
                            if (user?.isEmailVerified == true) {
                                intentosFallidos = 0
                                mensajeAlerta = null
                                tipoAlerta = TipoMensaje.NINGUNO
                                mostrarVerificacionPendiente = false
                                onLoginExitoso(correoLimpio)
                            } else {
                                // Correo no verificado
                                user?.sendEmailVerification()
                                correoPendiente = correoLimpio
                                mostrarVerificacionPendiente = true
                                tipoAlerta = TipoMensaje.ROJO
                                mensajeAlerta = "Correo no verificado. Te enviamos un nuevo enlace a $correoLimpio. Revisa tu bandeja de entrada o Spam y presiona 'Ya verifiqué mi correo'."
                            }
                        } else {
                            intentosFallidos++
                            tipoAlerta = TipoMensaje.ROJO
                            if (intentosFallidos >= 3) {
                                mensajeAlerta = "Credenciales incorrectas (Intento $intentosFallidos de 3). Sistema bloqueado por seguridad ISO/IEC 27400. Restablezca su contraseña por correo a continuación."
                            } else {
                                mensajeAlerta = "Credenciales incorrectas (Intento $intentosFallidos de 3)"
                            }
                        }
                    }
            },
            enabled = !estaBloqueado && !procesando,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = when {
                    estaBloqueado -> "Bloqueado (3 intentos fallidos)"
                    procesando -> "Autenticando..."
                    else -> "Iniciar Sesión Segura"
                },
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Botón secundario "Crear Usuario" (OutlinedButton)
        OutlinedButton(
            onClick = {
                val correoLimpio = correo.trim().lowercase()
                val claveLimpia = clave.trim()

                // 1. Validaciones de correo
                if (correoLimpio.isEmpty()) {
                    tipoAlerta = TipoMensaje.ROJO
                    mensajeAlerta = "Debe ingresar un correo electrónico."
                    return@OutlinedButton
                }

                if (!esFormatoCorreoValido(correoLimpio)) {
                    tipoAlerta = TipoMensaje.ROJO
                    mensajeAlerta = "El correo debe tener formato válido con '@' y dominio con '.' (ej. usuario@dominio.cl)."
                    return@OutlinedButton
                }

                if (esCorreoGenerico(correoLimpio)) {
                    tipoAlerta = TipoMensaje.ROJO
                    mensajeAlerta = "PROHIBIDO (ISO/IEC 27400): No se permite el uso de nombres genéricos como 'admin' en el correo."
                    return@OutlinedButton
                }

                // 2. Validación de política de contraseña segura ISO/IEC 27400
                val erroresPassword = validarPoliticaPassword(claveLimpia)
                if (erroresPassword.isNotEmpty()) {
                    tipoAlerta = TipoMensaje.ROJO
                    val listaFaltantes = erroresPassword.joinToString(separator = "\n• ", prefix = "• ")
                    mensajeAlerta = "Contraseña insegura según norma ISO/IEC 27400. Requisitos faltantes:\n$listaFaltantes"
                    return@OutlinedButton
                }

                // 3. Registro en Firebase Auth con verificación de correo obligatoria
                procesando = true
                auth.createUserWithEmailAndPassword(correoLimpio, claveLimpia)
                    .addOnCompleteListener { task ->
                        procesando = false
                        if (task.isSuccessful) {
                            val user = task.result?.user
                            user?.sendEmailVerification()

                            intentosFallidos = 0
                            correoPendiente = correoLimpio
                            mostrarVerificacionPendiente = true
                            tipoAlerta = TipoMensaje.VERDE
                            mensajeAlerta = "Cuenta creada. Enviamos un enlace de validación a $correoLimpio."
                        } else {
                            tipoAlerta = TipoMensaje.ROJO
                            val errorMsg = task.exception?.localizedMessage ?: "Error al registrar usuario en Firebase"
                            mensajeAlerta = errorMsg
                        }
                    }
            },
            enabled = !estaBloqueado && !procesando,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = "Crear Usuario",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Bloque de Recuperación de Contraseña Real por Correo (destacado si está bloqueado o visible como botón)
        if (estaBloqueado) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⛔ SISTEMA BLOQUEADO (ISO/IEC 27400)",
                        color = Color(0xFFB71C1C),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Se superaron los 3 intentos fallidos. Para desbloquear el acceso, solicita el enlace de recuperación a tu correo institucional.",
                        color = Color(0xFF880E4F),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { ejecutarRecuperacionPassword() },
                        enabled = !enviandoReset && !procesando,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (enviandoReset) "Enviando enlace..." else "Recuperar contraseña por correo",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            OutlinedButton(
                onClick = { ejecutarRecuperacionPassword() },
                enabled = !enviandoReset && !procesando,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (enviandoReset) "Enviando enlace..." else "¿Olvidaste tu contraseña? Recuperar por correo",
                    fontSize = 13.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaDashboardRiego(
    usuario: String,
    onCerrarSesion: () -> Unit
) {
    val database = remember { FirebaseDatabase.getInstance() }
    val refTelemetria = remember { database.getReference("riego/telemetria") }
    val refComandos = remember { database.getReference("riego/comandos") }
    val refHistorial = remember { database.getReference("riego/historial") }

    var humedad by remember { mutableIntStateOf(0) }
    var bombaActiva by remember { mutableStateOf(false) }

    var riegoAutomatizadoActivo by remember { mutableStateOf(true) }
    var modoManual by remember { mutableStateOf(false) }
    var bombaManual by remember { mutableStateOf(false) }
    var umbralMinimo by remember { mutableIntStateOf(30) }
    var umbralInput by remember { mutableStateOf("30") }
    var tramaCifradaActual by remember { mutableStateOf("") }

    var listaHistorial by remember { mutableStateOf(listOf<EventoHistorial>()) }
    var mostrarDialogoReconexion by remember { mutableStateOf(false) }
    var estadoConexion by remember { mutableStateOf("En línea • Enlace OT Seguro") }

    fun registrarEvento(accion: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val horaActual = sdf.format(Date())
        val evento = EventoHistorial(
            hora = horaActual,
            usuario = usuario,
            accion = accion
        )
        refHistorial.push().setValue(evento)
    }

    fun enviarComandos(sensorActivo: Boolean, manual: Boolean, bomba: Boolean, umbral: Int) {
        val tramaPlana = "AUTO=${if (sensorActivo) 1 else 0}|MODO=${if (manual) "MAN" else "AUTO"}|BOMBA=${if (bomba) 1 else 0}|UMBRAL=$umbral"
        val tramaCifrada = SeguridadAES.cifrar(tramaPlana)
        tramaCifradaActual = tramaCifrada

        val datos = mapOf(
            "sensorActivo" to sensorActivo,
            "modoManual" to manual,
            "bombaManual" to bomba,
            "umbral" to umbral,
            "tramaCifradaAES" to tramaCifrada
        )
        refComandos.setValue(datos)
    }

    // Escuchadores de Firebase en tiempo real
    DisposableEffect(Unit) {
        val telemetriaListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                humedad = snapshot.child("humedad").getValue(Int::class.java) ?: humedad
                bombaActiva = snapshot.child("bombaActiva").getValue(Boolean::class.java) ?: bombaActiva
            }

            override fun onCancelled(error: DatabaseError) {
                estadoConexion = "Interrupción OT: ${error.message}"
                mostrarDialogoReconexion = true
            }
        }

        val comandosListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sensor = snapshot.child("sensorActivo").getValue(Boolean::class.java) ?: true
                val manual = snapshot.child("modoManual").getValue(Boolean::class.java) ?: false
                val bomba = snapshot.child("bombaManual").getValue(Boolean::class.java) ?: false
                val umb = snapshot.child("umbral").getValue(Int::class.java) ?: 30
                val trama = snapshot.child("tramaCifradaAES").getValue(String::class.java) ?: ""

                riegoAutomatizadoActivo = sensor
                modoManual = manual
                bombaManual = bomba
                umbralMinimo = umb
                umbralInput = umb.toString()
                tramaCifradaActual = trama
            }

            override fun onCancelled(error: DatabaseError) {
                estadoConexion = "Interrupción OT: ${error.message}"
                mostrarDialogoReconexion = true
            }
        }

        val historialListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tempList = mutableListOf<EventoHistorial>()
                for (child in snapshot.children) {
                    val hora = child.child("hora").getValue(String::class.java) ?: ""
                    val user = child.child("usuario").getValue(String::class.java) ?: ""
                    val accion = child.child("accion").getValue(String::class.java) ?: ""
                    tempList.add(EventoHistorial(hora, user, accion))
                }
                listaHistorial = tempList.reversed()
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        refTelemetria.addValueEventListener(telemetriaListener)
        refComandos.addValueEventListener(comandosListener)
        refHistorial.limitToLast(40).addValueEventListener(historialListener)

        onDispose {
            refTelemetria.removeEventListener(telemetriaListener)
            refComandos.removeEventListener(comandosListener)
            refHistorial.removeEventListener(historialListener)
        }
    }

    // Diálogo de Reconexión Rápida OT
    if (mostrarDialogoReconexion) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoReconexion = false },
            title = {
                Text(text = "📡 Alerta de Conectividad OT", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = "Se detectó una interrupción con el nodo ESP32. ¿Desea Reconectar el Sistema?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        database.goOnline()
                        estadoConexion = "En línea • Reconexión exitosa"
                        registrarEvento("Reconexión rápida OT ejecutada por el operador")
                        mostrarDialogoReconexion = false
                    }
                ) {
                    Text("Sí")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        mostrarDialogoReconexion = false
                    }
                ) {
                    Text("No")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AccessGuard Riego OT",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = estadoConexion,
                            fontSize = 11.sp,
                            color = Color(0xFF2E7D32)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { mostrarDialogoReconexion = true }) {
                        Text("Reconectar", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onCerrarSesion) {
                        Text("Salir", color = Color(0xFFD32F2F))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Tarjeta Superior: Telemetría de Riego
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Humedad del Suelo",
                            fontSize = 13.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$humedad%",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "Nodo Sensor ESP32-OT",
                            fontSize = 11.sp,
                            color = Color(0xFF558B2F)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                color = if (bombaActiva) Color(0xFF1976D2) else Color(0xFF616161),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (bombaActiva) "REGANDO" else "EN REPOSO",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 2. Tarjeta Central: Panel de Comandos y Control Cifrado
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Comandos y Modo de Operación",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Switch claro para Riego Automatizado (Sensor Humedad)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Riego Automatizado (Sensor Humedad)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (riegoAutomatizadoActivo) {
                                    "Activo: riega si humedad < umbral%"
                                } else {
                                    "Desactivado: detección automática pausada"
                                },
                                fontSize = 11.sp,
                                color = if (riegoAutomatizadoActivo) Color(0xFF2E7D32) else Color.Gray
                            )
                        }
                        Switch(
                            checked = riegoAutomatizadoActivo,
                            onCheckedChange = { activo ->
                                riegoAutomatizadoActivo = activo
                                enviarComandos(
                                    sensorActivo = activo,
                                    manual = modoManual,
                                    bomba = bombaManual,
                                    umbral = umbralMinimo
                                )
                                registrarEvento(
                                    if (activo) "Riego automatizado ACTIVADO" else "Riego automatizado DESACTIVADO"
                                )
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // RadioButtons para alternar entre modo Automático y Manual
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !modoManual,
                            onClick = {
                                if (modoManual) {
                                    modoManual = false
                                    bombaManual = false
                                    enviarComandos(
                                        sensorActivo = riegoAutomatizadoActivo,
                                        manual = false,
                                        bomba = false,
                                        umbral = umbralMinimo
                                    )
                                    registrarEvento("Cambio a Modo Automático (Sensor ESP32)")
                                }
                            }
                        )
                        Text("Automático", fontSize = 14.sp)

                        Spacer(modifier = Modifier.width(20.dp))

                        RadioButton(
                            selected = modoManual,
                            onClick = {
                                if (!modoManual) {
                                    modoManual = true
                                    enviarComandos(
                                        sensorActivo = riegoAutomatizadoActivo,
                                        manual = true,
                                        bomba = bombaManual,
                                        umbral = umbralMinimo
                                    )
                                    registrarEvento("Cambio a Modo Manual OT")
                                }
                            }
                        )
                        Text("Manual", fontSize = 14.sp)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Switch para encender/apagar la bomba (habilitado solo en modo Manual)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Bomba de Riego (Actuador)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                text = if (modoManual) "Control manual activo" else "Deshabilitado (Activo en Manual)",
                                fontSize = 11.sp,
                                color = if (modoManual) Color(0xFF1565C0) else Color.Gray
                            )
                        }
                        Switch(
                            checked = bombaManual,
                            enabled = modoManual,
                            onCheckedChange = { encender ->
                                bombaManual = encender
                                enviarComandos(
                                    sensorActivo = riegoAutomatizadoActivo,
                                    manual = true,
                                    bomba = encender,
                                    umbral = umbralMinimo
                                )
                                registrarEvento(
                                    if (encender) "Bomba activada manualmente" else "Bomba apagada manualmente"
                                )
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Umbral mínimo con OutlinedTextField + botón Actualizar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = umbralInput,
                            onValueChange = { umbralInput = it },
                            label = { Text("Umbral Mínimo Humedad (%)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {
                                val nuevoValor = umbralInput.toIntOrNull()?.coerceIn(1, 99) ?: 30
                                umbralMinimo = nuevoValor
                                umbralInput = nuevoValor.toString()
                                enviarComandos(
                                    sensorActivo = riegoAutomatizadoActivo,
                                    manual = modoManual,
                                    bomba = bombaManual,
                                    umbral = nuevoValor
                                )
                                registrarEvento("Umbral mínimo actualizado a $nuevoValor%")
                            }
                        ) {
                            Text("Actualizar")
                        }
                    }

                    if (tramaCifradaActual.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "🔒 Trama AES-128: $tramaCifradaActual",
                            fontSize = 10.sp,
                            color = Color(0xFF424242),
                            maxLines = 1
                        )
                    }
                }
            }

            // 3. Bitácora de Eventos e Historial en Vivo
            Text(
                text = "Bitácora de Eventos (Auditoría OT)",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (listaHistorial.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Sin registros de auditoría", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(listaHistorial) { evento ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = evento.accion,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B5E20)
                                    )
                                    Text(
                                        text = evento.hora,
                                        fontSize = 11.sp,
                                        color = Color(0xFF558B2F)
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Usuario: ${evento.usuario}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF33691E)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
