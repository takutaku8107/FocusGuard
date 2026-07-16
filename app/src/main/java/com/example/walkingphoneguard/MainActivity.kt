package com.example.walkingphoneguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val AccentRed = Color(0xFFB00020)

class MainActivity : ComponentActivity() {

    private var isMonitoring by mutableStateOf(false)
    private var showAlertScreen by mutableStateOf(false)
    private var alertTitle by mutableStateOf("歩きスマホ注意！")
    private var alertMessage by mutableStateOf("歩きスマホをやめてください！")

    private var todayAlertCount by mutableIntStateOf(0)
    private var monthStats by mutableStateOf(listOf<DailyStat>())
    private var yearStats by mutableStateOf(listOf<DailyStat>())

    private var currentSpeedMps by mutableFloatStateOf(0f)
    private var speedWalkingState by mutableStateOf(false)
    private var shakeWalkingState by mutableStateOf(false)
    private var finalJudgeState by mutableStateOf(false)
    private var locationAccuracyM by mutableFloatStateOf(0f)

    private var bleStatus by mutableStateOf("未接続")
    private var rawText by mutableStateOf("")
    private var bleSwitchOn by mutableStateOf(false)

    private var postureForwardAngle by mutableFloatStateOf(0f)
    private var postureSideAngle by mutableFloatStateOf(0f)
    private var lookingDownWalkingSeconds by mutableIntStateOf(0)
    private var leaningLeftWalkingSeconds by mutableIntStateOf(0)
    private var leaningRightWalkingSeconds by mutableIntStateOf(0)

    private var showDevStatus by mutableStateOf(true)
    private var darkModeEnabled by mutableStateOf(false)
    private var vibrationEnabled by mutableStateOf(true)
    private var alertNotificationEnabled by mutableStateOf(true)
    private var warningSeconds by mutableIntStateOf(3)

    private var deviceWarningMode by mutableIntStateOf(WalkingAppPrefs.DEVICE_WARNING_POSTURE_AND_WALKING)
    private var deviceWarningSeconds by mutableIntStateOf(3)

    private var showPermissionGuide by mutableStateOf(false)

    @RequiresApi(Build.VERSION_CODES.Q)
    private val requestMultiplePermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            // 初回案内画面は再表示しない
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WalkingMonitorService.ACTION_ALERT_ON -> {
                    showAlertScreen = true
                    alertTitle = intent.getStringExtra("title") ?: "歩きスマホ注意！"
                    alertMessage = intent.getStringExtra("message") ?: "歩きスマホをやめてください！"
                    refreshStats()
                }
                //警告表示

                WalkingMonitorService.ACTION_ALERT_OFF -> {
                    showAlertScreen = false
                }
                //警告終了

                WalkingMonitorService.ACTION_MONITORING_STARTED -> {
                    isMonitoring = true
                }
                //監視開始

                WalkingMonitorService.ACTION_MONITORING_STOPPED -> {
                    isMonitoring = false
                    showAlertScreen = false
                    currentSpeedMps = 0f
                    speedWalkingState = false
                    shakeWalkingState = false
                    finalJudgeState = false
                    locationAccuracyM = 0f
                }
                //警告終了

