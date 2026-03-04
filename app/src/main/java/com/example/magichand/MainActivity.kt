package com.example.magichand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.example.magichand.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private val movementThreshold = 5.0f
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Gesture detection variables
    private var isRecording = false
    private val recordedPoints = mutableListOf<SensorPoint>()
    private val gestureLibrary = mutableMapOf<Int, List<SensorPoint>>()
    
    // Live detection buffer
    private val liveBuffer = mutableListOf<SensorPoint>()
    private val bufferSize = 50 // Adjust based on gesture length

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

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

        // Update Slider Display
        binding.gSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.gDisplay.text = "Slot: $progress"
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
        if (recordedPoints.isNotEmpty()) {
            gestureLibrary[currentSlot] = recordedPoints.toList()
            binding.SensorData.text = "Saved gesture to Slot $currentSlot (${recordedPoints.size} points)"
        }
        binding.rootLayout.setBackgroundColor(Color.WHITE)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val currentPoint = SensorPoint(x, y, z)

            // Display raw data
            binding.SensorData.text = String.format(Locale.getDefault(), "X: %.2f\nY: %.2f\nZ: %.2f", x, y, z)

            if (isRecording) {
                recordedPoints.add(currentPoint)
            } else {
                // Live gesture detection
                processLiveBuffer(currentPoint)
            }
        }
    }

    private fun processLiveBuffer(point: SensorPoint) {
        liveBuffer.add(point)
        if (liveBuffer.size > bufferSize) {
            liveBuffer.removeAt(0)
        }

        // Only try to detect if we have templates saved
        if (gestureLibrary.isNotEmpty() && liveBuffer.size == bufferSize) {
            val templates = gestureLibrary.map { GestureTemplate(it.key.toString(), it.value) }
            val detectedGesture = classifyGesture(liveBuffer, templates, threshold = 100f)

            if (detectedGesture != "UNKNOWN") {
                showDetectedGesture(detectedGesture)
            } else {
                binding.rootLayout.setBackgroundColor(Color.WHITE)
            }
        }
    }

    private fun showDetectedGesture(gestureName: String) {
        binding.rootLayout.setBackgroundColor(Color.YELLOW)
        binding.gDisplay.text = "DETECTED: $gestureName"
        // Here you could add a large overlay or change a specific TextView to be bigger
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
