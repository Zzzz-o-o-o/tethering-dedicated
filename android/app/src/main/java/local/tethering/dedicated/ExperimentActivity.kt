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
            Ui.text(
                c,
                tr(
                    "条件 A（今の設定）と条件 B（試す設定）を交互に計測し、平均消費と通信の安定性を比べます。",
                    "Measures condition A (current setting) and condition B (setting to test) alternately, and compares average consumption and connection stability.",
                    "Mesure alternativement la condition A (réglage actuel) et la condition B (réglage à tester), puis compare la consommation moyenne et la stabilité de la connexion.",
                ),
                13.5f,
            )
            val min = prefs.int(Defs.EXP_PHASE)
            val rep = prefs.int(Defs.EXP_REPEATS)
            val order = "A→B" + if (rep > 1) "→A→B…" else ""
            val total = Fmt.duration(min * rep * 2 * 60_000L)
            Ui.small(
                c,
                tr(
                    "・バッテリー駆動中の時間だけを数えます（給電中は消費を測れないため止まります）。\n" +
                        "・1 条件 $min 分 × $rep 回（$order）＝ バッテリー駆動で $total。設定で変えられます。\n" +
                        "・OS の設定は、案内に従って手動で切り替えます（「切り替えた」を押すまで計測しません）。このアプリのチェック間隔だけは自動で切り替えます。\n" +
                        "・効果がない・通信が悪化した場合は、元に戻すよう知らせます。テスト中は接続端末の使い方をできるだけ同じにしてください。",
                    "• Only time on battery counts (measuring pauses while charging, since consumption can't be measured then).\n" +
                        "• $min min per condition × $rep ($order) = $total on battery. You can change this in Settings.\n" +
                        "• You switch system settings by hand as instructed (nothing is measured until you tap “Switched”). Only this app's check interval is switched automatically.\n" +
                        "• If there's no effect or the connection gets worse, you're told to switch back. Keep the use of connected devices as similar as possible during the test.",
                    "• Seul le temps sur batterie compte (la mesure s'interrompt en charge, car la consommation ne peut alors pas être mesurée).\n" +
                        "• $min min par condition × $rep ($order) = $total sur batterie. Modifiable dans les paramètres.\n" +
                        "• Vous changez les réglages du système à la main selon les instructions (rien n'est mesuré avant d'avoir touché « C'est fait »). Seul l'intervalle de vérification de cette application change automatiquement.\n" +
                        "• S'il n'y a pas d'effet ou si la connexion se dégrade, il vous est demandé de revenir en arrière. Pendant le test, utilisez les appareils connectés de façon aussi semblable que possible.",
                ),
            )
        }
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(statusBox)

        startBox = Ui.card(page)
        Ui.heading(startBox, tr("試す変更", "Change to test", "Changement à tester"))
        group = RadioGroup(this)
        for (cand in Candidates.all) {
            val a = cand.labelA
            val b = cand.labelB
            val rb = RadioButton(this).apply { text = cand.title + "\n" + tr("（A: $a → B: $b）", "(A: $a → B: $b)", "(A : $a → B : $b)") }
            group.addView(rb)
            ids[rb.id] = cand.key
        }
        group.setOnCheckedChangeListener { _, id -> customBox.visibility = if (ids[id] == Candidates.KEY_CUSTOM) View.VISIBLE else View.GONE }
        startBox.addView(group)
        customBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        Ui.small(customBox, tr("条件 A（今の設定）の名前", "Name of condition A (current setting)", "Nom de la condition A (réglage actuel)"))
        customA = Ui.textField(customBox, "")
        Ui.small(customBox, tr("条件 B（試す設定）の名前", "Name of condition B (setting to test)", "Nom de la condition B (réglage à tester)"))
        customB = Ui.textField(customBox, "")
        startBox.addView(customBox)
        Ui.button(startBox, tr("比較テストを開始", "Start comparison test", "Démarrer le test comparatif")) { start() }

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
            toast(tr("監視を開始すると実行できます", "Start monitoring first", "Démarrez d'abord la surveillance"))
            return
        }
        val key = ids[group.checkedRadioButtonId] ?: run {
            toast(tr("試す変更を選んでください", "Choose a change to test", "Choisissez un changement à tester"))
            return
        }
        val cand = Candidates.of(key) ?: return
        val onBattery = StateStore.state.power?.plug == Plug.NONE
        AlertDialog.Builder(this)
            .setTitle(tr("比較テストを開始しますか？", "Start the comparison test?", "Démarrer le test comparatif ?"))
            .setMessage(
                cand.title + "\n\n" + tr(
                    "まず今の設定（A）を計測します。B に切り替える時が来たら通知で知らせます。",
                    "The current setting (A) is measured first. You'll be notified when it's time to switch to B.",
                    "Le réglage actuel (A) est mesuré en premier. Une notification vous indiquera quand passer à B.",
                ) + if (onBattery) {
                    ""
                } else {
                    "\n\n" + tr(
                        "今は給電中のため、給電を外すと計測が始まります。", "The phone is charging now; measuring starts when you unplug it.",
                        "Le téléphone est en charge : la mesure commencera quand vous le débrancherez.",
                    )
                },
            )
            .setPositiveButton(tr("開始", "Start", "Démarrer")) { _, _ ->
                MonitorService.startExperiment(this, key, customA.text.toString(), customB.text.toString())
                toast(tr("開始しました", "Started", "Démarré"))
            }
            .setNegativeButton(Ui.cancel(), null)
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
        Ui.heading(c, e.title.let { tr("実行中：$it", "Running: $it", "En cours : $it") })
        val label = if (e.condition == 'B') e.labelB else e.labelA
        val cond = e.condition
        val step = "${e.index + 1}/${e.total}"
        Ui.text(c, tr("条件 $cond（$label）· $step", "Condition $cond ($label) · $step", "Condition $cond ($label) · $step"), 15f, bold = true)
        if (e.waitingSwitch) {
            val how = e.instruction
            Ui.text(c, tr("⚠ 条件 $cond に切り替えてください：$how", "⚠ Switch to condition $cond: $how", "⚠ Passez à la condition $cond : $how"), 14f, R.color.status_warn)
            Ui.button(c, tr("切り替えた", "Switched", "C'est fait")) { MonitorService.send(this, MonitorService.ACTION_EXP_CONFIRM) }
        } else {
            val m = e.measuredSec / 60
            val p = e.phaseSec / 60
            Ui.text(c, tr("計測 $m / $p 分（バッテリー駆動中の時間）", "Measured $m / $p min (time on battery)", "Mesuré $m / $p min (temps sur batterie)"), 14f)
            if (!onBattery) {
                Ui.small(
                    c,
                    tr(
                        "給電中のため計測を止めています。給電を外すと再開します。", "Measuring is paused while charging. It resumes when you unplug.",
                        "Mesure suspendue pendant la charge. Elle reprend quand vous débranchez.",
                    ),
                )
            }
        }
        Ui.button(c, tr("中止して元に戻す", "Stop and revert", "Arrêter et rétablir")) {
            val a = e.labelA
            AlertDialog.Builder(this)
                .setTitle(tr("比較テストを中止しますか？", "Stop the comparison test?", "Arrêter le test comparatif ?"))
                .setMessage(
                    if (e.auto) {
                        tr("チェック間隔は自動で元に戻します。", "The check interval is restored automatically.", "L'intervalle de vérification est rétabli automatiquement.")
                    } else {
                        tr("中止したら、設定を元（$a）に戻してください。", "After stopping, switch your setting back to “$a”.", "Après l'arrêt, remettez votre réglage sur « $a ».")
                    },
                )
                .setPositiveButton(tr("中止する", "Stop", "Arrêter")) { _, _ -> MonitorService.send(this, MonitorService.ACTION_EXP_ABORT) }
                .setNegativeButton(tr("続ける", "Continue", "Continuer"), null)
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
        Ui.heading(c, tr("これまでの結果", "Past results", "Résultats précédents"))
        for (h in list) {
            Ui.text(c, "${Fmt.dateTime(h.endedAt).take(16)}  ${h.title}", 14f, bold = true)
            fun v(x: Double?) = x?.let { String.format(Locale.US, "%.0f %s", it, h.unit) } ?: Fmt.NA
            val saving = h.savingPct?.let {
                String.format(I18n.locale, tr("（B は A より %+.1f%%）", " (B vs A: %+.1f%%)", " (B par rapport à A : %+.1f %%)"), -it)
            } ?: ""
            val la = h.labelA
            val lb = h.labelB
            val va = v(h.avgA)
            val vb = v(h.avgB)
            Ui.text(c, tr("A $la: $va / B $lb: $vb", "A $la: $va / B $lb: $vb", "A $la : $va / B $lb : $vb") + saving, 13.5f)
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