                WalkingMonitorService.ACTION_STATUS_UPDATE -> {
                    currentSpeedMps = intent.getFloatExtra("speed_mps", 0f)
                    //速度の受信
                    speedWalkingState = intent.getBooleanExtra("speed_walking", false)
                    //歩行判定の受信
                    shakeWalkingState = intent.getBooleanExtra("shake_walking", false)
                    //揺れの受信
                    finalJudgeState = intent.getBooleanExtra("final_judge", false)
                    //最終判定の受信
                    locationAccuracyM = intent.getFloatExtra("location_accuracy_m", 0f)

                    bleStatus = intent.getStringExtra("ble_status") ?: bleStatus
                    //Bluetooth状態の受信
                    rawText = intent.getStringExtra("raw_text") ?: rawText
                    //生データ受信
                    bleSwitchOn = intent.getBooleanExtra("ble_connected", bleSwitchOn)

                    postureForwardAngle = intent.getFloatExtra(
                        "posture_forward_angle",
                        postureForwardAngle
                    )
                    //前傾角度
                    postureSideAngle = intent.getFloatExtra(
                        "posture_side_angle",
                        postureSideAngle
                    )
                    //左右の傾き
                    lookingDownWalkingSeconds = intent.getIntExtra(
                        "looking_down_seconds",
                        lookingDownWalkingSeconds
                    )
                    //下を向いて歩いた時間
                    leaningLeftWalkingSeconds = intent.getIntExtra(
                        "leaning_left_seconds",
                        leaningLeftWalkingSeconds
                    )
                    //左に傾いて歩いた時間
                    leaningRightWalkingSeconds = intent.getIntExtra(
                        "leaning_right_seconds",
                        leaningRightWalkingSeconds
                    )
                    //右に傾いて歩いた時間
                }
                //状態の更新
            }
        }
    }
    //WalkingMonitorServiceから送られてくる情報を受け取る

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //親クラスのonCreate()も実行

        loadSettings()
        //設定を読み込む
        refreshStats()
        //統計情報を更新
        loadPostureStats()
        //姿勢のデータを読み込む
        isMonitoring = WalkingAppPrefs.isMonitoring(this)
        //保存されている監視状態を取得
        showPermissionGuide = !WalkingAppPrefs.hasShownPermissionGuide(this)

        setContent {
            MaterialTheme(
                colorScheme = if (darkModeEnabled) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        isMonitoring = isMonitoring,
                        showAlertScreen = showAlertScreen,
                        alertTitle = alertTitle,
                        alertMessage = alertMessage,
                        todayAlertCount = todayAlertCount,
                        monthStats = monthStats,
                        yearStats = yearStats,
                        currentSpeedMps = currentSpeedMps,
                        speedWalkingState = speedWalkingState,
                        shakeWalkingState = shakeWalkingState,
                        finalJudgeState = finalJudgeState,
                        locationAccuracyM = locationAccuracyM,
                        bleStatus = bleStatus,
                        rawText = rawText,
                        bleSwitchOn = bleSwitchOn,
                        postureForwardAngle = postureForwardAngle,
                        postureSideAngle = postureSideAngle,
                        lookingDownWalkingSeconds = lookingDownWalkingSeconds,
                        leaningLeftWalkingSeconds = leaningLeftWalkingSeconds,
                        leaningRightWalkingSeconds = leaningRightWalkingSeconds,
                        showDevStatus = showDevStatus,
                        darkModeEnabled = darkModeEnabled,
                        vibrationEnabled = vibrationEnabled,
                        alertNotificationEnabled = alertNotificationEnabled,
                        warningSeconds = warningSeconds,
                        deviceWarningMode = deviceWarningMode,
                        deviceWarningSeconds = deviceWarningSeconds,
                        onMonitoringChange = { enabled ->
                            requestNeededPermissionsIfAny()

                            if (enabled) {
                                sendServiceAction(WalkingMonitorService.ACTION_START, true)
                                isMonitoring = true
                            } else {
                                sendServiceAction(WalkingMonitorService.ACTION_STOP, false)
                                isMonitoring = false
                                showAlertScreen = false
                                currentSpeedMps = 0f
                                speedWalkingState = false
                                shakeWalkingState = false
                                finalJudgeState = false
                                locationAccuracyM = 0f
                            }
                        }
                        //監視スイッチが押された時
                        ,
                        onCloseAlert = {
                            showAlertScreen = false
                        }
                        //警告画面の閉じるボタン
                        ,
                        onBleConnectClick = {
                            requestNeededPermissionsIfAny()
                            bleSwitchOn = true
                            bleStatus = "接続準備中..."
                            sendServiceAction(WalkingMonitorService.ACTION_BLE_CONNECT, true)
                        }
                        //Bluetooth接続ボタン
                        ,
                        onBleDisconnectClick = {
                            sendServiceAction(WalkingMonitorService.ACTION_BLE_DISCONNECT, false)
                            bleSwitchOn = false
                            bleStatus = "未接続"
                        }
                        //Bluetooth切断ボタン
                        ,
                        onDevStatusChange = {
                            showDevStatus = it
                            WalkingAppPrefs.setShowDevStatus(this, it)
                        },
                        onDarkModeChange = {
                            darkModeEnabled = it
                            WalkingAppPrefs.setDarkModeEnabled(this, it)
                        },
                        onVibrationChange = {
                            vibrationEnabled = it
                            WalkingAppPrefs.setVibrationEnabled(this, it)
                        },
                        onAlertNotificationChange = {
                            alertNotificationEnabled = it
                            WalkingAppPrefs.setAlertNotificationEnabled(this, it)
                        },
                        onWarningSecondsChange = {
                            warningSeconds = it
                            WalkingAppPrefs.setWarningSeconds(this, it)
                        },
                        onDeviceWarningModeChange = {
                            deviceWarningMode = it
                            WalkingAppPrefs.setDeviceWarningMode(this, it)
                        },
                        onDeviceWarningSecondsChange = {
                            deviceWarningSeconds = it
                            WalkingAppPrefs.setDeviceWarningSeconds(this, it)
                        },
                        onSetCurrentPostureAsDefault = {
                             val currentBaseForward = WalkingAppPrefs.getBaseForwardAngle(this)
                            val currentBaseSide = WalkingAppPrefs.getBaseSideAngle(this)

                            WalkingAppPrefs.setBaseForwardAngle(
                                this,
                                postureForwardAngle + currentBaseForward
                            )

                            WalkingAppPrefs.setBaseSideAngle(
                                this,
                                postureSideAngle + currentBaseSide
                            )

                            postureForwardAngle = 0f
                            postureSideAngle = 0f
                        },
                        //姿勢補正ボタン
                        onResetPostureDefault = {
                            WalkingAppPrefs.setBaseForwardAngle(this, 0f)
                            WalkingAppPrefs.setBaseSideAngle(this, 0f)
                        },
                        showPermissionGuide = showPermissionGuide,
                        onRequestPermissions = {
                            WalkingAppPrefs.setPermissionGuideShown(
                                this,
                                true
                            )

                            showPermissionGuide = false
                            requestNeededPermissionsIfAny()
                        },
                    )
                    //画面本体
                }
                //画面の土台
            }
            //アプリ全体のデザイン
        }
        //画面を作る
    }
    //アプリが起動したときに最初に実行される

    private fun sendServiceAction(action: String, foreground: Boolean) {
        val intent = Intent(this, WalkingMonitorService::class.java).apply {
            this.action = action
        }
        //命令を入れる封筒

        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        //Android8以上かつforeground=trueのときフォアグラウンドサービスとして開始
    }
    //WalkingMonitorServiceに命令を送る

    private fun loadSettings() {
        showDevStatus = WalkingAppPrefs.getShowDevStatus(this)
        darkModeEnabled = WalkingAppPrefs.getDarkModeEnabled(this)
        vibrationEnabled = WalkingAppPrefs.getVibrationEnabled(this)
        alertNotificationEnabled = WalkingAppPrefs.getAlertNotificationEnabled(this)
        warningSeconds = WalkingAppPrefs.getWarningSeconds(this)

        deviceWarningMode = WalkingAppPrefs.getDeviceWarningMode(this)
        deviceWarningSeconds = WalkingAppPrefs.getDeviceWarningSeconds(this)
    }
    //設定を読み込む関数

    private fun refreshStats() {
        todayAlertCount = WalkingAppPrefs.getTodayAlertCount(this)
        monthStats = WalkingAppPrefs.getLast30DaysStats(this)
        yearStats = WalkingAppPrefs.getLast12MonthsStats(this)
    }
    //統計データを読み込む関数

    private fun loadPostureStats() {
        WalkingAppPrefs.resetPostureStatsIfNeeded(this)
        lookingDownWalkingSeconds = WalkingAppPrefs.getLookingDownSeconds(this)
        leaningLeftWalkingSeconds = WalkingAppPrefs.getLeaningLeftSeconds(this)
        leaningRightWalkingSeconds = WalkingAppPrefs.getLeaningRightSeconds(this)
    }
    //姿勢に関する統計データを読み込む関数

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestNeededPermissionsIfAny() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        //通知権限

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        //歩行認識

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        //GPS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        //Bluetooth

        if (permissions.isNotEmpty()) {
            requestMultiplePermissions.launch(permissions.toTypedArray())
        }
        //権限ダイアログを表示
    }
    //権限を確認して、足りないものがあればユーザーに許可を求める

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasAllRequiredPermissions(): Boolean {
        val activityGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED

        val locationGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val notificationGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        val bluetoothGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        return activityGranted &&
                locationGranted &&
                notificationGranted &&
                bluetoothGranted
    }

    override fun onStart() {
        super.onStart()
        //親クラスのonStart()も実行

        isMonitoring = WalkingAppPrefs.isMonitoring(this)
        //監視状態の読み込み
        refreshStats()
        //警告回数の更新
        loadPostureStats()
        //姿勢データ更新
        loadSettings()
        //設定の読み込み

        val filter = IntentFilter().apply {
            addAction(WalkingMonitorService.ACTION_ALERT_ON)
            addAction(WalkingMonitorService.ACTION_ALERT_OFF)
            addAction(WalkingMonitorService.ACTION_MONITORING_STARTED)
            addAction(WalkingMonitorService.ACTION_MONITORING_STOPPED)
            addAction(WalkingMonitorService.ACTION_STATUS_UPDATE)
        }
        //部分的に受け取る

        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
    //画面がユーザーに見えるようになるたび実行

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }
    //ホーム画面に戻った時
}

