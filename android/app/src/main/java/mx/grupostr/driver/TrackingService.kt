package mx.grupostr.driver

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Native location collector launched by the existing Base44 driver web page.
 * SQLite preserves GPS points through lock-screen / poor network / process restart.
 */
class TrackingService : Service() {
    companion object {
        const val ACTION_START = "mx.grupostr.driver.START_TRACKING"
        const val ACTION_STOP = "mx.grupostr.driver.STOP_TRACKING"
        private const val TAG = "STR-TRACKING"
        private const val CHANNEL = "str_route_gps"
    }

    private var locationClient: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var syncExecutor: ScheduledExecutorService? = null
    private var activeRoute = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val session = GpsSession.load(this)
        if (session == null ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (activeRoute == session.routeId && callback != null) return START_STICKY
        tearDownTracking()
        activeRoute = session.routeId

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Grupo STR · seguimiento GPS", NotificationManager.IMPORTANCE_LOW)
        )
        val launchIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Grupo STR · GPS activo")
            .setContentText("Registrando recorrido con la pantalla bloqueada")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        startForeground(101, notification)
        GpsSync.scheduleOfflineRetries(applicationContext)

        val client = LocationServices.getFusedLocationProviderClient(this)
        locationClient = client
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location: Location ->
                    if (location.hasAccuracy() && location.accuracy in 0f..75f &&
                        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0) {
                        try {
                            GpsStore(applicationContext).use { store ->
                                store.save(session.routeId, location.latitude, location.longitude,
                                    location.accuracy, location.time)
                            }
                        } catch (error: Exception) {
                            Log.e(TAG, "Unable to persist GPS point", error)
                        }
                    }
                }
            }
        }
        callback = listener

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .setMinUpdateDistanceMeters(10f)
            .build()
        try {
            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
        } catch (error: SecurityException) {
            Log.e(TAG, "Fine location permission missing", error)
            stopSelf()
            return START_NOT_STICKY
        }

        syncExecutor = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                val result = GpsSync.flush(applicationContext, session)
                if (result.reachable && (!result.tracking || !result.sessionValid)) {
                    stopSelf()
                }
            }, 5, 25, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    private fun tearDownTracking() {
        syncExecutor?.shutdownNow()
        syncExecutor = null
        val listener = callback
        if (listener != null) locationClient?.removeLocationUpdates(listener)
        callback = null
        locationClient = null
        activeRoute = ""
    }

    override fun onDestroy() {
        tearDownTracking()
        super.onDestroy()
    }
}
