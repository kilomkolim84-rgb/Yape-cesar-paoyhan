package com.cibercesarin.yape

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import kotlinx.coroutines.delay

const val CLAVE_LIMPIAR_TODO = "123456"
const val AVISOS_YAPE_GUARDADO = "avisos_yape_guardado"
const val CANAL_SERVICIO = "canal_yape_servicio"
const val CANAL_ALERTAS = "canal_yape_alertas"
const val ID_NOTIFICACION_SERVICIO = 54321
const val ID_NOTIFICACION_NUEVO = 12345
const val TIEMPO_INICIAL_ESPERA = 600L // 10 minutos en segundos

data class AvisoYape(
    val id: String = "",
    val mac: String = "",
    val ip: String = "",
    val fecha_hora: String = "",
    val nombre: String = "",
    val monto: String = "",
    val estado: String = "pendiente",
    val tiempo_total_seg: Long = TIEMPO_INICIAL_ESPERA,
    var tiempo_restante_seg: Long = TIEMPO_INICIAL_ESPERA
)

class ServicioEscuchaYape : Service() {
    private lateinit var db: DatabaseReference
    private var escucha: ChildEventListener? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalesNotificaciones()
        
        val notificacionFija = NotificationCompat.Builder(this, CANAL_SERVICIO)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("YAPE - CIBER CESARÍN")
            .setContentText("✅ Esperando conexiones y pagos...")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        
        startForeground(ID_NOTIFICACION_SERVICIO, notificacionFija)

