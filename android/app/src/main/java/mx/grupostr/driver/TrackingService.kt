package mx.grupostr.driver

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.*

class TrackingService : Service() {
 private lateinit var client: FusedLocationProviderClient
 private lateinit var callback: LocationCallback
 private var routeId = ""
 override fun onBind(intent: Intent?): IBinder? = null
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  routeId = intent?.getStringExtra("route_id") ?: return START_NOT_STICKY
  val channel = NotificationChannel("route_gps", "Route GPS tracking", NotificationManager.IMPORTANCE_LOW)
  getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  val notification = Notification.Builder(this, "route_gps").setContentTitle("Grupo STR · GPS active").setContentText("Recording route $routeId").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
  startForeground(101, notification)
  client = LocationServices.getFusedLocationProviderClient(this)
  callback = object : LocationCallback() {
   override fun onLocationResult(result: LocationResult) {
    result.locations.forEach { location: Location ->
     if (location.hasAccuracy() && location.accuracy <= 75f && location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0) {
      GpsStore(applicationContext).save(routeId, location.latitude, location.longitude, location.accuracy, location.time)
     }
    }
   }
  }
  if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
   val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L).setMinUpdateIntervalMillis(5000L).setMinUpdateDistanceMeters(10f).build()
   client.requestLocationUpdates(request, callback, Looper.getMainLooper())
  } else stopSelf()
  return START_NOT_STICKY
 }
 override fun onDestroy() { if (::client.isInitialized && ::callback.isInitialized) client.removeLocationUpdates(callback); super.onDestroy() }
}
