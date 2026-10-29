package com.remindero.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.cos

class MonitorService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var player: AlarmPlayer? = null
    private var monitoring = false
    private var outsideCount = 0
    private var alarmLatched = false // true tras sonar, hasta que vuelvas a entrar a la zona

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_SILENCE -> {
                silence()
                return if (monitoring) START_STICKY else START_NOT_STICKY
            }
            ACTION_TEST -> {
                enterForeground()
                fireAlarm()
                return if (monitoring) START_STICKY else START_NOT_STICKY
            }
            else -> {
                startMonitoring()
                return START_STICKY
            }
        }
    }

    // ---------- Vigilancia ----------

    @SuppressLint("MissingPermission")
    private fun startMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        enterForeground()
        if (monitoring) return
        monitoring = true
        outsideCount = 0
        alarmLatched = false
        MonitorState.running.value = true

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1500L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { handleLocation(it) }
            }
        }
        callback = cb
        fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    private fun handleLocation(loc: Location) {
        val p = Prefs(this)
        if (!p.hasCenter) return

        val res = FloatArray(1)
        Location.distanceBetween(p.centerLat, p.centerLon, loc.latitude, loc.longitude, res)
        val d = res[0]
        val dx = ((loc.longitude - p.centerLon) * 111320.0 * cos(Math.toRadians(p.centerLat))).toFloat()
        val dy = ((loc.latitude - p.centerLat) * 110540.0).toFloat()
        MonitorState.distance.value = d
        MonitorState.offset.value = Pair(dx, dy)

        // Ignora lecturas muy imprecisas para evitar falsas alarmas
        if (loc.accuracy > 100f) return

        val outside = d > p.radius
        MonitorState.outside.value = outside
        if (outside) {
            outsideCount++
            // Dos lecturas seguidas fuera = confirmado
            if (outsideCount >= 2 && !alarmLatched) {
                alarmLatched = true
                fireAlarm()
            }
        } else {
            outsideCount = 0
            alarmLatched = false
        }
    }

    // ---------- Alarma ----------

    private fun fireAlarm() {
        val p = Prefs(this)
        MonitorState.alarmActive.value = true
        if (player == null) player = AlarmPlayer(this)
        player?.start(p.vibrate)
        showAlarmNotification(p.message)
    }

    private fun silence() {
        player?.stop()
        player = null
        MonitorState.alarmActive.value = false
        getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIF_ID)
        if (!monitoring) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun shutdown() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
        monitoring = false
        player?.stop()
        player = null
        MonitorState.alarmActive.value = false
        MonitorState.running.value = false
        MonitorState.outside.value = false
        MonitorState.distance.value = null
        MonitorState.offset.value = null
        getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIF_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
        player?.stop()
        player = null
        MonitorState.running.value = false
        MonitorState.alarmActive.value = false
        super.onDestroy()
    }

    // ---------- Notificaciones ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Vigilancia de zona", NotificationManager.IMPORTANCE_LOW)
        )
        val alarmCh = NotificationChannel(CH_ALARM, "Alarma de zona", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // el sonido lo reproduce AlarmPlayer
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alarmCh)
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent {
        val i = Intent(this, MonitorService::class.java).setAction(action)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun enterForeground() {
        val radius = Prefs(this).radius.toInt()
        val n = NotificationCompat.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Remindero activo")
            .setContentText("Vigilando tu zona de $radius m")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Detener", serviceIntent(ACTION_STOP, 2))
            .build()
        ServiceCompat.startForeground(
            this, MONITOR_NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
    }

    private fun showAlarmNotification(message: String) {
        val n = NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("🚨 ¡Fuera de la zona!")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setFullScreenIntent(openAppIntent(), true)
            .addAction(0, "SILENCIAR", serviceIntent(ACTION_SILENCE, 1))
            .build()
        getSystemService(NotificationManager::class.java).notify(ALARM_NOTIF_ID, n)
    }

    companion object {
        private const val ACTION_START = "com.remindero.app.START"
        private const val ACTION_STOP = "com.remindero.app.STOP"
        private const val ACTION_SILENCE = "com.remindero.app.SILENCE"
        private const val ACTION_TEST = "com.remindero.app.TEST"
        private const val CH_MONITOR = "monitor"
        private const val CH_ALARM = "alarm"
        private const val MONITOR_NOTIF_ID = 1
        private const val ALARM_NOTIF_ID = 2

        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
        )

        fun test(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_TEST)
        )

        fun silence(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_SILENCE))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
