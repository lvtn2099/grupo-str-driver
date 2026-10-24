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

        /** Specific, non-secret error to display when a deep link is rejected. */
        fun explainUriProblem(uri: Uri): String {
            if (uri.scheme != "strdriver" || uri.host != "track") {
                return "El enlace no corresponde al GPS de Grupo STR. Abre la ruta desde el navegador."
            }
            val route = uri.getQueryParameter("route_id")
            if (route.isNullOrBlank() || route.length > 100) {
                return "Falta la identificación de la ruta. Prepara otro enlace en Grupo STR."
            }
            val token = uri.getQueryParameter("token")
            if (token == null || !Regex("^[a-f0-9]{64}$").matches(token)) {
                return "La sesión GPS está incompleta. Prepara un enlace nuevo en Grupo STR."
            }
            val endpoint = uri.getQueryParameter("endpoint")
            if (endpoint.isNullOrBlank()) {
                return "Falta el servidor GPS. Prepara la conexión nuevamente desde Grupo STR."
            }
            val url = try { URL(endpoint) } catch (_: Exception) {
                return "La dirección del servidor GPS no es válida."
            }
            if (url.protocol != "https" || url.userInfo != null || url.port != -1) {
                return "El servidor GPS requiere una conexión HTTPS segura."
            }
            val hostname = url.host.lowercase()
            val allowed = hostname == "cdguzman.grupostr.com.mx" ||
                hostname == "app.base44.com" || hostname == "base44.app" ||
                hostname.endsWith(".base44.app") || hostname.endsWith(".base44.com")
            if (!allowed) return "Servidor GPS no autorizado: \${hostname.take(80)}."
            if (!url.path.endsWith("/functions/gpsRouteBridge") &&
                !url.path.endsWith("/gpsRouteBridge")) {
                return "La ruta del servidor GPS es incorrecta. Actualiza el enlace desde Grupo STR."
            }
            return "El enlace no se pudo validar. Prepara una sesión nueva desde Grupo STR."
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
