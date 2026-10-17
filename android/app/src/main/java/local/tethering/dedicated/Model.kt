package local.tethering.dedicated

/*
 * 画面・ログ・判定で共通に使う状態の型。
 * 取得できなかった値は null にして、画面では「取得不可」と表示する（推測値で埋めない）。
 */

/** 3値＋未実行の判定結果 */
enum class Tri(val mark: String) {
    OK("✓"), NG("✗"), SKIP("－"), UNKNOWN("取得不可")
}

/** 給電の種類 */
enum class Plug(val label: String) {
    AC("AC"), USB("USB"), WIRELESS("ワイヤレス"), NONE("バッテリー駆動"), UNKNOWN("取得不可");

    val powered: Boolean? get() = when (this) {
        AC, USB, WIRELESS -> true
        NONE -> false
        UNKNOWN -> null
    }
}

/** チェックを実行したきっかけ */
enum class Trigger(val label: String) {
    SCHEDULED("定期チェック"),
    RECHECK("再確認"),
    EVENT("状態変化"),
    MANUAL("手動チェック"),
    BOOT("起動後チェック"),
    // イベント名「監視開始」（Ev.START）と区別する（直近の記録に二重に出ないように）
    START("開始時チェック"),
}

/** 回線・インターネット・テザリングの異常（段階的な再確認の対象） */
enum class Anomaly(val label: String, val upstream: Boolean) {
    SIM("SIMを認識していない", true),
    NO_SERVICE("モバイル回線が圏外・未登録", true),
    NO_DATA("データ通信（IP）が確立していない", true),
    DNS("DNS名前解決に失敗", true),
    INTERNET("インターネットに到達できない", true),
    TETHER_OFF("Wi-Fiテザリングが停止中", false),
    TETHER_FAILED("Wi-Fiテザリングの起動に失敗", false),
}

/** 端末の警告（再確認はせず、しきい値の出入りで通知・記録する） */
enum class DeviceAlert(val label: String) {
    TEMP_WARN("電池温度が高い"),
    TEMP_CRIT("電池温度が非常に高い"),
    THERMAL("端末の熱状態が高い"),
    BATT_LOW("電池残量が少ない"),
    RUNTIME_LOW("推定残り時間が少ない"),
    SIGNAL_WEAK("電波の悪化"),
}

/** ローカル管理画面（仕様 F-43）の待ち受けの状態。[url] は待ち受け中だけ。[retry] はアドレスの割り当て待ち */
data class WebStatus(val url: String?, val message: String, val retry: Boolean = false)

/** 画面上の総合状態 */
enum class Overall(val label: String) {
    OK("正常"), WARN("注意"), ERROR("異常"), MAINT("メンテナンス中"), STOPPED("監視停止中"), UNKNOWN("確認中")
}

data class PowerInfo(
    val levelPct: Int?,
    val plug: Plug,
    val charging: Boolean?,
    val tempC: Double?,
    val voltageMv: Int?,
    val currentMa: Int?,
    val powerSave: Boolean?,
    val deviceIdle: Boolean?,
    /** PowerManager.THERMAL_STATUS_*（0=なし〜6=シャットダウン） */
    val thermalStatus: Int?,
    val socTempC: Double?,
    val socZone: String?,
    val batteryOptExempt: Boolean?,
    /** データセーバー（ON のあいだテザリングは使えない） */
    val dataSaver: Boolean?,
    /** 充電カウンタ（電池の残り容量 µAh。未対応なら null） */
    val counterUah: Long? = null,
)

data class TetherInfo(
    /** WifiManager.WIFI_AP_STATE_*（10=停止中 11=OFF 12=起動中 13=ON 14=失敗） */
    val apState: Int?,
    val apIface: String?,
    /** 1=テザリング 2=ローカル専用 */
    val apMode: Int?,
    /** 共有中のインターフェース（Wi-Fi / USB / Bluetooth） */
    val tethered: List<String>?,
    val clients: Int?,
    val clientsMethod: String,
    /** 「接続がないときにアクセスポイントを自動でOFF」の設定（null=取得不可・未設定） */
    val autoOff: Boolean?,
) {
    /** Wi-Fi テザリングが動作中か（取得できなければ null） */
    val wifiOn: Boolean? get() = when (apState) {
        AP_ENABLED -> true
        AP_DISABLING, AP_DISABLED, AP_ENABLING, AP_FAILED -> false
        else -> tethered?.any { Ifaces.isWifi(it) }
    }

    companion object {
        const val AP_DISABLING = 10
        const val AP_DISABLED = 11
        const val AP_ENABLING = 12
        const val AP_ENABLED = 13
        const val AP_FAILED = 14
    }
}

data class LineInfo(
    val phonePermission: Boolean,
    /** TelephonyManager.SIM_STATE_*（5=READY） */
    val simState: Int?,
    val simOperator: String?,
    val networkOperator: String?,
    /** ServiceState.STATE_*（0=圏内 1=圏外 2=緊急通報のみ 3=電源OFF） */
    val voiceState: Int?,
    /** TelephonyManager.NETWORK_TYPE_*（0=不明＝データ未登録） */
    val dataRat: Int?,
    /** TelephonyManager.DATA_*（0=切断 1=接続中 2=接続 3=一時停止） */
    val dataState: Int?,
    val dataEnabled: Boolean?,
    val airplane: Boolean?,
    val roaming: Boolean?,
    val signalType: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Double?,
    val dbm: Int?,
    val level: Int?,
    val earfcn: Int?,
    val band: String?,
    val bandwidthsMhz: List<Double>?,
) {
    val simReady: Boolean? get() = simState?.let { it == SIM_READY }

    /** データ網に登録できているか（データ専用 SIM は音声が圏外でも正常なので、こちらを優先して見る） */
    val dataRegistered: Boolean? get() = when {
        dataRat == null -> null
        dataRat != 0 -> true
        else -> false
    }

    companion object {
        const val SIM_READY = 5
        const val STATE_IN_SERVICE = 0
        const val STATE_OUT_OF_SERVICE = 1
        const val STATE_EMERGENCY_ONLY = 2
        const val STATE_POWER_OFF = 3
    }
}

data class CheckResult(
    val at: Long,
    val trigger: Trigger,
    val registered: Tri,
    val ip: Tri,
    val dns: Tri,
    val http: Tri,
    val validated: Tri,
    val httpCode: Int?,
    val httpMs: Long?,
    val viaCellular: Boolean,
    /** 確認に使った経路（モバイル回線 / Wi-Fi / VPN など。ネットワークがなければ null） */
    val route: String?,
    val failure: Anomaly?,
    val diagnosis: String,
    val detail: String,
    /** 接続端末の通信が流れていたので、到達確認（DNS・HTTP）を省いた（仕様 F-32） */
    val probeSkipped: Boolean = false,
)

/** 画面に出す直近の記録 */
data class RecentEvent(val at: Long, val kind: String, val text: String)

/** 共有中インターフェース名の分類（機種によって名前が違うため前方一致で判定） */
object Ifaces {
    fun isWifi(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap")
    }

    fun isUsb(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")
    }

    fun isBluetooth(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("bt-pan") || n.startsWith("bnep")
    }

    fun label(name: String): String = when {
        isWifi(name) -> "Wi-Fi"
        isUsb(name) -> "USB"
        isBluetooth(name) -> "Bluetooth"
        else -> name
    }
}
