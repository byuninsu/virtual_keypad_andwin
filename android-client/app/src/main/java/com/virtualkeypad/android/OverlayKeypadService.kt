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
import androidx.core.app.NotificationCompat

class OverlayKeypadService : Service(), RelayConnectionManager.Listener {
    private lateinit var windowManager: WindowManager
    private lateinit var settingsStore: KeypadSettingsStore
    private var latestSnapshot = RelaySnapshot()
    private var isExpanded = true

    private data class OverlayWindow(val view: View, val params: WindowManager.LayoutParams)

    private var dpadWindow: OverlayWindow? = null
    private var fkeysWindow: OverlayWindow? = null
    private var abcdWindow: OverlayWindow? = null

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
            ACTION_INIT -> {}
            ACTION_HIDE -> removeOverlay()
            ACTION_SHOW -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlay()
            }
            ACTION_CONNECT -> RelayConnectionManager.connect(settingsStore.getServerUrl(), settingsStore.getRoomId())
            ACTION_DISCONNECT -> RelayConnectionManager.disconnect("notification_disconnect")
            ACTION_OPEN_APP -> removeOverlay()
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
        if (fkeysWindow != null) {
            if (!isExpanded) setExpanded(true)
            bindButtons()
            refreshNotification()
            return
        }

        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val density = dm.density
        val midY = (sh * 0.33f).toInt()

        // F1~F5 패널: 항상 살아있고 X→O 토글로 expand/collapse 제어
        val fkeysView = LayoutInflater.from(this).inflate(R.layout.overlay_fkeys, null)
        // ≡(~40dp) + F1-F5(5×44+4×6dp) + X(44dp) + padding(8dp) ≈ 344dp
        val fkeysWidthPx = (344 * density).toInt()
        val fkeysDefaultX = ((sw - fkeysWidthPx) / 2).coerceAtLeast(0)
        val fkeysAnchor = settingsStore.loadFkeysAnchor() ?: OverlayAnchor(fkeysDefaultX, 0)
        val fkeysParams = makeParams(fkeysAnchor)
        fkeysWindow = OverlayWindow(fkeysView, fkeysParams)
        setupWindowDrag(fkeysView.findViewById(R.id.overlayFkeysDrag), fkeysWindow!!) {
            settingsStore.saveFkeysAnchor(it)
        }
        fkeysView.findViewById<View>(R.id.overlayCloseButton).setOnClickListener {
            setExpanded(false)
        }
        // O 버튼: 드래그 가능 + 탭으로 복원
        setupWindowDrag(
            handle = fkeysView.findViewById(R.id.overlayRestoreButton),
            window = fkeysWindow!!,
            onTap = { setExpanded(true) }
        ) { settingsStore.saveFkeysAnchor(it) }
        windowManager.addView(fkeysView, fkeysParams)

        // D-pad: 좌측 끝
        val dpadView = LayoutInflater.from(this).inflate(R.layout.overlay_dpad, null)
        val dpadAnchor = settingsStore.loadDpadAnchor() ?: OverlayAnchor(0, midY)
        val dpadParams = makeParams(dpadAnchor)
        dpadWindow = OverlayWindow(dpadView, dpadParams)
        setupWindowDrag(dpadView.findViewById(R.id.overlayDpadDrag), dpadWindow!!) {
            settingsStore.saveDpadAnchor(it)
        }
        windowManager.addView(dpadView, dpadParams)

        // A,B,C,D: 우측 끝
        val abcdView = LayoutInflater.from(this).inflate(R.layout.overlay_abcd, null)
        val abcdWidthPx = (164 * density).toInt()
        val abcdDefaultX = (sw - abcdWidthPx).coerceAtLeast(0)
        val abcdAnchor = settingsStore.loadAbcdAnchor() ?: OverlayAnchor(abcdDefaultX, midY)
        val abcdParams = makeParams(abcdAnchor)
        abcdWindow = OverlayWindow(abcdView, abcdParams)
        setupWindowDrag(abcdView.findViewById(R.id.overlayAbcdDrag), abcdWindow!!) {
            settingsStore.saveAbcdAnchor(it)
        }
        windowManager.addView(abcdView, abcdParams)

        isExpanded = true
        bindButtons()
        refreshNotification()
    }

    private fun setExpanded(expanded: Boolean) {
        val fkeys = fkeysWindow?.view ?: return
        val dpad = dpadWindow ?: return
        val abcd = abcdWindow ?: return

        fkeys.findViewById<View>(R.id.overlayFkeysExpanded).visibility =
            if (expanded) View.VISIBLE else View.GONE
        fkeys.findViewById<View>(R.id.overlayRestoreButton).visibility =
            if (expanded) View.GONE else View.VISIBLE

        if (expanded) {
            if (!dpad.view.isAttachedToWindow) windowManager.addView(dpad.view, dpad.params)
            if (!abcd.view.isAttachedToWindow) windowManager.addView(abcd.view, abcd.params)
        } else {
            if (dpad.view.isAttachedToWindow) windowManager.removeView(dpad.view)
            if (abcd.view.isAttachedToWindow) windowManager.removeView(abcd.view)
        }
        isExpanded = expanded
        refreshNotification()
    }

    private fun makeParams(anchor: OverlayAnchor) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = anchor.x
        y = anchor.y
    }

    private fun bindButtons() {
        val dpad = dpadWindow?.view ?: return
        val fkeys = fkeysWindow?.view ?: return
        val abcd = abcdWindow?.view ?: return
        val buttons = settingsStore.loadButtons().associateBy { it.prefKey }

        bindKeyButton(dpad.findViewById(R.id.overlayUpButton), buttons.getValue("up"))
        bindKeyButton(dpad.findViewById(R.id.overlayDownButton), buttons.getValue("down"))
        bindKeyButton(dpad.findViewById(R.id.overlayLeftButton), buttons.getValue("left"))
        bindKeyButton(dpad.findViewById(R.id.overlayRightButton), buttons.getValue("right"))
        bindKeyButton(fkeys.findViewById(R.id.overlayTop1Button), buttons.getValue("top1"))
        bindKeyButton(fkeys.findViewById(R.id.overlayTop2Button), buttons.getValue("top2"))
        bindKeyButton(fkeys.findViewById(R.id.overlayTop3Button), buttons.getValue("top3"))
        bindKeyButton(fkeys.findViewById(R.id.overlayTop4Button), buttons.getValue("top4"))
        bindKeyButton(fkeys.findViewById(R.id.overlayTop5Button), buttons.getValue("top5"))
        bindKeyButton(abcd.findViewById(R.id.overlayPrimaryButton), buttons.getValue("primary"))
        bindKeyButton(abcd.findViewById(R.id.overlaySecondaryButton), buttons.getValue("secondary"))
        bindKeyButton(abcd.findViewById(R.id.overlayTertiaryButton), buttons.getValue("tertiary"))
        bindKeyButton(abcd.findViewById(R.id.overlayQuaternaryButton), buttons.getValue("quaternary"))
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
                    val inside = event.x >= 0f && event.y >= 0f &&
                        event.x <= button.width && event.y <= button.height
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

    private fun setupWindowDrag(
        handle: View,
        window: OverlayWindow,
        onTap: (() -> Unit)? = null,
        onSave: (OverlayAnchor) -> Unit
    ) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = window.params
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
                            windowManager.updateViewLayout(window.view, params)
                            onSave(OverlayAnchor(params.x, params.y))
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) onTap?.invoke()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun removeOverlay() {
        dpadWindow?.view?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        fkeysWindow?.view?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        abcdWindow?.view?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        dpadWindow = null
        fkeysWindow = null
        abcdWindow = null
        isExpanded = true
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
        val overlayStatus = when {
            fkeysWindow == null -> getString(R.string.notification_overlay_hidden)
            !isExpanded -> getString(R.string.notification_overlay_collapsed)
            else -> getString(R.string.notification_overlay_visible)
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
            Intent(this, OverlayKeypadService::class.java).apply { this.action = action },
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
            context.startForegroundService(
                Intent(context, OverlayKeypadService::class.java).apply { action = ACTION_INIT }
            )
        }

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, OverlayKeypadService::class.java).apply { action = ACTION_SHOW }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayKeypadService::class.java).apply { action = ACTION_HIDE }
            )
        }
    }
}
