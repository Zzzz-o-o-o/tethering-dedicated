package local.tethering.dedicated

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** 運用ダッシュボード（仕様 F-01）。取得できない値は「取得不可」と表示する */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_OPEN_TETHER = "open_tether"
        private const val REQ_PHONE = 1
        private const val REQ_EXPORT = 2
    }

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var resumed = false
    private var live: Live? = null
    private var bannerKey = ""
    private var recentKey = ""

    /** 画面表示中だけ 5 秒ごとに読む値（定期チェックとは別。通信はしない） */
    private class Live(val power: PowerInfo, val tether: TetherInfo, val line: LineInfo)

    private lateinit var chip: TextView
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var banner: LinearLayout

    private lateinit var vAp: TextView
    private lateinit var vShared: TextView
    private lateinit var vClients: TextView
    private lateinit var vAutoOff: TextView

    private lateinit var vSim: TextView
    private lateinit var vOperator: TextView
    private lateinit var vData: TextView
    private lateinit var vVoice: TextView
    private lateinit var vSignal: TextView
    private lateinit var vQuality: TextView
    private lateinit var vBand: TextView
    private lateinit var vBandwidth: TextView
    private lateinit var vRoaming: TextView

    private lateinit var vReg: TextView
    private lateinit var vIp: TextView
    private lateinit var vDns: TextView
    private lateinit var vHttp: TextView
    private lateinit var vValidated: TextView
    private lateinit var vDiag: TextView

    private lateinit var vBattery: TextView
    private lateinit var vPlug: TextView
    private lateinit var vTemp: TextView
    private lateinit var vThermal: TextView
    private lateinit var vSoc: TextView
    private lateinit var vCurrent: TextView
    private lateinit var vSaver: TextView
    private lateinit var vIdle: TextView
    private lateinit var vOpt: TextView

    private lateinit var vProfile: TextView
    private lateinit var vInterval: TextView
    private lateinit var vNext: TextView
    private lateinit var modeGroup: RadioGroup
    private val modeIds = HashMap<BaseMode, Int>()
    private lateinit var vMaint: TextView
    private lateinit var maintButton: Button
    private lateinit var recentBox: LinearLayout
    private lateinit var monitorButton: Button

    private val listener: (StateStore.State) -> Unit = { render() }
    private val ticker = Runnable { tick() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        build()
        if (prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_START)
        askPhonePermissionOnce()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        StateStore.listen(listener)
        tick()
    }

    override fun onPause() {
        resumed = false
        StateStore.unlisten(listener)
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_OPEN_TETHER, false) == true) {
            i.removeExtra(EXTRA_OPEN_TETHER)
            SystemScreens.tether(this)
        }
    }

    private fun tick() {
        main.removeCallbacks(ticker)
        if (!resumed) return
        bg.execute {
            val l = runCatching { Live(PowerProbe.read(this), TetherProbe.read(this, countClients = false), LineProbe.read(this, null)) }.getOrNull()
            main.post {
                if (l != null) live = l
                render()
            }
        }
        main.postDelayed(ticker, 5_000L)
    }

    // ---- 画面の組み立て ----

    private fun build() {
        val page = Ui.page(this)

        Ui.card(page).also { c ->
            chip = Ui.chip(c, Overall.UNKNOWN.label, R.color.status_off)
            headline = Ui.text(c, "", 16f, bold = true)
            subline = Ui.small(c, "")
            Ui.button(c, "今すぐチェック") { checkNow() }
        }

        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(banner)

        Ui.card(page).also { c ->
            Ui.heading(c, "テザリング")
            vAp = Ui.kv(c, "Wi-Fiテザリング")
            vShared = Ui.kv(c, "共有中")
            vClients = Ui.kv(c, "接続端末")
            vAutoOff = Ui.kv(c, "自動OFF設定")
            Ui.button(c, "テザリング設定を開く") { SystemScreens.tether(this) }
        }

        Ui.card(page).also { c ->
            Ui.heading(c, "モバイル回線")
            vSim = Ui.kv(c, "SIM")
            vOperator = Ui.kv(c, "接続先")
            vData = Ui.kv(c, "データ")
            vVoice = Ui.kv(c, "音声")
            vSignal = Ui.kv(c, "電波")
            vQuality = Ui.kv(c, "品質")
            vBand = Ui.kv(c, "バンド")
            vBandwidth = Ui.kv(c, "帯域幅")
            vRoaming = Ui.kv(c, "ローミング・機内モード")
            Ui.button(c, "APN設定を開く") { SystemScreens.apn(this) }
        }

        Ui.card(page).also { c ->
            Ui.heading(c, "接続性（段階ごとの判定）")
            vReg = Ui.kv(c, "① 回線登録")
            vIp = Ui.kv(c, "② IP通信")
            vDns = Ui.kv(c, "③ DNS")
            vHttp = Ui.kv(c, "④ インターネット")
            vValidated = Ui.kv(c, "システムの接続検証")
            vDiag = Ui.small(c, "")
        }

        Ui.card(page).also { c ->
            Ui.heading(c, "電源・温度")
            vBattery = Ui.kv(c, "電池")
            vPlug = Ui.kv(c, "給電")
            vTemp = Ui.kv(c, "電池温度")
            vThermal = Ui.kv(c, "熱状態")
            vSoc = Ui.kv(c, "SoC温度（参考）")
            vCurrent = Ui.kv(c, "電流（参考）")
            vSaver = Ui.kv(c, "バッテリーセーバー")
            vIdle = Ui.kv(c, "Doze（省電力待機）")
            vOpt = Ui.kv(c, "電池の最適化")
        }

        Ui.card(page).also { c ->
            Ui.heading(c, "運用プロファイル")
            vProfile = Ui.kv(c, "現在")
            vInterval = Ui.kv(c, "チェック間隔")
            vNext = Ui.kv(c, "次回チェック")
            modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val labels = mapOf(
                BaseMode.AUTO to "自動（給電中は通常・バッテリー駆動中は省電力）",
                BaseMode.NORMAL to "通常（常に）",
                BaseMode.SAVER to "省電力（常に）",
            )
            for ((m, label) in labels) {
                val rb = RadioButton(this).apply { text = label }
                modeGroup.addView(rb)
                modeIds[m] = rb.id
            }
            modeGroup.check(modeIds.getValue(prefs.mode))
            modeGroup.setOnCheckedChangeListener { _, id -> onModeChanged(id) }
            c.addView(modeGroup)
            Ui.spacer(c, 6)
            vMaint = Ui.small(c, "")
            maintButton = Ui.button(c, "メンテナンスモードを開始") { toggleMaintenance() }
        }

        Ui.card(page).also { c ->
            Ui.heading(c, "直近の記録")
            recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(recentBox)
        }

        Ui.card(page).also { c ->
            Ui.heading(c, "操作")
            Ui.button(c, "ログをCSVで保存") { exportLog() }
            Ui.buttonRow(c, "設定" to { startActivity(Intent(this, SettingsActivity::class.java)) }, "設定ガイド" to { startActivity(Intent(this, GuideActivity::class.java)) })
            Ui.button(c, "診断情報") { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
            monitorButton = Ui.button(c, "監視を停止") { toggleMonitoring() }
            Ui.small(c, "このアプリはOS標準のテザリング機能を変更しません。状態の確認・記録・通知だけを行います。")
        }
    }

    // ---- 表示の更新 ----

    private fun render() {
        val s = StateStore.state
        val lv = live
        val p = lv?.power ?: s.power
        val t = lv?.tether ?: s.tether
        val l = lv?.line ?: s.line
        val c = s.check
        val monitoring = prefs.monitoring
        val o = if (!monitoring) Overall.STOPPED else s.overall()

        chip.text = o.label
        Ui.setChipColor(chip, Ui.statusColor(o))
        val up = s.upstream
        headline.text = when {
            !monitoring -> "監視は停止しています"
            o == Overall.MAINT -> "メンテナンス中（〜${Fmt.hm(s.maintUntil)}）：自動の再確認と通知を止めています"
            up != null && up.confirmed -> "異常：${up.kind.label}（${Fmt.time(up.startedAt)}から）"
            up != null -> "確認中：${up.kind.label}（再確認 ${up.rechecks}/${up.total}）"
            s.tetherIssue != null -> s.tetherIssue.kind.label
            c != null -> c.diagnosis
            else -> "最初のチェックを実行しています…"
        }
        subline.text = listOfNotNull(
            c?.let { "最終チェック ${Fmt.time(it.at)}（${it.trigger.label}）" },
            if (monitoring && s.nextCheckAt > 0) "次回 ${Fmt.hm(s.nextCheckAt)}" else null,
            if (s.checking) "実行中…" else null,
        ).joinToString(" · ")

        renderBanner(p, t, l, monitoring)

        // テザリング（接続端末数は定期チェックのときだけ数える）
        vAp.text = when {
            t == null -> Fmt.NA
            t.apState != null -> Fmt.ap(t.apState) + if (t.apMode == 2) "（ローカル専用）" else ""
            else -> Fmt.onOff(t.wifiOn)
        }
        vShared.text = Fmt.tethered(t?.tethered)
        val counted = s.tether
        vClients.text = when {
            counted?.clients != null -> "${counted.clients}台（${counted.clientsMethod}で推定）"
            counted != null -> counted.clientsMethod
            else -> Fmt.NA
        }
        vAutoOff.text = when (t?.autoOff) {
            false -> "OFF ✓"
            true -> "ON（接続がないと停止）⚠"
            null -> "未設定（AOSPの既定はON）"
        }

        // モバイル回線
        vSim.text = l?.let { Fmt.sim(it.simState) + (it.simOperator?.let { op -> " · $op" } ?: "") } ?: Fmt.NA
        vOperator.text = l?.networkOperator ?: Fmt.NA
        vData.text = l?.let {
            buildString {
                append(Fmt.rat(it.dataRat)).append(" · ").append(Fmt.dataState(it.dataState))
                if (it.dataEnabled == false) append(" · モバイルデータOFF")
                if (!it.phonePermission) append("（権限なし）")
            }
        } ?: Fmt.NA
        vVoice.text = l?.let {
            Fmt.voice(it.voiceState) + if (it.voiceState != LineInfo.STATE_IN_SERVICE && it.dataRegistered == true) "（データ専用SIMでは通常）" else ""
        } ?: Fmt.NA
        vSignal.text = l?.let {
            val strength = it.rsrp?.let { v -> "RSRP $v dBm" } ?: it.dbm?.let { v -> "$v dBm" } ?: Fmt.NA
            val bars = it.level?.let { lv2 -> " " + "■".repeat(lv2) + "□".repeat(4 - lv2) } ?: ""
            (it.signalType?.let { type -> "$type · " } ?: "") + strength + bars
        } ?: Fmt.NA
        vQuality.text = l?.let { "RSRQ ${Fmt.num(it.rsrq, " dB")} · SINR ${Fmt.db(it.sinr)}" } ?: Fmt.NA
        vBand.text = l?.let { line -> line.band?.let { b -> b + (line.earfcn?.let { e -> " · EARFCN $e" } ?: "") } ?: Fmt.NA } ?: Fmt.NA
        vBandwidth.text = Fmt.bandwidths(l?.bandwidthsMhz)
        vRoaming.text = l?.let { "${Fmt.onOff(it.roaming, "ローミング中", "なし")} · 機内モード ${Fmt.onOff(it.airplane)}" } ?: Fmt.NA

        // 接続性
        vReg.text = c?.let { tri(it.registered) } ?: "－"
        vIp.text = c?.let {
            tri(it.ip) + when (it.route) {
                null -> "（ネットワークなし）"
                "モバイル回線" -> ""
                else -> "（${it.route}経由）"
            }
        } ?: "－"
        vDns.text = c?.let { tri(it.dns) } ?: "－"
        vHttp.text = c?.let {
            when (it.http) {
                Tri.OK -> "✓（${it.httpCode}・${it.httpMs} ms）"
                Tri.NG -> "✗" + (it.httpCode?.let { code -> "（HTTP $code）" } ?: "")
                Tri.SKIP -> if (prefs.bool(Defs.HTTP)) "－（前の段階で停止）" else "－（確認しない設定）"
                Tri.UNKNOWN -> Fmt.NA
            }
        } ?: "－"
        vValidated.text = c?.let { tri(it.validated) } ?: "－"
        vDiag.text = c?.let { it.diagnosis + if (it.detail.isNotEmpty()) "\n${it.detail}" else "" } ?: ""

        // 電源・温度
        vBattery.text = p?.let { "${Fmt.num(it.levelPct, "%")}" + if (it.charging == true) "・充電中" else "" } ?: Fmt.NA
        vPlug.text = p?.plug?.label ?: Fmt.NA
        vTemp.text = Fmt.temp(p?.tempC)
        vThermal.text = Fmt.thermal(p?.thermalStatus)
        vSoc.text = p?.let { pw -> pw.socTempC?.let { Fmt.temp(it) + (pw.socZone?.let { z -> "（$z）" } ?: "") } } ?: Fmt.NA
        vCurrent.text = p?.currentMa?.let { "$it mA" } ?: Fmt.NA
        vSaver.text = Fmt.onOff(p?.powerSave)
        vIdle.text = Fmt.onOff(p?.deviceIdle, "待機中", "なし")
        vOpt.text = Fmt.onOff(p?.batteryOptExempt, "除外済み ✓", "対象 ⚠")

        // プロファイル
        vProfile.text = "${s.effective.label}（モード: ${s.mode.label}）"
        vInterval.text = when (s.base) {
            Effective.SAVER -> "${s.intervalMin}分（省電力）"
            else -> "${s.intervalMin}分（通常）"
        }
        vNext.text = if (monitoring && s.nextCheckAt > 0) Fmt.time(s.nextCheckAt) else "－"
        // 選択の正はサービスの状態ではなく保存値（サービスの反映待ちの間に選択が戻らないように）
        modeIds[prefs.mode]?.let { id -> if (modeGroup.checkedRadioButtonId != id) modeGroup.check(id) }
        val maint = s.maintUntil > System.currentTimeMillis()
        vMaint.text = if (maint) "メンテナンス中：${Fmt.hm(s.maintUntil)} まで自動の再確認・通知を止めています" else "設定変更や更新作業の間は、メンテナンスモードで誤検知の通知を止められます"
        maintButton.text = if (maint) "メンテナンスモードを終了" else "メンテナンスモードを開始"
        maintButton.isEnabled = monitoring

        renderRecent(s.recent)
        monitorButton.text = if (monitoring) "監視を停止" else "監視を開始"
    }

    private fun tri(t: Tri) = when (t) {
        Tri.OK -> "✓"
        Tri.NG -> "✗"
        Tri.SKIP -> "－"
        Tri.UNKNOWN -> Fmt.NA
    }

    /** 利用者の操作が必要な注意点だけを上にまとめる */
    private fun renderBanner(p: PowerInfo?, t: TetherInfo?, l: LineInfo?, monitoring: Boolean) {
        val items = ArrayList<Triple<String, String, () -> Unit>>()
        if (!monitoring) items.add(Triple("監視を停止中です。", "監視を開始") { toggleMonitoring() })
        if (l != null && !l.phonePermission) {
            items.add(Triple("「電話」の権限がないため、データ網の種類・回線登録の一部が取得できません（発信・通話はしません）。", "許可する") { requestPhonePermission() })
        }
        if (p?.batteryOptExempt == false) {
            items.add(Triple("電池の最適化の対象です。画面消灯中にチェックが遅れることがあります。", "除外を依頼") { SystemScreens.requestIgnoreBatteryOptimizations(this) })
        }
        if (p?.dataSaver == true) {
            items.add(Triple("データセーバーがONです。ONのあいだテザリングは使えません。", "設定を開く") { SystemScreens.dataUsage(this) })
        }
        if (t?.autoOff == true) {
            items.add(Triple("アクセスポイントの自動OFFが有効です。接続がないとテザリングが止まります。", "設定を開く") { SystemScreens.hotspot(this) })
        }
        val key = items.joinToString("|") { it.first }
        if (key == bannerKey) return
        bannerKey = key
        banner.removeAllViews()
        if (items.isEmpty()) return
        val c = Ui.card(banner)
        Ui.heading(c, "要確認")
        for ((msg, label, action) in items) {
            Ui.text(c, "⚠ $msg", 13.5f, R.color.status_warn)
            Ui.button(c, label, action)
            Ui.spacer(c, 4)
        }
    }

    private fun renderRecent(list: List<RecentEvent>) {
        val shown = list.take(10)
        val key = shown.joinToString("|") { "${it.at}${it.kind}" }
        if (key == recentKey) return
        recentKey = key
        recentBox.removeAllViews()
        if (shown.isEmpty()) {
            Ui.small(recentBox, "まだ記録はありません")
            return
        }
        for (e in shown) {
            Ui.text(recentBox, "${Fmt.time(e.at)}  ${e.kind}" + if (e.text.isNotEmpty()) "：${e.text}" else "", 13f)
        }
    }

    // ---- 操作 ----

    private fun checkNow() {
        if (!prefs.monitoring) {
            toast("監視を開始すると実行できます")
            return
        }
        MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
        toast("チェックを開始しました")
    }

    private fun onModeChanged(id: Int) {
        val m = modeIds.entries.firstOrNull { it.value == id }?.key ?: return
        if (m == prefs.mode) return
        val old = prefs.mode
        prefs.mode = m
        LogStore.write(this, Ev.SETTINGS, StateStore.state, result = "運用モード: ${old.label} → ${m.label}")
        if (prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_SETTINGS)
    }

    private fun toggleMaintenance() {
        if (StateStore.state.maintUntil > System.currentTimeMillis()) {
            MonitorService.send(this, MonitorService.ACTION_MAINT, 0L)
            return
        }
        val labels = arrayOf("30分", "1時間", "2時間", "4時間")
        val minutes = intArrayOf(30, 60, 120, 240)
        AlertDialog.Builder(this)
            .setTitle("メンテナンスモード（自動の再確認・通知を一時停止）")
            .setItems(labels) { _, which ->
                MonitorService.send(this, MonitorService.ACTION_MAINT, System.currentTimeMillis() + minutes[which] * 60_000L)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun toggleMonitoring() {
        if (prefs.monitoring) {
            AlertDialog.Builder(this)
                .setTitle("監視を停止しますか？")
                .setMessage("定期チェック・異常の通知・再起動後の自動開始が止まります。OSのテザリング機能には影響しません。")
                .setPositiveButton("停止する") { _, _ ->
                    MonitorService.send(this, MonitorService.ACTION_STOP)
                    main.postDelayed({ render() }, 500L)
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            prefs.monitoring = true
            MonitorService.send(this, MonitorService.ACTION_START)
            render()
        }
    }

    private fun exportLog() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "tethering-log-${Fmt.fileStamp(System.currentTimeMillis())}.csv")
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (_: ActivityNotFoundException) {
            toast("保存先を選ぶ画面を開けませんでした")
        }
    }

    @Deprecated("Activity の旧 API（AndroidX を使わないため）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        bg.execute {
            val n = runCatching { contentResolver.openOutputStream(uri)?.use { LogStore.export(this, it) } ?: -1 }.getOrDefault(-1)
            main.post {
                if (n >= 0) {
                    LogStore.write(this, Ev.EXPORT, StateStore.state, result = "${n}行")
                    toast("${n}行を書き出しました")
                } else {
                    toast("書き出しに失敗しました")
                }
            }
        }
    }

    /** 初回だけ、OS の権限ダイアログの前に用途を説明する（OS の文言は「電話の発信と管理」で誤解されやすい） */
    private fun askPhonePermissionOnce() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        if (ui.getBoolean("asked_phone", false)) return
        ui.edit().putBoolean("asked_phone", true).apply()
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle("「電話」の権限について")
            .setMessage(
                "データ網の種類（LTE など）と回線の登録状態を読むために使います。\n\n" +
                    "次の画面で「電話の発信と管理」と表示されますが、このアプリは発信・通話・SMS を一切行いません。" +
                    "許可しなくても動作し、該当する項目は「取得不可」になります。",
            )
            .setPositiveButton("次へ") { _, _ -> requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE) }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun requestPhonePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE) ||
            !getSharedPreferences("ui", MODE_PRIVATE).getBoolean("phone_denied", false)
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE)
        } else {
            // 「今後表示しない」が選ばれた後はダイアログが出ないので、アプリ情報の権限画面を開く
            toast("「権限」から「電話」を許可してください")
            SystemScreens.open(this, SystemScreens.appDetails(packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PHONE) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("phone_denied", !granted).apply()
        if (granted && prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
        bannerKey = ""
        tick()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
