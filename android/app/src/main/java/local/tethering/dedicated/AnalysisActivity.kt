package local.tethering.dedicated

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.LinearLayout
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * 省電力の分析（仕様 F-30 / F-36 / F-35）。記録から集計して、省電力の判断材料を出す（集計のための常駐処理はない）。
 * 消費はバッテリー駆動中に計測できた区間だけ。アプリ別の通信量は OS の記録から読む（使用状況へのアクセスが必要）。
 */
class AnalysisActivity : Activity() {
    companion object {
        private const val DAYS = 7
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var powerBox: LinearLayout
    private lateinit var appBox: LinearLayout
    private var appDays = 1

    private class PowerData(
        val bySignal: List<PowerGroup>,
        val byBand: List<PowerGroup>,
        val slope: Double?,
        val measuredH: Double,
        val standby: Standby.Result,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.text(c, "記録から、省電力の判断材料を出します（直近 ${DAYS} 日）。", 13.5f)
            Ui.small(c, "消費はバッテリー駆動中に計測できた区間だけです（給電中は消費を測れません）。条件（接続端末の使い方・通信量）が違うと比べにくいので、目安として見てください。")
        }
        powerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(powerBox)
        appBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(appBox)
        Ui.small(powerBox, "集計しています…")
        bg.execute {
            val d = runCatching { loadPower() }.getOrNull()
            main.post { renderPower(d) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 使用状況へのアクセスを許可して戻ってきたときにも読み直す
        loadApps()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadPower(): PowerData {
        val rows = PowerStore.days(this).take(DAYS + 1).flatMap { PowerStore.rowsOfDay(this, it) }
        val capacity = Prefs(this).int(Defs.BATT_CAPACITY).takeIf { it > 0 } ?: StateStore.state.meter?.capacityMah
        val measured = rows.filter { it.onBattery && it.mah != null }.sumOf { it.sec } / 3600.0
        return PowerData(
            SignalPower.byRsrp(rows), SignalPower.byBand(rows), SignalPower.slopeMaPerDb(rows), measured,
            Standby.analyze(rows, TimeZone.getDefault(), capacity),
        )
    }

    private fun hours(h: Double) = if (h <= 0) "0分" else Fmt.duration((h * 3_600_000).toLong())

    private fun ma(v: Double?) = v?.let { String.format(Locale.US, "%.0f mA", it) } ?: "未計測"

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

    private fun renderPower(d: PowerData?) {
        powerBox.removeAllViews()
        if (d == null) {
            Ui.card(powerBox).also { Ui.small(it, "集計できませんでした") }
            return
        }
        Ui.card(powerBox).also { c ->
            Ui.heading(c, "電波と消費")
            if (d.measuredH < 0.25) {
                Ui.small(c, "バッテリー駆動中の計測がまだありません。給電を外して使うと、電波の強さ・バンドごとの消費が分かります。")
            } else {
                Ui.small(c, "バッテリー駆動中に計測できた ${hours(d.measuredH)} の内訳（時間・平均消費・通信量）")
                for (g in d.bySignal.filter { it.sec > 0 }) Ui.kv(c, g.label).text = line(g)
                if (d.byBand.size > 1 || d.byBand.firstOrNull()?.label != "不明") {
                    Ui.spacer(c, 6)
                    Ui.small(c, "バンド別")
                    for (g in d.byBand) Ui.kv(c, g.label).text = line(g)
                }
                d.slope?.let { s ->
                    val delta = -10 * s
                    Ui.spacer(c, 4)
                    Ui.text(
                        c,
                        if (delta > 0) {
                            String.format(Locale.US, "RSRP が 10 dB 下がる（電波が悪くなる）と、平均の消費は約 %.0f mA 増えています（目安）。", delta)
                        } else {
                            "この記録では、電波が悪いときに消費が増える傾向は見えません（接続端末の使い方など、ほかの条件の影響が大きい可能性があります）。"
                        },
                        13.5f,
                    )
                }
            }
            Ui.button(c, "設置場所アシストを開く") { startActivity(Intent(this, PlacementActivity::class.java)) }
        }

        val s = d.standby
        Ui.card(powerBox).also { c ->
            Ui.heading(c, "接続 0 台の待機と運用方針")
            if (s.recordedHours < 0.5) {
                Ui.small(c, "まだ記録が足りません（チェックの区切りごとに記録します）。")
            } else {
                Ui.kv(c, "記録").text = "${hours(s.recordedHours)}\n接続 0 台 ${hours(s.idleHours)}\n接続あり ${hours(s.usedHours)}\nテザリング OFF ${hours(s.offHours)}"
                Ui.kv(c, "接続 0 台の時間").text = "1 日あたり約 ${hours(s.idleHoursPerDay)}" +
                    if (s.stretches > 0) "\n${s.stretches} 回・最長 ${hours(s.longestIdleH)}" else ""
                Ui.kv(c, "消費（接続 0 台）").text = ma(s.idleMa)
                Ui.kv(c, "消費（テザリング OFF）").text = ma(s.offMa)
                Ui.kv(c, "消費（接続あり）").text = ma(s.usedMa)
                Ui.kv(c, "待機のための消費").text = s.dailyCostMah?.let { mah ->
                    String.format(Locale.US, "1 日あたり約 %.0f mAh", mah) + (s.dailyCostPct?.let { String.format(Locale.US, "（電池の約 %.0f%%）", it) } ?: "")
                } ?: "－"
            }
            Ui.spacer(c, 4)
            Ui.text(c, advice(s), 13.5f, if (s.advice == Standby.Advice.CONSIDER_OFF) R.color.status_warn else R.color.text_primary)
            Ui.button(c, "比較テストで待機電力を測る") { startActivity(Intent(this, ExperimentActivity::class.java)) }
        }
    }

    private fun line(g: PowerGroup): String = "${hours(g.hours)}・${ma(g.avgMa)}" + (g.mbPerHour?.let { String.format(Locale.US, "\n通信 %.1f MB/時", it) } ?: "")

    private fun advice(s: Standby.Result): String = when (s.advice) {
        Standby.Advice.NEED_DATA -> "バッテリー駆動で接続 0 台の時間が 30 分以上記録されると、待機の消費を計算できます。"
        Standby.Advice.NEED_OFF -> "テザリング OFF のときの消費がまだ計測できていないので、待機のための消費（差）を出せません。比較テスト「使わないときにテザリングを止める」で測れます。"
        Standby.Advice.KEEP_ON -> "待機のための消費は小さいので、常時待機（テザリング ON のまま）で問題ありません。"
        Standby.Advice.CONSIDER_OFF -> String.format(
            Locale.US,
            "接続 0 台の時間に、1 日あたり電池の約 %.0f%% を待機に使っています。長く使わない時間帯があるなら、使わないときはテザリングを止める運用も検討できます。" +
                "再開は端末での操作が必要です（このアプリからは ON にできません）。OS の「アクセスポイントを自動的に OFF」を使うなら、設定の「Wi-Fiテザリングの停止を異常とする」を OFF にしてください。",
            s.dailyCostPct ?: 0.0,
        )
    }

    // ---- アプリ別の通信（仕様 F-35） ----

    private fun loadApps() {
        val days = appDays
        bg.execute {
            val access = AppUsageProbe.hasAccess(this)
            val now = System.currentTimeMillis()
            val sum = if (access) runCatching { AppUsageProbe.summary(this, now - days * 24 * 3_600_000L, now) }.getOrNull() else null
            main.post { renderApps(access, sum, days) }
        }
    }

    private fun renderApps(access: Boolean, sum: AppTraffic.Summary?, days: Int) {
        appBox.removeAllViews()
        val c = Ui.card(appBox)
        Ui.heading(c, "端末自身の通信の内訳（アプリ別）")
        if (!access) {
            Ui.text(c, "アプリ別のモバイル通信量を見るには「使用状況へのアクセス」の許可が必要です（読むだけで、ほかのアプリには何もしません）。", 13.5f)
            Ui.button(c, "使用状況へのアクセスを開く") { SystemScreens.usageAccess(this) }
            Ui.small(c, "PC から許可する場合（tools\\adb-setup.bat -Apply でも許可します）:")
            Ui.mono(c, "adb shell appops set ${BuildConfig.APPLICATION_ID} GET_USAGE_STATS allow")
            return
        }
        Ui.buttonRow(c, "直近 24 時間" to { appDays = 1; loadApps() }, "直近 7 日" to { appDays = 7; loadApps() })
        if (sum == null) {
            Ui.small(c, "OS の記録を読めませんでした")
            return
        }
        Ui.small(c, "直近 ${if (days == 1) "24 時間" else "$days 日"}のモバイル回線の通信量")
        Ui.kv(c, "テザリング").text = mb(sum.tetheringBytes)
        Ui.kv(c, "この端末自身").text = mb(sum.ownBytes)
        // ごく少ないもの（50 KB 未満）は並べない
        val own = sum.rows.filter { it.kind != AppTraffic.Kind.TETHERING && it.total >= 50 * 1024 }.take(15)
        if (own.isEmpty()) {
            Ui.small(c, "この端末自身の目立った通信はありません")
            return
        }
        for (r in own) {
            Ui.spacer(c, 6)
            val tag = when {
                AppTraffic.isCandidate(r) -> "　⚠ 制限の候補"
                AppTraffic.needsManual(r) -> "　⚠ 手動で対策"
                else -> ""
            }
            Ui.text(c, r.label + tag, 14.5f, if (tag.isNotEmpty()) R.color.status_warn else R.color.text_primary, bold = true)
            val counts = listOfNotNull(
                r.resumed.takeIf { it > 0 }?.let { "前面 ${it} 回" },
                r.services.takeIf { it > 0 }?.let { "常駐サービス ${it} 回" },
            ).joinToString("・")
            val kind = r.kind.takeIf { it == AppTraffic.Kind.USER_APP || it == AppTraffic.Kind.SYSTEM_APP }?.let { " · ${it.label}" } ?: ""
            Ui.small(c, "合計 ${mb(r.total)}（裏 ${mb(r.bgBytes)}）$kind" + if (counts.isNotEmpty()) " · $counts" else "")
            when {
                AppTraffic.isCandidate(r) -> Ui.small(c, "裏での通信が多いユーザーアプリです。PC から tools\\adb-setup.bat -Apply -RestrictApps でまとめて制限できます。")
                AppTraffic.needsManual(r) -> Ui.small(
                    c,
                    "システムアプリのため一括制限の対象外です。自動同期や Play ストアの自動更新の OFF、アプリ情報 → モバイルデータ → バックグラウンドデータの OFF で減らせることがあります。",
                )
            }
            if (r.pkg != null && (r.kind == AppTraffic.Kind.USER_APP || r.kind == AppTraffic.Kind.SYSTEM_APP)) {
                Ui.button(c, "アプリ情報を開く") { SystemScreens.open(this, SystemScreens.appDetails(r.pkg)) }
            }
        }
        Ui.spacer(c, 6)
        Ui.small(c, "OS の通信量の記録（2 時間ごとにまとめられる）から読んだ値です。「裏」は画面に出ていないときの通信です。")
    }
}
