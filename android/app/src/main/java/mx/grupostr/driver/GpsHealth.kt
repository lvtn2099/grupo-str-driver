package mx.grupostr.driver

import android.content.Context
import android.location.Location
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostics. Never stores or logs bearer tokens, customer names, or GPS coordinates.
 * The driver can open the companion APK to see whether the native collector has
 * a fresh GPS fix and when Base44 last acknowledged a sync attempt.
 */
object GpsHealth {
    private const val PREFS = "str_gps_health"

    fun onLocation(context: Context, location: Location) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_fix_ms", location.time)
            .putFloat("last_accuracy_m", location.accuracy)
            .apply()
    }

    fun onServerSuccess(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_success_ms", System.currentTimeMillis())
            .remove("last_error")
            .apply()
    }

    fun onFailure(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_error", code.take(160))
            .apply()
    }

    private fun format(epoch: Long): String {
        if (epoch <= 0) return "sin datos"
        return SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(epoch))
    }

    fun describe(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fix = prefs.getLong("last_fix_ms", 0)
        val synced = prefs.getLong("last_success_ms", 0)
        val accuracy = prefs.getFloat("last_accuracy_m", 0f)
        val error = prefs.getString("last_error", null)
        val session = GpsSession.load(context)
        val stored = if (session != null) {
            GpsStore(context).use { store ->
                "Pendientes: ${store.pendingCount(session.routeId)} · Rechazados: ${store.rejectedCount(session.routeId)}"
            }
        } else "Sin ruta enlazada"
        return buildString {
            appendLine(stored)
            appendLine("Última posición GPS: ${format(fix)}")
            if (fix > 0) appendLine("Precisión: ${accuracy.toInt()} m")
            appendLine("Última comunicación Base44: ${format(synced)}")
            if (error != null) appendLine("Advertencia: $error")
        }
    }
}