@Composable
fun AppRoot(
    isMonitoring: Boolean,
    showAlertScreen: Boolean,
    alertTitle: String,
    alertMessage: String,
    todayAlertCount: Int,
    monthStats: List<DailyStat>,
    yearStats: List<DailyStat>,
    currentSpeedMps: Float,
    speedWalkingState: Boolean,
    shakeWalkingState: Boolean,
    finalJudgeState: Boolean,
    locationAccuracyM: Float,
    bleStatus: String,
    rawText: String,
    bleSwitchOn: Boolean,
    postureForwardAngle: Float,
    postureSideAngle: Float,
    lookingDownWalkingSeconds: Int,
    leaningLeftWalkingSeconds: Int,
    leaningRightWalkingSeconds: Int,
    showDevStatus: Boolean,
    darkModeEnabled: Boolean,
    vibrationEnabled: Boolean,
    alertNotificationEnabled: Boolean,
    warningSeconds: Int,
    deviceWarningMode: Int,
    deviceWarningSeconds: Int,
    onMonitoringChange: (Boolean) -> Unit,
    onCloseAlert: () -> Unit,
    onBleConnectClick: () -> Unit,
    onBleDisconnectClick: () -> Unit,
    onDevStatusChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onAlertNotificationChange: (Boolean) -> Unit,
    onWarningSecondsChange: (Int) -> Unit,
    onDeviceWarningModeChange: (Int) -> Unit,
    onDeviceWarningSecondsChange: (Int) -> Unit,
    onSetCurrentPostureAsDefault: () -> Unit,
    onResetPostureDefault: () -> Unit,
    showPermissionGuide: Boolean,
    onRequestPermissions: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    //今どのタブを選んでいるか

    val backgroundColor = if (darkModeEnabled) Color(0xFF121212) else Color(0xFFF5F5F5)
    val cardColor = if (darkModeEnabled) Color(0xFF1E1E1E) else Color.White
    val textSecondary = if (darkModeEnabled) Color(0xFFBBBBBB) else Color(0xFF777777)
    //ダークモードか否か

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Image(
                        painter = painterResource(id = R.drawable.focusguard_header),
                        contentDescription = "FocusGuard",
                        modifier = Modifier.size(56.dp)
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {

                        Text(
                            text = "FocusGuard",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = textSecondary
                        )

                        Text(
                            text = "Stay focused. Stay safe.",
                            fontSize = 14.sp,
                            color = textSecondary
                        )
                    }
                }
            }
            //ロゴとサブタイトル

            Box(
                modifier = Modifier
                    .weight(1f)
                    //ロゴとタブバー以外の部分
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        isMonitoring = isMonitoring,
                        currentSpeedMps = currentSpeedMps,
                        speedWalkingState = speedWalkingState,
                        shakeWalkingState = shakeWalkingState,
                        finalJudgeState = finalJudgeState,
                        locationAccuracyM = locationAccuracyM,
                        showDevStatus = showDevStatus,
                        darkModeEnabled = darkModeEnabled,
                        onMonitoringChange = onMonitoringChange,
                        postureForwardAngle = postureForwardAngle,
                        postureSideAngle = postureSideAngle
                    )

                    1 -> StatsScreen(
                        todayAlertCount = todayAlertCount,
                        monthStats = monthStats,
                        yearStats = yearStats,
                        postureForwardAngle = postureForwardAngle,
                        postureSideAngle = postureSideAngle,
                        lookingDownWalkingSeconds = lookingDownWalkingSeconds,
                        leaningLeftWalkingSeconds = leaningLeftWalkingSeconds,
                        leaningRightWalkingSeconds = leaningRightWalkingSeconds,
                        darkModeEnabled = darkModeEnabled,
                    )

                    2 -> LinkScreen(
                        bleStatus = bleStatus,
                        rawText = rawText,
                        bleSwitchOn = bleSwitchOn,
                        postureForwardAngle = postureForwardAngle,
                        postureSideAngle = postureSideAngle,
                        showDevStatus = showDevStatus,
                        darkModeEnabled = darkModeEnabled,
                        onBleConnectClick = onBleConnectClick,
                        onBleDisconnectClick = onBleDisconnectClick
                    )

                    3 -> SettingsScreen(
                        showDevStatus = showDevStatus,
                        darkModeEnabled = darkModeEnabled,
                        vibrationEnabled = vibrationEnabled,
                        alertNotificationEnabled = alertNotificationEnabled,
                        warningSeconds = warningSeconds,
                        deviceWarningMode = deviceWarningMode,
                        deviceWarningSeconds = deviceWarningSeconds,
                        postureForwardAngle = postureForwardAngle,
                        postureSideAngle = postureSideAngle,
                        onDevStatusChange = onDevStatusChange,
                        onDarkModeChange = onDarkModeChange,
                        onVibrationChange = onVibrationChange,
                        onAlertNotificationChange = onAlertNotificationChange,
                        onWarningSecondsChange = onWarningSecondsChange,
                        onDeviceWarningModeChange = onDeviceWarningModeChange,
                        onDeviceWarningSecondsChange = onDeviceWarningSecondsChange,
                        onSetCurrentPostureAsDefault = onSetCurrentPostureAsDefault,
                        onResetPostureDefault = onResetPostureDefault
                    )
                }
                //タブの切り替え
            }
            //メイン画面

            NavigationBar(containerColor = cardColor) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Image(
                            painter = painterResource(id = R.drawable.home_icon),
                            contentDescription = "ホーム",
                            modifier = Modifier.height(26.dp),
                            colorFilter = ColorFilter.tint(
                                if (selectedTab == 0) AccentRed else Color.Gray
                            )
                        )
                    },
                    label = { Text("ホーム") }
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Image(
                            painter = painterResource(id = R.drawable.graph_icon),
                            contentDescription = "統計",
                            modifier = Modifier.height(26.dp),
                            colorFilter = ColorFilter.tint(
                                if (selectedTab == 1) AccentRed else Color.Gray
                            )
                        )
                    },
                    label = { Text("統計") }
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Image(
                            painter = painterResource(id = R.drawable.device_link),
                            contentDescription = "連携",
                            modifier = Modifier.height(26.dp),
                            colorFilter = ColorFilter.tint(
                                if (selectedTab == 2) AccentRed else Color.Gray
                            )
                        )
                    },
                    label = { Text("連携") }
                )

                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        Image(
                            painter = painterResource(id = R.drawable.settings_icon),
                            contentDescription = "設定",
                            modifier = Modifier.height(26.dp),
                            colorFilter = ColorFilter.tint(
                                if (selectedTab == 3) AccentRed else Color.Gray
                            )
                        )
                    },
                    label = { Text("設定") }
                )
            }
            //タブバー
        }
        //中身を縦に並べる

        if (showAlertScreen) {
            AlertOverlay(
                title = alertTitle,
                message = alertMessage,
                onCloseAlert = onCloseAlert
            )
        }
        //警告画面を表示
    }
    //画面全体の土台
    if (showPermissionGuide) {
        PermissionGuideOverlay(
            darkModeEnabled = darkModeEnabled,
            onRequestPermissions = onRequestPermissions
        )
    }
}
//アプリ全体の画面構成を管理する親画面

