package local.tethering.dedicated

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.InputType
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * 設置場所アシスト（仕様 F-29）。端末を動かしながら RSRP・SINR・バンドを 1 秒ごとに表示し、良し悪しを音の高さでも知らせる。
 * 場所ごとに 20 秒記録して比べ、いちばん良い置き場所を示す。「ここに設置した」場所は、運用中の電波の悪化を判定する基準になる。
 * この画面を開いている間だけ動く（閉じれば読み取りも音も止まる）。
 */
class PlacementActivity : Activity() {
    companion object {
        /** この画面を開いている（端末を動かしている）間は、電波の悪化の通知を出さない */
        @Volatile
        var visible = false

        private const val RECORD_SEC = 20
        private const val KEY_SOUND = "placement_sound"
    }

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var beeper: Beeper? = null
    private var resumed = false

    private lateinit var vScore: TextView
    private lateinit var vGrade: TextView
    private lateinit var meter: ProgressBar
    private lateinit var vRsrp: TextView
    private lateinit var vSinr: TextView
    private lateinit var vRsrq: TextView
    private lateinit var vBand: TextView
    private lateinit var vType: TextView
    private lateinit var vUpdated: TextView
    private lateinit var sound: Switch
    private lateinit var nameField: EditText
    private lateinit var recordButton: Button
    private lateinit var vRecording: TextView
    private lateinit var listBox: LinearLayout

    private var recording: MutableList<SignalSample>? = null
    private var recordLeft = 0

