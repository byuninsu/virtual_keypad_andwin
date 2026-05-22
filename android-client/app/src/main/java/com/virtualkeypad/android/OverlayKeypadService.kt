package com.virtualkeypad.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat

class OverlayKeypadService : Service(), RelayConnectionManager.Listener {
    private lateinit var windowManager: WindowManager
    private lateinit var settingsStore: KeypadSettingsStore
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var latestSnapshot = RelaySnapshot()
    private var isOverlayExpanded = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsStore = KeypadSettingsStore(this)
        RelayConnectionManager.addListener(this)
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> {
            }

            ACTION_HIDE -> {
                removeOverlay()
            }

            ACTION_SHOW -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlay()
            }

            ACTION_CONNECT -> {
                RelayConnectionManager.connect(settingsStore.getServerUrl(), settingsStore.getRoomId())
            }

            ACTION_DISCONNECT -> {
                RelayConnectionManager.disconnect("notification_disconnect")
            }

            ACTION_OPEN_APP -> {
                removeOverlay()
            }

            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlay()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        RelayConnectionManager.removeListener(this)
        super.onDestroy()
    }

    override fun onSnapshotChanged(snapshot: RelaySnapshot) {
        latestSnapshot = snapshot
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onLog(line: String) = Unit

    override fun onError(message: String) = Unit

    private fun showOverlay() {
        if (overlayView != null) {
            bindButtons(overlayView!!)
            setOverlayExpanded(true)
            refreshNotification()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_keypad, null)
        val anchor = settingsStore.loadOverlayAnchor()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchor.x
            y = anchor.y
        }

        overlayView = view
        layoutParams = params
        bindButtons(view)
        setupDrag(view.findViewById(R.id.overlayHeader))
        setupDrag(view.findViewById(R.id.overlayToggleButton)) {
            setOverlayExpanded(true)
        }
        windowManager.addView(view, params)
        setOverlayExpanded(true)
        refreshNotification()
    }

    private fun bindButtons(view: View) {
        val buttons = settingsStore.loadButtons().associateBy { it.prefKey }

        bindKeyButton(view.findViewById(R.id.overlayUpButton), buttons.getValue("up"))
        bindKeyButton(view.findViewById(R.id.overlayDownButton), buttons.getValue("down"))
        bindKeyButton(view.findViewById(R.id.overlayLeftButton), buttons.getValue("left"))
        bindKeyButton(view.findViewById(R.id.overlayRightButton), buttons.getValue("right"))
        bindKeyButton(view.findViewById(R.id.overlayTop1Button), buttons.getValue("top1"))
        bindKeyButton(view.findViewById(R.id.overlayTop2Button), buttons.getValue("top2"))
        bindKeyButton(view.findViewById(R.id.overlayTop3Button), buttons.getValue("top3"))
        bindKeyButton(view.findViewById(R.id.overlayTop4Button), buttons.getValue("top4"))
        bindKeyButton(view.findViewById(R.id.overlayTop5Button), buttons.getValue("top5"))
        bindKeyButton(view.findViewById(R.id.overlayPrimaryButton), buttons.getValue("primary"))
        bindKeyButton(view.findViewById(R.id.overlaySecondaryButton), buttons.getValue("secondary"))
        bindKeyButton(view.findViewById(R.id.overlayTertiaryButton), buttons.getValue("tertiary"))
        bindKeyButton(view.findViewById(R.id.overlayQuaternaryButton), buttons.getValue("quaternary"))

        view.findViewById<View>(R.id.overlayCloseButton).setOnClickListener {
            setOverlayExpanded(false)
        }
    }

    private fun bindKeyButton(button: Button, config: KeypadButtonState) {
        button.text = config.label
        button.alpha = 1f
        button.setOnTouchListener { _, event ->
            fun releaseIfNeeded() {
                button.isPressed = false
                button.alpha = 1f
                RelayConnectionManager.sendKeyUp(config)
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    button.isPressed = true
                    button.alpha = 0.72f
                    RelayConnectionManager.sendKeyDown(config)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val inside = event.x >= 0f &&
                        event.y >= 0f &&
                        event.x <= button.width &&
                        event.y <= button.height
                    button.isPressed = inside
                    button.alpha = if (inside) 0.72f else 1f
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    releaseIfNeeded()
                    true
                }

                else -> false
            }
        }
    }

    private fun setupDrag(handle: View, onTap: (() -> Unit)? = null) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = layoutParams ?: return false

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - touchX
                        val deltaY = event.rawY - touchY
                        if (!dragging && (kotlin.math.abs(deltaX) > 8f || kotlin.math.abs(deltaY) > 8f)) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = startX + deltaX.toInt()
                            params.y = startY + deltaY.toInt()
                            overlayView?.let { windowManager.updateViewLayout(it, params) }
                            settingsStore.saveOverlayAnchor(OverlayAnchor(params.x, params.y))
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            onTap?.invoke()
                            v.performClick()
                        }
                        return true
                    }
                }

                return false
            }
        })
    }

    private fun setOverlayExpanded(expanded: Boolean) {
        val view = overlayView ?: return
        val overlayPanel = view.findViewById<LinearLayout>(R.id.overlayPanel)
        val toggleButton = view.findViewById<Button>(R.id.overlayToggleButton)
        isOverlayExpanded = expanded
        overlayPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleButton.visibility = if (expanded) View.GONE else View.VISIBLE
        refreshNotification()
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            windowManager.removeView(view)
        }
        overlayView = null
        layoutParams = null
        isOverlayExpanded = true
        refreshNotification()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Virtual Keypad Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(buildNotificationStatusText())
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_connect), serviceActionPendingIntent(ACTION_CONNECT, 10))
            .addAction(0, getString(R.string.notification_disconnect), serviceActionPendingIntent(ACTION_DISCONNECT, 11))
            .addAction(0, getString(R.string.notification_show_overlay), serviceActionPendingIntent(ACTION_SHOW, 12))
            .addAction(0, getString(R.string.notification_hide_overlay), serviceActionPendingIntent(ACTION_HIDE, 13))
            .build()
    }

    private fun buildNotificationStatusText(): String {
        val overlayStatus = if (overlayView == null) {
            getString(R.string.notification_overlay_hidden)
        } else if (isOverlayExpanded) {
            getString(R.string.notification_overlay_visible)
        } else {
            getString(R.string.notification_overlay_collapsed)
        }

        return "$overlayStatus · ${latestSnapshot.serverStatus}"
    }

    private fun refreshNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, OverlayKeypadService::class.java).apply {
                this.action = action
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "overlay_keypad"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_SHOW = "com.virtualkeypad.android.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.virtualkeypad.android.action.HIDE_OVERLAY"
        const val ACTION_INIT = "com.virtualkeypad.android.action.INIT"
        const val ACTION_CONNECT = "com.virtualkeypad.android.action.CONNECT"
        const val ACTION_DISCONNECT = "com.virtualkeypad.android.action.DISCONNECT"
        const val ACTION_OPEN_APP = "com.virtualkeypad.android.action.OPEN_APP"

        fun ensureRunning(context: Context) {
            val intent = Intent(context, OverlayKeypadService::class.java).apply {
                action = ACTION_INIT
            }
            context.startForegroundService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, OverlayKeypadService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayKeypadService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }
}