@Composable
fun HomeScreen(
    isMonitoring: Boolean,
    currentSpeedMps: Float,
    speedWalkingState: Boolean,
    shakeWalkingState: Boolean,
    finalJudgeState: Boolean,
    locationAccuracyM: Float,
    showDevStatus: Boolean,
    darkModeEnabled: Boolean,
    postureForwardAngle: Float,
    postureSideAngle: Float,
    onMonitoringChange: (Boolean) -> Unit
) {
    val backgroundColor = if (darkModeEnabled) Color(0xFF121212) else Color(0xFFF5F5F5)
    val cardColor = if (darkModeEnabled) Color(0xFF1E1E1E) else Color.White
    val textPrimary = if (darkModeEnabled) Color(0xFFF2F2F2) else Color(0xFF333333)
    val textSecondary = if (darkModeEnabled) Color(0xFFBBBBBB) else Color(0xFF666666)
    //ダークモードか否か

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        //中央寄り
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                    Text(
                        text = if (isMonitoring) "監視中" else "停止中",
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMonitoring) AccentRed else textPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isMonitoring) {
                            "バックグラウンドで監視しています"
                        } else {
                            "スイッチをONにすると監視を開始します"
                        },
                        fontSize = 17.sp,
                        color = textSecondary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Switch(
                    checked = isMonitoring,
                    onCheckedChange = { onMonitoringChange(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentRed,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFBBBBBB)
                    )
                )
                //監視中ならスイッチON、停止中ならOFF
            }
            //横画面
        }
        //監視状態

        Spacer(modifier = Modifier.height(20.dp))

        val postureBad =
            postureForwardAngle > 25f ||
                    postureSideAngle < -15f ||
                    postureSideAngle > 15f

        val dangerText = when {
            finalJudgeState || postureBad && speedWalkingState && shakeWalkingState -> "危険"
            postureBad -> "注意"
            else -> "安全"
        }

        val dangerColor = when (dangerText) {
            "危険" -> AccentRed
            "注意" -> Color(0xFFFF9800)
            else -> Color(0xFF4CAF50)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "現在の危険度",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = dangerText,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = dangerColor
                )
            }
        }
        //危険度

        Spacer(modifier = Modifier.height(20.dp))

        if (showDevStatus) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "開発者モード",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "現在速度: ${String.format(Locale.JAPAN, "%.2f", currentSpeedMps)} m/s",
                        fontSize = 17.sp,
                        color = textSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "速度での歩行判定: ${if (speedWalkingState) "歩行" else "非歩行"}",
                        fontSize = 17.sp,
                        color = if (speedWalkingState) AccentRed else textSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "揺れでの歩行判定: ${if (shakeWalkingState) "歩行" else "非歩行"}",
                        fontSize = 17.sp,
                        color = if (shakeWalkingState) AccentRed else textSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "最終判定: ${if (finalJudgeState) "警告対象" else "対象外"}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (finalJudgeState) AccentRed else textSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "位置情報精度: ${String.format(Locale.JAPAN, "%.1f", locationAccuracyM)} m",
                        fontSize = 17.sp,
                        color = textSecondary
                    )
                }
            }
            //開発者カード
        }
        //開発者モードがオンか否か
    }
    //縦画面
}
//ホーム画面

