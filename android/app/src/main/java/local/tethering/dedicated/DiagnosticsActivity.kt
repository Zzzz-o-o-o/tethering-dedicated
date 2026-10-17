package local.tethering.dedicated

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 診断情報（仕様 F-24）。この端末で「何が取得できて、何が取得不可か」を確認するための報告。
 * 電話番号・IMEI・SSID・MAC アドレス・IP アドレスは含めない。
 */
class DiagnosticsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        val c = Ui.card(page)
        Ui.small(c, "対象端末での取得可否の確認（要検証の項目）や、問い合わせ・記録に使えます。個人を特定する情報は含みません。")
        Ui.buttonRow(c, "コピー" to { copy() }, "共有" to { share() })
        val body: TextView = Ui.mono(c, "作成中…")
        bg.execute {
            val text = runCatching { Diagnostics.build(this) }.getOrElse { "作成に失敗しました: ${it.javaClass.simpleName}" }
            main.post {
                report = text
                body.text = text
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun copy() {
        if (report.isEmpty()) return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("診断情報", report))
        Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        if (report.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
        startActivity(Intent.createChooser(i, "診断情報を共有"))
    }
}

object Diagnostics {
    fun build(ctx: Context): String {
        val s = StateStore.state
        val prefs = Prefs(ctx)
        val p = PowerProbe.read(ctx)
        val t = TetherProbe.read(ctx, countClients = true, exclude = PlugUrls.exclude(prefs))
        val l = LineProbe.read(ctx, null)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val c = s.check
        return buildString {
            appendLine("# テザリング専用化 診断情報")
            appendLine("作成: ${Fmt.dateTime(System.currentTimeMillis())}")
            appendLine("アプリ: ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            appendLine("端末: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("ビルド: ${Build.DISPLAY}")
            appendLine()
            appendLine("## 権限・許可")
            appendLine("電話の状態: ${granted(ctx, Manifest.permission.READ_PHONE_STATE)}")
            appendLine("電池の最適化: ${Fmt.onOff(pm?.isIgnoringBatteryOptimizations(ctx.packageName), "除外済み", "対象")}")
            appendLine("通知: ${Fmt.onOff(nm?.areNotificationsEnabled(), "有効", "無効")}")
            appendLine()
            appendLine("## 監視")
            appendLine("状態: ${if (prefs.monitoring) s.overall().label else "停止中"} / モード: ${prefs.mode.label} / 実効: ${s.effective.label} / 間隔: ${s.intervalMin}分")
            appendLine("次回: ${Fmt.time(s.nextCheckAt)} / メンテナンス: ${if (prefs.maintenanceActive()) "〜" + Fmt.hm(prefs.maintUntil) else "なし"}")
            s.upstream?.let { appendLine("回線の異常: ${it.kind.label}（${Fmt.time(it.startedAt)}〜・${if (it.confirmed) "確定" else "再確認 ${it.rechecks}/${it.total}"}）") }
            s.tetherIssue?.let { appendLine("テザリングの異常: ${it.kind.label}（${if (it.confirmed) "確定" else "確認中"}）") }
            if (s.alerts.isNotEmpty()) appendLine("警告: " + s.alerts.joinToString { it.label })
            appendLine()
            appendLine("## 取得の可否（この端末）")
            appendLine("Wi-Fi AP 状態: ${Fmt.ap(t.apState)}（モード ${t.apMode ?: "－"}）")
            appendLine("共有中: ${Fmt.tethered(t.tethered)}")
            appendLine("接続端末数: ${t.clients?.let { "${it}台" } ?: "－"}（方法: ${t.clientsMethod}）")
            appendLine("アクセスポイント自動OFF（${TetherProbe.KEY_SOFT_AP_TIMEOUT}）: ${Os.global(ctx, TetherProbe.KEY_SOFT_AP_TIMEOUT) ?: "未設定"}")
            appendLine("電池: ${Fmt.num(p.levelPct, "%")} / 給電 ${p.plug.label} / 温度 ${Fmt.temp(p.tempC)} / 電圧 ${Fmt.num(p.voltageMv, " mV")}")
            appendLine("電流: ${p.currentMa?.let { "$it mA" } ?: Fmt.NA}")
            appendLine("熱状態: ${Fmt.thermal(p.thermalStatus)}")
            appendLine("SoC温度: ${p.socTempC?.let { Fmt.temp(it) + "（${p.socZone}）" } ?: Fmt.NA}")
            appendLine("データセーバー: ${Fmt.onOff(p.dataSaver)} / バッテリーセーバー: ${Fmt.onOff(p.powerSave)} / Doze: ${Fmt.onOff(p.deviceIdle)}")
            appendLine("SIM: ${Fmt.sim(l.simState)} / 事業者: ${l.simOperator ?: Fmt.NA} / 接続先: ${l.networkOperator ?: Fmt.NA}")
            appendLine("音声: ${Fmt.voice(l.voiceState)} / データ網: ${Fmt.rat(l.dataRat)} / データ: ${Fmt.dataState(l.dataState)} / モバイルデータ: ${Fmt.onOff(l.dataEnabled)}")
            appendLine("電波: ${l.signalType ?: Fmt.NA} / RSRP ${Fmt.num(l.rsrp)} / RSRQ ${Fmt.num(l.rsrq)} / SINR ${Fmt.db(l.sinr)} / レベル ${Fmt.num(l.level)}")
            appendLine("EARFCN: ${Fmt.num(l.earfcn)} / バンド: ${l.band ?: Fmt.NA} / 帯域幅: ${Fmt.bandwidths(l.bandwidthsMhz)}")
            appendLine("機内モード: ${Fmt.onOff(l.airplane)} / ローミング: ${Fmt.onOff(l.roaming)}")
            appendLine()
            appendLine("## 消費電力の計測")
            val m = s.meter
            appendLine("充電カウンタ: ${p.counterUah?.let { "$it µAh" } ?: "未対応"}（区間の計算で使えたか: ${Fmt.onOff(m?.counterSupported, "使えた", "使えない")}）")
            appendLine("電池容量: ${m?.capacityMah?.let { "$it mAh" } ?: Fmt.NA}（設定 ${prefs.int(Defs.BATT_CAPACITY).takeIf { it > 0 }?.let { "$it mAh" } ?: "自動"}）")
            appendLine("直近の消費: ${m?.rateMa?.let { "%.0f mA".format(it) } ?: "－"}${m?.rateMw?.let { " / %.0f mW".format(it) } ?: ""}（${m?.method?.label ?: "－"}）")
            appendLine("推定残り時間: ${m?.remainingMin?.let { Fmt.duration(it * 60_000L) } ?: "－"}")
            // 接続端末を数え損ねたときの補い（LanTraffic）に使う値
            appendLine("テザリングの通信（いまの区間・モバイル回線以外の送受信）: ${m?.lanBytes?.let { "%.1f MB".format(it / 1048576.0) } ?: Fmt.NA}")
            s.experiment?.let { appendLine("比較テスト: ${it.title} / 条件 ${it.condition} ${it.measuredSec / 60}/${it.phaseSec / 60}分${if (it.waitingSwitch) "（切り替え待ち）" else ""}") }
            appendLine()
            appendLine("## 設置場所・通信量・管理画面")
            val base = PlaceStore.baseline(prefs)
            appendLine("電波の点数: ${SignalQuality.score(l.rsrp ?: l.dbm, l.sinr)?.let { "$it 点" } ?: Fmt.NA} / 設置場所の記録: ${PlaceStore.list(prefs).size}件（基準: ${base?.let { "${it.name} RSRP ${Fmt.num(it.rsrp)}" } ?: "なし"}）")
            appendLine("待機中の通信の通知: 接続端末なし ${prefs.int(Defs.IDLE_TRAFFIC_MB)} MB/時 / 使わない時間帯 ${QuietHours.parse(prefs.text(Defs.QUIET_HOURS))?.label ?: "未設定"}（${prefs.int(Defs.QUIET_TRAFFIC_MB)} MB）")
            // 管理画面の URL はこの端末のアクセスポイントのアドレス（接続端末の情報ではない）
            appendLine("管理画面: ${if (!prefs.bool(Defs.WEB)) "OFF" else s.web?.let { it.url ?: it.message } ?: "－"}")
            appendLine()
            appendLine("## 自動化（第 3 段階）")
            appendLine("自動切り替え: バッテリーセーバー ${Fmt.onOff(prefs.bool(Defs.AUTO_SAVER), "使う", "使わない")}（残量 ${prefs.int(Defs.AUTO_SAVER_PCT)}% 以下） / Doze を早める ${Fmt.onOff(prefs.bool(Defs.AUTO_DOZE), "使う", "使わない")}")
            appendLine("設定変更の権限: ${if (AutoSwitch.permitted(ctx)) "付与済み" else "なし"} / 自動で変えている: バッテリーセーバー ${Fmt.onOff(prefs.autoSaverApplied)}・Doze ${Fmt.onOff(prefs.autoDozeApplied)}")
            appendLine("global low_power = ${Os.global(ctx, AutoSwitch.KEY_SAVER) ?: "未設定"} / device_idle_constants = ${Os.global(ctx, AutoSwitch.KEY_DOZE) ?: "未設定"}")
            appendLine("待機: ${s.idleReason ?: "していない"}（設定 ${prefs.int(Defs.IDLE_INTERVAL)}分・残量 ${prefs.int(Defs.IDLE_BATT)}%） / 到達確認の省略: ${Fmt.onOff(prefs.bool(Defs.SKIP_PROBE), "使う", "使わない")}${if (s.check?.probeSkipped == true) "（直近のチェックで省略）" else ""}")
            appendLine("使用状況へのアクセス: ${if (AppUsageProbe.hasAccess(ctx)) "許可" else "未許可"} / 優先ネットワーク: ${Os.preferredNetworkMode(ctx)?.let { "${NetworkModes.label(it)}（$it）" } ?: Fmt.NA}")
            appendLine()
            appendLine("## 第 4 段階")
            // テザリングは止めずに、切り替えられるかだけを確かめる
            appendLine("テザリングの切り替え: システム設定の変更 ${if (TetherControl.permitted(ctx)) "許可" else "未許可"} / 確認 ${TetherControl.probe(ctx).text}")
            val quiet = QuietHours.parse(prefs.text(Defs.QUIET_HOURS))
            appendLine(
                "時間帯スケジュール: ${Fmt.onOff(prefs.bool(Defs.TETHER_SCHEDULE), "使う", "使わない")}（${quiet?.label ?: "時間帯の未設定"}） / " +
                    "止めている: ${if (prefs.schedStoppedWindow != 0L) "はい（${Fmt.time(prefs.schedStoppedAt)}から）" else "いいえ"}${s.sched?.note?.let { " / $it" } ?: ""}",
            )
            appendLine(
                "ハードウェアアクセラレーション: global tether_offload_disabled = ${Os.global(ctx, "tether_offload_disabled") ?: "未設定（既定は有効）"}" +
                    "（端末が対応しているかはアプリから読めません。PC の adb-setup で確認できます）",
            )
            val dest = ExtNotify.parse(prefs.text(Defs.EXT_URL))
            appendLine(
                "外部への通知: ${Fmt.onOff(prefs.bool(Defs.EXT_NOTIFY), "使う", "使わない")} / 送り先 ${dest?.label ?: "未設定"}" +
                    " / 未送信 ${s.ext?.pending ?: 0}件${s.ext?.lastResult?.let { " / 直近 $it" } ?: ""}",
            )
            val kind = prefs.text(Defs.PLUG_KIND)
            val excluded = PlugUrls.ipv4(kind, prefs.text(Defs.PLUG_HOST), prefs.text(Defs.PLUG_ON_URL)) != null
            appendLine(
                "スマートプラグ: ${Fmt.onOff(prefs.bool(Defs.PLUG), "使う", "使わない")} / ${PlugUrls.label(kind)} / 範囲 ${prefs.int(Defs.PLUG_LOW)}〜${prefs.int(Defs.PLUG_HIGH)}% / " +
                    "高温 ${prefs.int(Defs.PLUG_HOT)}℃ / 自動で ON に戻す ${prefs.int(Defs.PLUG_FLIPBACK)}分 / 接続端末の数から除く: ${if (excluded && prefs.bool(Defs.PLUG)) "あり" else "なし"}",
            )
            s.plug?.let { appendLine("スマートプラグの状態: 目標 ${it.want?.let { w -> if (w) "ON" else "OFF" } ?: "未定"}${it.why?.let { w -> "（$w）" } ?: ""} / 直近 ${it.lastResult ?: "－"}${if (it.stuck) " / 給電の状態が合わない" else ""}") }
            appendLine()
            appendLine("## 直近の接続性チェック")
            if (c == null) {
                appendLine("まだ実行していません")
            } else {
                appendLine("${Fmt.dateTime(c.at)}（${c.trigger.label}）")
                appendLine("回線登録 ${c.registered.mark} / IP ${c.ip.mark} / DNS ${c.dns.mark} / HTTP ${c.http.mark}（${c.httpCode ?: "－"}・${c.httpMs ?: "－"} ms） / システム検証 ${c.validated.mark}")
                appendLine("判定: ${c.diagnosis}")
                if (c.detail.isNotEmpty()) appendLine("詳細: ${c.detail}")
            }
            appendLine()
            appendLine("## OS の設定値（読み取りのみ）")
            for (
                key in listOf(
                    "wifi_scan_always_enabled", "ble_scan_always_enabled", "stay_on_while_plugged_in", "ota_disable_automatic_update",
                    "mobile_data_always_on", "tether_offload_disabled", Settings.Global.AIRPLANE_MODE_ON,
                )
            ) {
                appendLine("global $key = ${Os.global(ctx, key) ?: "未設定"}")
            }
            for (
                key in listOf(
                    "doze_enabled", "doze_always_on", "doze_pulse_on_pick_up", "doze_pulse_on_double_tap", "doze_tap_gesture",
                    "wake_gesture_enabled", "double_tap_to_wake", "location_mode",
                )
            ) {
                appendLine("secure $key = ${Os.secure(ctx, key) ?: "未設定"}")
            }
            appendLine("system screen_off_timeout = ${Os.system(ctx, Settings.System.SCREEN_OFF_TIMEOUT) ?: "未設定"}")
            appendLine()
            appendLine("## アプリの設定")
            // 通知先・プラグの URL はトークン・パスワードを含みうるので出さない
            val secret = Defs.secretKeys
            prefs.values().forEach { (k, v) ->
                appendLine("$k = " + if (k in secret) (if ((v as? String).isNullOrEmpty()) "（空欄）" else "（設定あり・非表示）") else v)
            }
            appendLine()
            appendLine("## 最新のログ（末尾）")
            LogStore.tail(ctx, 15).forEach { appendLine(it) }
        }
    }

    private fun granted(ctx: Context, perm: String) =
        if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) "許可" else "未許可"
}
