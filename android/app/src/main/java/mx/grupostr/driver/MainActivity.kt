package mx.grupostr.driver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The APK has no route selection or login. Base44 launches it using a scoped
 * one-time handoff when the driver starts a municipal route in the web app.
 */
class MainActivity : Activity() {
    private var pendingSession: GpsSession? = null
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 64, 40, 32)
        }
        root.addView(TextView(this).apply {
            text = "GRUPO STR · GPS"
            textSize = 24f
        })
        message = TextView(this).apply {
            text = "Este módulo registra el GPS. Abre Grupo STR en el navegador y pulsa Iniciar ruta."
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        root.addView(message)
        root.addView(Button(this).apply {
            text = "Revisar GPS, conexión y puntos pendientes"
            setOnClickListener {
                message.text = GpsHealth.describe(this@MainActivity)
            }
        })
        root.addView(Button(this).apply {
            text = "Ajustes Android: GPS y batería"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${packageName}")))
            }
        })
        setContentView(root)
        acceptDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDeepLink(intent)
    }

    private fun acceptDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "strdriver") return
        if (uri.host == "stop") {
            val routeId = uri.getQueryParameter("route_id")
            val current = GpsSession.load(this)
            if (current != null && current.routeId == routeId) {
                stopService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
            }
            finish()
            return
        }
        val session = GpsSession.fromUri(uri)
        if (session == null) {
            message.text = "No se pudo validar el enlace GPS. Abre la ruta desde Grupo STR."
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingSession = session
            message.text = "Autoriza ubicación precisa para registrar la ruta incluso con la pantalla bloqueada."
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        activate(session)
    }

    private fun activate(session: GpsSession) {
        try {
            session.save(this)
            startForegroundService(
                Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START)
            )
            message.text = "Seguimiento GPS activado. El servicio seguirá grabando al bloquear la pantalla.\n" +
                GpsHealth.describe(this)
            // Return to the existing Base44 driver interface; the APK is a companion.
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                finish()
            }
        } catch (error: Exception) {
            message.text = "No se pudo iniciar GPS: ${error.message}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> {
                val session = pendingSession
                pendingSession = null
                if (grantResults.isNotEmpty() &&
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    session != null) activate(session)
                else message.text = "Se requiere ubicación precisa. Abre Grupo STR y vuelve a iniciar GPS."
            }
            101 -> finish() // Notifications are optional; foreground GPS was already started.
        }
    }
}
