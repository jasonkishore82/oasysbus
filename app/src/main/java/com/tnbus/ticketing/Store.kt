package com.tnbus.ticketing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Ticket(
    val number: String,
    val from: String,
    val to: String,
    val km: Int,
    val pax: Int,
    val fare: Int,
    val mode: String,
    val time: Long,
    val cancelled: Boolean = false,
    val cancelTime: Long = 0,
    val given: Int = 0,
) {
    val total get() = fare * pax
}

val PAYMENT_MODES = listOf("Cash", "UPI", "Card")

/** Settings and ticket log, kept in SharedPreferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ticketing", Context.MODE_PRIVATE)

    var operatorName: String
        get() = prefs.getString("operator", "OASYS BUS") ?: "OASYS BUS"
        set(v) = prefs.edit().putString("operator", v).apply()
    var busNumber: String
        get() = prefs.getString("bus", "TN 01 N 1234") ?: "TN 01 N 1234"
        set(v) = prefs.edit().putString("bus", v).apply()
    var conductorId: String
        get() = prefs.getString("conductor", "") ?: ""
        set(v) = prefs.edit().putString("conductor", v).apply()
    var gstin: String
        get() = prefs.getString("gstin", "") ?: ""
        set(v) = prefs.edit().putString("gstin", v).apply()
    var prefix: String
        get() = prefs.getString("prefix", "T") ?: "T"
        set(v) = prefs.edit().putString("prefix", v).apply()
    var autoCount: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()
    var nextNumber: Long
        get() = prefs.getLong("next", 1)
        set(v) = prefs.edit().putLong("next", v).apply()

    var topLines: Int
        get() = prefs.getInt("top_lines", 0)
        set(v) = prefs.edit().putInt("top_lines", v).apply()
    var feedLines: Int
        get() = prefs.getInt("feed_lines", 10)
        set(v) = prefs.edit().putInt("feed_lines", v).apply()
    var ratePerKm: Float
        get() = prefs.getFloat("rate", FareRules.RATE_PER_KM.toFloat())
        set(v) = prefs.edit().putFloat("rate", v).apply()
    var minFare: Int
        get() = prefs.getInt("minfare", FareRules.MIN_FARE)
        set(v) = prefs.edit().putInt("minfare", v).apply()

    private fun fareKey(a: String, b: String) = listOf(a, b).sorted().joinToString("|")

    /** Fixed prices per route pair ("A|B" -> Rs), valid in both directions. */
    fun customFares(): Map<String, Int> {
        val o = JSONObject(prefs.getString("fares", "{}"))
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }

    fun setCustomFare(a: String, b: String, price: Int) {
        val o = JSONObject(prefs.getString("fares", "{}"))
        o.put(fareKey(a, b), price)
        prefs.edit().putString("fares", o.toString()).apply()
    }

    fun removeCustomFare(key: String) {
        val o = JSONObject(prefs.getString("fares", "{}"))
        o.remove(key)
        prefs.edit().putString("fares", o.toString()).apply()
    }

    fun hasCustomFare(from: Stop, to: Stop) = customFares().containsKey(fareKey(from.name, to.name))

    /** Route price from Settings if set, otherwise distance x rate (min fare applies). */
    fun fare(from: Stop, to: Stop): Int =
        customFares()[fareKey(from.name, to.name)] ?: fareFor(from, to, ratePerKm.toDouble(), minFare)

    fun formatNumber(n: Long) = prefix + n.toString().padStart(6, '0')

    fun tickets(): List<Ticket> {
        val arr = JSONArray(prefs.getString("tickets", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Ticket(
                o.getString("number"), o.getString("from"), o.getString("to"), o.getInt("km"),
                o.getInt("pax"), o.getInt("fare"), o.getString("mode"), o.getLong("time"),
                o.optBoolean("cancelled"), o.optLong("cancelTime"), o.optInt("given")
            )
        }
    }

    private fun save(list: List<Ticket>) {
        val arr = JSONArray()
        list.takeLast(200).forEach {
            arr.put(JSONObject().put("number", it.number).put("from", it.from).put("to", it.to)
                .put("km", it.km).put("pax", it.pax).put("fare", it.fare).put("mode", it.mode)
                .put("time", it.time).put("cancelled", it.cancelled).put("cancelTime", it.cancelTime).put("given", it.given))
        }
        prefs.edit().putString("tickets", arr.toString()).apply()
    }

    /** Creates and records a ticket with the next number (the counter only advances in auto mode). */
    fun issue(from: Stop, to: Stop, pax: Int, mode: String, fare: Int, given: Int = 0): Ticket {
        val t = Ticket(formatNumber(nextNumber), from.name, to.name, roadKm(from, to).toInt(),
            pax, fare, mode, System.currentTimeMillis(), given = given)
        save(tickets() + t)
        if (autoCount) nextNumber += 1
        return t
    }

    /** Undo issue() when the printer failed, so no number is wasted. */
    fun revoke(t: Ticket) {
        save(tickets().filterNot { it.number == t.number && it.time == t.time })
        if (autoCount && nextNumber > 1) nextNumber -= 1
    }

    fun markCancelled(t: Ticket): Ticket {
        val c = t.copy(cancelled = true, cancelTime = System.currentTimeMillis())
        save(tickets().map { if (it.number == t.number && it.time == t.time) c else it })
        return c
    }
}
