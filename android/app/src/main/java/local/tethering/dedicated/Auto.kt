package local.tethering.dedicated

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * プロファイル連動の自動切り替え（仕様 F-42）の判断。Android に依存しない。
 * バッテリー駆動中（または残量が設定以下のとき）にバッテリーセーバーを ON にし、Doze（省電力待機）を早め、給電で元に戻す。
 * データセーバー・機内モード・モバイルデータは扱わない（通信が止まるため）。
 */
object AutoPolicy {
    data class Config(val saver: Boolean, val saverPct: Int, val doze: Boolean)

    data class Want(val saver: Boolean, val doze: Boolean)

    /** [prev] は今の状態。残量のしきい値付近でばたつかないよう、解除はしきい値より 5% 上で行う */
    fun want(cfg: Config, p: PowerInfo?, prev: Want): Want {
        val onBattery = p?.plug == Plug.NONE
        val level = p?.levelPct
        val saver = cfg.saver && onBattery && when {
            cfg.saverPct >= 100 -> true
            level == null -> prev.saver
            prev.saver -> level <= cfg.saverPct + 5
            else -> level <= cfg.saverPct
        }
        return Want(saver = saver, doze = cfg.doze && onBattery)
    }

    /**
     * Doze を早める設定（Android 10 の DeviceIdleController の定数）。画面消灯・静止から約 10 分で深い Doze に入る
     * （標準は約 1 時間）。軽い Doze も 1 分で始める。テザリングの中継は Doze の制限を受けない
     */
    const val DOZE_EARLY = "light_after_inactive_to=60000,inactive_to=300000,sensing_to=0,locating_to=0,idle_after_inactive_to=300000"
}

/**
 * 自動切り替え（仕様 F-42）の実行。ADB で「設定変更の権限」（WRITE_SECURE_SETTINGS）を付けたときだけ動く。
 * 変えるのは Settings.Global の low_power（バッテリーセーバー）と device_idle_constants（Doze）だけで、変更前の値を保存して戻す。
 * 切り替えるのは条件が変わったときだけ（利用者が手で戻したら、次に条件が変わるまで追いかけない）。メインスレッドから呼ぶ。
 */