@Composable
fun StatsScreen(
    todayAlertCount: Int,
    monthStats: List<DailyStat>,
    yearStats: List<DailyStat>,
    postureForwardAngle: Float,
    postureSideAngle: Float,
    lookingDownWalkingSeconds: Int,
    leaningLeftWalkingSeconds: Int,
    leaningRightWalkingSeconds: Int,
    darkModeEnabled: Boolean
) {
    var showYear by remember { mutableStateOf(false) }
    //グラフを「月表示」にするか「年表示」にするか

    val backgroundColor = if (darkModeEnabled) Color(0xFF121212) else Color(0xFFF5F5F5)
    val cardColor = if (darkModeEnabled) Color(0xFF1E1E1E) else Color.White
    val textPrimary = if (darkModeEnabled) Color(0xFFF2F2F2) else Color(0xFF333333)
    val textSecondary = if (darkModeEnabled) Color(0xFFBBBBBB) else Color(0xFF666666)
    //ダークモードか否か

    val postureMessage = when {
        postureForwardAngle > 25f -> "下を向くと危険です。前を向いて歩きましょう"
        postureSideAngle < -15f -> "首が左に傾く癖があるようです"
        postureSideAngle > 15f -> "首が右に傾く癖があるようです"
        else -> "姿勢は安定しています"
    }
    //姿勢チェック

    val postureBadSeconds =
        lookingDownWalkingSeconds +
                leaningLeftWalkingSeconds +
                leaningRightWalkingSeconds
    //姿勢が悪かった秒数

    val postureScore =
        (100 - todayAlertCount * 3 - postureBadSeconds / 10).coerceIn(0, 100)
    //今日の姿勢スコア

    WalkingAppPrefs.saveTodayPostureScore(
        LocalContext.current,
        postureScore
    )
    //姿勢スコアを保存

    val postureImprovementRate =
        WalkingAppPrefs.getPostureImprovementRate(
            LocalContext.current
        )
    //姿勢改善率

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            //スクロールできる
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "今日の警告回数",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$todayAlertCount 回",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentRed,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
        //今日の警告回数カード

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "今日の姿勢スコア",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$postureScore 点",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        postureScore >= 80 -> Color(0xFF4CAF50)
                        postureScore >= 60 -> Color(0xFFFF9800)
                        else -> AccentRed
                    }
                )
            }
        }
        //今日の姿勢スコアカード

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = "姿勢改善率",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "${if (postureImprovementRate > 0) "+" else ""}$postureImprovementRate%",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        postureImprovementRate > 0 -> Color(0xFF4CAF50)
                        postureImprovementRate < 0 -> AccentRed
                        else -> textSecondary
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "過去7日間の平均姿勢スコアとの比較",
                    fontSize = 13.sp,
                    color = textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
        //姿勢改善率カード

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("警告回数グラフ", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                        Text(
                            text = if (showYear) "過去1年（月単位）" else "過去1か月（日単位）",
                            fontSize = 14.sp,
                            color = textSecondary
                        )
                    }

                    Text("月", fontSize = 13.sp, color = textSecondary)

                    Switch(
                        checked = showYear,
                        onCheckedChange = { showYear = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentRed,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFBBBBBB)
                        )
                    )

                    Text("年", fontSize = 13.sp, color = textSecondary)
                }

                Spacer(modifier = Modifier.height(20.dp))

                AlertBarChart(stats = if (showYear) yearStats else monthStats)
            }
        }
        //警告回数グラフカード

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("姿勢チェック", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textPrimary)

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = postureMessage,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (postureMessage == "姿勢は安定しています") textSecondary else AccentRed
                )
            }
        }
        //姿勢チェックカード

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("姿勢の傾向", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textPrimary)

                Spacer(modifier = Modifier.height(16.dp))

                StatRow("下を向いて歩いていた時間", lookingDownWalkingSeconds, textSecondary)
                StatRow("左に傾いて歩いていた時間", leaningLeftWalkingSeconds, textSecondary)
                StatRow("右に傾いて歩いていた時間", leaningRightWalkingSeconds, textSecondary)
            }
        }
        //姿勢の傾向カード

        Spacer(modifier = Modifier.height(20.dp))
    }
}
//統計画面

@Composable
fun StatRow(
    label: String,
    seconds: Int,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = textColor,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "${seconds}秒",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AccentRed
        )
    }
}
//姿勢の傾向カードの表示内容

