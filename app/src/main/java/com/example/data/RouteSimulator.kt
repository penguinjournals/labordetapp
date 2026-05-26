package com.example.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos

data class MapRoute(
    val id: String,
    val name: String,
    val description: String,
    val waypoints: List<RoutePoint>,
    val milestones: List<Milestone>
)

data class RoutePoint(
    val latitude: Double,
    val longitude: Double
)

data class Milestone(
    val title: String,
    val summary: String,
    val latitude: Double,
    val longitude: Double,
    val targetIndex: Int // Index in waypoint list where landmark is situated
)

class RouteSimulator {

    // Presets matching the landmark options for clean, interactive previews
    val routes = listOf(
        MapRoute(
            id = "route_mudejar",
            name = "Ruta Aragonesa (Zaragoza ➔ Belchite ➔ Teruel)",
            description = "Cruza el desierto de los Monegros y el páramo mudéjar hasta llegar al sur aragonés.",
            waypoints = generatePointsBetween(41.6568, -0.8783, 40.3426, -1.1072, steps = 100),
            milestones = listOf(
                Milestone(
                    title = "Basílica del Pilar (Zaragoza)",
                    summary = "La Catedral-Basílica de Nuestra Señora del Pilar es un importante templo barroco situado a orillas del río Ebro en Zaragoza. Cuenta la tradición que es el primer templo mariano de la Cristiandad, erigido en torno a la columna o pilar donde se apareció la Virgen María al apóstol Santiago el Mayor. Un lugar lleno de historia, cantado por Labordet en su juventud.",
                    latitude = 41.6568,
                    longitude = -0.8783,
                    targetIndex = 0
                ),
                Milestone(
                    title = "Pueblo Viejo de Belchite",
                    summary = "Belchite es el testimonio mudo del horror de la Guerra Civil española. El pueblo viejo fue destruido por completo durante una cruenta batalla en mil novecientos treinta y siete y se dejó sin reconstruir como monumento conmemorativo. Sus ruinas evocan un hondo silencio histórico que hiela el alma de quien lo visita.",
                    latitude = 41.3031,
                    longitude = -0.7505,
                    targetIndex = 35
                ),
                Milestone(
                    title = "Torre de El Salvador (Teruel)",
                    summary = "La Torre de El Salvador es una espectacular estructura de arte mudéjar aragonés del siglo trece, declarada Patrimonio de la Humanidad. El mudéjar combina la arquitectura cristiana con los detalles ornamentales del arte islámico utilizando ladrillo y cerámica vidriada multicolor, creando un tapiz visual que adorna Teruel.",
                    latitude = 40.3426,
                    longitude = -1.1072,
                    targetIndex = 98
                )
            )
        ),
        MapRoute(
            id = "route_prepyrenees",
            name = "Senda del Pirineo (Huesca ➔ Loarre ➔ Canfranc)",
            description = "Desde las llanuras oscenses ascendiendo por fortalezas medievales hasta la cumbre pirenaica.",
            waypoints = generatePointsBetween(42.1318, -0.4081, 42.7162, -0.5147, steps = 100),
            milestones = listOf(
                Milestone(
                    title = "Castillo de Loarre",
                    summary = "El Castillo de Loarre es una imponente fortaleza románica del siglo once en la provincia de Huesca. Se asienta sobre un espolón de roca caliza dominando toda la llanura de la Hoya de Huesca. Es el castillo románico mejor conservado de Europa y ha servido de escenario cinematográfico en múltiples películas históricas.",
                    latitude = 42.3258,
                    longitude = -0.6117,
                    targetIndex = 40
                ),
                Milestone(
                    title = "Estación de Canfranc",
                    summary = "La Estación Internacional de Ferrocarril de Canfranc es un majestuoso edificio de estilo industrial inaugurado en 1928, en pleno Pirineo Aragonés. Fue un nexo vital de comercio y de espías durante la Segunda Guerra Mundial, y tras décadas de abandono melancólico que inspiraron poesía, hoy ha sido rehabilitada como un hotel de lujo.",
                    latitude = 42.7162,
                    longitude = -0.5147,
                    targetIndex = 95
                )
            )
        )
    )

    private val _currentPointIndex = MutableStateFlow(0)
    val currentPointIndex: StateFlow<Int> = _currentPointIndex.asStateFlow()

    private val _activeRoute = MutableStateFlow<MapRoute?>(null)
    val activeRoute: StateFlow<MapRoute?> = _activeRoute.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // speed in km/h (simulate fast so a user can preview in an emulator without waiting hours)
    private val _simulationSpeedMultiplier = MutableStateFlow(2) 
    val simulationSpeedMultiplier: StateFlow<Int> = _simulationSpeedMultiplier.asStateFlow()

    fun selectRoute(routeId: String) {
        val found = routes.find { it.id == routeId } ?: routes.first()
        _activeRoute.value = found
        _currentPointIndex.value = 0
        _isPlaying.value = false
    }

    fun togglePlay() {
        _isPlaying.value = !_isPlaying.value
    }

    fun setSpeedMultiplier(multiplier: Int) {
        _simulationSpeedMultiplier.value = multiplier.coerceIn(1, 15)
    }

    fun setIndex(index: Int) {
        val route = _activeRoute.value ?: return
        _currentPointIndex.value = index.coerceIn(0, route.waypoints.lastIndex)
    }

    suspend fun runSimulationLoop(onLocationChanged: (Double, Double) -> Unit) {
        while (true) {
            delay(1000)
            val route = _activeRoute.value
            if (route != null && _isPlaying.value) {
                val currentIndex = _currentPointIndex.value
                val nextIndex = currentIndex + _simulationSpeedMultiplier.value
                
                if (nextIndex <= route.waypoints.lastIndex) {
                    _currentPointIndex.value = nextIndex
                    val point = route.waypoints[nextIndex]
                    onLocationChanged(point.latitude, point.longitude)
                } else {
                    // Loop or stop
                    _currentPointIndex.value = 0
                    val point = route.waypoints[0]
                    onLocationChanged(point.latitude, point.longitude)
                }
            }
        }
    }

    companion object {
        // Linear path interpolation for simulation
        private fun generatePointsBetween(
            startLat: Double, startLng: Double,
            endLat: Double, endLng: Double,
            steps: Int
        ): List<RoutePoint> {
            val list = mutableListOf<RoutePoint>()
            // Mix in some slight random bezier curvatures so the coordinates feel curvy as if on real roads
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val lat = startLat + (endLat - startLat) * t
                val lng = startLng + (endLng - startLng) * t
                
                // Add minor waving offset based on sine wave to simulate highway curves
                val curveOffsetLat = 0.015 * Math.sin(t * Math.PI * 4)
                val curveOffsetLng = 0.015 * Math.cos(t * Math.PI * 4)
                
                list.add(RoutePoint(lat + curveOffsetLat * (1 - t) * t, lng + curveOffsetLng * (1 - t) * t))
            }
            return list
        }
    }
}
