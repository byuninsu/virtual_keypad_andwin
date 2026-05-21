package com.virtualkeypad.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), RelayConnectionManager.Listener {
    private lateinit var settingsStore: KeypadSettingsStore
    private lateinit var urlInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var serverStatusText: TextView
    private lateinit var roomStatusText: TextView
    private lateinit var peerStatusText: TextView
    private lateinit var lastEventText: TextView
    private lateinit var logText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var releaseAllButton: Button
    private lateinit var startOverlayButton: Button
    private lateinit var stopOverlayButton: Button
    private lateinit var labelInputs: Map<String, EditText>
    private lateinit var keyInputs: Map<String, EditText>
    private lateinit var delayInputs: Map<String, EditText>

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            ensureNotificationPermissionAndStartOverlay()
        } else {
            showToast(getString(R.string.overlay_permission_required))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startOverlayService()
        } else {
            showToast(getString(R.string.notification_permission_required))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsStore = KeypadSettingsStore(this)
        OverlayKeypadService.ensureRunning(this)

        urlInput = findViewById(R.id.urlInput)
        roomInput = findViewById(R.id.roomInput)
        serverStatusText = findViewById(R.id.serverStatusText)
        roomStatusText = findViewById(R.id.roomStatusText)
        peerStatusText = findViewById(R.id.peerStatusText)
        lastEventText = findViewById(R.id.lastEventText)
        logText = findViewById(R.id.logText)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        releaseAllButton = findViewById(R.id.releaseAllButton)
        startOverlayButton = findViewById(R.id.startOverlayButton)
        stopOverlayButton = findViewById(R.id.stopOverlayButton)

        labelInputs = mapOf(
            "up" to findViewById(R.id.labelUpInput),
            "down" to findViewById(R.id.labelDownInput),
            "left" to findViewById(R.id.labelLeftInput),
            "right" to findViewById(R.id.labelRightInput),
            "primary" to findViewById(R.id.labelPrimaryInput),
            "secondary" to findViewById(R.id.labelSecondaryInput),
            "tertiary" to findViewById(R.id.labelTertiaryInput),
            "quaternary" to findViewById(R.id.labelQuaternaryInput)
        )
        keyInputs = mapOf(
            "up" to findViewById(R.id.keyUpInput),
            "down" to findViewById(R.id.keyDownInput),
            "left" to findViewById(R.id.keyLeftInput),
            "right" to findViewById(R.id.keyRightInput),
            "primary" to findViewById(R.id.keyPrimaryInput),
            "secondary" to findViewById(R.id.keySecondaryInput),
            "tertiary" to findViewById(R.id.keyTertiaryInput),
            "quaternary" to findViewById(R.id.keyQuaternaryInput)
        )
        delayInputs = mapOf(
            "up" to findViewById(R.id.delayUpInput),
            "down" to findViewById(R.id.delayDownInput),
            "left" to findViewById(R.id.delayLeftInput),
            "right" to findViewById(R.id.delayRightInput),
            "primary" to findViewById(R.id.delayPrimaryInput),
            "secondary" to findViewById(R.id.delaySecondaryInput),
            "tertiary" to findViewById(R.id.delayTertiaryInput),
            "quaternary" to findViewById(R.id.delayQuaternaryInput)
        )

        restoreSettings()
        setupKeyPickers()

        connectButton.setOnClickListener {
            if (!saveSettings()) {
                return@setOnClickListener
            }

            RelayConnectionManager.connect(
                urlInput.text.toString().trim(),
                roomInput.text.toString().trim()
            )
        }

        disconnectButton.setOnClickListener {
            RelayConnectionManager.disconnect()
        }

        releaseAllButton.setOnClickListener {
            RelayConnectionManager.releaseAll()
        }

        startOverlayButton.setOnClickListener {
            if (!saveSettings()) {
                return@setOnClickListener
            }
            requestOverlayAndStart()
        }

        stopOverlayButton.setOnClickListener {
            OverlayKeypadService.stop(this)
        }
    }

    override fun onStart() {
        super.onStart()
        RelayConnectionManager.addListener(this)
    }

    override fun onStop() {
        RelayConnectionManager.removeListener(this)
        super.onStop()
    }

    override fun onSnapshotChanged(snapshot: RelaySnapshot) {
        serverStatusText.text = snapshot.serverStatus
        roomStatusText.text = snapshot.roomStatus
        peerStatusText.text = snapshot.peerStatus
        lastEventText.text = snapshot.lastEvent

        val connected = snapshot.isConnected
        connectButton.isEnabled = !connected
        disconnectButton.isEnabled = connected
        releaseAllButton.isEnabled = connected
    }

    override fun onLog(line: String) {
        val current = logText.text?.toString().orEmpty()
        val updated = if (current.isBlank()) line else "$line\n$current"
        logText.text = updated.take(6000)
    }

    override fun onError(message: String) {
        onLog("! $message")
        showToast(message)
    }

    private fun restoreSettings() {
        urlInput.setText(settingsStore.getServerUrl())
        roomInput.setText(settingsStore.getRoomId())

        settingsStore.loadButtons().forEach { button ->
            labelInputs[button.prefKey]?.setText(button.label)
            keyInputs[button.prefKey]?.setText(button.key)
            delayInputs[button.prefKey]?.setText(button.delayMs.toString())
        }
    }

    private fun setupKeyPickers() {
        keyInputs.forEach { (_, input) ->
            input.isFocusable = false
            input.isFocusableInTouchMode = false
            input.isClickable = true
            input.setOnClickListener {
                KeyComboPickerDialog(this, input.text.toString()) { value ->
                    input.setText(value)
                }.show()
            }
        }
    }

    private fun saveSettings(): Boolean {
        val serverUrl = urlInput.text.toString().trim()
        val roomId = roomInput.text.toString().trim()

        if (serverUrl.isBlank() || roomId.isBlank()) {
            showToast(getString(R.string.url_room_required))
            return false
        }

        val buttons = KeypadSettingsStore.DEFAULT_BUTTONS.map { config ->
            val label = labelInputs[config.prefKey]?.text?.toString()?.trim().orEmpty()
            val key = keyInputs[config.prefKey]?.text?.toString()?.trim().orEmpty()
            val delayText = delayInputs[config.prefKey]?.text?.toString()?.trim().orEmpty()

            if (label.isBlank() || key.isBlank()) {
                showToast(getString(R.string.button_config_required))
                return false
            }

            val delayMs = delayText.toLongOrNull()
            if (delayMs == null || delayMs < 0) {
                showToast(getString(R.string.button_delay_required))
                return false
            }

            KeypadButtonState(
                prefKey = config.prefKey,
                label = label,
                key = key,
                delayMs = delayMs
            )
        }

        settingsStore.saveConnection(serverUrl, roomId)
        settingsStore.saveButtons(buttons)
        return true
    }

    private fun requestOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        ensureNotificationPermissionAndStartOverlay()
    }

    private fun ensureNotificationPermissionAndStartOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startOverlayService()
    }

    private fun startOverlayService() {
        OverlayKeypadService.start(this)
        showToast(getString(R.string.overlay_started))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
