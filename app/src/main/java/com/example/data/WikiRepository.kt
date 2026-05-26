package com.example.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class WikiRepository(
    private val service: MediaWikiService = MediaWikiService.create()
) {
    private val tag = "WikiRepository"

    suspend fun getNearbyLandmarks(latitude: Double, longitude: Double, radiusMeters: Int = 10000): List<Landmark> {
        return withContext(Dispatchers.IO) {
            try {
                val coordString = "$latitude|$longitude"
                Log.d(tag, "Fetching from MediaWiki with coords: $coordString")
                
                val response = service.getNearbyPlaces(
                    coord = coordString,
                    radius = radiusMeters,
                    limit = 20
                )

                val pages = response.query?.pages?.values?.toList() ?: emptyList()
                Log.d(tag, "Received ${pages.size} pages from MediaWiki")

                if (pages.isEmpty()) {
                    Log.d(tag, "MediaWiki returned empty. Using fallback presets.")
                    return@withContext getFallbackPresetLandmarks(latitude, longitude)
                }

                pages.sortedBy { it.index }.map { page ->
                    val mainCoord = page.coordinates?.firstOrNull()
                    val pageLat = mainCoord?.lat ?: latitude
                    val pageLng = mainCoord?.lon ?: longitude
                    val distance = calculateDistanceInMeters(latitude, longitude, pageLat, pageLng)
                    
                    Landmark(
                        id = page.pageId.toString(),
                        title = page.title,
                        summary = page.extract ?: "Lugar de interés histórico y cultural cercano.",
                        latitude = pageLat,
                        longitude = pageLng,
                        imageUrl = page.thumbnail?.source,
                        distanceMeters = distance
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Error loading MediaWiki pages: ${e.localizedMessage}. Using presets.", e)
                getFallbackPresetLandmarks(latitude, longitude)
            }
        }
    }

    private fun getFallbackPresetLandmarks(currentLat: Double, currentLng: Double): List<Landmark> {
        // Compute dynamic distance to presets so that nearby calculations feel alive
        return LabordetPresets.presets.map { preset ->
            val distance = calculateDistanceInMeters(currentLat, currentLng, preset.latitude, preset.longitude)
            preset.copy(distanceMeters = distance)
        }.sortedBy { it.distanceMeters }
    }

    // Haversine formula to compute distance in meters
    fun calculateDistanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371e3 // Earth's radius in meters
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return (r * c).toInt()
    }
}
