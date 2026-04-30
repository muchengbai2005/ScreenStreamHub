package com.mcbcc.mcbtm.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.mcbcc.mcbtm.R

class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isContentVisible = true
    private var zoomLevel = 1f
    private val minZoom = 0.3f
    private val maxZoom = 4f
    private val zoomStep = 0.25f

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var dragStartTime = 0L

    companion object {
        const val ACTION_SHOW = "com.mcbcc.mcbtm.FLOATING_SHOW"
        const val ACTION_HIDE = "com.mcbcc.mcbtm.FLOATING_HIDE"
        const val ACTION_TOGGLE = "com.mcbcc.mcbtm.FLOATING_TOGGLE"
        const val ACTION_STOP = "com.mcbcc.mcbtm.FLOATING_STOP"
        const val ACTION_UPDATE_COORDS = "com.mcbcc.mcbtm.UPDATE_COORDS"
        const val EXTRA_X = "extra_x"
        const val EXTRA_Y = "extra_y"
        const val EXTRA_Z = "extra_z"

        private const val NOTIFICATION_ID = 0x5678
        private const val CHANNEL_ID = "floating_window_channel"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        createFloatingWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showWindow()
            ACTION_HIDE -> hideWindow()
            ACTION_TOGGLE -> toggleVisibility()
            ACTION_STOP -> stopSelf()
            ACTION_UPDATE_COORDS -> {
                val x = intent.getStringExtra(EXTRA_X)
                val y = intent.getStringExtra(EXTRA_Y)
                val z = intent.getStringExtra(EXTRA_Z)
                updateCoordinates(x ?: "", y ?: "", z ?: "")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingView()
        isRunning = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_window_title),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Floating overlay window service"
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_window_title))
            .setContentText("Overlay window running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createFloatingWindow() {
        if (floatingView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as android.view.LayoutInflater
        floatingView = inflater.inflate(R.layout.layout_floating_window, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(floatingView, params)

        setupDragOnEyeButton(params)
        setupButtonListeners()
    }

    private fun setupDragOnEyeButton(params: WindowManager.LayoutParams) {
        val eyeBtn = floatingView?.findViewById<ImageButton>(R.id.btnToggleVisibility) ?: return

        eyeBtn.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    dragStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        isDragging = false
                        true
                    } else {
                        view.performClick()
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun setupButtonListeners() {
        floatingView?.findViewById<ImageButton>(R.id.btnToggleVisibility)?.setOnClickListener {
            toggleVisibility()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnZoomIn)?.setOnClickListener {
            zoomIn()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnZoomOut)?.setOnClickListener {
            zoomOut()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFullscreen)?.setOnClickListener {
        }
    }

    private fun showWindow() {
        isContentVisible = true
        updateVisibilityIcon()
    }

    private fun hideWindow() {
        isContentVisible = false
        updateVisibilityIcon()
    }

    private fun toggleVisibility() {
        if (isContentVisible) {
            hideWindow()
        } else {
            showWindow()
        }
    }

    private fun updateVisibilityIcon() {
        val btn = floatingView?.findViewById<ImageButton>(R.id.btnToggleVisibility) ?: return
        btn.setImageResource(if (isContentVisible) R.drawable.ic_eye_open else R.drawable.ic_eye_closed)

        val rootFrame = floatingView as? View ?: return
        val contentRoot = rootFrame.findViewById<View>(R.id.floatingContentRoot)
        val contentPanel = rootFrame.findViewById<View>(R.id.contentPanel)
        val btnZoomIn = rootFrame.findViewById<View>(R.id.btnZoomIn)
        val btnZoomOut = rootFrame.findViewById<View>(R.id.btnZoomOut)
        val btnFullscreen = rootFrame.findViewById<View>(R.id.btnFullscreen)
        val divider1 = rootFrame.findViewById<View>(R.id.divider1)
        val divider2 = rootFrame.findViewById<View>(R.id.divider2)
        val buttonPanel = rootFrame.findViewById<LinearLayout>(R.id.buttonPanel)

        if (isContentVisible) {
            rootFrame.setBackgroundResource(R.drawable.bg_floating_window)
            rootFrame.setPadding(2, 2, 2, 2)

            contentRoot?.layoutParams = contentRoot?.layoutParams?.apply {
                width = (200 * resources.displayMetrics.density).toInt()
                height = (150 * resources.displayMetrics.density).toInt()
            }

            buttonPanel?.layoutParams = buttonPanel?.layoutParams?.apply {
                width = (36 * resources.displayMetrics.density).toInt()
            }

            contentPanel?.visibility = View.VISIBLE
            btnZoomIn?.visibility = View.VISIBLE
            btnZoomOut?.visibility = View.VISIBLE
            btnFullscreen?.visibility = View.VISIBLE
            divider1?.visibility = View.VISIBLE
            divider2?.visibility = View.VISIBLE
        } else {
            rootFrame.setBackgroundResource(0)
            rootFrame.setPadding(0, 0, 0, 0)

            contentPanel?.visibility = View.GONE
            btnZoomIn?.visibility = View.GONE
            btnZoomOut?.visibility = View.GONE
            btnFullscreen?.visibility = View.GONE
            divider1?.visibility = View.GONE
            divider2?.visibility = View.GONE

            buttonPanel?.layoutParams = buttonPanel?.layoutParams?.apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
            }

            contentRoot?.layoutParams = contentRoot?.layoutParams?.apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                height = LinearLayout.LayoutParams.WRAP_CONTENT
            }
        }

        contentRoot?.requestLayout()
        rootFrame.requestLayout()
    }

    private fun zoomIn() {
        zoomLevel = (zoomLevel + zoomStep).coerceAtMost(maxZoom)
        applyZoom()
    }

    private fun zoomOut() {
        zoomLevel = (zoomLevel - zoomStep).coerceAtLeast(minZoom)
        applyZoom()
    }

    private fun applyZoom() {
        val contentPanel = floatingView?.findViewById<View>(R.id.mapPreviewArea) ?: return
        contentPanel.scaleX = zoomLevel
        contentPanel.scaleY = zoomLevel
        val tvZoom = floatingView?.findViewById<TextView>(R.id.tvZoomLevel)
        tvZoom?.text = String.format("%.0f%%", zoomLevel * 100)
    }

    private fun updateCoordinates(x: String, y: String, z: String) {
        val tvCoords = floatingView?.findViewById<TextView>(R.id.tvCoordinates)
        tvCoords?.text = "$x, $y, $z"
    }

    private fun removeFloatingView() {
        try {
            if (floatingView != null && windowManager != null) {
                windowManager?.removeView(floatingView)
                floatingView = null
            }
        } catch (_: Exception) {}
    }
}