@Composable
fun AlertBarChart(stats: List<DailyStat>) {
    val maxCount = maxOf(1, stats.maxOfOrNull { it.count } ?: 1)
    val isMonthView = stats.size > 20

    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            if (isMonthView && stats.isNotEmpty()) {
                val step = size.width / stats.size

                stats.forEachIndexed { index, _ ->
                    if (index % 10 == 0) {
                        val x = step * index + step / 2f

                        drawLine(
                            color = Color.Gray.copy(alpha = 0.18f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 2f
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            stats.forEachIndexed { index, stat ->
                val ratio = stat.count.toFloat() / maxCount.toFloat()
                val barHeight = (160f * ratio).dp

                val showLabel = if (isMonthView) {
                    index == 0 || index == stats.lastIndex || index % 10 == 0
                } else {
                    true
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Text(
                        text = stat.count.toString(),
                        fontSize = 9.sp,
                        color = Color(0xFF666666)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .width(if (isMonthView) 8.dp else 20.dp)
                            .height(barHeight)
                            .background(
                                color = if (stat.count > 0) AccentRed else Color(0xFFE0E0E0),
                                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                            )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (showLabel) stat.label else "",
                        fontSize = if (isMonthView) 8.sp else 11.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        }
    }
}
//警告回数を棒グラフで表示

@Composable
fun LinkScreen(
    bleStatus: String,
    rawText: String,
    bleSwitchOn: Boolean,
    postureForwardAngle: Float,
    postureSideAngle: Float,
    showDevStatus: Boolean,
    darkModeEnabled: Boolean,
    onBleConnectClick: () -> Unit,
    onBleDisconnectClick: () -> Unit
) {
    val backgroundColor = if (darkModeEnabled) Color(0xFF121212) else Color(0xFFF5F5F5)
    val cardColor = if (darkModeEnabled) Color(0xFF1E1E1E) else Color.White
    val textPrimary = if (darkModeEnabled) Color(0xFFF2F2F2) else Color(0xFF333333)
    val textSecondary = if (darkModeEnabled) Color(0xFFBBBBBB) else Color(0xFF666666)
    //ダークモードか否か

    var hideBleError by remember { mutableStateOf(false) }

    val isBleConnected =
        bleStatus.contains("受信中") ||
                bleStatus.contains("接続成功")

    val isBleConnecting =
        bleStatus.contains("準備中") ||
                bleStatus.contains("スキャン中") ||
                bleStatus.contains("接続中") ||
                bleStatus.contains("サービス確認中") ||
                bleStatus.contains("PostureGuardを発見")

    val isBleError =
        !isBleConnected &&
                !isBleConnecting &&
                (
                        bleStatus.contains("失敗") ||
                                bleStatus.contains("見つかりません") ||
                                bleStatus.contains("切断") ||
                                bleStatus.contains("権限がありません") ||
                                bleStatus.contains("BluetoothがOFF")
                        )

    val values = Regex("""-?\d+(?:\.\d+)?""")
        .findAll(rawText)
        .map { it.value.toFloatOrNull() ?: 0f }
        //文字をFloatに変換
        .toList()
    //テキストから数値を取り出す

    val ax = values.getOrElse(0) { 0f }
    val ay = values.getOrElse(1) { 0f }
    val az = values.getOrElse(2) { 0f }

    LaunchedEffect(bleStatus) {
        if (!isBleError) {
            hideBleError = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("PostureGuard連携", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textPrimary)

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(bleStatus, fontSize = 14.sp, color = textSecondary)
                }

                Switch(
                    checked = bleSwitchOn,
                    onCheckedChange = {
                        if (it) onBleConnectClick() else onBleDisconnectClick()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentRed,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFBBBBBB)
                    )
                )
            }
            //横画面
        }
        //bluetooth接続カード

        if (isBleConnecting) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = cardColor
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = AccentRed,
                        trackColor = if (darkModeEnabled)
                            Color(0xFF555555)
                        else
                            Color(0xFFD8D8D8),
                        strokeWidth = 3.dp
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = when {
                            bleStatus.contains("スキャン中") ->
                                "PostureGuardを探しています"

                            bleStatus.contains("発見") ->
                                "PostureGuardに接続しています"

                            bleStatus.contains("サービス確認中") ->
                                "接続を準備しています"

                            else ->
                                "Bluetooth接続中"
                        },
                        fontSize = 14.sp,
                        color = textSecondary
                    )
                }
            }
        }

        if (isBleError && !hideBleError) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = AccentRed.copy(alpha = 0.12f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Bluetooth接続に失敗しました",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentRed
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "PostureGuardの電源とBluetoothを確認し、もう一度接続してください。",
                        fontSize = 14.sp,
                        color = textSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            hideBleError = true
                            onBleConnectClick()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentRed
                        )
                    ) {
                        Text("再接続する")
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = {
                            hideBleError = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFBBBBBB)
                        )
                    ) {
                        Text(
                            text = "閉じる",
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("首の傾きメーター", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textPrimary)

                Spacer(modifier = Modifier.height(20.dp))

                SemiCirclePostureMeter1(
                    title = "前後の傾き",
                    angle = postureForwardAngle,
                    leftLabel = "上向き",
                    rightLabel = "下向き",
                    darkModeEnabled = darkModeEnabled
                )

                Spacer(modifier = Modifier.height(24.dp))

                SemiCirclePostureMeter2(
                    title = "左右の傾き",
                    angle = postureSideAngle,
                    leftLabel = "左傾き",
                    rightLabel = "右傾き",
                    darkModeEnabled = darkModeEnabled
                )
            }
        }
        //首の傾きメーターカード

        if (showDevStatus) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "開発者モード",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("加速度", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                    Text("AX: ${"%.2f".format(ax)}", color = textSecondary)
                    Text("AY: ${"%.2f".format(ay)}", color = textSecondary)
                    Text("AZ: ${"%.2f".format(az)}", color = textSecondary)
                }
            }
            //開発者モードカード
        }
        //開発者モードがオンか否か

        Spacer(modifier = Modifier.height(20.dp))
    }
    //縦画面
}
//連携画面

