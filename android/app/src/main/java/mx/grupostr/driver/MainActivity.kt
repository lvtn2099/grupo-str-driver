package mx.grupostr.driver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 24) }
  layout.addView(TextView(this).apply { text = "GRUPO STR · Conductor municipal"; textSize = 22f })
  layout.addView(TextView(this).apply { text = "Prototype: foreground-service GPS with local offline storage. API synchronization not configured." })
  val route = EditText(this).apply { hint = "Municipal route ID" }
  layout.addView(route)
  layout.addView(Button(this).apply { text = "Start GPS tracking"; setOnClickListener {
   val id = route.text.toString().trim()
   if (id.isEmpty()) { route.error = "Route ID required"; return@setOnClickListener }
   if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10); return@setOnClickListener
   }
   val intent = Intent(this@MainActivity, TrackingService::class.java).putExtra("route_id", id)
   startForegroundService(intent)
  } })
  layout.addView(Button(this).apply { text = "Stop GPS tracking"; setOnClickListener { stopService(Intent(this@MainActivity, TrackingService::class.java)) } })
  layout.addView(Button(this).apply { text = "Show saved GPS point count"; setOnClickListener {
   val count = GpsStore(this@MainActivity).count(); text = "Saved GPS points: $count"
  } })
  setContentView(layout)
  if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
 }
}
