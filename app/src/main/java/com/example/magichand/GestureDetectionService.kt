package com.example.magichand

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.sqrt

class GestureDetectionService : Service(), SensorEventListener {

    private val CHANNEL_ID = "MagicHandGestureDetectionChannel"
    private val NOTIFICATION_ID = 1

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private lateinit var audioManager: AudioManager

    // Live detection buffer
    private val liveBuffer = mutableListOf<SensorPoint>()
    private val bufferSize = 80 // Increased for SENSOR_DELAY_GAME coverage (approx 1.5-2 seconds)

    // Back-off timer variables
    private var isGestureCooldown = false
    private val handler = Handler(Looper.getMainLooper())

    // SharedPreferences constants
    private val SHARED_PREFS_NAME = "MagicHandGesturePrefs"
    private val GESTURE_LIBRARY_KEY = "gesture_library"
    private val GESTURE_ACTION_MAPPING_KEY = "gesture_action_mapping"

    // Gesture detection variables
    private var gestureLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()
    private var gestureActionMapping = mutableMapOf<Int, GestureAction>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == GESTURE_LIBRARY_KEY) {
            Log.i("GestureDetectionService", "Gesture library updated in SharedPreferences, reloading...")
            loadGestureLibrary()
        } else if (key == GESTURE_ACTION_MAPPING_KEY) {
            Log.i("GestureDetectionService", "Action mapping updated, reloading...")
            loadGestureActionMapping()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        loadGestureLibrary()
        loadGestureActionMapping()

        val prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Force reload library whenever start is called (e.g. from UI)
        loadGestureLibrary()
        loadGestureActionMapping()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MagicHands is listening")
            .setContentText("Gesture detection is active.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        accelerometer?.let {
            // Using SENSOR_DELAY_GAME to match MainActivity's recording frequency
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i("GestureDetectionService", "Sensor listener registered with DELAY_GAME.")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sensorManager?.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
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
            
            // Reduced noise filter slightly to capture more subtle movements
            val magSq = x * x + y * y + z * z
            val currentPoint = if (magSq < 0.3) {
                SensorPoint(0f, 0f, 0f)
            } else {
                SensorPoint(x, y, z)
            }

            if (!isGestureCooldown) {
                processLiveBuffer(currentPoint)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processLiveBuffer(point: SensorPoint) {
        liveBuffer.add(point)
        if (liveBuffer.size > bufferSize) {
            liveBuffer.removeAt(0)
        }

        if (gestureLibrary.isNotEmpty() && liveBuffer.size == bufferSize) {
            var maxMagnitude = 0f
            var activePointsCount = 0
            for (p in liveBuffer) {
                val mag = sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()).toFloat()
                if (mag > maxMagnitude) maxMagnitude = mag
                if (mag > 1.2f) activePointsCount++ // Lowered from 1.5f
            }

            // Lowered trigger threshold to be more sensitive (from 3.5 to 2.5)
            if (maxMagnitude < 2.5f || activePointsCount < 5) {
                return
            }

            val templates = mutableListOf<GestureTemplate>()
            for ((slot, examples) in gestureLibrary) {
                for (example in examples) {
                    templates.add(GestureTemplate(slot.toString(), example))
                }
            }
            
            // Slightly loosened threshold (from 1.0 to 1.2) for better matching
            val detectedGestureSlot = classifyGesture(liveBuffer.toList(), templates, threshold = 1.2f)

            if (detectedGestureSlot != "UNKNOWN") {
                showDetectedGesture(detectedGestureSlot.toInt())
            }
        }
    }

    private fun showDetectedGesture(slot: Int) {
        isGestureCooldown = true
        Log.d("GestureDetectionService", "Gesture MATCHED in slot: $slot")
        
        val detectedAction = gestureActionMapping[slot] ?: GestureAction.NONE
        performGestureAction(detectedAction)

        liveBuffer.clear()

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
            GestureAction.SWIPE_LEFT -> Log.i("GestureDetectionService", "Action: Swipe Left (Log only)")
            GestureAction.SWIPE_RIGHT -> Log.i("GestureDetectionService", "Action: Swipe Right (Log only)")
            GestureAction.NONE -> Log.i("GestureDetectionService", "Action: No Action")
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
}