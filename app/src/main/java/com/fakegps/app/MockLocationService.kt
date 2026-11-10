package com.fakegps.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.random.Random

/**
 * Foreground service that keeps pushing a fixed location into the system test providers
 * (GPS, network and, on Android 12+, fused) so every app sees the spoofed position.
 */
class MockLocationService : Service() {

    sealed interface State {
        data object Idle : State
        data class Running(val lat: Double, val lng: Double) : State
        data object MockNotAllowed : State
    }

    companion object {
        private const val ACTION_START = "com.fakegps.app.START"
        private const val ACTION_STOP = "com.fakegps.app.STOP"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val CHANNEL_ID = "fake_gps"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 1000L

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state

        fun start(context: Context, lat: Double, lng: Double) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MockLocationService::class.java).setAction(ACTION_STOP))
        }

        fun resetError() {
            if (_state.value is State.MockNotAllowed) _state.value = State.Idle
        }
    }

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val providers = mutableListOf<String>()
    private var lat = 0.0
    private var lng = 0.0

    private val updateRunnable = object : Runnable {
        override fun run() {
            pushLocation()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMocking()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                startForegroundCompat()
                if (!setupProviders()) {
                    _state.value = State.MockNotAllowed
                    stopMocking(keepState = true)
                    return START_NOT_STICKY
                }
                _state.value = State.Running(lat, lng)
                handler.removeCallbacks(updateRunnable)
                handler.post(updateRunnable)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        removeProviders()
        if (_state.value is State.Running) _state.value = State.Idle
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), piFlags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP), piFlags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(String.format(Locale.US, "%.6f, %.6f", lat, lng))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    /** Registers test providers. Returns false if this app is not the selected mock location app. */
    private fun setupProviders(): Boolean {
        removeProviders()
        val candidates = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) candidates += LocationManager.FUSED_PROVIDER
        for (provider in candidates) {
            try {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    provider,
                    false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
                providers += provider
            } catch (e: SecurityException) {
                removeProviders()
                return false
            } catch (e: IllegalArgumentException) {
                // Provider not supported on this device; skip it.
            }
        }
        return providers.isNotEmpty()
    }

    private fun removeProviders() {
        for (provider in providers) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
            }
        }
        providers.clear()
    }

    private fun pushLocation() {
        for (provider in providers) {
            val location = Location(provider).apply {
                // Tiny jitter (~1 m) so the position looks like a real, slightly noisy fix.
                latitude = lat + Random.nextDouble(-0.000008, 0.000008)
                longitude = lng + Random.nextDouble(-0.000008, 0.000008)
                altitude = 10.0
                accuracy = 3f + Random.nextFloat() * 2f
                speed = 0f
                bearing = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 3f
                    speedAccuracyMetersPerSecond = 0.1f
                    bearingAccuracyDegrees = 1f
                }
            }
            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (e: SecurityException) {
                _state.value = State.MockNotAllowed
                stopMocking(keepState = true)
                return
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun stopMocking(keepState: Boolean = false) {
        handler.removeCallbacks(updateRunnable)
        removeProviders()
        if (!keepState) _state.value = State.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
