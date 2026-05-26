package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Landmark
import com.example.data.MapRoute
import com.example.data.Milestone
import com.example.data.RouteSimulator
import com.example.data.WikiRepository
import com.example.speech.SpeechManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppMode {
    PASEAR,   // Walking audioguide
    DESCUBRIR // Road trip gps-guided audioguide
}

class LabordetViewModel(
    private val wikiRepository: WikiRepository = WikiRepository()
) : ViewModel() {

    private val tag = "LabordetViewModel"

    // App core states
    private val _currentMode = MutableStateFlow(AppMode.PASEAR)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    private val _latitude = MutableStateFlow(41.6568) // Default Zaragoza
    val latitude: StateFlow<Double> = _latitude.asStateFlow()

    private val _longitude = MutableStateFlow(-0.8783)
    val longitude: StateFlow<Double> = _longitude.asStateFlow()

    private val _isMockLocation = MutableStateFlow(true) // Start with Mock Zaragoza for beautiful first-run UX
    val isMockLocation: StateFlow<Boolean> = _isMockLocation.asStateFlow()

    private val _allLandmarks = MutableStateFlow<List<Landmark>>(emptyList())
    val allLandmarks: StateFlow<List<Landmark>> = _allLandmarks.asStateFlow()

    // Candidates loaded in windows of 3, as requested: "leerá de 3 en 3"
    private val _displayedLandmarks = MutableStateFlow<List<Landmark>>(emptyList())
    val displayedLandmarks: StateFlow<List<Landmark>> = _displayedLandmarks.asStateFlow()

    private val _candidatePageIndex = MutableStateFlow(0)
    val candidatePageIndex: StateFlow<Int> = _candidatePageIndex.asStateFlow()

    private val _selectedLandmark = MutableStateFlow<Landmark?>(null)
    val selectedLandmark: StateFlow<Landmark?> = _selectedLandmark.asStateFlow()

    private val _isHandsFree = MutableStateFlow(true) // Enabled by default for secure drive focus
    val isHandsFree: StateFlow<Boolean> = _isHandsFree.asStateFlow()

    // Route Simulator
    val routeSimulator = RouteSimulator()
    private var simulationJob: Job? = null

    // For keeping track of already played spots so they don't loop endlessly
    private val narratedMilestones = mutableSetOf<String>()

    // Tracking upcoming milestone info
    private val _upcomingMilestone = MutableStateFlow<Milestone?>(null)
    val upcomingMilestone: StateFlow<Milestone?> = _upcomingMilestone.asStateFlow()

    private val _secondsToMilestone = MutableStateFlow<Int?>(null)
    val secondsToMilestone: StateFlow<Int?> = _secondsToMilestone.asStateFlow()

    private val _secondsSpeechDuration = MutableStateFlow<Int?>(null)
    val secondsSpeechDuration: StateFlow<Int?> = _secondsSpeechDuration.asStateFlow()

    // Speech manager connector
    private var speechManager: SpeechManager? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    init {
        // Prepare initial route selection
        routeSimulator.selectRoute(routeSimulator.routes.first().id)
        startSimulationWorker()
        refreshNearbyPlaces()
    }

    fun bindSpeechManager(manager: SpeechManager) {
        this.speechManager = manager
        // Sync initial state
        manager.setVoiceGender(manager.isMaleVoice.value)
        manager.isHandsFreeActive = _isHandsFree.value
    }

    fun toggleVoiceGender() {
        val manager = speechManager ?: return
        val currentMale = manager.isMaleVoice.value
        manager.setVoiceGender(!currentMale)
    }

    fun isMaleVoiceEnabled(): Boolean {
        return speechManager?.isMaleVoice?.value ?: false
    }

    fun setHandsFree(enabled: Boolean) {
        _isHandsFree.value = enabled
        speechManager?.isHandsFreeActive = enabled
        if (!enabled) {
            speechManager?.stopVoiceListening()
        } else {
            // Restart candidate speech or listening if we are waiting
            val state = speechManager?.state?.value
            if (state == com.example.speech.SpeechState.WAITING_FOR_SELECTION || _currentMode.value == AppMode.DESCUBRIR) {
                speechManager?.startVoiceListening()
            }
        }
    }

    fun toggleMode(mode: AppMode) {
        _currentMode.value = mode
        stopPlayback()
        
        if (mode == AppMode.DESCUBRIR) {
            // Auto switch to mock route location to let simulator drive
            _isMockLocation.value = true
            syncToCurrentSimulatorPoint()
            if (_isHandsFree.value) {
                speechManager?.isHandsFreeActive = true
                speechManager?.startVoiceListening()
            }
        } else {
            // Resume default location setting
            _isMockLocation.value = false
            refreshNearbyPlaces()
        }
    }

    fun selectPresetRoute(routeId: String) {
        routeSimulator.selectRoute(routeId)
        narratedMilestones.clear()
        _upcomingMilestone.value = null
        _secondsToMilestone.value = null
        _secondsSpeechDuration.value = null
        stopPlayback()
        syncToCurrentSimulatorPoint()
    }

    private fun syncToCurrentSimulatorPoint() {
        val route = routeSimulator.activeRoute.value ?: return
        val idx = routeSimulator.currentPointIndex.value
        if (idx in route.waypoints.indices) {
            val point = route.waypoints[idx]
            updateLocation(point.latitude, point.longitude)
        }
    }

    fun startSimulationWorker() {
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            routeSimulator.runSimulationLoop { lat, lng ->
                if (_isMockLocation.value && _currentMode.value == AppMode.DESCUBRIR) {
                    updateLocation(lat, lng)
                }
            }
        }
    }

    private fun updateLocation(lat: Double, lng: Double) {
        _latitude.value = lat
        _longitude.value = lng
        
        // Compute upcoming milestone math in Driving/Descubrir mode
        if (_currentMode.value == AppMode.DESCUBRIR) {
            evaluateUpcomingMilestoneTriggers(lat, lng)
        } else {
            // In walking mode, query places is triggered dynamically when location shifts
            // limit API requests to save resources but keep it active
            refreshNearbyPlaces()
        }
    }

    private fun evaluateUpcomingMilestoneTriggers(lat: Double, lng: Double) {
        val route = routeSimulator.activeRoute.value ?: return
        val currentIndex = routeSimulator.currentPointIndex.value
        
        // Get remaining upcoming list of milestones
        val upcoming = route.milestones.filter { it.targetIndex > currentIndex }
        val nextMilestone = upcoming.firstOrNull()
        _upcomingMilestone.value = nextMilestone

        if (nextMilestone != null) {
            val distance = wikiRepository.calculateDistanceInMeters(
                lat, lng, nextMilestone.latitude, nextMilestone.longitude
            )

            // Dynamic speed model: let's assume standard car speed is 90 km/h (25 meters/second)
            // Multiplying by simulation speed multiplier allows testing in an accelerated flow
            val baseSpeedMps = 25.0 
            val multiplier = routeSimulator.simulationSpeedMultiplier.value
            val simulatedSpeedMps = baseSpeedMps * multiplier

            val secondsToReach = (distance / simulatedSpeedMps).toInt()
            _secondsToMilestone.value = secondsToReach

            // Calculate speech word duration
            val words = nextMilestone.summary.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            val speechReadTimeSec = (words / 130.0 * 60).toInt().coerceAtLeast(10)
            _secondsSpeechDuration.value = speechReadTimeSec

            // Log parameters for debugging
            Log.d(tag, "Milestone approach: ${nextMilestone.title}, Distance: $distance m, ETA: $secondsToReach s, TTS Speech len: $speechReadTimeSec s")

            // Trigger! trigger early: ETA <= Speech duration
            if (secondsToReach <= speechReadTimeSec && !narratedMilestones.contains(nextMilestone.title)) {
                narratedMilestones.add(nextMilestone.title)
                Log.d(tag, "Triggering ahead of arrival! Reading milestone: ${nextMilestone.title}")
                
                // Construct temporary Landmark and narrate
                val landmark = Landmark(
                    id = "milestone_${nextMilestone.targetIndex}",
                    title = nextMilestone.title,
                    summary = nextMilestone.summary,
                    latitude = nextMilestone.latitude,
                    longitude = nextMilestone.longitude,
                    distanceMeters = distance
                )
                
                _selectedLandmark.value = landmark
                speechManager?.narrate(landmark.title, landmark.summary)
            }
        } else {
            _secondsToMilestone.value = null
            _secondsSpeechDuration.value = null
        }
    }

    fun setLocationOption(useMock: Boolean) {
        _isMockLocation.value = useMock
        if (useMock) {
            if (_currentMode.value == AppMode.DESCUBRIR) {
                syncToCurrentSimulatorPoint()
            } else {
                // Pin to default Zaragoza Center
                updateLocation(41.6568, -0.8783)
            }
        } else {
            speechManager?.stopAllSpeech()
            requestRealLocationUpdates()
        }
    }

    fun refreshNearbyPlaces() {
        viewModelScope.launch {
            val list = wikiRepository.getNearbyLandmarks(_latitude.value, _longitude.value)
            _allLandmarks.value = list
            _candidatePageIndex.value = 0
            updateDisplayedCandidates(0)
        }
    }

    private fun updateDisplayedCandidates(pageIndex: Int) {
        val items = _allLandmarks.value
        val start = pageIndex * 3
        if (start < items.size) {
            val subList = items.subList(start, (start + 3).coerceAtMost(items.size))
            _displayedLandmarks.value = subList

            // Hands-Free Auto speech candidate triggers, read them aloud
            if (_isHandsFree.value && _currentMode.value == AppMode.PASEAR) {
                triggerCandidateAudibleReadout()
            }
        } else {
            // Wrap or fetch default
            _candidatePageIndex.value = 0
            if (items.isNotEmpty()) {
                updateDisplayedCandidates(0)
            }
        }
    }

    fun triggerCandidateAudibleReadout() {
        val candidates = _displayedLandmarks.value
        if (candidates.isEmpty()) return
        
        speechManager?.speakCandidates(
            introText = "He encontrado lugares de interés cercanos.",
            candidateTitles = candidates.map { it.title },
            onDone = {
                Log.d(tag, "Candidate readout intro has concluded speaking")
            }
        )
    }

    // Interactive selections (from screen or voices)
    fun selectCandidateIndex(index: Int) {
        val candidates = _displayedLandmarks.value
        if (index in candidates.indices) {
            val selected = candidates[index]
            _selectedLandmark.value = selected
            speechManager?.narrate(selected.title, selected.summary)
        }
    }

    fun requestMoreCandidates() {
        // Scroll to next window of 3 candidates
        val totalSize = _allLandmarks.value.size
        val nextStart = (_candidatePageIndex.value + 1) * 3
        if (nextStart < totalSize) {
            val nextIndex = _candidatePageIndex.value + 1
            _candidatePageIndex.value = nextIndex
            updateDisplayedCandidates(nextIndex)
        } else {
            // Loop back to index 0
            _candidatePageIndex.value = 0
            updateDisplayedCandidates(0)
        }
    }

    fun pausePlayback() {
        speechManager?.pauseNarration()
    }

    fun resumePlayback() {
        val selected = _selectedLandmark.value
        if (selected != null) {
            speechManager?.resumeNarration {
                speechManager?.narrate(selected.title, selected.summary)
            }
        }
    }

    fun skipOrNext() {
        speechManager?.stopAllSpeech()
        // If narrating currently, stop and clear. If on candidates list, scroll candidates.
        val currentTtsState = speechManager?.state?.value
        if (currentTtsState == com.example.speech.SpeechState.NARRATING_LANDMARK || _selectedLandmark.value != null) {
            _selectedLandmark.value = null
            // Return to candidates list reading in Walking mode
            if (_currentMode.value == AppMode.PASEAR) {
                updateDisplayedCandidates(_candidatePageIndex.value)
            }
        } else {
            // We were looking at candidate choice list, scroll to next three!
            requestMoreCandidates()
        }
    }

    fun stopPlayback() {
        _selectedLandmark.value = null
        speechManager?.stopAllSpeech()
    }

    // Handles SpeechManager voice signals
    fun handleVoiceCommandInput(action: SpeechManager.ActionType) {
        Log.d(tag, "Received hands-free voice trigger callback: $action")
        when (action) {
            SpeechManager.ActionType.SELECT_ONE -> selectCandidateIndex(0)
            SpeechManager.ActionType.SELECT_TWO -> selectCandidateIndex(1)
            SpeechManager.ActionType.SELECT_THREE -> selectCandidateIndex(2)
            SpeechManager.ActionType.REQUEST_MORE -> requestMoreCandidates()
            SpeechManager.ActionType.SKIP_NEXT -> skipOrNext()
            SpeechManager.ActionType.PAUSE -> {
                // speechManager already internally paused, let's keep state matching
            }
            SpeechManager.ActionType.RESUME -> {
                resumePlayback()
            }
            SpeechManager.ActionType.MORE_INFO -> {
                val selected = _selectedLandmark.value
                if (selected != null) {
                    val extendedSummary = "${selected.summary} Además, es de gran relevancia señalar la inestimable riqueza patrimonial, folclórica e histórica de este emblemático destino de Aragón, el cual atrae a miles de viajeros y de apasionados de la cultura de todas partes."
                    _selectedLandmark.value = selected.copy(summary = extendedSummary)
                    speechManager?.narrate(selected.title, extendedSummary)
                } else {
                    val upcoming = _upcomingMilestone.value
                    if (upcoming != null) {
                        val extendedSummary = "${upcoming.summary} Este singular hito geográfico ofrece vistas sumamente conmovedoras y representa una parada indispensable en toda andadura por estas bellas tierras."
                        speechManager?.narrate(upcoming.title, extendedSummary)
                    } else {
                        speechManager?.speakCandidates("No hay ningún elemento seleccionado actualmente. Prueba a seleccionar un lugar primero.", emptyList()) {}
                    }
                }
            }
            SpeechManager.ActionType.NEXT_ROUTE -> {
                if (_currentMode.value == AppMode.DESCUBRIR) {
                    val currentRouteId = routeSimulator.activeRoute.value?.id ?: ""
                    val routesList = routeSimulator.routes
                    val currentIndex = routesList.indexOfFirst { it.id == currentRouteId }
                    val nextIndex = (currentIndex + 1) % routesList.size
                    val nextRoute = routesList[nextIndex]
                    
                    selectPresetRoute(nextRoute.id)
                    // If simulator wasn't playing, turn it on so they enjoy driving!
                    if (!routeSimulator.isPlaying.value) {
                        routeSimulator.togglePlay()
                    }
                    val introMessage = "Cambiando de itinerario activo. Nueva ruta seleccionada: ${nextRoute.name}."
                    speechManager?.speakCandidates(introMessage, emptyList()) {}
                } else {
                    speechManager?.speakCandidates("El cambio rápido de ruta solo está habilitado en el modo Descubrir en carretera.", emptyList()) {}
                }
            }
            SpeechManager.ActionType.REPEAT_NARRATION -> {
                val selected = _selectedLandmark.value
                if (selected != null) {
                    speechManager?.narrate(selected.title, selected.summary)
                } else {
                    val upcoming = _upcomingMilestone.value
                    if (upcoming != null) {
                        speechManager?.narrate(upcoming.title, upcoming.summary)
                    } else {
                        speechManager?.speakCandidates("No hay ninguna narración en reproducción para repetir.", emptyList()) {}
                    }
                }
            }
        }
    }

    // Live Geolocation Updates via FusedLocationProviderClient
    @SuppressLint("MissingPermission")
    fun initFusedLocationClient(context: Context) {
        if (fusedLocationClient != null) return
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (_isMockLocation.value) return // Ignore GPS if mocking
                val location = locationResult.lastLocation
                if (location != null) {
                    Log.d(tag, "Fused Location received: ${location.latitude}, ${location.longitude}")
                    updateLocation(location.latitude, location.longitude)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestRealLocationUpdates() {
        val client = fusedLocationClient ?: return
        val callback = locationCallback ?: return
        try {
            val req = LocationRequest.Builder(
                LocationRequest.PRIORITY_HIGH_ACCURACY, 5000
            ).apply {
                setMinUpdateIntervalMillis(3000)
            }.build()

            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            // Immediately pull last known
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !_isMockLocation.value) {
                    updateLocation(loc.latitude, loc.longitude)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error setting up Android GPS tracking", e)
        }
    }

    fun cancelRealLocationUpdates() {
        val client = fusedLocationClient
        val callback = locationCallback
        if (client != null && callback != null) {
            client.removeLocationUpdates(callback)
        }
    }

    override fun onCleared() {
        super.onCleared()
        simulationJob?.cancel()
        cancelRealLocationUpdates()
        speechManager?.destroy()
    }
}
