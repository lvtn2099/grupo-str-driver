package mx.grupostr.driver

import android.content.Context
import android.location.Location
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GpsHealthSnapshot(
    val attemptAt: Long,
    val serviceRunning: Boolean,
    val lastGpsFix: Long,
    val lastServerSuccess: Long,
    val lastError: String?,
    val lastErrorAt: Long
)

/**
 * Local, persisted diagnostics. No token, customer information, or coordinates
 * are ever stored in diagnostic preferences. Every new route handoff resets
 * old status so yesterday's success is never shown as today's connection.
 */
object GpsHealth {
    private const val PREFS = "str_gps_health"
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun onSessionOpened(context: Context, routeId: String) {
        prefs(context).edit()
            .putString("status_route_id", routeId)
            .putLong("attempt_ms", System.currentTimeMillis())
            .putBoolean("service_running", false)
            .putLong("last_fix_ms", 0L)
            .putLong("last_success_ms", 0L)
            .putLong("last_error_ms", 0L)
            .remove("last_error")
            .apply()
    }

    fun onServiceStarted(context: Context, routeId: String) {
        if (prefs(context).getString("status_route_id", null) != routeId) return
        prefs(context).edit().putBoolean("service_running", true).apply()
    }

    fun onServiceStopped(context: Context, routeId: String) {
        if (prefs(context).getString("status_route_id", null) != routeId) return
        prefs(context).edit().putBoolean("service_running", false).apply()
    }

    fun onLocation(context: Context, location: Location) {
        prefs(context).edit()
            .putLong("last_fix_ms", location.time)
            .putFloat("last_accuracy_m", location.accuracy)
            .apply()
    }

    fun onServerSuccess(context: Context) {
        prefs(context).edit()
            .putLong("last_success_ms", System.currentTimeMillis())
            .remove("last_error")
            .putLong("last_error_ms", 0L)
            .apply()
    }

    fun onFailure(context: Context, code: String) {
        prefs(context).edit()
            .putString("last_error", code.take(200))
            .putLong("last_error_ms", System.currentTimeMillis())
            .apply()
    }

    fun snapshot(context: Context, routeId: String): GpsHealthSnapshot {
        val p = prefs(context)
        val matches = p.getString("status_route_id", null) == routeId
        return GpsHealthSnapshot(
            attemptAt = if (matches) p.getLong("attempt_ms", 0L) else 0L,
            serviceRunning = matches && p.getBoolean("service_running", false),
            lastGpsFix = if (matches) p.getLong("last_fix_ms", 0L) else 0L,
            lastServerSuccess = if (matches) p.getLong("last_success_ms", 0L) else 0L,
            lastError = if (matches) p.getString("last_error", null) else null,
            lastErrorAt = if (matches) p.getLong("last_error_ms", 0L) else 0L
        )
    }

    private fun format(epoch: Long): String {
        if (epoch <= 0) return "sin datos"
        return SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(epoch))
    }

    fun describe(context: Context): String {
        val p = prefs(context)
        val session = GpsSession.load(context)
        val state = session?.let { snapshot(context, it.routeId) }
        val stored = try {
            if (session != null) {
                GpsStore(context).use { store ->
                    "Puntos pendientes: \${store.pendingCount(session.routeId)} · Rechazados: \${store.rejectedCount(session.routeId)}"
                }
            } else "Sin ruta enlazada"
        } catch (_: Exception) { "No se pudieron leer los puntos pendientes" }
        return buildString {
            appendLine("Estado: \${if (state?.serviceRunning == true) "GPS Android iniciado" else "GPS Android no confirmado"}")
            appendLine(stored)
            appendLine("Último GPS: \${format(state?.lastGpsFix ?: 0L)}")
            if (state != null && state.lastGpsFix > 0) {
                appendLine("Precisión: \${p.getFloat("last_accuracy_m", 0f).toInt()} m")
            }
            appendLine("Servidor confirmó: \${format(state?.lastServerSuccess ?: 0L)}")
            if (state?.lastError != null) {
                appendLine("Error (\${format(state.lastErrorAt)}): \${state.lastError}")
            }
        }
    }
}
