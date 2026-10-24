package mx.grupostr.driver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The GPS companion remains visible until a server acknowledgment or a
 * specific failure is shown. Starting an Android service alone is NOT
 * proof of connection or GPS tracking.
 */
class MainActivity : Activity() {
    private var pendingSession: GpsSession? = null
    private var currentSession: GpsSession? = null
    private lateinit var status: TextView
    private lateinit var instructions: TextView
    private lateinit var diagnostics: TextView
    private lateinit var retryButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 44, 32, 32)
        }
        content.addView(TextView(this).apply {
            text = "GRUPO STR · GPS"
            textSize = 23f
            setTextColor(Color.DKGRAY)
        })
        status = TextView(this).apply {
            text = "SIN CONEXIÓN GPS"
            textSize = 20f
            setTextColor(Color.rgb(146, 36, 36))
            setPadding(0, 28, 0, 12)
        }
        content.addView(status)
        instructions = TextView(this).apply {
            text = "Abre la ruta desde Grupo STR para conectar el GPS."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 16)
        }
        content.addView(instructions)
        diagnostics = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 4, 0, 20)
        }
        content.addView(diagnostics)
        retryButton = Button(this).apply {
            text = "REINTENTAR CONEXIÓN GPS"
            setOnClickListener {
                val session = currentSession ?: GpsSession.load(this@MainActivity)
                if (session != null) activate(session)
                else showStatus("ENLACE GPS NECESARIO",
                    "Regresa a Grupo STR y pulsa «Preparar conexión GPS».", false)
            }
        }
        content.addView(retryButton)
        content.addView(Button(this).apply {
            text = "REVISAR GPS, CONEXIÓN Y PUNTOS PENDIENTES"
            setOnClickListener { refreshStatus() }
        })
        content.addView(Button(this).apply {
            text = "VOLVER A GRUPO STR (GPS SIGUE ACTIVO)"
            setOnClickListener { finish() }
        })
        content.addView(Button(this).apply {
            text = "AJUSTES ANDROID: GPS Y BATERÍA"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${packageName}")))
            }
        })
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        currentSession = GpsSession.load(this)
        acceptDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDeepLink(intent)
    }

    private fun showStatus(title: String, detail: String, connected: Boolean) {
        status.text = title
        status.setTextColor(if (connected) Color.rgb(20, 122, 69)
            else Color.rgb(161, 54, 30))
        instructions.text = detail
    }

    private fun acceptDeepLink(intent: Intent?) {
        val uri = intent?.data
        if (uri == null || uri.scheme != "strdriver") {
            currentSession = GpsSession.load(this)
            refreshStatus()
            return
        }
        if (uri.host == "stop") {
            val routeId = uri.getQueryParameter("route_id")
            val session = GpsSession.load(this)
            if (session != null && session.routeId == routeId) {
                stopService(Intent(this, TrackingService::class.java))
                GpsHealth.onServiceStopped(this, session.routeId)
            }
            currentSession = null
            showStatus("GPS DETENIDO", "El recorrido se cerró desde Grupo STR.", false)
            diagnostics.text = GpsHealth.describe(this)
            return
        }
        val session = GpsSession.fromUri(uri)
        if (session == null) {
            currentSession = null
            showStatus("ERROR EN ENLACE GPS", GpsSession.explainUriProblem(uri), false)
            diagnostics.text = "No se inició el seguimiento. Vuelve a Grupo STR y prepara un enlace nuevo."
            retryButton.visibility = View.GONE
            return
        }
        currentSession = session
        retryButton.visibility = View.VISIBLE
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingSession = session
            showStatus("PERMISO GPS PENDIENTE",
                "Autoriza ubicación precisa. La ubicación aproximada no sirve para registrar esta ruta.", false)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        activate(session)
    }

    private fun activate(session: GpsSession) {
        currentSession = session
        retryButton.visibility = View.VISIBLE
        GpsHealth.onSessionOpened(this, session.routeId)
        showStatus("CONECTANDO CON EL SERVIDOR…",
            "Enlace recibido. Iniciando el GPS Android y esperando confirmación de Grupo STR.", false)
        try {
            session.save(this)
            startForegroundService(Intent(this, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START))
        } catch (error: Exception) {
            GpsHealth.onFailure(this,
                "Android no pudo iniciar el servicio GPS: ${error.javaClass.simpleName}")
            showStatus("ERROR AL INICIAR GPS",
                "Android rechazó el servicio de ubicación. Revisa permisos y batería.", false)
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val session = currentSession ?: GpsSession.load(this)
        if (session == null) {
            if (!::status.isInitialized) return
            // Preserve a specific invalid-deep-link error until a new link arrives.
            if (status.text != "ERROR EN ENLACE GPS" && status.text != "GPS DETENIDO") {
                showStatus("SIN RUTA ENLAZADA",
                    "Abre Grupo STR, inicia una ruta y toca «Abrir GPS nativo».", false)
            }
            diagnostics.text = GpsHealth.describe(this)
            return
        }
        val health = GpsHealth.snapshot(this, session.routeId)
        val now = System.currentTimeMillis()
        val sinceAttempt = now - health.attemptAt
        val connected = health.lastServerSuccess >= health.attemptAt &&
            health.lastServerSuccess > 0 &&
            now - health.lastServerSuccess < 90000
        val freshFix = health.lastGpsFix >= health.attemptAt - 30000 &&
            health.lastGpsFix > 0 && now - health.lastGpsFix < 150000
        when {
            health.lastError != null && health.lastErrorAt > health.lastServerSuccess &&
                health.lastErrorAt >= health.attemptAt -> {
                showStatus("ERROR DE CONEXIÓN GPS",
                    health.lastError + "\nPulsa «Reintentar conexión» o prepara otro enlace en Grupo STR.", false)
            }
            health.serviceRunning && connected && freshFix -> {
                showStatus("GPS CONECTADO · SEGUIMIENTO ACTIVO",
                    "Conexión establecida con Grupo STR. Android está capturando ubicación. Ya puedes volver al navegador; el GPS sigue activo.", true)
            }
            health.serviceRunning && connected -> {
                showStatus("SERVIDOR CONECTADO · BUSCANDO GPS",
                    "Grupo STR confirmó la conexión. Esperando una ubicación reciente con precisión suficiente.", true)
            }
            health.serviceRunning && sinceAttempt in 0..25000 -> {
                showStatus("CONECTANDO CON GRUPO STR…",
                    "El servicio Android arrancó. Esperando confirmación del servidor (normalmente tarda unos segundos).", false)
            }
            health.serviceRunning -> {
                showStatus("SIN CONFIRMACIÓN DEL SERVIDOR",
                    "El GPS Android se inició, pero Grupo STR aún no confirma la conexión. Comprueba internet o pulsa «Reintentar conexión».", false)
            }
            sinceAttempt in 0..12000 -> {
                showStatus("INICIANDO SERVICIO GPS…",
                    "Esperando a que Android active el servicio de ubicación.", false)
            }
            else -> {
                showStatus("NO SE PUDO CONFIRMAR EL GPS",
                    "Android no confirmó el inicio del seguimiento. Pulsa «Reintentar conexión» y revisa los ajustes del teléfono.", false)
            }
        }
        diagnostics.text = GpsHealth.describe(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val session = pendingSession
            pendingSession = null
            if (grantResults.isNotEmpty() &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                session != null) activate(session)
            else showStatus("FALTA PERMISO GPS PRECISO",
                "Autoriza la ubicación precisa en Ajustes Android para poder iniciar el recorrido.", false)
        }
    }
}
