package mx.grupostr.driver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class GpsStore(context: Context) : SQLiteOpenHelper(context, "str_gps.db", null, 1) {
 override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE gps_points (id TEXT PRIMARY KEY, route_id TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, recorded_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0)") }
 override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
 fun save(routeId: String, lat: Double, lon: Double, accuracy: Float, time: Long) {
  val values = ContentValues().apply { put("id", UUID.randomUUID().toString()); put("route_id", routeId); put("lat", lat); put("lon", lon); put("accuracy", accuracy); put("recorded_at", time); put("synced", 0) }
  writableDatabase.insertOrThrow("gps_points", null, values)
 }
 fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM gps_points", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
