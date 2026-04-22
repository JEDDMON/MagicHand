package com.example.magichand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager // Import for AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent // Import for KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.magichand.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.sqrt

enum class GestureAction(val actionName: String) {
    NONE("No Action"),
    VOLUME_UP("Volume Up"),
    VOLUME_DOWN("Volume Down"),
    MEDIA_PLAY_PAUSE("Play/Pause Media"),
    SWIPE_LEFT("Swipe Left"),
    SWIPE_RIGHT("Swipe Right")
    // Add more actions as needed
}

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var defaultBackground: Drawable? = null
    private lateinit var audioManager: AudioManager // Initialize AudioManager

    // Gesture detection variables - Now supports multiple examples per slot
    private var isRecording = false
    private val recordedPoints = mutableListOf<SensorPoint>()
    private var gestureLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()

    // Live detection buffer
    private val liveBuffer = mutableListOf<SensorPoint>()
    private val bufferSize = 50 // Adjust based on gesture length

    // Back-off timer variables
    private var isGestureCooldown = false
    private val handler = Handler(Looper.getMainLooper())

    // SharedPreferences constants
    private val SHARED_PREFS_NAME = "MagicHandGesturePrefs"
    private val GESTURE_LIBRARY_KEY = "gesture_library"
    private val GESTURE_ACTION_MAPPING_KEY = "gesture_action_mapping" // New key for action mapping

    // Gesture Action Mapping
    private var gestureActionMapping = mutableMapOf<Int, GestureAction>() // Maps slot to action

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Store the default background to revert to it later
        defaultBackground = binding.rootLayout.background

        // Initialize the Sensor Manager safely
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager // Initialize AudioManager

        if (accelerometer == null) {
            binding.SensorData.text = "Linear Acceleration sensor not available."
        }

        // Load gesture library and action mapping from SharedPreferences
        loadGestureLibrary()
        loadGestureActionMapping()

        // Setup Spinner for gesture actions
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, GestureAction.values().map { it.actionName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.actionSpinner.adapter = adapter

        binding.actionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedAction = GestureAction.values()[position]
                val currentSlot = binding.gSlider.progress
                gestureActionMapping[currentSlot] = selectedAction
                saveGestureActionMapping()
                binding.SensorData.text = "Slot $currentSlot mapped to ${selectedAction.actionName}"
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }

        // Handle Calibration Button Hold
        binding.gCalibrate.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopRecording()
                    true
                }
                else -> false
            }
        }

        // Handle Delete Button - Now deletes all examples for the current slot
        binding.gDelete.setOnClickListener {
            val currentSlot = binding.gSlider.progress
            gestureLibrary.remove(currentSlot)
            gestureActionMapping.remove(currentSlot) // Also remove action mapping
            updateSlotDisplay(currentSlot)
            binding.SensorData.text = "Deleted all examples in Slot $currentSlot"
            saveGestureLibrary() // Save after deleting
            saveGestureActionMapping() // Save action mapping after deleting
        }

        // Update Slider Display
        binding.gSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!isGestureCooldown) {
                    updateSlotDisplay(progress)
                    // Update spinner selection when slot changes
                    val currentAction = gestureActionMapping[progress] ?: GestureAction.NONE
                    binding.actionSpinner.setSelection(GestureAction.values().indexOf(currentAction))
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        updateSlotDisplay(binding.gSlider.progress)
    }

    private fun loadGestureLibrary() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val serializedData = sharedPrefs.getString(GESTURE_LIBRARY_KEY, "") ?: ""
        gestureLibrary = deserializeGestureLibrary(serializedData).toMutableMap()
        binding.SensorData.text = "Loaded ${gestureLibrary.size} gesture slots."
        updateSlotDisplay(binding.gSlider.progress) // Update display after loading
    }

    private fun saveGestureLibrary() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()
        val serializedData = serializeGestureLibrary()
        editor.putString(GESTURE_LIBRARY_KEY, serializedData)
        editor.apply()
        // binding.SensorData.text = "Saved gesture library." // This line is not needed often, can be removed to avoid clutter
    }

    private fun loadGestureActionMapping() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val serializedMapping = sharedPrefs.getString(GESTURE_ACTION_MAPPING_KEY, "") ?: ""
        gestureActionMapping = deserializeGestureActionMapping(serializedMapping).toMutableMap()
    }

    private fun saveGestureActionMapping() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()
        val serializedMapping = serializeGestureActionMapping()
        editor.putString(GESTURE_ACTION_MAPPING_KEY, serializedMapping)
        editor.apply()
    }

    private fun updateSlotDisplay(slot: Int) {
        val count = gestureLibrary[slot]?.size ?: 0
        val currentAction = gestureActionMapping[slot]?.actionName ?: GestureAction.NONE.actionName
        binding.gDisplay.text = """Slot: $slot ($count examples)
Action: $currentAction"""
    }

    private fun startRecording() {
        isRecording = true
        recordedPoints.clear()
        binding.rootLayout.setBackgroundColor(Color.RED)
    }

    private fun stopRecording() {
        isRecording = false
        val currentSlot = binding.gSlider.progress
        
        // Copy the points so they don't get cleared later
        val trimmedPoints = trimSilence(recordedPoints.toList())
        
        if (trimmedPoints.isNotEmpty()) {
            // Add the new example to the list for this slot
            val examples = gestureLibrary.getOrPut(currentSlot) { mutableListOf() }
            examples.add(trimmedPoints)
            
            updateSlotDisplay(currentSlot)
            binding.SensorData.text = "Saved example #${examples.size} to Slot $currentSlot"
            saveGestureLibrary() // Save after adding a gesture
        } else {
            binding.SensorData.text = "Recording was too quiet or empty."
        }
        resetBackground()
    }

    private fun resetBackground() {
        binding.rootLayout.background = defaultBackground
    }

    private fun trimSilence(points: List<SensorPoint>): List<SensorPoint> {
        val activityThreshold = 1.0f // Magnitude threshold for "active" movement
        val firstActive = points.indexOfFirst { p -> 
            sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()) > activityThreshold 
        }
        val lastActive = points.indexOfLast { p -> 
            sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()) > activityThreshold 
        }
        
        if (firstActive == -1 || lastActive == -1) return emptyList()
        return points.subList(firstActive, lastActive + 1).toList()
    }

    // NEW: Serialization method for gesture library
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

    // NEW: Deserialization method for gesture library
    private fun deserializeGestureLibrary(serializedData: String): Map<Int, MutableList<List<SensorPoint>>> {
        val loadedLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()
        if (serializedData.isBlank()) return loadedLibrary

        val slotStrings = serializedData.split("~")

        for (slotString in slotStrings) {
            // Manual split for ':', limit = 2
            val colonIndex = slotString.indexOf(':')
            val parts = if (colonIndex == -1) {
                listOf(slotString)
            } else {
                listOf(slotString.substring(0, colonIndex), slotString.substring(colonIndex + 1))
            }
            // End manual split

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

    // NEW: Serialization method for gesture action mapping
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

    // NEW: Deserialization method for gesture action mapping
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
                        // Handle cases where action name might be invalid (e.g., from old version)
                        println("Warning: Unknown GestureAction name '$actionName'")
                    }
                }
            }
        }
        return loadedMapping
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

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

            // Update UI safely
            binding.SensorData.text = String.format(Locale.getDefault(), "X: %.2f\nY: %.2f\nZ: %.2f", x, y, z)

            if (isRecording) {
                recordedPoints.add(currentPoint)
            } else if (!isGestureCooldown) {
                processLiveBuffer(currentPoint)
            }
        }
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
        binding.rootLayout.setBackgroundColor(Color.YELLOW)
        
        val detectedAction = gestureActionMapping[slot] ?: GestureAction.NONE
        binding.gDisplay.text = "DETECTED: Slot $slot (${detectedAction.actionName})"
        
        // Perform the action
        performGestureAction(detectedAction)

        liveBuffer.clear()

        handler.postDelayed({
            isGestureCooldown = false
            resetBackground()
            updateSlotDisplay(binding.gSlider.progress)
            binding.SensorData.text = "Ready for next gesture"
        }, 1000)
    }

    private fun performGestureAction(action: GestureAction) {
        when (action) {
            GestureAction.VOLUME_UP -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND)
                println("Action: Volume Up")
            }
            GestureAction.VOLUME_DOWN -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND)
                println("Action: Volume Down")
            }
            GestureAction.MEDIA_PLAY_PAUSE -> {
                val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(event)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                audioManager.dispatchMediaKeyEvent(eventUp)
                println("Action: Media Play/Pause")
            }
            GestureAction.SWIPE_LEFT -> {
                // These actions (swipe left/right) usually require AccessibilityService
                // or root permissions for direct input simulation across the system.
                // For now, we'll just log it.
                println("Action: Swipe Left")
            }
            GestureAction.SWIPE_RIGHT -> {
                println("Action: Swipe Right")
            }
            GestureAction.NONE -> {
                println("Action: No Action (ignored)")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}