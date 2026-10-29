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
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.cos

class MonitorService : Service() {

    private lateinit var lm: LocationManager
    private var listener: LocationListener? = null
    private var player: AlarmPlayer? = null
    private var monitoring = false
    private var outsideCount = 0
    private var alarmLatched = false // true tras sonar, hasta que vuelvas a entrar a la zona
    private var lastGpsTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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

    // ---------- Vigilancia (usa el GPS/red de Android, sin depender de Google) ----------

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
        lastGpsTime = 0L
        MonitorState.running.value = true

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocation(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = l

        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 2000L, 0f, l, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                // proveedor no disponible, se ignora
            }
        }
    }

    private fun removeUpdates() {
        listener?.let {
            try { lm.removeUpdates(it) } catch (e: Exception) { }
        }
        listener = null
    }

    private fun handleLocation(loc: Location) {
        val now = System.currentTimeMillis()
        if (loc.provider == LocationManager.GPS_PROVIDER) {
            lastGpsTime = now
        } else if (now - lastGpsTime < 20000) {
            // Si el GPS está dando datos, ignora la ubicación por red (es menos precisa)
            return
        }

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
        // (con zonas chicas somos más estrictos: máx. 20 m de error)
        val maxAcc = maxOf(20f, minOf(100f, p.radius * 2f))
        if (loc.hasAccuracy() && loc.accuracy > maxAcc) return

        val outside = d > p.radius
        MonitorState.outside.value = outside
        if (outside) {
            outsideCount++
            // Lecturas seguidas fuera = confirmado (3 en zonas chicas, 2 en las demás)
            val needed = if (p.radius < 25f) 3 else 2
            if (outsideCount >= needed && !alarmLatched) {
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
        removeUpdates()
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
        removeUpdates()
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
