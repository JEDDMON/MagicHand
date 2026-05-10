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

        // --- NAVIGATION LOGIC ---
        
        // Scene 1 -> Admin
        binding.btnAdmin.setOnClickListener {
            binding.welcomeLayout.visibility = View.GONE
            binding.recordGestureLayout.visibility = View.GONE
            binding.enableMagicLayout.visibility = View.GONE
            binding.legacyUiContainer.visibility = View.VISIBLE
        }

        // Admin -> Scene 1
        binding.btnBackToWelcome.setOnClickListener {
            binding.legacyUiContainer.visibility = View.GONE
            binding.welcomeLayout.visibility = View.VISIBLE
        }

        // Scene 1 -> Scene 2
        binding.btnProceed.setOnClickListener {
            binding.welcomeLayout.visibility = View.GONE
            binding.recordGestureLayout.visibility = View.VISIBLE
        }

        // Scene 2 -> Scene 1
        binding.btnBackToWelcomeUser.setOnClickListener {
            binding.recordGestureLayout.visibility = View.GONE
            binding.welcomeLayout.visibility = View.VISIBLE
        }

        // Scene 2 -> Scene 3
        binding.userBtnProceed.setOnClickListener {
            binding.recordGestureLayout.visibility = View.GONE
            binding.enableMagicLayout.visibility = View.VISIBLE
        }

        // Scene 3 -> Scene 2
        binding.btnBackToRecordUser.setOnClickListener {
            binding.enableMagicLayout.visibility = View.GONE
            binding.recordGestureLayout.visibility = View.VISIBLE
        }

        // --- USER UI MAPPING (SCENE 2) ---

        binding.userGSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.gSlider.progress = progress
                updateSlotDisplay(progress)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.userActionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (binding.actionSpinner.selectedItemPosition != position) {
                    binding.actionSpinner.setSelection(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.userGCalibrate.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startRecording(); true }
                MotionEvent.ACTION_UP -> { stopRecording(); true }
                else -> false
            }
        }

        binding.userGDelete.setOnClickListener { binding.gDelete.performClick() }

        // --- USER UI MAPPING (SCENE 3) ---

        binding.userPingServerButton.setOnClickListener {
            val url = binding.userServerUrlEditText.text.toString()
            if (url.isNotBlank()) pingServer(url)
        }

        binding.btnGrantPermissions.setOnClickListener {
            requestUsageStatsPermission()
        }

        binding.userStartServiceButton.setOnClickListener {
            startDetectionService()
        }

        binding.userStopServiceButton.setOnClickListener {
            stopDetectionService()
        }

        // Sync URL between User UI and Legacy UI (Fixed logic to prevent infinite loop)
        binding.userServerUrlEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val url = s.toString()
                if (binding.serverUrlEditText.text.toString() != url) {
                    binding.serverUrlEditText.setText(url)
                }
                getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit().putString(SERVER_URL_KEY, url).apply()
            }
        })
        binding.serverUrlEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val url = s.toString()
                if (binding.userServerUrlEditText.text.toString() != url) {
                    binding.userServerUrlEditText.setText(url)
                }
                getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit().putString(SERVER_URL_KEY, url).apply()
            }
        })

        // --- CORE INITIALIZATION ---

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Commit logic: Always check for usage stats on startup
        requestUsageStatsPermission()

        defaultBackground = binding.rootLayout.background
        loadGestureLibrary()
        loadGestureActionMapping()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, GestureAction.values().map { it.actionName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.actionSpinner.adapter = adapter
        binding.userActionSpinner.adapter = adapter

        binding.actionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedAction = GestureAction.values()[position]
                val currentSlot = binding.gSlider.progress
                gestureActionMapping[currentSlot] = selectedAction
                saveGestureActionMapping()
                binding.SensorData.text = "Slot $currentSlot mapped to ${selectedAction.actionName}"
                if (binding.userActionSpinner.selectedItemPosition != position) {
                    binding.userActionSpinner.setSelection(position)
                }
                updateSlotDisplay(currentSlot)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.gCalibrate.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startRecording(); true }
                MotionEvent.ACTION_UP -> { stopRecording(); true }
                else -> false
            }
        }

        binding.gDelete.setOnClickListener {
            val currentSlot = binding.gSlider.progress
            gestureLibrary.remove(currentSlot)
            gestureActionMapping.remove(currentSlot)
            updateSlotDisplay(currentSlot)
            binding.SensorData.text = "Deleted all examples in Slot $currentSlot"
            saveGestureLibrary()
            saveGestureActionMapping()
        }

        binding.gSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateSlotDisplay(progress)
                val currentAction = gestureActionMapping[progress] ?: GestureAction.NONE
                binding.actionSpinner.setSelection(GestureAction.values().indexOf(currentAction))
                if (fromUser) binding.userGSlider.progress = progress
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.startServiceButton.setOnClickListener { startDetectionService() }
        binding.stopServiceButton.setOnClickListener { stopDetectionService() }

        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = sharedPrefs.getString(SERVER_URL_KEY, "http://10.0.0.35:5000")
        binding.serverUrlEditText.setText(savedUrl)
        binding.userServerUrlEditText.setText(savedUrl)

        binding.pingServerButton.setOnClickListener {
            val url = binding.serverUrlEditText.text.toString()
            if (url.isNotBlank()) {
                pingServer(url)
            } else {
                binding.pingStatusTextView.text = "Server URL cannot be empty."
                binding.pingStatusTextView.setTextColor(Color.RED)
            }
        }
        
        // Set initial state for spinners and display
        val initialSlot = binding.gSlider.progress
        val initialAction = gestureActionMapping[initialSlot] ?: GestureAction.NONE
        val actionIdx = GestureAction.values().indexOf(initialAction)
        binding.actionSpinner.setSelection(actionIdx)
        binding.userActionSpinner.setSelection(actionIdx)
        updateSlotDisplay(initialSlot)
    }

    private fun startDetectionService() {
        val serviceIntent = Intent(this, GestureDetectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "Background gesture detection started", Toast.LENGTH_SHORT).show()
    }

    private fun stopDetectionService() {
        val serviceIntent = Intent(this, GestureDetectionService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "Background gesture detection stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (isRecording && event != null && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            recordedPoints.add(SensorPoint(event.values[0], event.values[1], event.values[2]))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun loadGestureLibrary() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val serializedData = sharedPrefs.getString(GESTURE_LIBRARY_KEY, "") ?: ""
        gestureLibrary = deserializeGestureLibrary(serializedData).toMutableMap()
        binding.SensorData.text = "Loaded ${gestureLibrary.size} gesture slots."
        updateSlotDisplay(binding.gSlider.progress)
    }

    private fun saveGestureLibrary() {
        val serializedData = serializeGestureLibrary()
        getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit().putString(GESTURE_LIBRARY_KEY, serializedData).apply()
    }

    private fun loadGestureActionMapping() {
        val serializedMapping = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).getString(GESTURE_ACTION_MAPPING_KEY, "") ?: ""
        gestureActionMapping = deserializeGestureActionMapping(serializedMapping).toMutableMap()
    }

    private fun saveGestureActionMapping() {
        val serializedMapping = serializeGestureActionMapping()
        getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit().putString(GESTURE_ACTION_MAPPING_KEY, serializedMapping).apply()
    }

    private fun updateSlotDisplay(slot: Int) {
        val count = gestureLibrary[slot]?.size ?: 0
        val currentAction = gestureActionMapping[slot] ?: GestureAction.NONE
        binding.gDisplay.text = """Slot: $slot ($count examples)
Action: ${currentAction.actionName}"""
        // Enhanced Scene 2 display
        binding.userGDisplay.text = "Gesture ${slot + 1} - Action: ${currentAction.actionName}"
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
        val trimmedPoints = trimSilenceForRecording(recordedPoints.toList())
        if (trimmedPoints.isNotEmpty()) {
            val examples = gestureLibrary.getOrPut(currentSlot) { mutableListOf() }
            examples.add(trimmedPoints)
            updateSlotDisplay(currentSlot)
            binding.SensorData.text = "Saved example #${examples.size} to Slot $currentSlot"
            saveGestureLibrary()
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
            if (!firstSlot) stringBuilder.append("~")
            stringBuilder.append(slot).append(":")
            val examplesString = examples.joinToString("#") { gestureExample ->
                gestureExample.joinToString(";") { point -> "${point.x},${point.y},${point.z}" }
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

    private fun serializeGestureActionMapping(): String {
        val stringBuilder = StringBuilder()
        var firstEntry = true
        for ((slot, action) in gestureActionMapping) {
            if (!firstEntry) stringBuilder.append("|")
            stringBuilder.append(slot).append(",").append(action.name)
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
                val slot = parts[0].toIntOrNull() ?: continue
                val actionName = parts[1]
                try {
                    loadedMapping[slot] = GestureAction.valueOf(actionName)
                } catch (e: Exception) {}
            }
        }
        return loadedMapping
    }
    
    private fun pingServer(serverUrl: String) {
        binding.pingStatusTextView.text = "Pinging..."
        binding.pingStatusTextView.setTextColor(Color.GRAY)
        binding.userPingStatusText.text = "Status: Pinging..."
        
        val request = Request.Builder().url("$serverUrl/ping").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                CoroutineScope(Dispatchers.Main).launch {
                    binding.pingStatusTextView.text = "Ping failed: ${e.message}"
                    binding.pingStatusTextView.setTextColor(Color.RED)
                    binding.userPingStatusText.text = "Status: Ping failed"
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
                                binding.userPingStatusText.text = "Status: Connected"
                            } catch (e: Exception) {
                                binding.pingStatusTextView.text = "Parsing failed"
                                binding.pingStatusTextView.setTextColor(Color.RED)
                                binding.userPingStatusText.text = "Status: Error"
                            }
                        } else {
                            binding.pingStatusTextView.text = "Ping failed: ${response.code}"
                            binding.pingStatusTextView.setTextColor(Color.RED)
                            binding.userPingStatusText.text = "Status: Error ${response.code}"
                        }
                    }
                }
            }
        })
    }
    
    private fun getActionMap(appName: String, serverUrl: String) {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val requestBody = gson.toJson(ActionMapRequest(appName)).toRequestBody(JSON)
        val request = Request.Builder().url("$serverUrl/post").post(requestBody).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(this@MainActivity, "Failed to get action map: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = response.body?.string()
                    CoroutineScope(Dispatchers.Main).launch {
                        if (response.isSuccessful && responseBody != null) {
                            try {
                                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                                val actionMap: Map<String, List<String>> = gson.fromJson(responseBody, type)
                                val actions = actionMap[appName]
                                if (actions != null) {
                                    Toast.makeText(this@MainActivity, "Action Map for $appName: $actions", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {}
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
            startActivity(intent)
            Toast.makeText(this, "Please enable Usage Access for MagicHand in settings.", Toast.LENGTH_LONG).show()
        }
    }
}
