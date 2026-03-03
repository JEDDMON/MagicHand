package com.example.magichand
import android.content.Context
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.view.View
import android.widget.TextView
class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var rootLayout: View
    private lateinit var textView: TextView
    private val THRESHOLD = 12.0f // Adjust this: 9.8 is gravity, 12+ is a firm move

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Link the UI elements to your code
        rootLayout = findViewById(R.id.root_layout)
        textView = findViewById(R.id.SensorData)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize the Sensor Manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // 2. Get the Linear Acceleration sensor (excludes gravity)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        // 'findViewById' looks into your XML and finds the item with that specific ID
        val myDisplay = findViewById<TextView>(R.id.SensorData)

        myDisplay.text = "Sensor Data: 0.0"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // This is where you will send data to your gesture detection logic
            detectGesture(x, y, z)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Usually left empty for basic gesture apps
    }

    private fun detectGesture(x: Float, y: Float, z: Float) {
        // For now, we just print to the console to see if it's working
        if (x > 10 || y > 10 || z > 10) {
            println("Gesture Detected: Sudden Movement!")

        }
    }
}