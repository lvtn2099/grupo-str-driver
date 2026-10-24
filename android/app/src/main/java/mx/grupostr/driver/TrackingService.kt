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
 * The native GPS collector is independent of the Base44 browser lifecycle.
 * The persistent foreground service keeps recording while the screen is locked.
 * SQLite buffers every accepted fix. WorkManager uploads after network returns.
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
    private var lastLocationTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val session = GpsSession.load(this)
        if (session == null) {
            GpsHealth.onFailure(this, "Sin sesión GPS válida. Abre la ruta desde Base44.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            GpsHealth.onFailure(this, "Ubicación precisa no autorizada")
            stopSelf()
            return START_NOT_STICKY
        }

        // Base44 can renew the token without stopping native location collection.
        if (activeRoute == session.routeId && callback != null) return START_STICKY
        tearDownTracking()
        activeRoute = session.routeId
        lastLocationTime = 0L

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Grupo STR · GPS de ruta", NotificationManager.IMPORTANCE_LOW)
        )
        try {
            startForeground(101, makeNotification("Buscando señal GPS…"))
        } catch (error: RuntimeException) {
            Log.e(TAG, "Android refused to start foreground GPS service", error)
            GpsHealth.onFailure(this, "Android bloqueó el GPS en segundo plano: ${error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            GpsSync.scheduleOfflineRetries(applicationContext)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to schedule periodic GPS recovery", error)
        }

        val client = LocationServices.getFusedLocationProviderClient(this)
        locationClient = client
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location: Location ->
                    val now = System.currentTimeMillis()
                    if (location.time <= lastLocationTime ||
                        location.time < now - 120000 || location.time > now + 30000) return@forEach
                    if (!location.hasAccuracy() || location.accuracy !in 0f..75f ||
                        location.latitude !in -90.0..90.0 ||
                        location.longitude !in -180.0..180.0) return@forEach
                    try {
                        GpsStore(applicationContext).use { store ->
                            store.save(session.routeId, location.latitude, location.longitude,
                                location.accuracy, location.time)
                        }
                        lastLocationTime = location.time
                        GpsHealth.onLocation(applicationContext, location)
                    } catch (error: Exception) {
                        GpsHealth.onFailure(applicationContext, "No se pudo guardar un punto GPS")
                        Log.e(TAG, "Unable to store location locally", error)
                    }
                }
            }
        }
        callback = listener

        // Do NOT set a minimum movement distance: stationary drivers still need
        // fresh GPS fixes so the server can distinguish a live phone from a dead one.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .build()
        try {
            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
        } catch (error: SecurityException) {
            GpsHealth.onFailure(this, "Se perdió el permiso de GPS")
            Log.e(TAG, "Fine location permission revoked", error)
            stopSelf()
            return START_NOT_STICKY
        }

        syncExecutor = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                try {
                    val currentSession = GpsSession.load(applicationContext)
                    if (currentSession == null || currentSession.routeId != activeRoute) {
                        stopSelf()
                        return@scheduleWithFixedDelay
                    }
                    val result = GpsSync.flush(applicationContext, currentSession, foreground = true)
                    val newestSession = GpsSession.load(applicationContext)
                    // A new Base44 link can rotate the token while a request is in flight.
                    if (newestSession?.token != currentSession.token) return@scheduleWithFixedDelay
                    if (!result.reachable) {
                        GpsSync.enqueueConnectivityRetry(applicationContext)
                    }
                    updateForegroundStatus(result)
                    if (result.reachable && (!result.tracking || !result.sessionValid)) {
                        stopSelf()
                    }
                } catch (error: Exception) {
                    // Uncaught exceptions would permanently terminate ScheduledExecutorService.
                    GpsHealth.onFailure(applicationContext, "Error interno de sincronización")
                    Log.e(TAG, "Sync loop recovered from unexpected exception", error)
                }
            }, 5, 25, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    private fun makeNotification(detail: String): Notification {
        val launch = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Grupo STR · GPS en segundo plano")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundStatus(result: GpsSyncResult) {
        val queued = try {
            GpsStore(applicationContext).use { it.pendingCount(activeRoute) }
        } catch (_: Exception) { 0 }
        val detail = if (result.reachable) "GPS activo · Base44 conectado · ${queued} pendientes"
                     else "GPS activo sin internet · ${queued} puntos guardados"
        try {
            getSystemService(NotificationManager::class.java).notify(101, makeNotification(detail))
        } catch (error: Exception) {
            Log.w(TAG, "Unable to update foreground notification", error)
        }
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
        val previousRoute = activeRoute
        tearDownTracking()
        // A completed route may still have buffered points. They remain available
        // for deferred upload via WorkManager; no records are deleted on stop.
        if (previousRoute.isNotEmpty()) {
            try {
                GpsStore(applicationContext).use { store ->
                    if (store.pendingCount(previousRoute) > 0) GpsSync.enqueueConnectivityRetry(applicationContext)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Unable to schedule final buffered upload", error)
            }
        }
        super.onDestroy()
    }
}