class AutoSwitch(private val ctx: Context, private val prefs: Prefs) {
    companion object {
        const val KEY_SAVER = "low_power"

        /** アプリからバッテリーセーバーを ON にすると、OS は「手動で ON」とみなしてこれを 1 にする（次にバッテリー駆動になったとき自動で ON） */
        const val KEY_SAVER_STICKY = "low_power_sticky"
        const val KEY_DOZE = "device_idle_constants"

        /** 元の値が「未設定」だったことを表す印（設定値としては使われない文字列） */
        private const val UNSET = "<unset>"

        fun permitted(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /**
         * 「Doze を早める」が効く OS か。Android 12 からは Doze の設定が DeviceConfig（device_idle）に移り、
         * device_idle_constants を書いても変わらない（アプリからは DeviceConfig を変えられない）ので、Android 12 以降では使わない
         */
        val dozeWorks: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

        /** 「Doze を早める」を使う設定で、効く OS か */
        fun dozeEnabled(prefs: Prefs): Boolean = prefs.bool(Defs.AUTO_DOZE) && dozeWorks
    }

    val applied: AutoPolicy.Want get() = AutoPolicy.Want(prefs.autoSaverApplied, prefs.autoDozeApplied)

    private fun config() = AutoPolicy.Config(prefs.bool(Defs.AUTO_SAVER), prefs.int(Defs.AUTO_SAVER_PCT), dozeEnabled(prefs))

    /** 必要なら切り替え、行った変更の説明を返す。[hold] のあいだ（比較テスト中）は変えない */
    fun evaluate(p: PowerInfo?, hold: Boolean): List<String> {
        if (hold) return emptyList()
        val cur = applied
        val want = AutoPolicy.want(config(), p, cur)
        val out = ArrayList<String>()
        if (want.saver != cur.saver && setSaver(want.saver)) out.add(if (want.saver) saverOn() else saverRestored())
        if (want.doze != cur.doze && setDoze(want.doze)) out.add(if (want.doze) dozeEarly() else dozeRestored())
        return out
    }

    /** 自動で変えたものをすべて元に戻す（設定の「自動切り替え」も OFF にするのは呼び出し側） */
    fun restoreAll(): List<String> {
        val out = ArrayList<String>()
        if (prefs.autoSaverApplied && setSaver(false)) out.add(saverRestored())
        if (prefs.autoDozeApplied && setDoze(false)) out.add(dozeRestored())
        return out
    }

    private fun saverOn() = tr("バッテリーセーバーを ON", "Battery Saver on", "Économiseur de batterie activé")
    private fun saverRestored() = tr("バッテリーセーバーを元に戻した", "Battery Saver restored", "Économiseur de batterie rétabli")
    private fun dozeEarly() = tr("Doze（省電力待機）を早めた", "Doze (deep sleep) made earlier", "Doze (veille profonde) avancé")
    private fun dozeRestored() = tr("Doze の設定を元に戻した", "Doze settings restored", "Réglages Doze rétablis")

    private fun setSaver(on: Boolean): Boolean {
        if (!permitted(ctx)) return false
        val cr = ctx.contentResolver
        return runCatching {
            if (on) {
                // 元の値は「low_power,low_power_sticky」で残す
                if (!prefs.autoSaverApplied) {
                    prefs.autoSaverOriginal = (Settings.Global.getString(cr, KEY_SAVER) ?: "0") + "," + (Settings.Global.getString(cr, KEY_SAVER_STICKY) ?: "0")
                }
                Settings.Global.putString(cr, KEY_SAVER, "1")
            } else {
                val orig = prefs.autoSaverOriginal?.split(',').orEmpty()
                Settings.Global.putString(cr, KEY_SAVER, orig.getOrNull(0) ?: "0")
                // 給電で OS が先にセーバーを切ると「次も自動で ON」が残ることがあるので、こちらも明示的に戻す
                Settings.Global.putString(cr, KEY_SAVER_STICKY, orig.getOrNull(1) ?: "0")
                prefs.autoSaverOriginal = null
            }
            prefs.autoSaverApplied = on
            true
        }.getOrDefault(false)
    }

    private fun setDoze(on: Boolean): Boolean {
        if (!permitted(ctx)) return false
        val cr = ctx.contentResolver
        return runCatching {
            if (on) {
                if (!prefs.autoDozeApplied) prefs.autoDozeOriginal = Settings.Global.getString(cr, KEY_DOZE) ?: UNSET
                Settings.Global.putString(cr, KEY_DOZE, AutoPolicy.DOZE_EARLY)
            } else {
                val orig = prefs.autoDozeOriginal
                Settings.Global.putString(cr, KEY_DOZE, if (orig == null || orig == UNSET) null else orig)
                prefs.autoDozeOriginal = null
            }
            prefs.autoDozeApplied = on
            true
        }.getOrDefault(false)
    }
}

/**
 * 監視自体の通信の最小化（仕様 F-32）。
 * 接続端末の通信が流れていて、OS の接続検証も OK なら、到達確認（DNS・HTTP）を省く（インターネットに届いているのは明らかなため）。
 */
object ProbeSkip {
    /** 前回のチェックからこれ以上受信していれば「通信が流れている」とみなす */
    const val MIN_RX_BYTES = 256 * 1024L

    fun should(enabled: Boolean, trigger: Trigger, troubled: Boolean, clients: Int?, rxDelta: Long?): Boolean =
        enabled && trigger == Trigger.SCHEDULED && !troubled && (clients ?: 0) >= 1 && rxDelta != null && rxDelta >= MIN_RX_BYTES
}

/**
 * 接続 0 台・低残量のときの待機（仕様 F-32）。チェック間隔を長くし、状態の変化（イベント）で確認する運用にする。
 */
object IdleMode {
    enum class Reason(private val tx: Tx) {
        NO_CLIENTS(Tx("接続 0 台", "no devices", "aucun appareil")),
        LOW_BATTERY(Tx("低残量", "low battery", "batterie faible"));

        val label: String get() = tx.get()
    }

    /** 接続 0 台がこれだけ続いたら待機にする */
    const val NO_CLIENTS_MS = 30 * 60_000L

    /** 直近の区間の通信がこれ以上（KB/時）なら、使われているとみなして待機にしない */
    const val BUSY_KB_PER_HOUR = 1024.0

    /**
     * 待機にする理由（待機にしないなら null）。バッテリー駆動中だけ（給電中は電力に余裕があり、記録の精度を優先する）。
     * [zeroSinceMs] は接続 0 台（またはテザリング OFF）になった時刻（経過時間）、不明なら null。
     * [lastKbPerHour] は直近の区間の通信量（使われ始めたら待機をやめる）
     */
    fun reason(intervalMin: Int, zeroSinceMs: Long?, nowMs: Long, p: PowerInfo?, battPct: Int, lastKbPerHour: Double?): Reason? {
        if (intervalMin <= 0 || p?.plug != Plug.NONE) return null
        if (lastKbPerHour != null && lastKbPerHour >= BUSY_KB_PER_HOUR) return null
        val level = p.levelPct
        if (level != null && level <= battPct) return Reason.LOW_BATTERY
        if (zeroSinceMs != null && nowMs - zeroSinceMs >= NO_CLIENTS_MS) return Reason.NO_CLIENTS
        return null
    }
}
