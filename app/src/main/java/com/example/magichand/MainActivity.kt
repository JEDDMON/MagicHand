package com.example.magichand

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var rootLayout: View
    private lateinit var textView: TextView
    private val THRESHOLD = 5.0f // Sensitivity for movement detection

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Link the UI elements to your code
        rootLayout = findViewById(R.id.root_layout)
        textView = findViewById(R.id.SensorData)
        display = findViewById(R.id.g_display)
        slider = findViewById(R.id.g_slider)
        calibrate = findViewById(R.id.g_calibrate)


        // Initialize the Sensor Manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Use Linear Acceleration to detect movement (ignores gravity)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelerometer == null) {
            textView.text = "Linear Acceleration sensor not available on this device."
        } else {
            textView.text = "Sensor Data: Waiting for movement..."
        }
    }

    override fun onResume() {
        super.onResume()
        // Register the sensor listener
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister to save battery
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Constantly update the display with raw sensor data
            textView.text = String.format(Locale.getDefault(), "X: %.2f\nY: %.2f\nZ: %.2f", x, y, z)

            // Detect movement and update background color
            detectMovement(x, y, z)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this basic implementation
    }

    private fun detectMovement(x: Float, y: Float, z: Float) {
        // Calculate the magnitude of total acceleration
        val accelerationMagnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (accelerationMagnitude > THRESHOLD) {
            // Turn screen green when movement is detected
            rootLayout.setBackgroundColor(Color.GREEN)
        } else {
            // Revert to white when the device is still
            rootLayout.setBackgroundColor(Color.WHITE)
        }
    }
}
