package com.example.magichand

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.magichand.databinding.ActivityMainBinding
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
import java.lang.Exception

enum class GestureAction(val actionName: String) {
    NONE("No Action"),
    VOLUME_UP("Volume Up"),
    VOLUME_DOWN("Volume Down"),
    MEDIA_PLAY_PAUSE("Play/Pause Media"),
    MEDIA_NEXT("Next Track"),
    MEDIA_PREVIOUS("Previous Track"),
    TOGGLE_FLASHLIGHT("Toggle Flashlight"),
    TOGGLE_MUTE("Toggle Mute")
}

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var defaultBackground: Drawable? = null

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    // Gesture recording variables
    private var isRecording = false
    private val recordedPoints = mutableListOf<SensorPoint>()
    private var gestureLibrary = mutableMapOf<Int, MutableList<List<SensorPoint>>>()

    // SharedPreferences constants
    private val SHARED_PREFS_NAME = "MagicHandGesturePrefs"
    private val GESTURE_LIBRARY_KEY = "gesture_library"
    private val GESTURE_ACTION_MAPPING_KEY = "gesture_action_mapping"
    private val SERVER_URL_KEY = "server_url"

    // Gesture Action Mapping
    private var gestureActionMapping = mutableMapOf<Int, GestureAction>() // Maps slot to action

    // Network client and JSON parser
    private val client = OkHttpClient()
    private val gson = Gson()

    // Notification permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notifications permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications permission denied. Background service notifications may not appear.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Sensor Manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        // Request POST_NOTIFICATIONS permission for Android 13+ devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Request PACKAGE_USAGE_STATS permission
        requestUsageStatsPermission()

        // Store the default background to revert to it later
        defaultBackground = binding.rootLayout.background

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
                updateSlotDisplay(progress)
                // Update spinner selection when slot changes
                val currentAction = gestureActionMapping[progress] ?: GestureAction.NONE
                binding.actionSpinner.setSelection(GestureAction.values().indexOf(currentAction))
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        updateSlotDisplay(binding.gSlider.progress)

        // Handle Start Service Button
        binding.startServiceButton.setOnClickListener { 
            val serviceIntent = Intent(this, GestureDetectionService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "Background gesture detection started", Toast.LENGTH_SHORT).show()
        }

        // Handle Stop Service Button
        binding.stopServiceButton.setOnClickListener { 
            val serviceIntent = Intent(this, GestureDetectionService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "Background gesture detection stopped", Toast.LENGTH_SHORT).show()
        }

        // Server URL and Ping functionality
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = sharedPrefs.getString(SERVER_URL_KEY, "http://10.0.0.35:5000")
        binding.serverUrlEditText.setText(savedUrl)
        
        binding.serverUrlEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sharedPrefs.edit().putString(SERVER_URL_KEY, s.toString()).apply()
            }
        })

        binding.pingServerButton.setOnClickListener {
            val serverUrl = binding.serverUrlEditText.text.toString()
            if (serverUrl.isNotBlank()) {
                pingServer(serverUrl)
            } else {
                binding.pingStatusTextView.text = "Server URL cannot be empty."
                binding.pingStatusTextView.setTextColor(Color.RED)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (isRecording && event != null && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            recordedPoints.add(SensorPoint(x, y, z))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
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
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        binding.rootLayout.setBackgroundColor(Color.RED)
        binding.SensorData.text = "Recording..."
    }

    private fun stopRecording() {
        isRecording = false
        sensorManager?.unregisterListener(this)
        val currentSlot = binding.gSlider.progress
        
        // Copy the points so they don't get cleared later
        val trimmedPoints = trimSilenceForRecording(recordedPoints.toList())
        
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
                        println("Warning: Unknown GestureAction name \'$actionName\'")
                    }
                }
            }
        }
        return loadedMapping
    }
    
    private fun pingServer(serverUrl: String) {
        binding.pingStatusTextView.text = "Pinging..."
        binding.pingStatusTextView.setTextColor(Color.GRAY)

        val request = Request.Builder()
            .url("$serverUrl/ping")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                CoroutineScope(Dispatchers.Main).launch {
                    binding.pingStatusTextView.text = "Ping failed: ${e.message}"
                    binding.pingStatusTextView.setTextColor(Color.RED)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = response.body?.string()
                    CoroutineScope(Dispatchers.Main).launch {
                        if (response.isSuccessful && responseBody != null) {
                            try {
                                val pingResponse = gson.fromJson(responseBody, PingResponse::class.java)
                                binding.pingStatusTextView.text = "Ping successful: ${pingResponse.message}"
                                binding.pingStatusTextView.setTextColor(Color.GREEN)
                            } catch (e: Exception) {
                                binding.pingStatusTextView.text = "Ping response parsing failed: ${e.message}"
                                binding.pingStatusTextView.setTextColor(Color.RED)
                            }
                        } else {
                            binding.pingStatusTextView.text = "Ping failed: ${response.code} - ${response.message}"
                            binding.pingStatusTextView.setTextColor(Color.RED)
                        }
                    }
                }
            }
        })
    }
    
    private fun getActionMap(appName: String, serverUrl: String) {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val requestBody = gson.toJson(ActionMapRequest(appName)).toRequestBody(JSON)

        val request = Request.Builder()
            .url("$serverUrl/post")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                CoroutineScope(Dispatchers.Main).launch {
                    // Handle failure, e.g., show a Toast or update a TextView
                    Toast.makeText(this@MainActivity, "Failed to get action map: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = response.body?.string()
                    CoroutineScope(Dispatchers.Main).launch {
                        if (response.isSuccessful && responseBody != null) {
                            try {
                                // Parse dynamically named key response using TypeToken
                                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                                val actionMap: Map<String, List<String>> = gson.fromJson(responseBody, type)
                                val actions = actionMap[appName] // Get the list of actions for the given appName

                                if (actions != null) {
                                    Toast.makeText(this@MainActivity, "Action Map for $appName: $actions", Toast.LENGTH_LONG).show()
                                    // Here you would typically store/use the actions list
                                    // For example, update a ViewModel or LiveData
                                } else {
                                    Toast.makeText(this@MainActivity, "Action map not found for $appName", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Failed to parse action map response: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val errorResponse = if (responseBody != null) {
                                try {
                                    gson.fromJson(responseBody, ErrorResponse::class.java)
                                } catch (e: Exception) {
                                    null
                                }
                            } else null
                            Toast.makeText(this@MainActivity, "Failed to get action map: ${response.code} - ${errorResponse?.error ?: response.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }

    private fun requestUsageStatsPermission() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }

        if (mode != AppOpsManager.MODE_ALLOWED) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            // Usage access settings don't support URI for specific app in older versions, 
            // and it's better to just open the list.
            startActivity(intent)
            Toast.makeText(this, "Please enable Usage Access for MagicHand in settings.", Toast.LENGTH_LONG).show()
        }
    }
}
