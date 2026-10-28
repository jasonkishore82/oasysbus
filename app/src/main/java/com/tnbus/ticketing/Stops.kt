package com.tnbus.ticketing

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class Stop(val name: String, val lat: Double, val lon: Double)

// Fare settings - adjust to the real tariff.
object FareRules {
    const val RATE_PER_KM = 1.40      // Rs per km
    const val MIN_FARE = 5            // Rs
    const val ROAD_FACTOR = 1.25      // straight-line -> approx. road distance
}

val STOPS = listOf(
    Stop("Chennai", 13.0827, 80.2707),
    Stop("Tambaram", 12.9249, 80.1000),
    Stop("Chengalpattu", 12.6819, 79.9888),
    Stop("Kanchipuram", 12.8342, 79.7036),
    Stop("Vellore", 12.9165, 79.1325),
    Stop("Tiruvannamalai", 12.2253, 79.0747),
    Stop("Villupuram", 11.9401, 79.4861),
    Stop("Pondicherry", 11.9416, 79.8083),
    Stop("Cuddalore", 11.7480, 79.7714),
    Stop("Salem", 11.6643, 78.1460),
    Stop("Namakkal", 11.2189, 78.1674),
    Stop("Erode", 11.3410, 77.7172),
    Stop("Tiruppur", 11.1085, 77.3411),
    Stop("Coimbatore", 11.0168, 76.9558),
    Stop("Ooty", 11.4102, 76.6950),
    Stop("Karur", 10.9601, 78.0766),
    Stop("Tiruchirappalli", 10.7905, 78.7047),
    Stop("Thanjavur", 10.7870, 79.1378),
    Stop("Kumbakonam", 10.9602, 79.3845),
    Stop("Dindigul", 10.3673, 77.9803),
    Stop("Madurai", 9.9252, 78.1198),
    Stop("Virudhunagar", 9.5680, 77.9624),
    Stop("Sivakasi", 9.4533, 77.8024),
    Stop("Tirunelveli", 8.7139, 77.7567),
    Stop("Thoothukudi", 8.7642, 78.1348),
    Stop("Nagercoil", 8.1833, 77.4119),
    Stop("Kanyakumari", 8.0883, 77.5385),
    Stop("Rameswaram", 9.2876, 79.3129),
)

fun distanceKm(a: Stop, b: Stop): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(h), sqrt(1 - h))
}

fun nearestStop(lat: Double, lon: Double): Stop =
    STOPS.minBy { distanceKm(Stop("", lat, lon), it) }

fun roadKm(from: Stop, to: Stop): Double = distanceKm(from, to) * FareRules.ROAD_FACTOR

fun fareFor(from: Stop, to: Stop, rate: Double = FareRules.RATE_PER_KM, minFare: Int = FareRules.MIN_FARE): Int =
    max(minFare, ceil(roadKm(from, to) * rate).toInt())