@Composable
fun SemiCirclePostureMeter1(
    title: String,
    angle: Float,
    leftLabel: String,
    rightLabel: String,
    darkModeEnabled: Boolean
) {
    val textPrimary = if (darkModeEnabled) Color(0xFFF2F2F2) else Color(0xFF333333)
    val textSecondary = if (darkModeEnabled) Color(0xFFBBBBBB) else Color(0xFF666666)
    val baseColor = if (darkModeEnabled) Color(0xFF444444) else Color(0xFFE0E0E0)
    val safeColor = Color(0xFF4CAF50).copy(alpha = 0.35f)
    //ダークモードか否か

    val clampedAngle = angle.coerceIn(-45f, 45f)
    //angleを-45°〜45°の範囲に制限
    val normalized = (clampedAngle + 45f) / 90f
    //角度を0〜1に変換
    val needleDegree = 180f + (normalized * 180f)
    //針の角度

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$title：${"%.1f".format(angle)}°",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                val strokeWidth = 18f
                val radius = size.width * 0.36f
                val center = Offset(size.width / 2f, size.height - 10f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)

                drawArc(
                    color = baseColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                //灰色の半円

                val safeStartNormalized = (-45f + 45f) / 90f
                val safeEndNormalized = (25f + 45f) / 90f
                val safeStartAngle = 180f + safeStartNormalized * 180f
                val safeSweep = (safeEndNormalized - safeStartNormalized) * 180f

                drawArc(
                    color = safeColor,
                    startAngle = safeStartAngle,
                    sweepAngle = safeSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                //安全範囲の半円

                val meterColor = when {
                    angle <= 15f -> Color(0xFF4CAF50)
                    angle <= 25f -> Color(0xFFFF9800)
                    else -> AccentRed
                }

                val rad = needleDegree * PI / 180.0
                val needleLength = radius * 0.78f

                val endX = center.x + cos(rad).toFloat() * needleLength
                val endY = center.y + sin(rad).toFloat() * needleLength

                drawLine(
                    color = meterColor,
                    start = center,
                    end = Offset(endX, endY),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
                //針

                drawCircle(
                    color = meterColor,
                    radius = 9f,
                    center = center
                )
                //針の根元の円
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(leftLabel, fontSize = 12.sp, color = textSecondary)
            Text("安全範囲 +25°", fontSize = 12.sp, color = Color(0xFF4CAF50))
            Text(rightLabel, fontSize = 12.sp, color = textSecondary)
        }
        //安全範囲
    }
}
//半円メーター

@Composable
fun SemiCirclePostureMeter2(
    title: String,
    angle: Float,
    leftLabel: String,
    rightLabel: String,
    darkModeEnabled: Boolean
) {
    val textPrimary = if (darkModeEnabled) Color(0xFFF2F2F2) else Color(0xFF333333)
    val textSecondary = if (darkModeEnabled) Color(0xFFBBBBBB) else Color(0xFF666666)
    val baseColor = if (darkModeEnabled) Color(0xFF444444) else Color(0xFFE0E0E0)
    val safeColor = Color(0xFF4CAF50).copy(alpha = 0.35f)
    //ダークモードか否か

    val clampedAngle = angle.coerceIn(-45f, 45f)
    //angleを-45°〜45°の範囲に制限
    val normalized = (clampedAngle + 45f) / 90f
    //角度を0〜1に変換
    val needleDegree = 180f + (normalized * 180f)
    //針の角度

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$title：${"%.1f".format(angle)}°",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                val strokeWidth = 18f
                val radius = size.width * 0.36f
                val center = Offset(size.width / 2f, size.height - 10f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)

                drawArc(
                    color = baseColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                //灰色の半円

                val safeStartNormalized = (-15f + 45f) / 90f
                val safeEndNormalized = (15f + 45f) / 90f
                val safeStartAngle = 180f + safeStartNormalized * 180f
                val safeSweep = (safeEndNormalized - safeStartNormalized) * 180f

                drawArc(
                    color = safeColor,
                    startAngle = safeStartAngle,
                    sweepAngle = safeSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                //安全範囲の半円

                val meterColor = when {
                    abs(angle) <= 10f -> Color(0xFF4CAF50)
                    abs(angle) <= 15f -> Color(0xFFFF9800)
                    else -> AccentRed
                }

                val rad = needleDegree * PI / 180.0
                val needleLength = radius * 0.78f

                val endX = center.x + cos(rad).toFloat() * needleLength
                val endY = center.y + sin(rad).toFloat() * needleLength

                drawLine(
                    color = meterColor,
                    start = center,
                    end = Offset(endX, endY),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
                //針

                drawCircle(
                    color = meterColor,
                    radius = 9f,
                    center = center
                )
                //針の根元の円
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(leftLabel, fontSize = 12.sp, color = textSecondary)
            Text("安全範囲 ±15°", fontSize = 12.sp, color = Color(0xFF4CAF50))
            Text(rightLabel, fontSize = 12.sp, color = textSecondary)
        }
        //安全範囲
    }
}
//半円メーター

@Composable
fun SettingsScreen(
    showDevStatus: Boolean,
    darkModeEnabled: Boolean,
    vibrationEnabled: Boolean,
    alertNotificationEnabled: Boolean,
    warningSeconds: Int,
    deviceWarningMode: Int,
    deviceWarningSeconds: Int,
    postureForwardAngle: Float,
    postureSideAngle: Float,
    onDevStatusChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onAlertNotificationChange: (Boolean) -> Unit,
    onWarningSecondsChange: (Int) -> Unit,
    onDeviceWarningModeChange: (Int) -> Unit,
    onDeviceWarningSecondsChange: (Int) -> Unit,
    onSetCurrentPostureAsDefault: () -> Unit,
    onResetPostureDefault: () -> Unit
) {
    var selectedSettingsTab by remember { mutableIntStateOf(0) }

    val backgroundColor = if (darkModeEnabled) Color(0xFF121212) else Color(0xFFF5F5F5)
    val cardColor = if (darkModeEnabled) Color(0xFF1E1E1E) else Color.White
    val textPrimary = if (darkModeEnabled) Color(0xFFF2F2F2) else Color(0xFF333333)
    val textSecondary = if (darkModeEnabled) Color(0xFFBBBBBB) else Color(0xFF666666)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { selectedSettingsTab = 0 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedSettingsTab == 0) AccentRed else Color(0xFFBBBBBB)
                )
            ) {
                Text("アプリ設定")
            }

            Button(
                onClick = { selectedSettingsTab = 1 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedSettingsTab == 1) AccentRed else Color(0xFFBBBBBB)
                )
            ) {
                Text("デバイス設定")
            }
        }
        //設定画面切り替えボタン

        Spacer(modifier = Modifier.height(20.dp))

        if (selectedSettingsTab == 0) {
            SettingsCard(
                title = "表示設定",
                cardColor = cardColor,
                textPrimary = textPrimary
            ) {
                SettingSwitchRow(
                    title = "ダークモード",
                    subtitle = if (darkModeEnabled) "暗い画面表示" else "明るい画面表示",
                    checked = darkModeEnabled,
                    onCheckedChange = onDarkModeChange,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    title = "開発者モード表示",
                    subtitle = if (showDevStatus) "表示中" else "非表示",
                    checked = showDevStatus,
                    onCheckedChange = onDevStatusChange,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            SettingsCard(
                title = "アプリ警告設定",
                cardColor = cardColor,
                textPrimary = textPrimary
            ) {
                SettingSwitchRow(
                    title = "警告通知",
                    subtitle = if (alertNotificationEnabled) "警告時に通知を表示" else "警告時の通知を非表示",
                    checked = alertNotificationEnabled,
                    onCheckedChange = onAlertNotificationChange,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    title = "スマホ振動",
                    subtitle = if (vibrationEnabled) "警告時にスマホを振動" else "スマホ振動なし",
                    checked = vibrationEnabled,
                    onCheckedChange = onVibrationChange,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "歩きスマホ警告まで: $warningSeconds 秒",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = warningSeconds.toFloat(),
                    onValueChange = { onWarningSecondsChange(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }
        }
        //アプリ設定の時
        else {
            SettingsCard(
                title = "デバイス警告設定",
                cardColor = cardColor,
                textPrimary = textPrimary
            ) {
                Button(
                    onClick = {
                        onDeviceWarningModeChange(WalkingAppPrefs.DEVICE_WARNING_POSTURE_ONLY)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor =
                            if (deviceWarningMode == WalkingAppPrefs.DEVICE_WARNING_POSTURE_ONLY) {
                                AccentRed
                            } else {
                                Color(0xFFBBBBBB)
                            }
                    )
                ) {
                    Text("首が傾いているだけで振動")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        onDeviceWarningModeChange(WalkingAppPrefs.DEVICE_WARNING_POSTURE_AND_WALKING)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor =
                            if (deviceWarningMode == WalkingAppPrefs.DEVICE_WARNING_POSTURE_AND_WALKING) {
                                AccentRed
                            } else {
                                Color(0xFFBBBBBB)
                            }
                    )
                ) {
                    Text("首が傾いていて、かつ歩いていると警告")
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "デバイス警告まで: $deviceWarningSeconds 秒",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = deviceWarningSeconds.toFloat(),
                    onValueChange = { onDeviceWarningSecondsChange(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            SettingsCard(
                title = "装着角度の補正",
                cardColor = cardColor,
                textPrimary = textPrimary
            ) {
                Text(
                    text = "現在の角度",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "前後: ${"%.1f".format(postureForwardAngle)}°",
                    fontSize = 14.sp,
                    color = textSecondary
                )

                Text(
                    text = "左右: ${"%.1f".format(postureSideAngle)}°",
                    fontSize = 14.sp,
                    color = textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onSetCurrentPostureAsDefault,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Text("現在の向きを基準にする")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Arduinoを装着した自然な姿勢で押すと、その向きを0度として扱います",
                    fontSize = 13.sp,
                    color = textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onResetPostureDefault,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBBBBBB))
                ) {
                    Text("基準をリセット")
                }
            }
        }
        //デバイス設定の時

        Spacer(modifier = Modifier.height(20.dp))
    }
    //縦画面
}
//設定画面

@Composable
fun SettingsCard(
    title: String,
    cardColor: Color,
    textPrimary: Color,
    content: @Composable ColumnScope.() -> Unit
    //子コンポーネントを受け取る
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}
//設定画面専用のカード部品

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    textPrimary: Color,
    textSecondary: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.75f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = textSecondary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentRed,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFBBBBBB)
            )
        )
    }
}
//設定項目を共通化した部品

