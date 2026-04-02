package com.example.magichand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.example.magichand.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    // Gesture detection variables - Now supports multiple examples per slot
    private var isRecording = false
    private val recordedPoints = mutableListOf<SensorPoint>()
    private val gestureLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()
    
    // Live detection buffer
    private val liveBuffer = mutableListOf<SensorPoint>()
    private val bufferSize = 50 // Adjust based on gesture length

    // Back-off timer variables
    private var isGestureCooldown = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the Sensor Manager safely
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelerometer == null) {
            binding.SensorData.text = "Linear Acceleration sensor not available."
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
            updateSlotDisplay(currentSlot)
            binding.SensorData.text = "Deleted all examples in Slot $currentSlot"
        }

        // Update Slider Display
        binding.gSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!isGestureCooldown) {
                    updateSlotDisplay(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        updateSlotDisplay(binding.gSlider.progress)
    }

    private fun updateSlotDisplay(slot: Int) {
        val count = gestureLibrary[slot]?.size ?: 0
        binding.gDisplay.text = "Slot: $slot ($count examples)"
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
        } else {
            binding.SensorData.text = "Recording was too quiet or empty."
        }
        binding.rootLayout.setBackgroundColor(Color.WHITE)
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
            val detectedGesture = classifyGesture(liveBuffer.toList(), templates, threshold = 150f)

            if (detectedGesture != "UNKNOWN") {
                showDetectedGesture(detectedGesture)
            }
        }
    }

    private fun showDetectedGesture(gestureName: String) {
        isGestureCooldown = true
        binding.rootLayout.setBackgroundColor(Color.YELLOW)
        binding.gDisplay.text = "DETECTED: $gestureName"
        
        liveBuffer.clear()

        handler.postDelayed({
            isGestureCooldown = false
            binding.rootLayout.setBackgroundColor(Color.WHITE)
            updateSlotDisplay(binding.gSlider.progress)
            binding.SensorData.text = "Ready for next gesture"
        }, 1000)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
