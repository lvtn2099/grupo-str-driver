package mx.grupostr.driver

import android.content.Context
import android.net.Uri
import java.net.URL

/**
 * A single route-scoped credential obtained from an authenticated Base44 page.
 * No Base44 admin/service-role key is present in the APK.
 */
data class GpsSession(
    val routeId: String,
    val token: String,
    val endpoint: String
) {
    fun save(context: Context) {
        context.getSharedPreferences("str_native_gps", Context.MODE_PRIVATE).edit()
            .putString("route_id", routeId)
            .putString("token", token)
            .putString("endpoint", endpoint)
            .apply()
    }

    companion object {
        fun load(context: Context): GpsSession? {
            val prefs = context.getSharedPreferences("str_native_gps", Context.MODE_PRIVATE)
            return validate(
                prefs.getString("route_id", null),
                prefs.getString("token", null),
                prefs.getString("endpoint", null)
            )
        }

        fun fromUri(uri: Uri): GpsSession? {
            if (uri.scheme != "strdriver" || uri.host != "track") return null
            return validate(uri.getQueryParameter("route_id"), uri.getQueryParameter("token"),
                uri.getQueryParameter("endpoint"))
        }

        private fun validate(routeId: String?, token: String?, endpoint: String?): GpsSession? {
            if (routeId.isNullOrBlank() || routeId.length > 100) return null
            if (token == null || !Regex("^[a-f0-9]{64}$").matches(token)) return null
            if (endpoint.isNullOrBlank()) return null
            val url = try { URL(endpoint) } catch (_: Exception) { return null }
            val hostname = url.host.lowercase()
            if (url.protocol != "https" || url.userInfo != null || url.port != -1) return null
            // Custom-domain municipal app and Base44's native API endpoints are
            // both valid; the route token is still checked by the backend.
            if (hostname != "cdguzman.grupostr.com.mx" &&
                hostname != "app.base44.com" && hostname != "base44.app" &&
                !hostname.endsWith(".base44.app") && !hostname.endsWith(".base44.com")) return null
            if (!url.path.endsWith("/functions/gpsRouteBridge") &&
                !url.path.endsWith("/gpsRouteBridge")) return null
            return GpsSession(routeId, token, endpoint)
        }
    }
}
