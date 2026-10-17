package local.tethering.dedicated

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import java.util.Locale

/** 省電力設定の比較テスト（仕様 F-27） */
class ExperimentActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var statusBox: LinearLayout
    private lateinit var startBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var group: RadioGroup
    private lateinit var customBox: LinearLayout
    private lateinit var customA: EditText
    private lateinit var customB: EditText
    private val ids = HashMap<Int, String>()
    private var statusKey = ""
    private var historyKey = ""

    private val listener: (StateStore.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        val page = Ui.page(this)

        Ui.card(page).also { c ->
            Ui.text(c, "条件 A（今の設定）と条件 B（試す設定）を交互に計測し、平均消費と通信の安定性を比べます。", 13.5f)
            val min = prefs.int(Defs.EXP_PHASE)
            val rep = prefs.int(Defs.EXP_REPEATS)
            Ui.small(
                c,
                "・バッテリー駆動中の時間だけを数えます（給電中は消費を測れないため止まります）。\n" +
                    "・1 条件 ${min} 分 × ${rep} 回（A→B${if (rep > 1) "→A→B…" else ""}）＝ バッテリー駆動で ${Fmt.duration(min * rep * 2 * 60_000L)}。設定で変えられます。\n" +
                    "・OS の設定は、案内に従って手動で切り替えます（「切り替えた」を押すまで計測しません）。このアプリのチェック間隔だけは自動で切り替えます。\n" +
                    "・効果がない・通信が悪化した場合は、元に戻すよう知らせます。テスト中は接続端末の使い方をできるだけ同じにしてください。",
            )
        }
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(statusBox)

        startBox = Ui.card(page)
        Ui.heading(startBox, "試す変更")
        group = RadioGroup(this)
        for (cand in Candidates.all) {
            val rb = RadioButton(this).apply { text = "${cand.title}\n（A: ${cand.labelA} → B: ${cand.labelB}）" }
            group.addView(rb)
            ids[rb.id] = cand.key
        }
        group.setOnCheckedChangeListener { _, id -> customBox.visibility = if (ids[id] == Candidates.KEY_CUSTOM) View.VISIBLE else View.GONE }
        startBox.addView(group)
        customBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        Ui.small(customBox, "条件 A（今の設定）の名前")
        customA = Ui.textField(customBox, "")
        Ui.small(customBox, "条件 B（試す設定）の名前")
        customB = Ui.textField(customBox, "")
        startBox.addView(customBox)
        Ui.button(startBox, "比較テストを開始") { start() }

        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(historyBox)
    }

    override fun onResume() {
        super.onResume()
        StateStore.listen(listener)
        render(StateStore.state)
    }

    override fun onPause() {
        StateStore.unlisten(listener)
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun start() {
        if (!prefs.monitoring) {
            toast("監視を開始すると実行できます")
            return
        }
        val key = ids[group.checkedRadioButtonId] ?: run {
            toast("試す変更を選んでください")
            return
        }
        val cand = Candidates.of(key) ?: return
        val onBattery = StateStore.state.power?.plug == Plug.NONE
        AlertDialog.Builder(this)
            .setTitle("比較テストを開始しますか？")
            .setMessage(
                "${cand.title}\n\nまず今の設定（A）を計測します。B に切り替える時が来たら通知で知らせます。" +
                    if (onBattery) "" else "\n\n今は給電中のため、給電を外すと計測が始まります。",
            )
            .setPositiveButton("開始") { _, _ ->
                MonitorService.startExperiment(this, key, customA.text.toString(), customB.text.toString())
                toast("開始しました")
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun render(s: StateStore.State) {
        renderStatus(s)
        renderHistory()
    }

    private fun renderStatus(s: StateStore.State) {
        val e = s.experiment
        val onBattery = s.power?.plug == Plug.NONE
        val key = e?.let { "${it.index}|${it.waitingSwitch}|${it.measuredSec / 60}|$onBattery" } ?: "none"
        if (key == statusKey) return
        statusKey = key
        statusBox.removeAllViews()
        startBox.visibility = if (e == null) View.VISIBLE else View.GONE
        if (e == null) return
        val c = Ui.card(statusBox)
        Ui.heading(c, "実行中：${e.title}")
        val label = if (e.condition == 'B') e.labelB else e.labelA
        Ui.text(c, "条件 ${e.condition}（$label）· ${e.index + 1}/${e.total}", 15f, bold = true)
        if (e.waitingSwitch) {
            Ui.text(c, "⚠ 条件 ${e.condition} に切り替えてください：${e.instruction}", 14f, R.color.status_warn)
            Ui.button(c, "切り替えた") { MonitorService.send(this, MonitorService.ACTION_EXP_CONFIRM) }
        } else {
            Ui.text(c, "計測 ${e.measuredSec / 60} / ${e.phaseSec / 60} 分（バッテリー駆動中の時間）", 14f)
            if (!onBattery) Ui.small(c, "給電中のため計測を止めています。給電を外すと再開します。")
        }
        Ui.button(c, "中止して元に戻す") {
            AlertDialog.Builder(this)
                .setTitle("比較テストを中止しますか？")
                .setMessage(if (e.auto) "チェック間隔は自動で元に戻します。" else "中止したら、設定を元（${e.labelA}）に戻してください。")
                .setPositiveButton("中止する") { _, _ -> MonitorService.send(this, MonitorService.ACTION_EXP_ABORT) }
                .setNegativeButton("続ける", null)
                .show()
        }
    }

    private fun renderHistory() {
        val list = ExperimentStore.history(prefs)
        val key = list.joinToString("|") { "${it.endedAt}" }
        if (key == historyKey) return
        historyKey = key
        historyBox.removeAllViews()
        if (list.isEmpty()) return
        val c = Ui.card(historyBox)
        Ui.heading(c, "これまでの結果")
        for (h in list) {
            Ui.text(c, "${Fmt.dateTime(h.endedAt).take(16)}  ${h.title}", 14f, bold = true)
            fun v(x: Double?) = x?.let { String.format(Locale.US, "%.0f %s", it, h.unit) } ?: Fmt.NA
            val saving = h.savingPct?.let { String.format(Locale.US, "（B は A より %+.1f%%）", -it) } ?: ""
            Ui.text(c, "A ${h.labelA}: ${v(h.avgA)} / B ${h.labelB}: ${v(h.avgB)}$saving", 13.5f)
            val color = when (h.decision) {
                Decision.ADOPT -> R.color.status_ok
                Decision.WORSE -> R.color.status_error
                else -> R.color.text_secondary
            }
            Ui.text(c, h.decision.label, 13.5f, color, bold = true)
            if (h.notes.isNotEmpty()) Ui.small(c, h.notes.joinToString("\n"))
            Ui.spacer(c, 8)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