        db = FirebaseDatabase.getInstance().reference
        db.keepSynced(true)
        escucharNuevosPagos()
    }

    private fun crearCanalesNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sonidoYape = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val canalServicio = NotificationChannel(
                CANAL_SERVICIO,
                "Servicio Activo",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Mantiene la app escuchando siempre"
            }

            val canalAlertas = NotificationChannel(
                CANAL_ALERTAS,
                "Avisos de Entrada",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisa cuando llega un usuario nuevo"
                enableVibration(true)
                setSound(sonidoYape, null)
            }

            val gestor = getSystemService(NotificationManager::class.java)
            gestor.createNotificationChannel(canalServicio)
            gestor.createNotificationChannel(canalAlertas)
        }
    }

    private fun escucharNuevosPagos() {
        escucha = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val datos = snapshot.value as? Map<*, *> ?: return
                val id = snapshot.key ?: ""
                val mac = datos["mac"]?.toString() ?: "Sin dato"
                val ip = datos["ip"]?.toString() ?: "Sin dato"
                val fecha = datos["fecha_hora"]?.toString() ?: "Sin fecha"
                val nombre = datos["nombre"]?.toString() ?: "Usuario Entrante"
                val monto = datos["monto"]?.toString() ?: ""

                val avisoEntrada = NotificationCompat.Builder(this@ServicioEscuchaYape, CANAL_ALERTAS)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("🔔 CONEXIÓN ENTRANTE")
                    .setContentText("$nombre - IP: $ip")
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText("Nombre: $nombre\nMAC: $mac\nIP: $ip\nFecha: $fecha")
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this@ServicioEscuchaYape,
                            id.hashCode(),
                            Intent(this@ServicioEscuchaYape, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .build()

                if (ContextCompat.checkSelfPermission(
                        this@ServicioEscuchaYape,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(this@ServicioEscuchaYape)
                        .notify(ID_NOTIFICACION_NUEVO + id.hashCode(), avisoEntrada)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
        }
        db.child("pagos_esperando").addChildEventListener(escucha!!)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        escucha?.let { db.child("pagos_esperando").removeEventListener(it) }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    private val permisoNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) {
            Toast.makeText(this, "✅ Permiso de avisos activado", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Necesitas permitir notificaciones", Toast.LENGTH_LONG).show()
        }
    }

    private val db = FirebaseDatabase.getInstance().reference
    private var avisosYape by mutableStateOf(listOf<AvisoYape>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = getSharedPreferences("YapePrefs", Context.MODE_PRIVATE)
        avisosYape = emptyList()
        cargarAvisosGuardados()
        
        try { FirebaseApp.initializeApp(this) } catch (e: Exception) { }
        db.keepSynced(true)

        pedirPermisosYActivarServicio()
        escucharListaEnTiempoReal()
        
        setContent { InterfazPrincipal() }
    }

    private fun pedirPermisosYActivarServicio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        startForegroundService(Intent(this, ServicioEscuchaYape::class.java))
    }

    private fun escucharListaEnTiempoReal() {
        db.child("pagos_esperando").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val datos = snapshot.value as? Map<*, *> ?: return
                val idNuevo = snapshot.key ?: ""

                if (avisosYape.any { it.id == idNuevo }) return

                val tiempoTotal = (datos["tiempo_total_seg"] as? Long) ?: TIEMPO_INICIAL_ESPERA
                val tiempoRestante = (datos["tiempo_restante_seg"] as? Long) ?: TIEMPO_INICIAL_ESPERA

                val nuevo = AvisoYape(
                    id = idNuevo,
                    mac = datos["mac"]?.toString() ?: "Sin dato",
                    ip = datos["ip"]?.toString() ?: "Sin dato",
                    fecha_hora = datos["fecha_hora"]?.toString() ?: "Sin fecha",
                    nombre = datos["nombre"]?.toString() ?: "Usuario Entrante",
                    monto = datos["monto"]?.toString() ?: "",
                    estado = datos["estado"]?.toString() ?: "pendiente",
                    tiempo_total_seg = tiempoTotal,
                    tiempo_restante_seg = tiempoRestante
                )
                avisosYape = listOf(nuevo) + avisosYape
                guardarAvisosGuardados()
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val datos = snapshot.value as? Map<*, *> ?: return
                val idActualizar = snapshot.key ?: ""
                val index = avisosYape.indexOfFirst { it.id == idActualizar }
                if (index == -1) return

                val tiempoTotal = (datos["tiempo_total_seg"] as? Long) ?: TIEMPO_INICIAL_ESPERA
                val tiempoRestante = (datos["tiempo_restante_seg"] as? Long) ?: TIEMPO_INICIAL_ESPERA

                val actualizado = AvisoYape(
                    id = idActualizar,
                    mac = datos["mac"]?.toString() ?: "Sin dato",
                    ip = datos["ip"]?.toString() ?: "Sin dato",
                    fecha_hora = datos["fecha_hora"]?.toString() ?: "Sin fecha",
                    nombre = datos["nombre"]?.toString() ?: "Usuario Entrante",
                    monto = datos["monto"]?.toString() ?: "",
                    estado = datos["estado"]?.toString() ?: "pendiente",
                    tiempo_total_seg = tiempoTotal,
                    tiempo_restante_seg = tiempoRestante
                )

                avisosYape = avisosYape.toMutableList().apply { set(index, actualizado) }
                guardarAvisosGuardados()
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val idBorrar = snapshot.key ?: ""
                avisosYape = avisosYape.filter { it.id != idBorrar }
                guardarAvisosGuardados()
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun abrirVentanaConfirmar(aviso: AvisoYape) {
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        // ✅ El nombre que tú escribas es el que manda, reemplaza cualquier otro
        val campoNombre = EditText(this).apply {
            hint = "Escribe nombre del cliente"
            setText(aviso.nombre)
            setPadding(0, 16, 0, 16)
        }

        val opcionesTiempo = listOf(
            "10 Minutos", "20 Minutos", "30 Minutos",
            "1 Hora", "2 Horas", "4 Horas", "8 Horas", "24 Horas"
        )
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, opcionesTiempo)
            setPadding(0, 16, 0, 16)
        }

        contenedor.addView(campoNombre)
        contenedor.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle("✅ CONFIRMAR ACCESO")
            .setMessage("Escribe el nombre real y asigna el tiempo")
            .setView(contenedor)
            .setPositiveButton("GUARDAR Y ENVIAR") { _, _ ->
                // ✅ Lo que escribas tú es lo que se guarda, no importa lo que venía antes
                val nombreFinal = campoNombre.text.toString().trim().ifBlank { "Sin nombre" }
                val opcionElegida = spinner.selectedItem.toString()

                val tiempoNuevo = when {
                    opcionElegida.contains("10 Minutos") -> 600L
                    opcionElegida.contains("20 Minutos") -> 1200L
                    opcionElegida.contains("30 Minutos") -> 1800L
                    opcionElegida.contains("1 Hora") -> 3600L
                    opcionElegida.contains("2 Horas") -> 7200L
                    opcionElegida.contains("4 Horas") -> 14400L
                    opcionElegida.contains("8 Horas") -> 28800L
                    opcionElegida.contains("24 Horas") -> 86400L
                    else -> TIEMPO_INICIAL_ESPERA
                }

                val datosActualizar = mapOf(
                    "nombre" to nombreFinal,
                    "estado" to "confirmado",
                    "tiempo_total_seg" to tiempoNuevo,
                    "tiempo_restante_seg" to tiempoNuevo,
                    "fecha_confirmacion" to System.currentTimeMillis().toString()
                )

                db.child("pagos_esperando").child(aviso.id).updateChildren(datosActualizar)
                    .addOnSuccessListener {
                        Toast.makeText(this, "✅ Guardado: $nombreFinal - Tiempo: ${formatearTiempo(tiempoNuevo)}", Toast.LENGTH_LONG).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "❌ Error: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun borrarUno(idAviso: String) {
        db.child("pagos_esperando").child(idAviso).removeValue()
    }

    private fun limpiarTodo() {
        val campo = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            setPadding(48, 16, 48, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("⚠️ BORRAR TODOS")
            .setMessage("Escribe la clave de 6 dígitos:")
            .setView(campo)
            .setPositiveButton("CONFIRMAR") { _, _ ->
                val entrada = campo.text.toString()
                if (entrada == CLAVE_LIMPIAR_TODO) {
                    db.child("pagos_esperando").removeValue()
                    Toast.makeText(this, "✅ Todo limpiado correctamente", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Clave incorrecta", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun cargarAvisosGuardados() {
        val texto = prefs.getString(AVISOS_YAPE_GUARDADO, "") ?: ""
        if (texto.isNotEmpty()) {
            avisosYape = texto.split("|||").mapNotNull { linea ->
                val campos = linea.split("§")
                if (campos.size >= 9) AvisoYape(
                    campos[0], campos[1], campos[2], campos[3], campos[4], campos[5], campos[6],
                    campos[7].toLongOrNull() ?: TIEMPO_INICIAL_ESPERA,
                    campos[8].toLongOrNull() ?: TIEMPO_INICIAL_ESPERA
                ) else null
            }
        }
    }

    private fun guardarAvisosGuardados() {
        val texto = avisosYape.joinToString("|||") {
            "${it.id}§${it.mac}§${it.ip}§${it.fecha_hora}§${it.nombre}§${it.monto}§${it.estado}§${it.tiempo_total_seg}§${it.tiempo_restante_seg}"
        }
        prefs.edit().putString(AVISOS_YAPE_GUARDADO, texto).apply()
    }

    private fun formatearTiempo(seg: Long): String {
        val minutos = seg / 60
        val segundos = seg % 60
        return "%02d:%02d".format(minutos, segundos)
    }

    @Composable
    fun InterfazPrincipal() {
        LaunchedEffect(Unit) {
            while (true) {
                avisosYape = avisosYape.map { aviso ->
                    if (aviso.tiempo_restante_seg > 0) {
                        aviso.copy(tiempo_restante_seg = aviso.tiempo_restante_seg - 1)
                    } else {
                        aviso
                    }
                }
                guardarAvisosGuardados()
                delay(1000)
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Color(0xFFF5F5F5)) { relleno ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(relleno)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("YAPE - CIBER CESARÍN", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A237E))
                    IconButton(onClick = {
                        cargarAvisosGuardados()
                        Toast.makeText(this@MainActivity, "🔄 Actualizado", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = Color(0xFF1976D2))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Conexiones / Solicitudes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = { limpiarTodo() },
                        colors = ButtonDefaults.buttonColors(Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("LIMPIAR TODO", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Card(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(12.dp)) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        if (avisosYape.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("✅ Sin conexiones pendientes", fontSize = 15.sp, color = Color.Gray)
                                }
                            }
                        } else {
                            items(avisosYape) { aviso ->
                                val esConfirmado = aviso.estado == "confirmado"
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(Color(0xFFFFF9C4))
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                val titulo = if (esConfirmado) "✅ ACCESO CONFIRMADO" else "⏳ CONEXIÓN ENTRANTE"
                                                val colorTitulo = if (esConfirmado) Color(0xFF2E7D32) else Color(0xFFFF9800)
                                                val colorTiempo = if (aviso.tiempo_restante_seg > 60) Color(0xFF2E7D32) else Color(0xFFD32F2F)

                                                Text(titulo, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colorTitulo)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("Nombre: ${aviso.nombre}", fontSize = 13.sp, color = Color.DarkGray)
                                                // ✅ No muestra ceros, solo pendiente si no hay monto
                                                val textoMonto = if (aviso.monto.isBlank() || aviso.monto == "0.00") "Pendiente de pago" else "S/ ${aviso.monto}"
                                                Text("Estado: $textoMonto", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (esConfirmado) Color(0xFF2E7D32) else Color.DarkGray)
                                                Text("MAC: ${aviso.mac}", fontSize = 12.sp, color = Color.DarkGray)
                                                Text("IP: ${aviso.ip}", fontSize = 12.sp, color = Color.DarkGray)
                                                Text("Fecha: ${aviso.fecha_hora}", fontSize = 12.sp, color = Color.DarkGray)
                                                Text("⏱️ Tiempo restante: ${formatearTiempo(aviso.tiempo_restante_seg)}",
                                                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colorTiempo)
                                            }
                                            IconButton(onClick = { borrarUno(aviso.id) }) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Borrar",
                                                    tint = Color(0xFFD32F2F),
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        if (!esConfirmado) {
                                            Button(
                                                onClick = { abrirVentanaConfirmar(aviso) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(Color(0xFF2E7D32)),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("✅ PONER NOMBRE Y ASIGNAR TIEMPO", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
