package com.example.walkingphoneguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import androidx.core.content.edit

class WalkingMonitorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var bleManager: BleImuManager? = null

    private val handler = Handler(Looper.getMainLooper())

    private var lastStepTime = 0L
    private var walkingStartTime = 0L
    private var isWalking = false
    private var isAlerting = false
    private var currentSpeedMps = 0f

    private var speedWalkingState = false
    private var shakeWalkingState = false
    private var finalJudgeState = false
    private var locationAccuracyM = 0f

    private var bleStatus = "未接続"
    private var rawText = ""
    private var bleConnected = false

    private var postureForwardAngle = 0f
    private var postureSideAngle = 0f
    private var lastPostureCountTime = 0L
    private var postureWarningSent = false
    private var postureBadStartTime = 0L

    private var serviceForeground = false
    private var monitoringActive = false

    private val KEY_POSTURE_STATS_DATE = "posture_stats_date"

    companion object {
        const val ACTION_START = "ACTION_START_MONITORING"
        const val ACTION_STOP = "ACTION_STOP_MONITORING"

        const val ACTION_BLE_CONNECT = "ACTION_BLE_CONNECT"
        const val ACTION_BLE_DISCONNECT = "ACTION_BLE_DISCONNECT"

        const val ACTION_ALERT_ON = "ACTION_ALERT_ON"
        const val ACTION_ALERT_OFF = "ACTION_ALERT_OFF"
        const val ACTION_MONITORING_STARTED = "ACTION_MONITORING_STARTED"
        const val ACTION_MONITORING_STOPPED = "ACTION_MONITORING_STOPPED"
        const val ACTION_STATUS_UPDATE = "ACTION_STATUS_UPDATE"

        private const val CHANNEL_ID = "walking_monitor_channel_v2"
        private const val NOTIFICATION_ID = 1001

        private const val STEP_CONTINUE_WINDOW_MS = 2000L
        private const val STOP_TIMEOUT_MS = 2000L
        private const val VIBRATION_MS = 500L
        private const val VIBRATION_INTERVAL_MS = 1000L

        private const val MIN_WALK_SPEED_MPS = 0.5f
        private const val MAX_WALK_SPEED_MPS = 2.5f

        private const val FORWARD_WARNING_ANGLE = 25f
        private const val SIDE_WARNING_ANGLE = 15f
    }

    override fun onCreate() {
        super.onCreate()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        bleManager = BleImuManager(
            context = this,
            onStatusChanged = {
                bleStatus = it

                bleConnected =
                    it.contains("受信中") ||
                            it.contains("接続成功") ||
                            it.contains("接続中")
                sendStatusUpdate()
            },
            onValuesChanged = { },
            onRawTextChanged = {
                rawText = it
                updatePostureFromRawTextInService(it)
                sendStatusUpdate()
            }
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()

            ACTION_BLE_CONNECT -> {
                startForegroundIfNeeded("連携中", "Arduinoと接続しています")
                startBle()
            }

            ACTION_BLE_DISCONNECT -> {
                stopPostureWarning()
                bleManager?.disconnect()

                bleStatus = "未接続"
                rawText = ""
                bleConnected = false

                sendStatusUpdate()

                if (!monitoringActive) {
                    stopForegroundAndSelf()
                }
            }
        }

        return START_STICKY
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startMonitoring() {
        startForegroundIfNeeded("監視中", "歩行を監視しています")

        if (stepSensor == null) {
            WalkingAppPrefs.setMonitoring(this, false)
            updateNotification("非対応", "このスマホは歩行センサー非対応です")
            sendSimpleBroadcast(ACTION_MONITORING_STOPPED)
            return
        }

        if (!hasRequiredPermissions()) {
            WalkingAppPrefs.setMonitoring(this, false)
            updateNotification("権限エラー", "歩行・位置情報の権限が必要です")
            sendSimpleBroadcast(ACTION_MONITORING_STOPPED)
            return
        }

        WalkingAppPrefs.resetPostureStatsIfNeeded(this)

        sensorManager.unregisterListener(this)
        sensorManager.registerListener(
            this,
            stepSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        startLocationUpdates()
        startBle()

        monitoringActive = true
        WalkingAppPrefs.setMonitoring(this, true)

        updateNotification("監視中", "歩行を監視しています")
        sendSimpleBroadcast(ACTION_MONITORING_STARTED)
        sendStatusUpdate()
    }

    private fun stopMonitoring() {
        WalkingAppPrefs.setMonitoring(this, false)
        monitoringActive = false

        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        stopAlertEffects()
        handler.removeCallbacksAndMessages(null)

        stopPostureWarning()

        bleManager?.disconnect()
        bleStatus = "未接続"
        rawText = ""
        bleConnected = false

        isWalking = false
        isAlerting = false
        currentSpeedMps = 0f
        speedWalkingState = false
        shakeWalkingState = false
        finalJudgeState = false
        locationAccuracyM = 0f

        lastStepTime = 0L
        walkingStartTime = 0L
        lastPostureCountTime = 0L

        sendAlertOffBroadcast()
        sendSimpleBroadcast(ACTION_MONITORING_STOPPED)
        sendStatusUpdate()

        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        super.onDestroy()

        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        stopAlertEffects()
        handler.removeCallbacksAndMessages(null)

        stopPostureWarning()
        bleManager?.disconnect()

        sendAlertOffBroadcast()
        sendStatusUpdate()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return

        val currentTime = System.currentTimeMillis()

        shakeWalkingState = true
        updateFinalJudgeState()
        checkPostureWarning()
        sendStatusUpdate()

        if (lastStepTime != 0L && currentTime - lastStepTime < STEP_CONTINUE_WINDOW_MS) {
            if (!isWalking) {
                isWalking = true
                walkingStartTime = currentTime
                updateNotification("歩行中", "歩行を検知しました")
                sendStatusUpdate()
            } else {
                val walkingDuration = currentTime - walkingStartTime
                val thresholdMs = getWalkingThresholdMs()

                if (walkingDuration >= thresholdMs && !isAlerting) {
                    startAlertIfValidSpeed()
                } else if (!isAlerting) {
                    val remainMs = thresholdMs - walkingDuration
                    val remainSec = (remainMs / 1000L) + 1L
                    updateNotification("歩行中", "あと ${remainSec} 秒で警告")
                }
            }
        } else {
            isWalking = false
            isAlerting = false
            stopAlertEffects()
            walkingStartTime = 0L
            shakeWalkingState = false
            updateFinalJudgeState()
            checkPostureWarning()

            updateNotification("検知中", "歩行開始を確認しています")
            sendAlertOffBroadcast()
            sendStatusUpdate()
        }

        lastStepTime = currentTime

        handler.removeCallbacks(stopCheckRunnable)
        handler.postDelayed(stopCheckRunnable, STOP_TIMEOUT_MS)
    }

    private val stopCheckRunnable = Runnable {
        val now = System.currentTimeMillis()

        if (now - lastStepTime >= STOP_TIMEOUT_MS) {
            stopAlertEffects()

            isWalking = false
            isAlerting = false
            walkingStartTime = 0L
            shakeWalkingState = false
            updateFinalJudgeState()
            checkPostureWarning()

            updateNotification("監視中", "歩行を監視しています")
            sendAlertOffBroadcast()
            sendStatusUpdate()
        }
    }

    private val vibrationRunnable = object : Runnable {
        override fun run() {
            if (isAlerting && WalkingAppPrefs.getVibrationEnabled(this@WalkingMonitorService)) {
                vibrateOnce()
                handler.postDelayed(this, VIBRATION_INTERVAL_MS)
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location: Location = result.lastLocation ?: return

            currentSpeedMps = if (location.hasSpeed()) location.speed else 0f
            locationAccuracyM = if (location.hasAccuracy()) location.accuracy else 0f
            speedWalkingState = isWalkLikeSpeed()

            updateFinalJudgeState()
            checkPostureWarning()
            sendStatusUpdate()
        }
    }

    private fun startBle() {
        bleManager?.startScan()
    }

    private fun updatePostureFromRawTextInService(text: String) {
        WalkingAppPrefs.resetPostureStatsIfNeeded(this)

        val values = Regex("""-?\d+(?:\.\d+)?""")
            .findAll(text)
            .map { it.value.toFloatOrNull() ?: 0f }
            .toList()

        if (values.size < 3) return

        val ax = values[0]
        val ay = values[1]
        val az = values[2]

        val totalG =
            kotlin.math.sqrt(
                ax * ax +
                        ay * ay +
                        az * az
            )

        if (totalG > 2.8f) {
            updateNotification(
                "転倒検知",
                "転倒の可能性があります"
            )
        }

        val rawForwardAngle = Math.toDegrees(
            atan2((-ax).toDouble(), ay.toDouble())
        ).toFloat()

        val rawSideAngle = Math.toDegrees(
            atan2(az.toDouble(), ay.toDouble())
        ).toFloat()

        val baseForwardAngle = WalkingAppPrefs.getBaseForwardAngle(this)
        val baseSideAngle = WalkingAppPrefs.getBaseSideAngle(this)

        postureForwardAngle = rawForwardAngle - baseForwardAngle
        postureSideAngle = rawSideAngle - baseSideAngle

        updatePostureSeconds()
        checkPostureWarning()
    }

    private fun updatePostureSeconds() {
        val now = System.currentTimeMillis()

        if (lastPostureCountTime == 0L) {
            lastPostureCountTime = now
            return
        }

        val elapsedSeconds = ((now - lastPostureCountTime) / 1000L).toInt()

        if (elapsedSeconds >= 1) {
            if (speedWalkingState && shakeWalkingState) {
                when {
                    postureForwardAngle > FORWARD_WARNING_ANGLE -> {
                        WalkingAppPrefs.addLookingDownSeconds(this, elapsedSeconds)
                    }

                    postureSideAngle < -SIDE_WARNING_ANGLE -> {
                        WalkingAppPrefs.addLeaningLeftSeconds(this, elapsedSeconds)
                    }

                    postureSideAngle > SIDE_WARNING_ANGLE -> {
                        WalkingAppPrefs.addLeaningRightSeconds(this, elapsedSeconds)
                    }
                }
            }

            lastPostureCountTime = now
        }
    }

    private fun checkPostureWarning() {
        val walkingByBoth = speedWalkingState && shakeWalkingState

        val badPosture =
            postureForwardAngle > FORWARD_WARNING_ANGLE ||
                    postureSideAngle < -SIDE_WARNING_ANGLE ||
                    postureSideAngle > SIDE_WARNING_ANGLE

        val warningMode = WalkingAppPrefs.getDeviceWarningMode(this)

        val warningCondition = when (warningMode) {
            WalkingAppPrefs.DEVICE_WARNING_POSTURE_ONLY -> badPosture
            else -> badPosture && walkingByBoth
        }

        val now = System.currentTimeMillis()
        val warningDelayMs =
            WalkingAppPrefs.getDeviceWarningSeconds(this).toLong() * 1000L

        if (warningCondition) {
            if (postureBadStartTime == 0L) {
                postureBadStartTime = now
            }

            if (now - postureBadStartTime >= warningDelayMs && !postureWarningSent) {
                bleManager?.ledBlink()
                postureWarningSent = true
            }
        } else {
            postureBadStartTime = 0L

            if (postureWarningSent) {
                bleManager?.ledOff()
                postureWarningSent = false
            }
        }
    }

    private fun stopPostureWarning() {
        postureWarningSent = false
        postureBadStartTime = 0L
        bleManager?.ledOff()
    }

    private fun getWalkingThresholdMs(): Long {
        return WalkingAppPrefs.getWarningSeconds(this).toLong() * 1000L
    }

    private fun hasRequiredPermissions(): Boolean {
        val activityPermission =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED

        val locationPermission =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return activityPermission && locationPermission
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        if (!hasRequiredPermissions()) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000L
        )
            .setMinUpdateIntervalMillis(1000L)
            .build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }

    private fun isWalkLikeSpeed(): Boolean {
        return currentSpeedMps in MIN_WALK_SPEED_MPS..MAX_WALK_SPEED_MPS
    }

    private fun updateFinalJudgeState() {
        finalJudgeState = shakeWalkingState && speedWalkingState && isScreenOn()
    }

    private fun startAlertIfValidSpeed() {
        updateFinalJudgeState()
        sendStatusUpdate()

        if (!isScreenOn()) return

        if (!isWalkLikeSpeed()) {
            finalJudgeState = false

            updateNotification(
                "歩行中",
                "速度 ${"%.1f".format(Locale.JAPAN, currentSpeedMps)} m/s のため警告しません"
            )

            sendStatusUpdate()
            return
        }

        isAlerting = true
        finalJudgeState = true

        val title = "歩きスマホ注意！"
        val message = "歩きスマホをやめてください！"
        val todayCount = WalkingAppPrefs.incrementTodayAlertCount(this)

        if (WalkingAppPrefs.getAlertNotificationEnabled(this)) {
            updateNotification(title, message)
        }

        sendAlertOnBroadcast(title, message, todayCount)

        stopAlertEffects()

        if (WalkingAppPrefs.getVibrationEnabled(this)) {
            vibrateOnce()
            handler.postDelayed(vibrationRunnable, VIBRATION_INTERVAL_MS)
        }

        sendStatusUpdate()
    }

    private fun stopAlertEffects() {
        handler.removeCallbacks(vibrationRunnable)

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.cancel()
    }

    private fun vibrateOnce() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    VIBRATION_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VIBRATION_MS)
        }
    }

    private fun sendStatusUpdate() {
        WalkingAppPrefs.resetPostureStatsIfNeeded(this)

        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra("speed_mps", currentSpeedMps)
            putExtra("is_walking", speedWalkingState && shakeWalkingState)
            putExtra("speed_walking", speedWalkingState)
            putExtra("shake_walking", shakeWalkingState)
            putExtra("final_judge", finalJudgeState)
            putExtra("location_accuracy_m", locationAccuracyM)

            putExtra("ble_status", bleStatus)
            putExtra("raw_text", rawText)
            putExtra("ble_connected", bleConnected)

            putExtra("posture_forward_angle", postureForwardAngle)
            putExtra("posture_side_angle", postureSideAngle)

            putExtra(
                "looking_down_seconds",
                WalkingAppPrefs.getLookingDownSeconds(this@WalkingMonitorService)
            )
            putExtra(
                "leaning_left_seconds",
                WalkingAppPrefs.getLeaningLeftSeconds(this@WalkingMonitorService)
            )
            putExtra(
                "leaning_right_seconds",
                WalkingAppPrefs.getLeaningRightSeconds(this@WalkingMonitorService)
            )
        }

        sendBroadcast(intent)
    }

    private fun startForegroundIfNeeded(title: String, text: String) {
        if (!serviceForeground) {
            startForeground(NOTIFICATION_ID, createNotification(title, text))
            serviceForeground = true
        } else {
            updateNotification(title, text)
        }
    }

    private fun stopForegroundAndSelf() {
        serviceForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(title: String, text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "歩行監視通知",
                NotificationManager.IMPORTANCE_HIGH
            )

            channel.description = "歩きスマホ防止アプリの監視通知"
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendAlertOnBroadcast(title: String, message: String, count: Int) {
        val intent = Intent(ACTION_ALERT_ON).apply {
            putExtra("title", title)
            putExtra("message", message)
            putExtra("count", count)
        }

        sendBroadcast(intent)
    }

    private fun sendAlertOffBroadcast() {
        sendBroadcast(Intent(ACTION_ALERT_OFF))
    }

    private fun sendSimpleBroadcast(action: String) {
        sendBroadcast(Intent(action))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null
}

data class DailyStat(
    val label: String,
    val count: Int
)

object WalkingAppPrefs {
    private const val PREFS_NAME = "walking_guard_prefs"

    private const val KEY_MONITORING = "key_monitoring"
    private const val KEY_SHOW_DEV_STATUS = "key_show_dev_status"
    private const val KEY_DARK_MODE = "key_dark_mode"
    private const val KEY_VIBRATION_ENABLED = "key_vibration_enabled"
    private const val KEY_ALERT_NOTIFICATION_ENABLED = "key_alert_notification_enabled"
    private const val KEY_WARNING_SECONDS = "key_warning_seconds"

    const val DEVICE_WARNING_POSTURE_ONLY = 0
    const val DEVICE_WARNING_POSTURE_AND_WALKING = 1

    private const val KEY_DEVICE_WARNING_MODE = "key_device_warning_mode"
    private const val KEY_DEVICE_WARNING_SECONDS = "key_device_warning_seconds"

    private const val KEY_BASE_FORWARD_ANGLE = "key_base_forward_angle"
    private const val KEY_BASE_SIDE_ANGLE = "key_base_side_angle"

    private const val KEY_POSTURE_DATE = "key_posture_date"
    private const val KEY_LOOKING_DOWN_SECONDS = "key_looking_down_seconds"
    private const val KEY_LEANING_LEFT_SECONDS = "key_leaning_left_seconds"
    private const val KEY_LEANING_RIGHT_SECONDS = "key_leaning_right_seconds"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun todayKey(): String {
        return dateKeyFromTime(System.currentTimeMillis())
    }

    private fun dateKeyFromTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(Date(timeMillis))
    }

    private fun dateLabelFromTime(timeMillis: Long): String {
        return SimpleDateFormat("M/d", Locale.JAPAN).format(Date(timeMillis))
    }

    private fun monthLabelFromTime(timeMillis: Long): String {
        return SimpleDateFormat("M月", Locale.JAPAN).format(Date(timeMillis))
    }

    private fun countKey(dateKey: String): String {
        return "count_$dateKey"
    }

    fun getTodayAlertCount(context: Context): Int {
        return prefs(context).getInt(countKey(todayKey()), 0)
    }

    fun incrementTodayAlertCount(context: Context): Int {
        val pref = prefs(context)
        val dayKey = todayKey()
        val newCount = pref.getInt(countKey(dayKey), 0) + 1
        pref.edit().putInt(countKey(dayKey), newCount).apply()
        return newCount
    }

    fun getLast30DaysStats(context: Context): List<DailyStat> {
        val pref = prefs(context)
        val calendar = Calendar.getInstance()
        val result = mutableListOf<DailyStat>()

        for (i in 29 downTo 0) {
            val cal = calendar.clone() as Calendar
            cal.add(Calendar.DAY_OF_YEAR, -i)

            val time = cal.timeInMillis
            val dateKey = dateKeyFromTime(time)
            val label = dateLabelFromTime(time)
            val count = pref.getInt(countKey(dateKey), 0)

            result.add(DailyStat(label, count))
        }

        return result
    }

    fun getLast12MonthsStats(context: Context): List<DailyStat> {
        val pref = prefs(context)
        val calendar = Calendar.getInstance()
        val result = mutableListOf<DailyStat>()

        for (i in 11 downTo 0) {
            val cal = calendar.clone() as Calendar
            cal.add(Calendar.MONTH, -i)

            val label = monthLabelFromTime(cal.timeInMillis)
            var total = 0
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            for (day in 1..daysInMonth) {
                val dayCal = cal.clone() as Calendar
                dayCal.set(Calendar.DAY_OF_MONTH, day)
                val dayKey = dateKeyFromTime(dayCal.timeInMillis)
                total += pref.getInt(countKey(dayKey), 0)
            }

            result.add(DailyStat(label, total))
        }

        return result
    }

    fun resetPostureStatsIfNeeded(context: Context) {
        val pref = prefs(context)
        val savedDate = pref.getString(KEY_POSTURE_DATE, todayKey())

        if (savedDate != todayKey()) {
            pref.edit()
                .putString(KEY_POSTURE_DATE, todayKey())
                .putInt(KEY_LOOKING_DOWN_SECONDS, 0)
                .putInt(KEY_LEANING_LEFT_SECONDS, 0)
                .putInt(KEY_LEANING_RIGHT_SECONDS, 0)
                .apply()
        }
    }

    fun addLookingDownSeconds(context: Context, seconds: Int) {
        resetPostureStatsIfNeeded(context)
        val pref = prefs(context)
        val value = pref.getInt(KEY_LOOKING_DOWN_SECONDS, 0) + seconds
        pref.edit().putInt(KEY_LOOKING_DOWN_SECONDS, value).apply()
    }

    fun addLeaningLeftSeconds(context: Context, seconds: Int) {
        resetPostureStatsIfNeeded(context)
        val pref = prefs(context)
        val value = pref.getInt(KEY_LEANING_LEFT_SECONDS, 0) + seconds
        pref.edit().putInt(KEY_LEANING_LEFT_SECONDS, value).apply()
    }

    fun addLeaningRightSeconds(context: Context, seconds: Int) {
        resetPostureStatsIfNeeded(context)
        val pref = prefs(context)
        val value = pref.getInt(KEY_LEANING_RIGHT_SECONDS, 0) + seconds
        pref.edit().putInt(KEY_LEANING_RIGHT_SECONDS, value).apply()
    }

    fun getLookingDownSeconds(context: Context): Int {
        resetPostureStatsIfNeeded(context)
        return prefs(context).getInt(KEY_LOOKING_DOWN_SECONDS, 0)
    }

    fun getLeaningLeftSeconds(context: Context): Int {
        resetPostureStatsIfNeeded(context)
        return prefs(context).getInt(KEY_LEANING_LEFT_SECONDS, 0)
    }

    fun getLeaningRightSeconds(context: Context): Int {
        resetPostureStatsIfNeeded(context)
        return prefs(context).getInt(KEY_LEANING_RIGHT_SECONDS, 0)
    }

    fun setDeviceWarningMode(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DEVICE_WARNING_MODE, value).apply()
    }

    fun getDeviceWarningMode(context: Context): Int {
        return prefs(context).getInt(
            KEY_DEVICE_WARNING_MODE,
            DEVICE_WARNING_POSTURE_AND_WALKING
        )
    }

    fun setDeviceWarningSeconds(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DEVICE_WARNING_SECONDS, value).apply()
    }

    fun getDeviceWarningSeconds(context: Context): Int {
        return prefs(context).getInt(KEY_DEVICE_WARNING_SECONDS, 3)
    }

    fun setBaseForwardAngle(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_BASE_FORWARD_ANGLE, value).apply()
    }

    fun getBaseForwardAngle(context: Context): Float {
        return prefs(context).getFloat(KEY_BASE_FORWARD_ANGLE, 0f)
    }

    fun setBaseSideAngle(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_BASE_SIDE_ANGLE, value).apply()
    }

    fun getBaseSideAngle(context: Context): Float {
        return prefs(context).getFloat(KEY_BASE_SIDE_ANGLE, 0f)
    }

    fun setMonitoring(context: Context, monitoring: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITORING, monitoring).apply()
    }

    fun isMonitoring(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MONITORING, false)
    }

    fun setShowDevStatus(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_DEV_STATUS, value).apply()
    }

    fun getShowDevStatus(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_DEV_STATUS, true)
    }

    fun setDarkModeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_MODE, value).apply()
    }

    fun getDarkModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DARK_MODE, false)
    }

    fun setVibrationEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()
    }

    fun getVibrationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VIBRATION_ENABLED, true)
    }

    fun setAlertNotificationEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALERT_NOTIFICATION_ENABLED, value).apply()
    }

    fun getAlertNotificationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ALERT_NOTIFICATION_ENABLED, true)
    }

    fun setWarningSeconds(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_WARNING_SECONDS, value).apply()
    }

    fun getWarningSeconds(context: Context): Int {
        return prefs(context).getInt(KEY_WARNING_SECONDS, 3)
    }

    private fun postureScoreKey(dateKey: String): String {
        return "posture_score_$dateKey"
    }

    fun saveTodayPostureScore(
        context: Context,
        score: Int
    ) {
        prefs(context)
            .edit()
            .putInt(
                postureScoreKey(todayKey()),
                score
            )
            .apply()
    }

    fun getTodayPostureScore(
        context: Context
    ): Int {
        return prefs(context)
            .getInt(
                postureScoreKey(todayKey()),
                100
            )
    }

    fun getLast7DaysAveragePostureScore(
        context: Context
    ): Int {

        val pref = prefs(context)

        var total = 0
        var count = 0

        val calendar = Calendar.getInstance()

        for (i in 1..7) {

            val cal = calendar.clone() as Calendar
            cal.add(Calendar.DAY_OF_YEAR, -i)

            val dateKey =
                dateKeyFromTime(cal.timeInMillis)

            val score =
                pref.getInt(
                    postureScoreKey(dateKey),
                    -1
                )

            if (score >= 0) {
                total += score
                count++
            }
        }

        if (count == 0) {
            return 100
        }

        return total / count
    }

    fun getPostureImprovementRate(
        context: Context
    ): Int {

        val today =
            getTodayPostureScore(context)

        val average =
            getLast7DaysAveragePostureScore(context)

        if (average <= 0) {
            return 0
        }

        return (
                (
                        (today - average).toFloat()
                                / average.toFloat()
                        ) * 100f
                ).toInt()
    }
}