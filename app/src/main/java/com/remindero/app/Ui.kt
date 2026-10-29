package com.remindero.app

import com.google.android.gms.location.CurrentLocationRequest
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.math.roundToInt
import kotlin.math.sqrt

@SuppressLint("MissingPermission")
fun fetchLocation(ctx: Context, onResult: (Location?) -> Unit) {
    @SuppressLint("MissingPermission")
fun fetchLocation(ctx: Context, onResult: (Location?) -> Unit) {
    val client = LocationServices.getFusedLocationProviderClient(ctx)

    fun tryLast() {
        client.lastLocation
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onResult(null) }
    }

    fun tryBalanced() {
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(10000)
            .setMaxUpdateAgeMillis(120000)
            .build()
        client.getCurrentLocation(req, CancellationTokenSource().token)
            .addOnSuccessListener { loc -> if (loc != null) onResult(loc) else tryLast() }
            .addOnFailureListener { tryLast() }
    }

    val req = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setDurationMillis(15000)
        .setMaxUpdateAgeMillis(60000)
        .build()
    client.getCurrentLocation(req, CancellationTokenSource().token)
        .addOnSuccessListener { loc -> if (loc != null) onResult(loc) else tryBalanced() }
        .addOnFailureListener { tryBalanced() }
}
}

