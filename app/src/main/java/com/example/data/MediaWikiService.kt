package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// MediaWiki API Response Models
data class WikiResponse(
    @Json(name = "query") val query: WikiQuery? = null
)

data class WikiQuery(
    @Json(name = "pages") val pages: Map<String, WikiPage>? = null
)

data class WikiPage(
    @Json(name = "pageid") val pageId: Long,
    @Json(name = "title") val title: String,
    @Json(name = "index") val index: Int = 0,
    @Json(name = "extract") val extract: String? = null,
    @Json(name = "coordinates") val coordinates: List<WikiCoordinate>? = null,
    @Json(name = "thumbnail") val thumbnail: WikiThumbnail? = null
)

data class WikiCoordinate(
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double
)

data class WikiThumbnail(
    @Json(name = "source") val source: String,
    @Json(name = "width") val width: Int,
    @Json(name = "height") val height: Int
)

interface MediaWikiService {
    @GET("w/api.php")
    suspend fun getNearbyPlaces(
        @Query("action") action: String = "query",
        @Query("format") format: String = "json",
        @Query("generator") generator: String = "geosearch",
        @Query("ggscoord") coord: String, // "lat|lon"
        @Query("ggsradius") radius: Int = 10000, // 10km radius
        @Query("ggslimit") limit: Int = 15,
        @Query("prop") prop: String = "coordinates|extracts|pageimages",
        @Query("exintro") exintro: Int = 1,
        @Query("explaintext") explaintext: Int = 1,
        @Query("piprop") piprop: String = "thumbnail",
        @Query("pithumbsize") thumbsize: Int = 300,
        @Query("formatversion") formatversion: Int = 2
    ): WikiResponse

    companion object {
        private const val BASE_URL = "https://es.wikipedia.org/"

        fun create(): MediaWikiService {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(MediaWikiService::class.java)
        }
    }
}

// Model parsed to expose inside the app
data class Landmark(
    val id: String,
    val title: String,
    val summary: String,
    val latitude: Double,
    val longitude: Double,
    val imageUrl: String? = null,
    val distanceMeters: Int = 0
) {
    // Dynamically calculate estimated reading length of summary (words / standard speaking speed ~ 130 words per minute)
    val readDurationSeconds: Int
        get() {
            val words = summary.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            val seconds = (words / 130.0 * 60).toInt()
            return seconds.coerceAtLeast(10) // minimum 10 seconds for layout completeness
        }
}

// Preset Aragón landmarks for hands-on simulation, testing, and absolute offline safety (themed after "Un país en la mochila")
object LabordetPresets {
    val presets = listOf(
        Landmark(
            id = "preset_pilar",
            title = "Basílica del Pilar (Zaragoza)",
            summary = "La Catedral-Basílica de Nuestra Señora del Pilar es un importante templo barroco situado a orillas del río Ebro en Zaragoza. Cuenta la tradición que es el primer templo mariano de la Cristiandad, erigido en torno a la columna o pilar donde se apareció la Virgen María al apóstol Santiago el Mayor. Un lugar lleno de historia, cantado por Labordet en su juventud.",
            latitude = 41.6568,
            longitude = -0.8783,
            imageUrl = null,
            distanceMeters = 150
        ),
        Landmark(
            id = "preset_albarracin",
            title = "Albarracín, Teruel",
            summary = "Albarracín es una joya medieval colgada sobre el meandro del río Guadalaviar en Teruel. Con sus calles empedradas, sus casas de color rojizo característico de la yesería local y sus imponentes murallas, es considerado uno de los pueblos más bonitos de España. Un rincón ideal para caminar con mochila pesada y soñar despierto.",
            latitude = 40.4077,
            longitude = -1.4423,
            imageUrl = null,
            distanceMeters = 800
        ),
        Landmark(
            id = "preset_canfranc",
            title = "Estación de Canfranc",
            summary = "La Estación Internacional de Ferrocarril de Canfranc es un majestuoso edificio de estilo industrial inaugurado en 1928, en pleno Pirineo Aragonés. Fue un nexo vital de comercio y de espías durante la Segunda Guerra Mundial, y tras décadas de abandono melancólico que inspiraron poesía, hoy ha sido rehabilitada como un hotel de lujo.",
            latitude = 42.7162,
            longitude = -0.5147,
            imageUrl = null,
            distanceMeters = 2200
        ),
        Landmark(
            id = "preset_loarre",
            title = "Castillo de Loarre",
            summary = "El Castillo de Loarre es una imponente fortaleza románica del siglo once en la provincia de Huesca. Se asienta sobre un espolón de roca caliza dominando toda la llanura de la Hoya de Huesca. Es el castillo románico mejor conservado de Europa y ha servido de escenario cinematográfico en múltiples películas históricas.",
            latitude = 42.3258,
            longitude = -0.6117,
            imageUrl = null,
            distanceMeters = 1400
        ),
        Landmark(
            id = "preset_teruel",
            title = "Torre de El Salvador (Teruel)",
            summary = "La Torre de El Salvador es una espectacular estructura de arte mudéjar aragonés del siglo trece, declarada Patrimonio de la Humanidad. El mudéjar combina la arquitectura cristiana con los detalles ornamentales del arte islámico utilizando ladrillo y cerámica vidriada multicolor, creando un tapiz visual que adorna Teruel.",
            latitude = 40.3426,
            longitude = -1.1072,
            imageUrl = null,
            distanceMeters = 300
        ),
        Landmark(
            id = "preset_belchite",
            title = "Pueblo Viejo de Belchite",
            summary = "Belchite es el testimonio mudo del horror de la Guerra Civil española. El pueblo viejo fue destruido por completo durante una cruenta batalla en mil novecientos treinta y siete y se dejó sin reconstruir como monumento conmemorativo. Sus ruinas evocan un hondo silencio histórico que hiela el alma de quien lo visita.",
            latitude = 41.3031,
            longitude = -0.7505,
            imageUrl = null,
            distanceMeters = 950
        )
    )
}
