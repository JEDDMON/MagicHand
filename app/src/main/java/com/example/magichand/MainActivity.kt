package com.example.magichand

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.magichand.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    // View Binding instance
    private lateinit var binding: ActivityMainBinding
    
    private val movementThreshold = 5.0f // Sensitivity for movement detection
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Inflate the layout using View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        
        // 2. Set the content view to the root of the binding
        setContentView(binding.root)

        // Initialize the Sensor Manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Use Linear Acceleration to detect movement (ignores gravity)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelerometer == null) {
            binding.SensorData.text = "Linear Acceleration sensor not available on this device."
        } else {
            binding.SensorData.text = "Sensor Data: Waiting for movement..."
        }
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

            // Constantly update the display using binding
            binding.SensorData.text = String.format(Locale.getDefault(), "X: %.2f\nY: %.2f\nZ: %.2f", x, y, z)

            detectMovement(x, y, z)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun detectMovement(x: Float, y: Float, z: Float) {
        // Use Kotlin's sqrt function
        val accelerationMagnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (accelerationMagnitude > movementThreshold) {
            // Accessing rootLayout via binding
            binding.rootLayout.setBackgroundColor(Color.GREEN)
        } else {
            binding.rootLayout.setBackgroundColor(Color.WHITE)
        }
    }
}
