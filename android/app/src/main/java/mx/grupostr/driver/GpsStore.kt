package mx.grupostr.driver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class GpsPoint(
    val id: String,
    val routeId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val recordedAt: Long
)

class GpsStore(context: Context) : SQLiteOpenHelper(context, "str_gps.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE gps_points (id TEXT PRIMARY KEY, route_id TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, recorded_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_gps_unsynced ON gps_points(route_id, synced, recorded_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun save(routeId: String, lat: Double, lon: Double, accuracy: Float, time: Long) {
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("route_id", routeId)
            put("lat", lat)
            put("lon", lon)
            put("accuracy", accuracy)
            put("recorded_at", time)
            put("synced", 0)
        }
        writableDatabase.insertOrThrow("gps_points", null, values)
    }

    fun pending(routeId: String, limit: Int = 50): List<GpsPoint> {
        val out = mutableListOf<GpsPoint>()
        readableDatabase.query(
            "gps_points",
            arrayOf("id", "route_id", "lat", "lon", "accuracy", "recorded_at"),
            "route_id = ? AND synced = 0",
            arrayOf(routeId),
            null, null, "recorded_at ASC", limit.coerceIn(1, 50).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(GpsPoint(cursor.getString(0), cursor.getString(1), cursor.getDouble(2),
                    cursor.getDouble(3), cursor.getDouble(4), cursor.getLong(5)))
            }
        }
        return out
    }

    fun acknowledge(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement("UPDATE gps_points SET synced = 1 WHERE id = ?")
            ids.forEach { statement.clearBindings(); statement.bindString(1, it); statement.executeUpdateDelete() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM gps_points", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun pendingCount(routeId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM gps_points WHERE route_id = ? AND synced = 0",
        arrayOf(routeId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
