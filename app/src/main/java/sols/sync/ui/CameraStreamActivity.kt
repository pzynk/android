package sols.sync.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import sols.sync.R
import sols.sync.SyncApp
import sols.sync.system.SyncService

class CameraStreamActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"

        fun start(context: Context, deviceId: String) {
            context.startActivity(
                Intent(context, CameraStreamActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
            )
        }
    }

    private lateinit var deviceId: String
    private lateinit var rowCameraFacing: View
    private lateinit var rowResolution: View
    private lateinit var rowFps: View
    private lateinit var rowRotation: View
    private lateinit var rowConnectionMode: View
    private lateinit var tvCameraFacingValue: TextView
    private lateinit var tvResolutionValue: TextView
    private lateinit var tvFpsValue: TextView
    private lateinit var tvRotationValue: TextView
    private lateinit var tvConnectionModeValue: TextView
    private lateinit var ivConnectionModeIcon: ImageView
    private lateinit var tvStreamStatus: TextView
    private lateinit var tvStreamAddress: TextView
    private lateinit var btnToggleStream: MaterialButton

    private var connectionModeSelectedIndex: Int = 0
    private var connectionModeOptions: List<BottomSheetSelect.OptionItem> = emptyList()

    private val rotationOptions = listOf(0, 90, 180, 270)

    private var rotationItems: List<BottomSheetSelect.OptionItem> = emptyList()
    private var rotationSelectedIndex: Int = 0

    private lateinit var cameraManager: CameraManager
    private var backCameraId: String? = null
    private var frontCameraId: String? = null

    private var facingOptions: List<BottomSheetSelect.OptionItem> = emptyList()
    private var facingSelectedIndex: Int = 0

    private var resolutionOptions: List<String> = emptyList()
    private var resolutionItems: List<BottomSheetSelect.OptionItem> = emptyList()
    private var resolutionSelectedIndex: Int = 0

    private var fpsOptions: List<Int> = emptyList()
    private var fpsItems: List<BottomSheetSelect.OptionItem> = emptyList()
    private var fpsSelectedIndex: Int = 0

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startStreamService()
        } else {
            Toast.makeText(this, "Camera permission is required to stream", Toast.LENGTH_SHORT).show()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_camera_stream)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run {
            finish()
            return
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.camera_stream_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rowCameraFacing = findViewById(R.id.row_camera_facing)
        rowResolution = findViewById(R.id.row_resolution)
        rowFps = findViewById(R.id.row_fps)
        rowRotation = findViewById(R.id.row_rotation)
        rowConnectionMode = findViewById(R.id.row_connection_mode)
        tvCameraFacingValue = findViewById(R.id.tv_camera_facing_value)
        tvResolutionValue = findViewById(R.id.tv_resolution_value)
        tvFpsValue = findViewById(R.id.tv_fps_value)
        tvRotationValue = findViewById(R.id.tv_rotation_value)
        tvConnectionModeValue = findViewById(R.id.tv_connection_mode_value)
        ivConnectionModeIcon = findViewById(R.id.iv_connection_mode_icon)
        tvStreamStatus = findViewById(R.id.tv_stream_status)
        tvStreamAddress = findViewById(R.id.tv_stream_address)
        btnToggleStream = findViewById(R.id.btn_toggle_stream)


        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        detectCameras()

        setupSettingsRows()
        loadSavedSettings()

        btnToggleStream.setOnClickListener {
            val app = applicationContext as SyncApp
            if (app.isCameraStreaming) {
                stopStreamService()
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startStreamService()
                } else {
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver,
            IntentFilter(SyncService.ACTION_STATE_CHANGED)
        )
        updateUi()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun detectCameras() {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && backCameraId == null) {
                backCameraId = id
            } else if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontCameraId == null) {
                frontCameraId = id
            }
        }
    }

    private fun setupSettingsRows() {
        val options = mutableListOf<BottomSheetSelect.OptionItem>()
        if (backCameraId != null) {
            options.add(
                BottomSheetSelect.OptionItem(
                    title = "Back Camera",
                    description = "Rear camera sensor. Best quality, standard configuration.",
                    iconRes = R.drawable.ic_camera
                )
            )
        }
        if (frontCameraId != null) {
            options.add(
                BottomSheetSelect.OptionItem(
                    title = "Front Camera",
                    description = "Selfie front-facing camera. Best for video conferencing.",
                    iconRes = R.drawable.ic_flip_camera
                )
            )
        }
        if (options.isEmpty()) {
            options.add(
                BottomSheetSelect.OptionItem(
                    title = "No Camera Detected",
                    description = "No integrated camera modules discovered on this device.",
                    iconRes = R.drawable.ic_videocam
                )
            )
        }
        facingOptions = options

        rowCameraFacing.setOnClickListener {
            val dialog = BottomSheetSelect.newInstance(
                title = "Select Camera Facing",
                options = facingOptions,
                selectedIndex = facingSelectedIndex,
                listener = object : BottomSheetSelect.OnOptionSelectedListener {
                    override fun onOptionSelected(index: Int) {
                        facingSelectedIndex = index
                        val selectedItem = facingOptions[index]
                        tvCameraFacingValue.text = selectedItem.title
                        
                        val isFrontSelected = selectedItem.title == "Front Camera"
                        val cameraId = if (isFrontSelected) frontCameraId else backCameraId
                        
                        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("camera_facing_front", isFrontSelected).apply()

                        updateResolutionAndFpsOptions(cameraId)
                    }
                }
            )
            dialog.show(supportFragmentManager, "SelectCameraFacing")
        }

        rowResolution.setOnClickListener {
            if (resolutionItems.isEmpty()) return@setOnClickListener
            val dialog = BottomSheetSelect.newInstance(
                title = "Select Resolution",
                options = resolutionItems,
                selectedIndex = resolutionSelectedIndex,
                listener = object : BottomSheetSelect.OnOptionSelectedListener {
                    override fun onOptionSelected(index: Int) {
                        resolutionSelectedIndex = index
                        val selectedRes = resolutionOptions[index]
                        tvResolutionValue.text = resolutionItems[index].title
                        
                        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("camera_resolution", selectedRes).apply()
                    }
                }
            )
            dialog.show(supportFragmentManager, "SelectResolution")
        }

        rowFps.setOnClickListener {
            if (fpsItems.isEmpty()) return@setOnClickListener
            val dialog = BottomSheetSelect.newInstance(
                title = "Select Target FPS",
                options = fpsItems,
                selectedIndex = fpsSelectedIndex,
                listener = object : BottomSheetSelect.OnOptionSelectedListener {
                    override fun onOptionSelected(index: Int) {
                        fpsSelectedIndex = index
                        val selectedFps = fpsOptions[index]
                        tvFpsValue.text = fpsItems[index].title
                        
                        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putInt("camera_fps", selectedFps).apply()
                    }
                }
            )
            dialog.show(supportFragmentManager, "SelectFps")
        }

        rotationItems = rotationOptions.map { deg ->
            val desc = when (deg) {
                90 -> "Rotate 90° clockwise (for portrait mode)."
                180 -> "Rotate 180° (upside down)."
                270 -> "Rotate 270° clockwise (90° counter-clockwise)."
                else -> "Standard direct camera output."
            }
            BottomSheetSelect.OptionItem(
                title = if (deg == 0) "0° (Default)" else "$deg°",
                description = desc,
                iconRes = R.drawable.ic_flip_camera
            )
        }

        rowRotation.setOnClickListener {
            val dialog = BottomSheetSelect.newInstance(
                title = "Select Rotation",
                options = rotationItems,
                selectedIndex = rotationSelectedIndex,
                listener = object : BottomSheetSelect.OnOptionSelectedListener {
                    override fun onOptionSelected(index: Int) {
                        rotationSelectedIndex = index
                        val selectedDeg = rotationOptions[index]
                        tvRotationValue.text = rotationItems[index].title
                        
                        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putInt("camera_rotation", selectedDeg).apply()
                    }
                }
            )
            dialog.show(supportFragmentManager, "SelectRotation")
        }

        connectionModeOptions = listOf(
            BottomSheetSelect.OptionItem(
                title = "Over Wi-Fi",
                description = "Stream wirelessly over the local network.",
                iconRes = R.drawable.ic_wifi_scan
            ),
            BottomSheetSelect.OptionItem(
                title = "Over USB (ADB)",
                description = "Stream over USB connection. Requires ADB debugging enabled.",
                iconRes = R.drawable.ic_usb
            )
        )

        rowConnectionMode.setOnClickListener {
            val dialog = BottomSheetSelect.newInstance(
                title = "Select Connection Mode",
                options = connectionModeOptions,
                selectedIndex = connectionModeSelectedIndex,
                listener = object : BottomSheetSelect.OnOptionSelectedListener {
                    override fun onOptionSelected(index: Int) {
                        connectionModeSelectedIndex = index
                        val selectedMode = if (index == 0) "wifi" else "adb"
                        
                        tvConnectionModeValue.text = connectionModeOptions[index].title
                        ivConnectionModeIcon.setImageResource(connectionModeOptions[index].iconRes ?: R.drawable.ic_wifi_scan)
                        
                        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

                        prefs.edit().putString("camera_connection_mode", selectedMode).apply()

                        if (selectedMode == "adb") {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@CameraStreamActivity)
                                .setTitle("USB Debugging Setup")
                                .setMessage(
                                    "To stream over USB, please perform the following steps:\n\n" +
                                    "1. Enable Developer Options: Go to Settings > About Phone, and tap 'Build number' 7 times.\n\n" +
                                    "2. Enable USB Debugging: Go to Settings > System > Developer options, and turn on 'USB debugging'.\n\n" +
                                    "3. Connect USB Cable: Plug your device into your computer and authorize USB debugging when prompted."
                                )
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            )
            dialog.show(supportFragmentManager, "SelectConnectionMode")
        }
    }


    private fun getFriendlyResolutionName(width: Int, height: Int): String {
        return when {
            width == 3840 && height == 2160 -> "3840x2160 (4K Ultra HD)"
            width == 2560 && height == 1440 -> "2560x1440 (QHD)"
            width == 1920 && height == 1080 -> "1920x1080 (FHD 1080p)"
            width == 1280 && height == 720 -> "1280x720 (HD 720p)"
            width == 960 && height == 540 -> "960x540 (qHD)"
            width == 640 && height == 480 -> "640x480 (SD 480p)"
            width == 320 && height == 240 -> "320x240 (QVGA)"
            else -> "${width}x${height}"
        }
    }

    private fun getResolutionDescription(width: Int, height: Int): String {
        return when {
            width == 3840 && height == 2160 -> "Ultra clear detail, high CPU & network bandwidth consumption."
            width == 2560 && height == 1440 -> "Quad HD detail, balanced and clear representation."
            width == 1920 && height == 1080 -> "Crisp Full HD output, standard stream format."
            width == 1280 && height == 720 -> "HD resolution, good balance of resource usages."
            width == 960 && height == 540 -> "Medium resolution format."
            width == 640 && height == 480 -> "Standard definition, low network consumption."
            width == 320 && height == 240 -> "Low resolution format, minimal power consumption."
            else -> "Dynamic camera custom resolution."
        }
    }

    private fun getFpsDescription(fps: Int): String {
        return when (fps) {
            60 -> "Maximum smoothness, recommended for moving objects."
            30 -> "Standard cinema video rate, smooth capture."
            15 -> "Low frame rate, conserves cellular data and power."
            else -> "Custom device-supported capture frame rate."
        }
    }

    private fun updateResolutionAndFpsOptions(cameraId: String?) {
        if (cameraId == null) {
            resolutionOptions = emptyList()
            resolutionItems = emptyList()
            resolutionSelectedIndex = -1
            fpsOptions = emptyList()
            fpsItems = emptyList()
            fpsSelectedIndex = -1
            tvResolutionValue.text = "N/A"
            tvFpsValue.text = "N/A"
            return
        }

        val chars = cameraManager.getCameraCharacteristics(cameraId)
        
        // 1. Resolutions
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        val allResolutions = sizes.sortedWith(compareByDescending<android.util.Size> { it.width }.thenByDescending { it.height })
            .map { "${it.width}x${it.height}" }
            .distinct()

        val standardResolutions = listOf(
            "3840x2160", // 4K Ultra HD
            "2560x1440", // QHD
            "1920x1080", // FHD 1080p
            "1280x720",  // HD 720p
            "640x480",   // SD 480p
            "320x240"    // QVGA
        )
        val filteredResolutions = standardResolutions.filter { it in allResolutions }

        resolutionOptions = if (filteredResolutions.isEmpty()) {
            if (allResolutions.isEmpty()) listOf("640x480") else listOf(allResolutions.first())
        } else {
            filteredResolutions
        }
        
        resolutionItems = resolutionOptions.map { res ->
            val parts = res.split("x")
            val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val icon = when {
                w >= 1920 -> R.drawable.ic_high_quality
                w >= 1280 -> R.drawable.ic_videocam
                else -> R.drawable.ic_camera
            }
            BottomSheetSelect.OptionItem(
                title = getFriendlyResolutionName(w, h),
                description = getResolutionDescription(w, h),
                iconRes = icon
            )
        }

        // Select previously saved resolution
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val savedRes = prefs.getString("camera_resolution", "640x480")
        resolutionSelectedIndex = resolutionOptions.indexOf(savedRes).coerceAtLeast(0)
        
        // Set display text
        if (resolutionOptions.isNotEmpty()) {
            tvResolutionValue.text = resolutionItems[resolutionSelectedIndex].title
            // Save it in case we fell back
            prefs.edit().putString("camera_resolution", resolutionOptions[resolutionSelectedIndex]).apply()
        } else {
            tvResolutionValue.text = "N/A"
        }

        // 2. FPS
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val uniqueFps = ranges.map { it.upper }.distinct().sortedDescending()
        fpsOptions = if (uniqueFps.isEmpty()) listOf(30, 15) else uniqueFps

        fpsItems = fpsOptions.map { fps ->
            BottomSheetSelect.OptionItem(
                title = "$fps FPS",
                description = getFpsDescription(fps),
                iconRes = R.drawable.ic_shutter_speed
            )
        }

        // Select previously saved FPS
        val savedFps = prefs.getInt("camera_fps", 30)
        fpsSelectedIndex = fpsOptions.indexOf(savedFps).coerceAtLeast(0)

        if (fpsOptions.isNotEmpty()) {
            tvFpsValue.text = fpsItems[fpsSelectedIndex].title
            // Save it in case we fell back
            prefs.edit().putInt("camera_fps", fpsOptions[fpsSelectedIndex]).apply()
        } else {
            tvFpsValue.text = "N/A"
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val isFront = prefs.getBoolean("camera_facing_front", false)
        
        val facingText = if (isFront) "Front Camera" else "Back Camera"
        facingSelectedIndex = facingOptions.indexOf(facingOptions.firstOrNull { it.title == facingText }).coerceAtLeast(0)
        tvCameraFacingValue.text = facingText

        val savedRotation = prefs.getInt("camera_rotation", 0)
        rotationSelectedIndex = rotationOptions.indexOf(savedRotation).coerceAtLeast(0)
        tvRotationValue.text = rotationItems[rotationSelectedIndex].title

        val savedMode = prefs.getString("camera_connection_mode", "wifi") ?: "wifi"
        connectionModeSelectedIndex = if (savedMode == "adb") 1 else 0
        if (connectionModeOptions.isNotEmpty()) {
            tvConnectionModeValue.text = connectionModeOptions[connectionModeSelectedIndex].title
            ivConnectionModeIcon.setImageResource(connectionModeOptions[connectionModeSelectedIndex].iconRes ?: R.drawable.ic_wifi_scan)
        } else {
            tvConnectionModeValue.text = if (savedMode == "adb") "Over USB (ADB)" else "Over Wi-Fi"
            ivConnectionModeIcon.setImageResource(if (savedMode == "adb") R.drawable.ic_usb else R.drawable.ic_wifi_scan)
        }


        val cameraId = if (isFront) frontCameraId else backCameraId
        updateResolutionAndFpsOptions(cameraId)
    }


    private fun updateUi() {
        val app = applicationContext as SyncApp
        val isStreaming = app.isCameraStreaming

        // Resolve themed colors
        val typedValue = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        val primaryColor = typedValue.data
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, typedValue, true)
        val onPrimaryColor = typedValue.data

        theme.resolveAttribute(com.google.android.material.R.attr.colorError, typedValue, true)
        val errorColor = typedValue.data
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnError, typedValue, true)
        val onErrorColor = typedValue.data

        if (isStreaming) {
            tvStreamStatus.text = "Active"
            val port = app.cameraStreamingPort
            val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
            val useAdb = prefs.getString("camera_connection_mode", "wifi") == "adb"
            if (useAdb) {
                tvStreamAddress.text = "http://127.0.0.1:$port/ (ADB)"
            } else {
                val ip = getWifiIpAddress()
                tvStreamAddress.text = "http://$ip:$port/"
            }
            
            rowCameraFacing.isEnabled = false
            rowResolution.isEnabled = false
            rowFps.isEnabled = false
            rowRotation.isEnabled = false
            rowConnectionMode.isEnabled = false
            rowCameraFacing.alpha = 0.5f
            rowResolution.alpha = 0.5f
            rowFps.alpha = 0.5f
            rowRotation.alpha = 0.5f
            rowConnectionMode.alpha = 0.5f
            
            btnToggleStream.text = "Stop Stream"
            btnToggleStream.backgroundTintList = ColorStateList.valueOf(errorColor)
            btnToggleStream.setTextColor(onErrorColor)
        } else {
            tvStreamStatus.text = "Stopped"
            tvStreamAddress.text = "Not active"
            
            rowCameraFacing.isEnabled = true
            rowResolution.isEnabled = true
            rowFps.isEnabled = true
            rowRotation.isEnabled = true
            rowConnectionMode.isEnabled = true
            rowCameraFacing.alpha = 1.0f
            rowResolution.alpha = 1.0f
            rowFps.alpha = 1.0f
            rowRotation.alpha = 1.0f
            rowConnectionMode.alpha = 1.0f
            
            btnToggleStream.text = "Start Stream"
            btnToggleStream.backgroundTintList = ColorStateList.valueOf(primaryColor)
            btnToggleStream.setTextColor(onPrimaryColor)
        }

    }

    private fun startStreamService() {
        val intent = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_START_CAMERA_STREAM
        }
        startService(intent)
    }

    private fun stopStreamService() {
        val intent = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_STOP_CAMERA_STREAM
        }
        startService(intent)
    }

    private fun getWifiIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return if (ipAddress == 0) {
            "127.0.0.1"
        } else {
            String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
        }
    }
}
