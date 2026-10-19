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
            vGrade = Ui.text(c, tr("読み取り中…", "Reading…", "Lecture…"), 16f, bold = true)
            meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            c.addView(meter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 18)))
            Ui.spacer(c, 6)
            vRsrp = Ui.kv(c, tr("RSRP（電波の強さ）", "RSRP (signal strength)", "RSRP (force du signal)"))
            vSinr = Ui.kv(c, tr("SINR（雑音に対する品質）", "SINR (quality vs noise)", "SINR (qualité par rapport au bruit)"))
            vRsrq = Ui.kv(c, "RSRQ")
            vBand = Ui.kv(c, tr("バンド", "Band", "Bande"))
            vType = Ui.kv(c, tr("種別", "Type", "Type"))
            vUpdated = Ui.kv(c, tr("更新", "Updated", "Mis à jour"))
            sound = Switch(this).apply {
                text = tr("音で知らせる（高い音ほど良い）", "Beep (higher pitch = better)", "Signal sonore (plus aigu = meilleur)")
                isChecked = getSharedPreferences("ui", MODE_PRIVATE).getBoolean(KEY_SOUND, true)
                setOnCheckedChangeListener { _, on -> getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean(KEY_SOUND, on).apply() }
            }
            c.addView(sound)
            Ui.small(
                c,
                tr(
                    "端末をゆっくり動かし、場所ごとに数秒止めて値が落ち着くのを待ってください。RSRP は −90 dBm より上なら良好、−110 dBm より下は弱い電波です。",
                    "Move the phone slowly and stop for a few seconds at each spot so the values settle. RSRP above −90 dBm is good; below −110 dBm is weak.",
                    "Déplacez le téléphone lentement et arrêtez-vous quelques secondes à chaque endroit pour que les valeurs se stabilisent. Un RSRP au-dessus de −90 dBm est bon ; en dessous de −110 dBm, il est faible.",
                ),
            )
        }

        Ui.card(page).also { c ->
            Ui.heading(c, tr("この場所を記録", "Record this spot", "Enregistrer cet emplacement"))
            Ui.small(
                c,
                tr(
                    "$RECORD_SEC 秒間の値の中央値を記録します。記録中は端末を動かさないでください。",
                    "Records the median of $RECORD_SEC seconds of values. Don't move the phone while recording.",
                    "Enregistre la médiane des valeurs sur $RECORD_SEC secondes. Ne bougez pas le téléphone pendant l'enregistrement.",
                ),
            )
            nameField = Ui.textField(c, defaultName()).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = tr("例: 窓際・棚の上", "e.g. by the window, top shelf", "ex. près de la fenêtre, sur l'étagère")
            }
            recordButton = Ui.button(
                c,
                tr("この場所を記録（${RECORD_SEC}秒）", "Record this spot ($RECORD_SEC s)", "Enregistrer cet emplacement ($RECORD_SEC s)"),
            ) { startRecording() }
            vRecording = Ui.small(c, "")
        }

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(listBox)

        Ui.card(page).also { c ->
            Ui.heading(c, tr("置き場所の目安", "Tips for placement", "Conseils d'emplacement"))
            Ui.small(
                c,
                tr(
                    "・電波が良いほど端末の送信電力が下がり、同じ通信でも消費と発熱が減ります。\n" +
                        "・窓際、高い位置、金属・水回り・家電から離れた場所が良いことが多いです。\n" +
                        "・バンド（周波数帯）が変わると RSRP の目安も変わるので、なるべく同じバンドどうしで比べてください。\n" +
                        "・「ここに設置した」を押すと、その記録より電波が悪くなったときに通知します（設定で変更できます）。",
                    "• The better the signal, the lower the phone's transmit power, so the same traffic uses less power and makes less heat.\n" +
                        "• Near a window, up high, and away from metal, water and appliances is often best.\n" +
                        "• RSRP levels differ between bands, so compare spots on the same band where possible.\n" +
                        "• Tap “Placed here” to be notified when the signal gets worse than that record (adjustable in Settings).",
                    "• Plus le signal est bon, plus la puissance d'émission du téléphone baisse : le même trafic consomme moins et chauffe moins.\n" +
                        "• Près d'une fenêtre, en hauteur, et loin du métal, de l'eau et des appareils électroménagers, c'est souvent mieux.\n" +
                        "• Les niveaux de RSRP diffèrent selon la bande : comparez si possible des emplacements sur la même bande.\n" +
                        "• Touchez « Installé ici » pour être alerté quand le signal devient moins bon que cet enregistrement (réglable dans les paramètres).",
                ),
            )
        }
        renderList()
    }

    private fun defaultName(): String = (PlaceStore.list(prefs).size + 1).let { tr("場所$it", "Spot $it", "Emplacement $it") }

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
            vRecording.text = tr("記録を中止しました（画面を離れたため）", "Recording stopped (you left the screen)", "Enregistrement arrêté (vous avez quitté l'écran)")
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
            vGrade.text = if (l?.phonePermission == false) {
                tr("「電話」の権限がないため読めません", "Can't read: no “Phone” permission", "Lecture impossible : pas d'autorisation « Téléphone »")
            } else {
                tr("電波の値を読めません（圏外・機内モードなど）", "Can't read the signal (no service, airplane mode…)", "Impossible de lire le signal (hors service, mode avion…)")
            }
            vGrade.setTextColor(getColor(R.color.status_off))
            meter.progress = 0
        } else {
            val g = SignalQuality.grade(score)
            vScore.text = Fmt.points(score)
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
        vRsrp.text = l?.rsrp?.let { "$it dBm" } ?: l?.dbm?.let { "$it dBm" + Fmt.paren(tr("参考", "reference", "indicatif")) } ?: Fmt.NA
        vSinr.text = Fmt.db(l?.sinr)
        vRsrq.text = Fmt.num(l?.rsrq, " dB")
        vBand.text = l?.band?.let { b -> b + (l.earfcn?.let { " · EARFCN $it" } ?: "") } ?: Fmt.NA
        vType.text = l?.signalType ?: Fmt.NA
        vUpdated.text = Fmt.time(System.currentTimeMillis())

        val rec = recording ?: return
        if (l != null) rec.add(SignalSample(l.rsrp, l.sinr, l.rsrq, l.band))
        recordLeft--
        val left = recordLeft.coerceAtLeast(0)
        val n = rec.count { it.rsrp != null }
        vRecording.text = tr("記録中… あと $left 秒（$n 回分）", "Recording… $left s left ($n samples)", "Enregistrement… encore $left s ($n mesures)")
        if (recordLeft <= 0) finishRecording(rec)
    }

    private fun startRecording() {
        if (recording != null) return
        recording = ArrayList()
        recordLeft = RECORD_SEC
        recordButton.isEnabled = false
        vRecording.text = tr("記録中… あと $RECORD_SEC 秒", "Recording… $RECORD_SEC s left", "Enregistrement… encore $RECORD_SEC s")
    }

    private fun finishRecording(samples: List<SignalSample>) {
        recording = null
        recordButton.isEnabled = true
        val p = Places.summarize(nameField.text.toString(), System.currentTimeMillis(), samples)
        if (p == null) {
            vRecording.text = tr(
                "電波の値を十分に読めなかったため、記録しませんでした", "Not recorded: not enough signal readings",
                "Non enregistré : pas assez de mesures du signal",
            )
            return
        }
        PlaceStore.add(prefs, p)
        val name = p.name
        val d = describe(p)
        LogStore.write(this, Ev.PLACE, StateStore.state, result = tr("記録：$name", "Recorded: $name", "Enregistré : $name"), detail = d)
        vRecording.text = tr("記録しました：$name（$d）", "Recorded: $name ($d)", "Enregistré : $name ($d)")
        nameField.setText(defaultName())
        renderList()
    }

    private fun describe(p: Place): String = listOfNotNull(
        p.score?.let { Fmt.points(it) + Fmt.paren(SignalQuality.grade(it).label) },
        p.rsrp?.let { "RSRP $it dBm" },
        p.sinr?.let { "SINR ${Fmt.dec(it)} dB" },
        p.band,
    ).joinToString(" · ")

    private fun renderList() {
        listBox.removeAllViews()
        val list = PlaceStore.list(prefs).sortedByDescending { it.score ?: -1 }
        if (list.isEmpty()) return
        val best = Places.best(list)
        val base = PlaceStore.baseline(prefs)
        val c = Ui.card(listBox)
        Ui.heading(c, tr("記録した場所（良い順）", "Recorded spots (best first)", "Emplacements enregistrés (meilleurs d'abord)"))
        if (list.mapNotNull { it.band }.distinct().size > 1) {
            Ui.small(
                c,
                tr(
                    "バンドが違う記録があります。バンドによって RSRP の目安が違うので、参考として比べてください。",
                    "Some records are on different bands. RSRP levels differ by band, so compare them only as a guide.",
                    "Certains enregistrements sont sur des bandes différentes. Les niveaux de RSRP varient selon la bande : comparez-les à titre indicatif.",
                ),
            )
        }
        if (best != null && base != null && best.at != base.at && best.rsrp != null && base.rsrp != null && best.rsrp - base.rsrp >= 3) {
            val b = best.name
            val cur = base.name
            val d = best.rsrp - base.rsrp
            Ui.text(
                c,
                tr(
                    "💡「$b」は今の設置場所（$cur）より RSRP が $d dB 良い記録です。", "💡 “$b” recorded an RSRP $d dB better than the current spot ($cur).",
                    "💡 « $b » a un RSRP meilleur de $d dB que l'emplacement actuel ($cur).",
                ),
                13.5f, R.color.status_ok,
            )
        }
        for (p in list) {
            Ui.spacer(c, 6)
            val marks = Fmt.join(
                listOfNotNull(
                    if (p.at == best?.at) tr("◎ おすすめ", "◎ Recommended", "◎ Recommandé") else null,
                    if (p.at == base?.at) tr("★ 設置場所", "★ Placed here", "★ Installé ici") else null,
                ),
            )
            Ui.text(c, p.name + if (marks.isNotEmpty()) tr("　$marks", "  $marks", "  $marks") else "", 15f, bold = true)
            Ui.text(c, describe(p), 13.5f)
            val t = Fmt.dateTime(p.at).take(16)
            val n = p.samples
            Ui.small(c, tr("$t · $n 回分の中央値", "$t · median of $n samples", "$t · médiane de $n mesures"))
            Ui.buttonRow(
                c,
                tr("ここに設置した", "Placed here", "Installé ici") to { setBaseline(p) },
                tr("削除", "Delete", "Supprimer") to { confirmDelete(p) },
            )
        }
    }

    private fun setBaseline(p: Place) {
        PlaceStore.setBaseline(prefs, p.at)
        val name = p.name
        LogStore.write(
            this, Ev.PLACE, StateStore.state,
            result = tr("設置場所に設定：$name", "Set as placement: $name", "Défini comme emplacement : $name"), detail = describe(p),
        )
        toast(
            tr(
                "「$name」を設置場所にしました。この記録より電波が悪くなったら通知します",
                "“$name” is now the placement. You'll be notified if the signal gets worse than this record",
                "« $name » est maintenant l'emplacement. Vous serez alerté si le signal devient moins bon que cet enregistrement",
            ),
        )
        renderList()
    }

    private fun confirmDelete(p: Place) {
        val name = p.name
        AlertDialog.Builder(this)
            .setTitle(tr("「$name」の記録を削除しますか？", "Delete the record of “$name”?", "Supprimer l'enregistrement « $name » ?"))
            .setPositiveButton(tr("削除", "Delete", "Supprimer")) { _, _ ->
                PlaceStore.remove(prefs, p.at)
                renderList()
            }
            .setNegativeButton(Ui.cancel(), null)
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
