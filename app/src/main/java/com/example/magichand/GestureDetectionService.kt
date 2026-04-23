package com.example.magichand

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
    private val bufferSize = 50 // Adjust based on gesture length

    // Back-off timer variables
    private var isGestureCooldown = false
    private val handler = Handler(Looper.getMainLooper())

    // SharedPreferences constants
    private val SHARED_PREFS_NAME = "MagicHandGesturePrefs"
    private val GESTURE_LIBRARY_KEY = "gesture_library"
    private val GESTURE_ACTION_MAPPING_KEY = "gesture_action_mapping"

    // Gesture detection variables (from MainActivity)
    private var gestureLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()
    private var gestureActionMapping = mutableMapOf<Int, GestureAction>() // Maps slot to action

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize the Sensor Manager safely
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Load gesture library and action mapping from SharedPreferences
        loadGestureLibrary()
        loadGestureActionMapping()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MagicHands is listening")
            .setContentText("Gesture detection is active in the background.")
            .setSmallIcon(R.mipmap.ic_launcher) // Use your app's launcher icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            Log.i("GestureDetectionService", "Sensor listener registered.")
        } ?: run {
            Log.e("GestureDetectionService", "Linear Acceleration sensor not available.")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        Log.i("GestureDetectionService", "Sensor listener unregistered and service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Gesture Detection Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // SensorEventListener methods
    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION && event.values.size >= 3) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            // Noise filtering: if acceleration is very low, treat it as zero
            val currentPoint = if (x * x + y * y + z * z < 0.5) {
                SensorPoint(0f, 0f, 0f)
            } else {
                SensorPoint(x, y, z)
            }

            if (!isGestureCooldown) {
                processLiveBuffer(currentPoint)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used for this application
    }

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
                if (mag > 1.5f) activePointsCount++
            }

            if (maxMagnitude < 3.5f || activePointsCount < 5) {
                return
            }

            // Map library to templates list - account for multiple examples
            val templates = mutableListOf<GestureTemplate>()
            for ((slot, examples) in gestureLibrary) {
                for (example in examples) {
                    templates.add(GestureTemplate(slot.toString(), example))
                }
            }
            
            // Pass a copy of the live buffer to the classifier
            val detectedGestureSlot = classifyGesture(liveBuffer.toList(), templates, threshold = 1.0f)

            if (detectedGestureSlot != "UNKNOWN") {
                showDetectedGesture(detectedGestureSlot.toInt()) // Pass the slot ID
            }
        }
    }

    private fun showDetectedGesture(slot: Int) {
        isGestureCooldown = true
        Log.d("GestureDetectionService", "Gesture detected in slot: $slot")
        
        val detectedAction = gestureActionMapping[slot] ?: GestureAction.NONE
        Log.d("GestureDetectionService", "Performing action: ${detectedAction.actionName}")
        
        // Perform the action
        performGestureAction(detectedAction)

        liveBuffer.clear()

        handler.postDelayed({
            isGestureCooldown = false
            Log.d("GestureDetectionService", "Ready for next gesture")
        }, 1000)
    }

    private fun performGestureAction(action: GestureAction) {
        when (action) {
            GestureAction.VOLUME_UP -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND)
                Log.i("GestureDetectionService", "Action: Volume Up")
            }
            GestureAction.VOLUME_DOWN -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND)
                Log.i("GestureDetectionService", "Action: Volume Down")
            }
            GestureAction.MEDIA_PLAY_PAUSE -> {
                val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(event)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(eventUp)
                Log.i("GestureDetectionService", "Action: Media Play/Pause")
            }
            GestureAction.SWIPE_LEFT -> {
                // These actions (swipe left/right) usually require AccessibilityService
                // or root permissions for direct input simulation across the system.
                // For now, we'll just log it.
                Log.i("GestureDetectionService", "Action: Swipe Left")
            }
            GestureAction.SWIPE_RIGHT -> {
                Log.i("GestureDetectionService", "Action: Swipe Right")
            }
            GestureAction.NONE -> {
                Log.i("GestureDetectionService", "Action: No Action (ignored)")
            }
        }
    }

    // Persistence methods (from MainActivity)
    private fun loadGestureLibrary() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val serializedData = sharedPrefs.getString(GESTURE_LIBRARY_KEY, "") ?: ""
        gestureLibrary = deserializeGestureLibrary(serializedData).toMutableMap()
        Log.d("GestureDetectionService", "Loaded ${gestureLibrary.size} gesture slots.")
    }

    private fun saveGestureLibrary() {
        // This service will not be saving new gestures, only loading them
        // Saving logic remains in MainActivity
    }

    private fun loadGestureActionMapping() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val serializedMapping = sharedPrefs.getString(GESTURE_ACTION_MAPPING_KEY, "") ?: ""
        gestureActionMapping = deserializeGestureActionMapping(serializedMapping).toMutableMap()
    }

    private fun saveGestureActionMapping() {
        // This service will not be saving new gesture mappings, only loading them
        // Saving logic remains in MainActivity
    }

    private fun serializeGestureLibrary(): String {
        val stringBuilder = StringBuilder()
        var firstSlot = true
        for ((slot, examples) in gestureLibrary) {
            if (!firstSlot) {
                stringBuilder.append("~") // Separator for slots
            }
            stringBuilder.append(slot).append(":") // Slot ID
            val examplesString = examples.joinToString("#") { gestureExample -> // Separator for examples within a slot
                gestureExample.joinToString(";") { point -> // Separator for points within an example
                    "${point.x},${point.y},${point.z}"
                }
            }
            stringBuilder.append(examplesString)
            firstSlot = false
        }
        return stringBuilder.toString()
    }

    private fun deserializeGestureLibrary(serializedData: String): Map<Int, MutableList<List<SensorPoint>>> {
        val loadedLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()
        if (serializedData.isBlank()) return loadedLibrary

        val slotStrings = serializedData.split("~")

        for (slotString in slotStrings) {
            val colonIndex = slotString.indexOf(':')
            val parts = if (colonIndex == -1) {
                listOf(slotString)
            } else {
                listOf(slotString.substring(0, colonIndex), slotString.substring(colonIndex + 1))
            }

            if (parts.size == 2) {
                val slotIndex = parts[0].toIntOrNull()
                val examplesData = parts[1]

                if (slotIndex != null) {
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
                                if (x != null && y != null && z != null) {
                                    points.add(SensorPoint(x, y, z))
                                }
                            }
                        }
                        if (points.isNotEmpty()) {
                            examplesForSlot.add(points)
                        }
                    }
                    if (examplesForSlot.isNotEmpty()) {
                        loadedLibrary[slotIndex] = examplesForSlot
                    }
                }
            }
        }
        return loadedLibrary
    }

    private fun serializeGestureActionMapping(): String {
        val stringBuilder = StringBuilder()
        var firstEntry = true
        for ((slot, action) in gestureActionMapping) {
            if (!firstEntry) {
                stringBuilder.append("|") // Separator for map entries
            }
            stringBuilder.append(slot).append(",").append(action.name) // Slot,ActionName
            firstEntry = false
        }
        return stringBuilder.toString()
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
                        val action = GestureAction.valueOf(actionName)
                        loadedMapping[slot] = action
                    } catch (e: IllegalArgumentException) {
                        Log.w("GestureDetectionService", "Warning: Unknown GestureAction name '$actionName'")
                    }
                }
            }
        }
        return loadedMapping
    }
}