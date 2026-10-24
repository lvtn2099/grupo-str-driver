package mx.grupostr.driver

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

data class GpsSyncResult(val reachable: Boolean, val tracking: Boolean, val sessionValid: Boolean)

object GpsSync {
    private const val TAG = "STR-GPS-SYNC"
    private const val PERIODIC_WORK = "str-gps-offline-sync"
    private const val CONNECTED_WORK = "str-gps-network-retry"

    private fun onlineConstraint(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleOfflineRetries(context: Context) {
        val request = PeriodicWorkRequestBuilder<GpsSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(onlineConstraint()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    // When Android loses connectivity, WorkManager waits for usable network.
    // Unlike the periodic job, this can run as soon as connectivity returns.
    fun enqueueConnectivityRetry(context: Context) {
        val request = OneTimeWorkRequestBuilder<GpsSyncWorker>()
            .setConstraints(onlineConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONNECTED_WORK, ExistingWorkPolicy.KEEP, request
        )
    }

    // Only the live Android foreground location service sets foreground=true.
    // WorkManager can resend old points, but must not mark GPS as currently active.
    fun flush(context: Context, session: GpsSession, foreground: Boolean = false): GpsSyncResult {
        val store = GpsStore(context)
        try {
            var tracking = true
            repeat(10) {
                val batch = store.pending(session.routeId)
                val body = JSONObject()
                    .put("action", if (batch.isEmpty()) "status" else "sync")
                    .put("route_id", session.routeId)
                    .put("token", session.token)

                if (foreground) {
                    body.put("foreground", true)
                    val latestFix = store.latest(session.routeId)
                    if (latestFix != null) {
                        body.put("last_fix_at", Instant.ofEpochMilli(latestFix.recordedAt).toString())
                        body.put("last_accuracy_m", latestFix.accuracyMeters)
                    }
                }
                if (batch.isNotEmpty()) {
                    val points = JSONArray()
                    batch.forEach { point ->
                        points.put(JSONObject()
                            .put("client_point_id", point.id)
                            .put("latitude", point.latitude)
                            .put("longitude", point.longitude)
                            .put("accuracy_m", point.accuracyMeters)
                            .put("recorded_at", Instant.ofEpochMilli(point.recordedAt).toString()))
                    }
                    body.put("points", points)
                }

                val response = post(session.endpoint, body)
                when (response.first) {
                    401, 403 -> {
                        GpsHealth.onFailure(context, "Sesión rechazada (HTTP ${response.first}). En Grupo STR, pulsa «Preparar conexión GPS» para obtener un enlace nuevo.")
                        Log.w(TAG, "GPS route session rejected; reopen route from Base44")
                        return GpsSyncResult(true, false, false)
                    }
                    409 -> {
                        GpsHealth.onFailure(context, "Ruta cerrada o no autorizada (HTTP 409). Selecciona la ruta correcta en Grupo STR.")
                        return GpsSyncResult(true, false, true)
                    }
                }
                if (response.first !in 200..299) {
                    GpsHealth.onFailure(context, "Servidor GPS: HTTP ${response.first}. Revisa la conexión y el enlace.")
                    return GpsSyncResult(false, tracking, true)
                }

                val payload = JSONObject(response.second)
                tracking = payload.optBoolean("tracking", false)
                GpsHealth.onServerSuccess(context)

                if (batch.isNotEmpty()) {
                    val accepted = mutableListOf<String>()
                    val rejected = mutableListOf<String>()
                    payload.optJSONArray("accepted")?.let { ids ->
                        for (i in 0 until ids.length()) {
                            ids.optString(i).takeIf(String::isNotBlank)?.let(accepted::add)
                        }
                    }
                    payload.optJSONArray("rejected")?.let { ids ->
                        for (i in 0 until ids.length()) {
                            ids.optString(i).takeIf(String::isNotBlank)?.let(rejected::add)
                        }
                    }
                    val batchIds = batch.map { it.id }.toSet()
                    // Only the exact IDs returned by Base44 may leave the retry queue.
                    store.acknowledge(accepted.filter(batchIds::contains))
                    // Rejected fixes remain in SQLite for diagnostics, not falsely "synced".
                    store.reject(rejected.filter(batchIds::contains))
                    if (rejected.isNotEmpty()) {
                        GpsHealth.onFailure(context, "${rejected.size} puntos GPS rechazados")
                    }
                    if (accepted.isEmpty() && rejected.isEmpty()) {
                        GpsHealth.onFailure(context, "Base44 no confirmó ningún punto GPS")
                        return GpsSyncResult(false, tracking, true)
                    }
                }
                if (batch.isEmpty() || batch.size < 50) return GpsSyncResult(true, tracking, true)
            }
            return GpsSyncResult(true, tracking, true)
        } catch (error: Exception) {
            GpsHealth.onFailure(context, "No se pudo sincronizar: ${error.javaClass.simpleName}")
            Log.w(TAG, "GPS points remain locally queued; retry on network restoration", error)
            return GpsSyncResult(false, true, true)
        } finally {
            store.close()
        }
    }

    private fun post(endpoint: String, body: JSONObject): Pair<Int, String> {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
            return Pair(status, responseStream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}

class GpsSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val session = GpsSession.load(applicationContext) ?: return Result.success()
        val result = GpsSync.flush(applicationContext, session, foreground = false)
        return when {
            !result.sessionValid -> Result.failure()
            !result.reachable -> Result.retry()
            else -> Result.success()
        }
    }
}
