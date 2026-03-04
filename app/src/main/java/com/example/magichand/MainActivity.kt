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

    // Gesture detection variables
    private var isRecording = false
    private val recordedPoints = mutableListOf<SensorPoint>()
    private val gestureLibrary = mutableMapOf<Int, List<SensorPoint>>()
    
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

        // Handle Delete Button
        binding.gDelete.setOnClickListener {
            val currentSlot = binding.gSlider.progress
            gestureLibrary.remove(currentSlot)
            binding.SensorData.text = "Deleted gesture in Slot $currentSlot"
        }

        // Update Slider Display
        binding.gSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!isGestureCooldown) {
                    binding.gDisplay.text = "Slot: $progress"
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        binding.gDisplay.text = "Slot: ${binding.gSlider.progress}"
    }

    private fun startRecording() {
        isRecording = true
        recordedPoints.clear()
        binding.rootLayout.setBackgroundColor(Color.RED)
    }

    private fun stopRecording() {
        isRecording = false
        val currentSlot = binding.gSlider.progress
        
        // IMPORTANT: Copy the points so they don't get cleared later
        val trimmedPoints = trimSilence(recordedPoints.toList())
        
        if (trimmedPoints.isNotEmpty()) {
            gestureLibrary[currentSlot] = trimmedPoints
            binding.SensorData.text = "Saved gesture to Slot $currentSlot (${trimmedPoints.size} points)"
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
        // Use subList and THEN toList() to create a hard copy of the data
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
        // Clean up any pending UI resets to avoid crashes if activity is paused
        handler.removeCallbacksAndMessages(null)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // Safe check for event and values
        if (event != null && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION && event.values.size >= 3) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            val currentPoint = SensorPoint(x, y, z)

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

        // Only try to detect if we have templates saved and the buffer is full
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

            // Map library to templates list
            val templates = gestureLibrary.map { GestureTemplate(it.key.toString(), it.value) }
            
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
            // Check if binding is still available before updating UI
            isGestureCooldown = false
            binding.rootLayout.setBackgroundColor(Color.WHITE)
            binding.gDisplay.text = "Slot: ${binding.gSlider.progress}"
            binding.SensorData.text = "Ready for next gesture"
        }, 1000)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        // Final cleanup of the handler
        handler.removeCallbacksAndMessages(null)
    }
}