    private val ticker = Runnable { tick() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        volumeControlStream = AudioManager.STREAM_MUSIC
        prefs = Prefs(this)
        val page = Ui.page(this)

        Ui.card(page).also { c ->
            vScore = Ui.text(c, "－", 40f, bold = true)
            vGrade = Ui.text(c, "読み取り中…", 16f, bold = true)
            meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            c.addView(meter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 18)))
            Ui.spacer(c, 6)
            vRsrp = Ui.kv(c, "RSRP（電波の強さ）")
            vSinr = Ui.kv(c, "SINR（雑音に対する品質）")
            vRsrq = Ui.kv(c, "RSRQ")
            vBand = Ui.kv(c, "バンド")
            vType = Ui.kv(c, "種別")
            vUpdated = Ui.kv(c, "更新")
            sound = Switch(this).apply {
                text = "音で知らせる（高い音ほど良い）"
                isChecked = getSharedPreferences("ui", MODE_PRIVATE).getBoolean(KEY_SOUND, true)
                setOnCheckedChangeListener { _, on -> getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean(KEY_SOUND, on).apply() }
            }
            c.addView(sound)
            Ui.small(c, "端末をゆっくり動かし、場所ごとに数秒止めて値が落ち着くのを待ってください。RSRP は −90 dBm より上なら良好、−110 dBm より下は弱い電波です。")
        }

        Ui.card(page).also { c ->
            Ui.heading(c, "この場所を記録")
            Ui.small(c, "${RECORD_SEC} 秒間の値の中央値を記録します。記録中は端末を動かさないでください。")
            nameField = Ui.textField(c, "場所${PlaceStore.list(prefs).size + 1}").apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "例: 窓際・棚の上"
            }
            recordButton = Ui.button(c, "この場所を記録（${RECORD_SEC}秒）") { startRecording() }
            vRecording = Ui.small(c, "")
        }

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(listBox)

        Ui.card(page).also { c ->
            Ui.heading(c, "置き場所の目安")
            Ui.small(
                c,
                "・電波が良いほど端末の送信電力が下がり、同じ通信でも消費と発熱が減ります。\n" +
                    "・窓際、高い位置、金属・水回り・家電から離れた場所が良いことが多いです。\n" +
                    "・バンド（周波数帯）が変わると RSRP の目安も変わるので、なるべく同じバンドどうしで比べてください。\n" +
                    "・「ここに設置した」を押すと、その記録より電波が悪くなったときに通知します（設定で変更できます）。",
            )
        }
        renderList()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        visible = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        beeper = Beeper()
        tick()
    }

    override fun onPause() {
        resumed = false
        visible = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.removeCallbacks(ticker)
        beeper?.close()
        beeper = null
        if (recording != null) {
            recording = null
            vRecording.text = "記録を中止しました（画面を離れたため）"
            recordButton.isEnabled = true
        }
        super.onPause()
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

    private fun tick() {
        main.removeCallbacks(ticker)
        if (!resumed) return
        bg.execute {
            val l = runCatching { LineProbe.read(this, null) }.getOrNull()
            main.post { if (resumed) show(l) }
        }
        main.postDelayed(ticker, 1_000L)
    }

    private fun show(l: LineInfo?) {
        val rsrp = l?.rsrp ?: l?.dbm
        val score = l?.let { SignalQuality.score(rsrp, it.sinr) }
        if (score == null) {
            vScore.text = "－"
            vGrade.text = if (l?.phonePermission == false) "「電話」の権限がないため読めません" else "電波の値を読めません（圏外・機内モードなど）"
            vGrade.setTextColor(getColor(R.color.status_off))
            meter.progress = 0
        } else {
            val g = SignalQuality.grade(score)
            vScore.text = "$score 点"
            vGrade.text = g.label
            val color = getColor(
                when (g) {
                    SignalQuality.Grade.EXCELLENT, SignalQuality.Grade.GOOD -> R.color.status_ok
                    SignalQuality.Grade.FAIR -> R.color.status_maint
                    SignalQuality.Grade.POOR, SignalQuality.Grade.BAD -> R.color.status_warn
                },
            )
            vScore.setTextColor(color)
            vGrade.setTextColor(color)
            meter.progress = score
            if (sound.isChecked) beeper?.beep(SignalQuality.toneHz(score))
        }
        vRsrp.text = l?.rsrp?.let { "$it dBm" } ?: l?.dbm?.let { "$it dBm（参考）" } ?: Fmt.NA
        vSinr.text = Fmt.db(l?.sinr)
        vRsrq.text = Fmt.num(l?.rsrq, " dB")
        vBand.text = l?.band?.let { b -> b + (l.earfcn?.let { " · EARFCN $it" } ?: "") } ?: Fmt.NA
        vType.text = l?.signalType ?: Fmt.NA
        vUpdated.text = Fmt.time(System.currentTimeMillis())

        val rec = recording ?: return
        if (l != null) rec.add(SignalSample(l.rsrp, l.sinr, l.rsrq, l.band))
        recordLeft--
        vRecording.text = "記録中… あと ${recordLeft.coerceAtLeast(0)} 秒（${rec.count { it.rsrp != null }} 回分）"
        if (recordLeft <= 0) finishRecording(rec)
    }

    private fun startRecording() {
        if (recording != null) return
        recording = ArrayList()
        recordLeft = RECORD_SEC
        recordButton.isEnabled = false
        vRecording.text = "記録中… あと ${RECORD_SEC} 秒"
    }

    private fun finishRecording(samples: List<SignalSample>) {
        recording = null
        recordButton.isEnabled = true
        val p = Places.summarize(nameField.text.toString(), System.currentTimeMillis(), samples)
        if (p == null) {
            vRecording.text = "電波の値を十分に読めなかったため、記録しませんでした"
            return
        }
        PlaceStore.add(prefs, p)
        LogStore.write(this, Ev.PLACE, StateStore.state, result = "記録：${p.name}", detail = describe(p))
        vRecording.text = "記録しました：${p.name}（${describe(p)}）"
        nameField.setText("場所${PlaceStore.list(prefs).size + 1}")
        renderList()
    }

    private fun describe(p: Place): String = listOfNotNull(
        p.score?.let { "$it 点（${SignalQuality.grade(it).label}）" },
        p.rsrp?.let { "RSRP $it dBm" },
        p.sinr?.let { String.format(Locale.US, "SINR %.1f dB", it) },
        p.band,
    ).joinToString(" · ")

    private fun renderList() {
        listBox.removeAllViews()
        val list = PlaceStore.list(prefs).sortedByDescending { it.score ?: -1 }
        if (list.isEmpty()) return
        val best = Places.best(list)
        val base = PlaceStore.baseline(prefs)
        val c = Ui.card(listBox)
        Ui.heading(c, "記録した場所（良い順）")
        if (list.mapNotNull { it.band }.distinct().size > 1) {
            Ui.small(c, "バンドが違う記録があります。バンドによって RSRP の目安が違うので、参考として比べてください。")
        }
        if (best != null && base != null && best.at != base.at && best.rsrp != null && base.rsrp != null && best.rsrp - base.rsrp >= 3) {
            Ui.text(c, "💡「${best.name}」は今の設置場所（${base.name}）より RSRP が ${best.rsrp - base.rsrp} dB 良い記録です。", 13.5f, R.color.status_ok)
        }
        for (p in list) {
            Ui.spacer(c, 6)
            val marks = listOfNotNull(if (p.at == best?.at) "◎ おすすめ" else null, if (p.at == base?.at) "★ 設置場所" else null).joinToString("・")
            Ui.text(c, p.name + if (marks.isNotEmpty()) "　$marks" else "", 15f, bold = true)
            Ui.text(c, describe(p), 13.5f)
            Ui.small(c, "${Fmt.dateTime(p.at).take(16)} · ${p.samples} 回分の中央値")
            Ui.buttonRow(
                c,
                "ここに設置した" to { setBaseline(p) },
                "削除" to { confirmDelete(p) },
            )
        }
    }

    private fun setBaseline(p: Place) {
        PlaceStore.setBaseline(prefs, p.at)
        LogStore.write(this, Ev.PLACE, StateStore.state, result = "設置場所に設定：${p.name}", detail = describe(p))
        toast("「${p.name}」を設置場所にしました。この記録より電波が悪くなったら通知します")
        renderList()
    }

    private fun confirmDelete(p: Place) {
        AlertDialog.Builder(this)
            .setTitle("「${p.name}」の記録を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                PlaceStore.remove(prefs, p.at)
                renderList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** 短いビープ音（正弦波を AudioTrack で鳴らす。音のファイルは使わない） */
class Beeper {
    private val thread = HandlerThread("beeper").apply { start() }
    private val h = Handler(thread.looper)
    private val live = ArrayList<AudioTrack>()

    fun beep(hz: Int, ms: Int = 110) {
        h.post { play(hz, ms) }
    }

    fun close() {
        h.post {
            live.forEach { runCatching { it.release() } }
            live.clear()
            thread.quitSafely()
        }
    }

    private fun play(hz: Int, ms: Int) {
        val rate = 22_050
        val n = rate * ms / 1000
        val fade = rate / 100
        val pcm = ShortArray(n) { i ->
            val env = minOf(1.0, i / fade.toDouble(), (n - 1 - i) / fade.toDouble())
            (sin(2 * PI * hz * i / rate) * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build()
        }.getOrNull() ?: return
        live.add(track)
        runCatching {
            track.write(pcm, 0, n)
            track.play()
        }
        h.postDelayed({
            runCatching { track.release() }
            live.remove(track)
        }, ms + 300L)
    }
}