private fun hasFineLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun RemindEroApp() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    val running by MonitorState.running.collectAsState()
    val alarm by MonitorState.alarmActive.collectAsState()
    val outside by MonitorState.outside.collectAsState()
    val distance by MonitorState.distance.collectAsState()
    val offset by MonitorState.offset.collectAsState()

    var message by remember { mutableStateOf(prefs.message) }
    var radius by remember { mutableFloatStateOf(prefs.radius) }
    var vibrate by remember { mutableStateOf(prefs.vibrate) }
    var hasCenter by remember { mutableStateOf(prefs.hasCenter) }
    var centerLat by remember { mutableStateOf(prefs.centerLat) }
    var centerLon by remember { mutableStateOf(prefs.centerLon) }
    var locating by remember { mutableStateOf(false) }
    var afterPerm by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasFineLocation(ctx)) {
            afterPerm?.invoke()
        } else {
            Toast.makeText(ctx, "Necesito el permiso de ubicación precisa 📍", Toast.LENGTH_LONG).show()
        }
        afterPerm = null
    }

    fun withPermissions(action: () -> Unit) {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            afterPerm = action
            launcher.launch(missing.toTypedArray())
        }
    }

    fun setCenterHere() = withPermissions {
        locating = true
        fetchLocation(ctx) { loc ->
            locating = false
            if (loc == null) {
                Toast.makeText(ctx, "No pude obtener tu ubicación. ¿Está el GPS encendido?", Toast.LENGTH_LONG).show()
            } else {
                prefs.centerLat = loc.latitude
                prefs.centerLon = loc.longitude
                prefs.hasCenter = true
                centerLat = loc.latitude
                centerLon = loc.longitude
                hasCenter = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SpaceBlack, Color(0xFF14083A), SpaceBlack))),
        contentAlignment = Alignment.TopCenter
    ) {
        if (alarm) {
            AlarmScreen(
                message = message,
                distance = distance,
                radius = radius,
                onSilence = { MonitorService.silence(ctx) }
            )
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "REMINDERO",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp,
                    color = Cyan
                )
                Text("Tu radar de zona 🛸", color = Color.White.copy(alpha = 0.7f))

                Radar(
                    radiusM = radius,
                    offset = offset,
                    outside = outside,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )

                val status = when {
                    !running -> "Vigilancia apagada"
                    distance == null -> "Buscando señal GPS…"
                    outside -> "⚠️ FUERA de la zona"
                    else -> "✅ Dentro de la zona"
                }
                Text(status, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                distance?.let {
                    Text(
                        "Distancia al centro: ${it.roundToInt()} m  /  Radio: ${radius.roundToInt()} m",
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("📍 Centro de la zona", fontWeight = FontWeight.Bold)
                        Text(
                            if (hasCenter) "Lat %.5f, Lon %.5f".format(centerLat, centerLon)
                            else "Aún no defines el centro",
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        OutlinedButton(
                            onClick = { setCenterHere() },
                            enabled = !locating,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (locating) "Buscando ubicación…" else "Usar mi ubicación actual")
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("⭕ Radio: ${radius.roundToInt()} metros", fontWeight = FontWeight.Bold)
                        Slider(
                            value = radius,
                            onValueChange = { radius = it; prefs.radius = it },
                            valueRange = 50f..3000f
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("💬 Tu mensaje", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it; prefs.message = it },
                            label = { Text("Mensaje de la alarma") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Vibrar con la alarma")
                            Switch(checked = vibrate, onCheckedChange = { vibrate = it; prefs.vibrate = it })
                        }
                    }
                }

                if (!running) {
                    Button(
                        onClick = {
                            if (!hasCenter) {
                                Toast.makeText(ctx, "Primero define el centro de la zona 📍", Toast.LENGTH_SHORT).show()
                            } else {
                                withPermissions { MonitorService.start(ctx) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("🚀 ACTIVAR VIGILANCIA", fontWeight = FontWeight.Bold) }
                } else {
                    Button(
                        onClick = { MonitorService.stop(ctx) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)
                    ) { Text("⏹ DETENER VIGILANCIA", fontWeight = FontWeight.Bold) }
                }

                OutlinedButton(
                    onClick = { withPermissions { MonitorService.test(ctx) } },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("🔊 Probar alarma") }

                Text(
                    "Tip: en ajustes del teléfono desactiva el ahorro de batería para Remindero y así no se duerme.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun AlarmScreen(message: String, distance: Float?, radius: Float, onSilence: () -> Unit) {
    val t = rememberInfiniteTransition(label = "alarm")
    val pulse by t.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Danger.copy(alpha = pulse)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("🚨", fontSize = 72.sp)
            Text(
                "¡FUERA DE ZONA!",
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Text(
                message,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            distance?.let {
                Text(
                    "Estás a ${it.roundToInt()} m del centro (radio ${radius.roundToInt()} m)",
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = onSilence,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text("SILENCIAR ALARMA", fontSize = 20.sp, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
fun Radar(
    radiusM: Float,
    offset: Pair<Float, Float>?,
    outside: Boolean,
    modifier: Modifier = Modifier
) {
    val t = rememberInfiniteTransition(label = "radar")
    val sweep by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "sweep"
    )
    val zone = if (outside) Danger else Cyan

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * 0.72f

        drawCircle(Color.White.copy(alpha = 0.08f), size.minDimension / 2f * 0.95f, c, style = Stroke(1.dp.toPx()))
        drawCircle(zone.copy(alpha = 0.10f), r, c)
        drawCircle(zone.copy(alpha = 0.25f), r * 0.5f, c, style = Stroke(1.dp.toPx()))
        drawArc(
            color = zone.copy(alpha = 0.28f),
            startAngle = sweep - 45f,
            sweepAngle = 45f,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(2 * r, 2 * r)
        )
        drawCircle(zone, r, c, style = Stroke(3.dp.toPx()))
        drawCircle(Magenta, 4.dp.toPx(), c)

        offset?.let { (dx, dy) ->
            val scale = r / radiusM
            var px = dx * scale
            var py = -dy * scale
            val maxLen = size.minDimension / 2f * 0.92f
            val len = sqrt(px * px + py * py)
            if (len > maxLen) {
                px *= maxLen / len
                py *= maxLen / len
            }
            val pos = Offset(c.x + px, c.y + py)
            drawCircle(Color.White, 9.dp.toPx(), pos)
            drawCircle(zone, 6.dp.toPx(), pos)
        }
    }
}
