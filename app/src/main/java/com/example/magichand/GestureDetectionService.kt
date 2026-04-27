package com.example.magichand

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class GestureDetectionService : Service(), SensorEventListener {

    private val CHANNEL_ID = "MagicHandGestureDetectionChannel"
    private val NOTIFICATION_ID = 1

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private lateinit var audioManager: AudioManager
    private lateinit var cameraManager: CameraManager
    private var isFlashlightOn = false

    // Live detection buffer (sliding window)
    private val liveBuffer = LinkedList<SensorPoint>()
    private val bufferSize = 100 // Target 2 seconds of data at ~50Hz (SENSOR_DELAY_GAME)

    // Low-pass filter variables
    private var lastFilteredPoint = SensorPoint(0f, 0f, 0f)
    private val LOW_PASS_ALPHA = 0.5f // Decreased from 0.8f for more smoothing

    // Step-wise classification variables
    private var sensorEventCount = 0
    private val CLASSIFICATION_STRIDE = 10 // Classify every 10th sensor event

    // Back-off timer variables
    private var isGestureCooldown = false
    private val handler = Handler(Looper.getMainLooper())

    // SharedPreferences constants
    private val SHARED_PREFS_NAME = "MagicHandGesturePrefs"
    private val GESTURE_LIBRARY_KEY = "gesture_library"
    private val GESTURE_ACTION_MAPPING_KEY = "gesture_action_mapping"
    private val SERVER_URL_KEY = "server_url"

    // Gesture detection variables
    private var gestureLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()
    private var gestureActionMapping = mutableMapOf<Int, GestureAction>()
    private var serverUrl: String? = null
    private var currentForegroundApp: String = ""
    private var currentActionMap: List<String>? = null

    // Network client and JSON parser
    private val client = OkHttpClient()
    private val gson = Gson()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == GESTURE_LIBRARY_KEY) {
            Log.i("GestureDetectionService", "Gesture library updated in SharedPreferences, reloading...")
            loadGestureLibrary()
        } else if (key == GESTURE_ACTION_MAPPING_KEY) {
            Log.i("GestureDetectionService", "Action mapping updated, reloading...")
            loadGestureActionMapping()
        } else if (key == SERVER_URL_KEY) { // Handle server URL updates
            Log.i("GestureDetectionService", "Server URL updated, reloading...")
            serverUrl = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).getString(SERVER_URL_KEY, null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        loadGestureLibrary()
        loadGestureActionMapping()

        val prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        serverUrl = prefs.getString(SERVER_URL_KEY, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Force reload library whenever start is called (e.g. from UI)
        loadGestureLibrary()
        loadGestureActionMapping()
        serverUrl = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).getString(SERVER_URL_KEY, null)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MagicHands is listening")
            .setContentText("Gesture detection is active.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        accelerometer?.let {
            // Using SENSOR_DELAY_GAME to match MainActivity's recording frequency
            // Set maxReportLatencyUs to 200ms for sensor batching
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 200_000)
            Log.i("GestureDetectionService", "Sensor listener registered with DELAY_GAME and batching (200ms).")
        }

        // Start foreground app monitoring
        startForegroundAppMonitoring()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sensorManager?.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        stopForegroundAppMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Gesture Detection Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val rawPoint = SensorPoint(x, y, z)

            // Apply low-pass filter
            lastFilteredPoint = lowPassFilter(rawPoint, lastFilteredPoint, LOW_PASS_ALPHA)

            if (!isGestureCooldown) {
                // Add filtered point to the sliding window
                liveBuffer.add(lastFilteredPoint)
                if (liveBuffer.size > bufferSize) {
                    liveBuffer.removeFirst()
                }

                sensorEventCount++
                if (sensorEventCount % CLASSIFICATION_STRIDE == 0 && liveBuffer.size == bufferSize) {
                    processLiveBuffer()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processLiveBuffer() {
        if (gestureLibrary.isEmpty()) {
            Log.d("GestureDetectionService", "Gesture library is empty. Skipping classification.")
            return
        }

        val templates = mutableListOf<GestureTemplate>()
        for ((slot, examples) in gestureLibrary) {
            for (example in examples) {
                templates.add(GestureTemplate(slot.toString(), example))
            }
        }

        // The threshold is tuned for normalized Unit Directional data and includes dynamic noise floor logic
        val detectedGestureSlot = classifyGesture(liveBuffer.toList(), templates, threshold = 0.8f) // Decreased from 1.2f for stricter matching

        if (detectedGestureSlot != "UNKNOWN") {
            showDetectedGesture(detectedGestureSlot.toInt())
        } else {
            Log.d("GestureDetectionService", "No gesture detected.")
        }
    }

    private fun showDetectedGesture(slot: Int) {
        isGestureCooldown = true
        Log.d("GestureDetectionService", "Gesture MATCHED in slot: $slot")

        val actionChar = when (slot) {
            0 -> currentActionMap?.getOrNull(0)
            1 -> currentActionMap?.getOrNull(1)
            2 -> currentActionMap?.getOrNull(2)
            else -> null
        }

        if (actionChar != null) {
            executeAction(actionChar)
        } else {
            // Fallback to locally mapped action if no server action or slot out of bounds
            val detectedAction = gestureActionMapping[slot] ?: GestureAction.NONE
            performGestureAction(detectedAction)
        }

        // Clear the buffer after a successful detection and before cooldown
        liveBuffer.clear()
        lastFilteredPoint = SensorPoint(0f, 0f, 0f) // Reset filter state

        handler.postDelayed({
            isGestureCooldown = false
            Log.d("GestureDetectionService", "Cooldown over, ready for next gesture")
        }, 1200) // Increased cooldown slightly
    }

    private fun performGestureAction(action: GestureAction) {
        when (action) {
            GestureAction.VOLUME_UP -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND)
                Log.i("GestureDetectionService", "Action: Volume Up performed")
            }
            GestureAction.VOLUME_DOWN -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND)
                Log.i("GestureDetectionService", "Action: Volume Down performed")
            }
            GestureAction.MEDIA_PLAY_PAUSE -> {
                val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(event)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(eventUp)
                Log.i("GestureDetectionService", "Action: Media Play/Pause performed")
            }
            GestureAction.MEDIA_NEXT -> {
                val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(event)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(eventUp)
                Log.i("GestureDetectionService", "Action: Media Next performed")
            }
            GestureAction.MEDIA_PREVIOUS -> {
                val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(event)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(eventUp)
                Log.i("GestureDetectionService", "Action: Media Previous performed")
            }
            GestureAction.TOGGLE_FLASHLIGHT -> {
                try {
                    val cameraId = cameraManager.cameraIdList[0]
                    isFlashlightOn = !isFlashlightOn
                    cameraManager.setTorchMode(cameraId, isFlashlightOn)
                    Log.i("GestureDetectionService", "Action: Toggle Flashlight performed. On: $isFlashlightOn")
                } catch (e: Exception) {
                    Log.e("GestureDetectionService", "Flashlight error: ${e.message}")
                }
            }
            GestureAction.TOGGLE_MUTE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        if (isMuted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE, 0)
                    Log.i("GestureDetectionService", "Action: Toggle Mute performed. Muted: ${!isMuted}")
                } else {
                    Log.i("GestureDetectionService", "Action: Toggle Mute not supported on this Android version.")
                }
            }
            GestureAction.NONE -> Log.i("GestureDetectionService", "Action: No Action")
        }
    }

    private fun executeAction(actionChar: String) {
        when (actionChar.uppercase(Locale.ROOT)) {
            "A" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND)
                Log.i("GestureDetectionService", "Server Action: Volume Up performed")
            }
            "B" -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND)
                Log.i("GestureDetectionService", "Server Action: Volume Down performed")
            }
            "C" -> {
                val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(event)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(eventUp)
                Log.i("GestureDetectionService", "Server Action: Media Play/Pause performed")
            }
            "D" -> {
                val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(event)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(eventUp)
                Log.i("GestureDetectionService", "Server Action: Media Next performed")
            }
            "E" -> {
                val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(event)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(eventUp)
                Log.i("GestureDetectionService", "Server Action: Media Previous performed")
            }
            "F" -> {
                try {
                    val cameraId = cameraManager.cameraIdList[0]
                    isFlashlightOn = !isFlashlightOn
                    cameraManager.setTorchMode(cameraId, isFlashlightOn)
                    Log.i("GestureDetectionService", "Server Action: Toggle Flashlight performed. On: $isFlashlightOn")
                } catch (e: Exception) {
                    Log.e("GestureDetectionService", "Server Action: Flashlight error: ${e.message}")
                }
            }
            else -> Log.i("GestureDetectionService", "Server Action: Unknown action character $actionChar")
        }
    }

    private fun loadGestureLibrary() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val serializedData = sharedPrefs.getString(GESTURE_LIBRARY_KEY, "") ?: ""
        gestureLibrary = deserializeGestureLibrary(serializedData).toMutableMap()
        Log.d("GestureDetectionService", "Library loaded: ${gestureLibrary.size} slots.")
    }

    private fun loadGestureActionMapping() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val serializedMapping = sharedPrefs.getString(GESTURE_ACTION_MAPPING_KEY, "") ?: ""
        gestureActionMapping = deserializeGestureActionMapping(serializedMapping).toMutableMap()
    }

    private fun deserializeGestureLibrary(serializedData: String): Map<Int, MutableList<List<SensorPoint>>> {
        val loadedLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()
        if (serializedData.isBlank()) return loadedLibrary
        val slotStrings = serializedData.split("~")
        for (slotString in slotStrings) {
            val colonIndex = slotString.indexOf(':')
            if (colonIndex == -1) continue
            val slotIndex = slotString.substring(0, colonIndex).toIntOrNull() ?: continue
            val examplesData = slotString.substring(colonIndex + 1)
            val examplesForSlot = mutableListOf<List<SensorPoint>>()
            val gestureExampleStrings = examplesData.split("#")
            for (gestureExampleString in gestureExampleStrings) {
                val points = mutableListOf<SensorPoint>()
                val pointStrings = gestureExampleString.split(";")
                for (pointString in pointStrings) {
                    val coords = pointString.split(",")
                    if (coords.size == 3) {
                        val x = coords[0].toFloatOrNull()
                        val y = coords[1].toFloatOrNull()
                        val z = coords[2].toFloatOrNull()
                        if (x != null && y != null && z != null) points.add(SensorPoint(x, y, z))
                    }
                }
                if (points.isNotEmpty()) examplesForSlot.add(points)
            }
            if (examplesForSlot.isNotEmpty()) loadedLibrary[slotIndex] = examplesForSlot
        }
        return loadedLibrary
    }

    private fun deserializeGestureActionMapping(serializedData: String): Map<Int, GestureAction> {
        val loadedMapping = mutableMapOf<Int, GestureAction>()
        if (serializedData.isBlank()) return loadedMapping
        val entries = serializedData.split("|")
        for (entry in entries) {
            val parts = entry.split(",")
            if (parts.size == 2) {
                val slot = parts[0].toIntOrNull()
                val actionName = parts[1]
                if (slot != null) {
                    try {
                        loadedMapping[slot] = GestureAction.valueOf(actionName)
                    } catch (e: Exception) {}
                }
            }
        }
        return loadedMapping
    }

    // --- Foreground App Monitoring --- 
    private val FOREGROUND_APP_CHECK_INTERVAL = 2000L // Check every 2 seconds
    private val foregroundAppChecker = object : Runnable {
        override fun run() {
            val appName = getForegroundAppPackageName()
            if (appName != "" && appName != currentForegroundApp) {
                Log.d("GestureDetectionService", "Foreground app changed to: $appName")
                currentForegroundApp = appName
                serverUrl?.let { url ->
                    getActionMapFromServer(appName, url)
                }
            }
            handler.postDelayed(this, FOREGROUND_APP_CHECK_INTERVAL)
        }
    }

    private fun startForegroundAppMonitoring() {
        handler.post(foregroundAppChecker)
    }

    private fun stopForegroundAppMonitoring() {
        handler.removeCallbacks(foregroundAppChecker)
    }

    private fun getForegroundAppPackageName(): String {
        var foregroundApp = ""
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTime = System.currentTimeMillis()

        // Query usage stats for the last 5 minutes
        val usageStatsList: Map<String, UsageStats>? = usageStatsManager.queryAndAggregateUsageStats(
            currentTime - TimeUnit.MINUTES.toMillis(5),
            currentTime
        )

        usageStatsList?.let { statsMap ->
            if (statsMap.isNotEmpty()) {
                var lastTimeUsed: Long = 0
                var topPackageName: String = ""

                for ((packageName, usageStats) in statsMap) {
                    if (usageStats.lastTimeUsed > lastTimeUsed) {
                        lastTimeUsed = usageStats.lastTimeUsed
                        topPackageName = packageName
                    }
                }
                foregroundApp = topPackageName
            }
        }
        return foregroundApp
    }

    private fun getActionMapFromServer(appName: String, serverUrl: String) {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val requestBody = gson.toJson(ActionMapRequest(appName)).toRequestBody(JSON)

        val request = Request.Builder()
            .url("$serverUrl/post")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("GestureDetectionService", "Failed to get action map from server: ${e.message}")
                // Optionally, clear currentActionMap or set a default
                currentActionMap = null
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val type = object : TypeToken<Map<String, List<String>>>() {}.type
                            val actionMap: Map<String, List<String>> = gson.fromJson(responseBody, type)
                            currentActionMap = actionMap[appName] // Update the service's action map
                            Log.d("GestureDetectionService", "Action Map for $appName loaded: $currentActionMap")
                        } catch (e: Exception) {
                            Log.e("GestureDetectionService", "Failed to parse action map response: ${e.message}")
                            currentActionMap = null
                        }
                    }
                    else {
                        val errorResponse = if (responseBody != null) {
                            try {
                                gson.fromJson(responseBody, ErrorResponse::class.java)
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                        Log.e("GestureDetectionService", "Failed to get action map: ${response.code} - ${errorResponse?.error ?: response.message}")
                        currentActionMap = null
                    }
                }
            }
        })
    }
}
