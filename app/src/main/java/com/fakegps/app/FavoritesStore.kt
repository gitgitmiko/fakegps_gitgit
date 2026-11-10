package com.fakegps.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(val name: String, val lat: Double, val lng: Double)

/** Saved places, persisted as a JSON array in SharedPreferences. */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("fakegps", Context.MODE_PRIVATE)

    fun all(): List<Favorite> {
        val json = JSONArray(prefs.getString(KEY_FAVORITES, "[]"))
        return (0 until json.length()).map {
            val o = json.getJSONObject(it)
            Favorite(o.getString("name"), o.getDouble("lat"), o.getDouble("lng"))
        }
    }

    fun add(favorite: Favorite) = save(all() + favorite)

    fun remove(favorite: Favorite) = save(all() - favorite)

    var lastLat: Double
        get() = prefs.getString(KEY_LAST_LAT, null)?.toDoubleOrNull() ?: DEFAULT_LAT
        set(value) = prefs.edit().putString(KEY_LAST_LAT, value.toString()).apply()

    var lastLng: Double
        get() = prefs.getString(KEY_LAST_LNG, null)?.toDoubleOrNull() ?: DEFAULT_LNG
        set(value) = prefs.edit().putString(KEY_LAST_LNG, value.toString()).apply()

    private fun save(list: List<Favorite>) {
        val json = JSONArray()
        list.forEach {
            json.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng))
        }
        prefs.edit().putString(KEY_FAVORITES, json.toString()).apply()
    }

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LNG = "last_lng"
        // Monas, Jakarta
        const val DEFAULT_LAT = -6.175392
        const val DEFAULT_LNG = 106.827153
    }
}