@Composable
fun AlertOverlay(
    title: String,
    message: String,
    onCloseAlert: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "alert_flash")

    val overlayAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay_alpha"
    )

    val cardAlpha by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "card_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AccentRed.copy(alpha = overlayAlpha)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .alpha(cardAlpha),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "⚠ 警告 ⚠",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentRed
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF222222)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = message,
                    fontSize = 18.sp,
                    color = Color(0xFF444444)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onCloseAlert,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Text("OK")
                }
            }
        }
    }
}
//歩きスマホ時の警告画面

@Composable
fun PermissionGuideOverlay(
    darkModeEnabled: Boolean,
    onRequestPermissions: () -> Unit
) {
    val backgroundColor =
        if (darkModeEnabled) Color(0xFF121212) else Color(0xFFF5F5F5)

    val cardColor =
        if (darkModeEnabled) Color(0xFF1E1E1E) else Color.White

    val textPrimary =
        if (darkModeEnabled) Color(0xFFF2F2F2) else Color(0xFF222222)

    val textSecondary =
        if (darkModeEnabled) Color(0xFFBBBBBB) else Color(0xFF666666)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = cardColor
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "必要な権限について",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                PermissionDescription(
                    title = "位置情報",
                    description = "スマホの移動速度を取得し、歩行中かどうかを判定するために使用します。",
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                PermissionDescription(
                    title = "身体活動",
                    description = "歩数センサーを使用して、歩行を検知するために使用します。",
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                PermissionDescription(
                    title = "近くのデバイス",
                    description = "PostureGuardとBluetooth接続するために使用します。",
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                PermissionDescription(
                    title = "通知",
                    description = "監視状態や歩きスマホ警告を表示するために使用します。",
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentRed
                    )
                ) {
                    Text("権限を許可する")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "権限を許可しない場合、一部の機能を使用できません。",
                    fontSize = 13.sp,
                    color = textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun PermissionDescription(
    title: String,
    description: String,
    textPrimary: Color,
    textSecondary: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = description,
            fontSize = 14.sp,
            color = textSecondary
        )
    }
}
//初回起動時の権限の案内