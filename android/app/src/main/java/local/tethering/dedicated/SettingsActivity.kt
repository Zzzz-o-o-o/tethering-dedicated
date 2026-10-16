package local.tethering.dedicated

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 設定の編集・バックアップ／復元（仕様 F-21）と、変更前の値への巻き戻し（F-23）。
 * 保存・復元・初期化の直前の設定は files/settings_before_change.json に残し、「直前の設定に戻す」で戻せる。
 */
class SettingsActivity : Activity() {
    companion object {
        private const val REQ_SAVE = 1
        private const val REQ_OPEN = 2
        private const val BEFORE_FILE = "settings_before_change.json"
        private const val MAX_BACKUP_BYTES = 256 * 1024
    }

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val intFields = LinkedHashMap<IntDef, EditText>()
    private val boolFields = LinkedHashMap<BoolDef, Switch>()
    private val textFields = LinkedHashMap<TextDef, EditText>()
    private lateinit var logSize: TextView
    private lateinit var undoButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        val page = Ui.page(this)

        section(page, "チェックの間隔", listOf(Defs.INTERVAL_NORMAL, Defs.INTERVAL_SAVER, Defs.TIMEOUT), texts = listOf(Defs.RECHECK))
        section(page, "インターネット到達確認", listOf(Defs.EXPECT_CODE), bools = listOf(Defs.HTTP), texts = listOf(Defs.URL), boolsFirst = true)
        section(page, "テザリング", emptyList(), bools = listOf(Defs.TETHER_EXPECTED, Defs.CLIENTS))
        section(page, "温度・電池の警告", listOf(Defs.TEMP_WARN, Defs.TEMP_CRIT, Defs.THERMAL_WARN, Defs.BATT_LOW))
        section(page, "通知", emptyList(), bools = listOf(Defs.NOTIFY_ALERT, Defs.NOTIFY_RECOVERY, Defs.NOTIFY_BOOT)).also { c ->
            Ui.button(c, "通知の詳細（音・重要度）をOSの設定で変える") { SystemScreens.appNotifications(this) }
        }
        section(page, "起動後チェック", listOf(Defs.BOOT_DELAY))
        section(page, "ログ", listOf(Defs.LOG_DAYS, Defs.LOG_MB)).also { c ->
            logSize = Ui.small(c, "")
            Ui.button(c, "ログを消去") { confirmClearLogs() }
        }

        Ui.button(page, "保存") { save() }
        Ui.spacer(page, 10)

        Ui.card(page).also { c ->
            Ui.heading(c, "バックアップと復元")
            Ui.small(c, "設定（運用モードを含む）を JSON ファイルに書き出し、別の端末や再インストール後に読み込めます。ログや監視の状態は含みません。")
            Ui.button(c, "設定をファイルに保存") { saveToFile() }
            Ui.button(c, "ファイルから復元") { openFile() }
            undoButton = Ui.button(c, "直前の設定に戻す") { undo() }
            Ui.button(c, "初期値に戻す") { confirmReset() }
        }
        refreshFooter()
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

    private fun section(
        page: LinearLayout,
        title: String,
        ints: List<IntDef>,
        bools: List<BoolDef> = emptyList(),
        texts: List<TextDef> = emptyList(),
        boolsFirst: Boolean = false,
    ): LinearLayout {
        val c = Ui.card(page)
        Ui.heading(c, title)
        if (boolsFirst) bools.forEach { addBool(c, it) }
        for (d in ints) {
            Ui.text(c, "${d.label}（${d.min}〜${d.max}${d.unit}）", 14f)
            if (d.help.isNotEmpty()) Ui.small(c, d.help)
            intFields[d] = Ui.numberField(c, prefs.int(d))
        }
        for (d in texts) {
            Ui.text(c, d.label, 14f)
            Ui.small(c, d.help)
            textFields[d] = Ui.textField(c, prefs.text(d))
        }
        if (!boolsFirst) bools.forEach { addBool(c, it) }
        return c
    }

    private fun addBool(c: LinearLayout, d: BoolDef) {
        val s = Switch(this).apply {
            text = d.label
            isChecked = prefs.bool(d)
        }
        c.addView(s)
        if (d.help.isNotEmpty()) Ui.small(c, d.help)
        boolFields[d] = s
    }

    private fun refreshFooter() {
        bg.execute {
            val bytes = LogStore.totalBytes(this)
            val hasBefore = File(filesDir, BEFORE_FILE).exists()
            main.post {
                logSize.text = "現在のログ: " + String.format(Locale.US, "%.1f KB", bytes / 1024.0)
                undoButton.isEnabled = hasBefore
            }
        }
    }

    // ---- 保存 ----

    private fun collect(): Pair<Map<String, Any>, List<String>> {
        val values = LinkedHashMap<String, Any>()
        val errors = ArrayList<String>()
        for ((d, e) in intFields) {
            val n = e.text.toString().trim().toIntOrNull()
            if (n == null || n !in d.min..d.max) errors.add("${d.label}: ${d.min}〜${d.max}${d.unit} で入力してください")
            else values[d.key] = n
        }
        for ((d, s) in boolFields) values[d.key] = s.isChecked
        for ((d, e) in textFields) {
            val v = e.text.toString().trim()
            val reason = d.check(v)
            if (reason != null) errors.add("${d.label}: $reason") else values[d.key] = v
        }
        return values to errors
    }

    private fun save() {
        val (values, errors) = collect()
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("入力を確認してください").setMessage(errors.joinToString("\n")).setPositiveButton("OK", null).show()
            return
        }
        val changes = SettingsCodec.describeChanges(prefs.values(), values)
        if (changes.isEmpty()) {
            toast("変更はありません")
            return
        }
        applyValues(values, Ev.SETTINGS, changes)
        toast("保存しました")
    }

    /** 変更前の設定を残してから反映する（巻き戻し用） */
    private fun applyValues(values: Map<String, Any>, event: String, changes: List<String>) {
        writeBefore()
        prefs.apply(values)
        LogStore.write(this, event, StateStore.state, result = "${changes.size}件", detail = changes.joinToString(" / "))
        notifyService()
        refreshFooter()
    }

    private fun writeBefore() {
        runCatching { File(filesDir, BEFORE_FILE).writeText(prefs.toJson().toString(2), Charsets.UTF_8) }
    }

    private fun notifyService() {
        if (prefs.monitoring) MonitorService.send(this, MonitorService.ACTION_SETTINGS)
    }

    // ---- バックアップ・復元 ----

    private fun saveToFile() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "tethering-settings-${Fmt.fileStamp(System.currentTimeMillis())}.json")
        launch(i, REQ_SAVE)
    }

    private fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        launch(i, REQ_OPEN)
    }

    private fun launch(i: Intent, req: Int) {
        try {
            startActivityForResult(i, req)
        } catch (_: ActivityNotFoundException) {
            toast("ファイルを選ぶ画面を開けませんでした")
        }
    }

    @Deprecated("Activity の旧 API（AndroidX を使わないため）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_SAVE -> bg.execute {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(prefs.toJson().toString(2).toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
                main.post { toast(if (ok) "設定を保存しました" else "保存に失敗しました") }
            }
            REQ_OPEN -> bg.execute {
                val parsed = runCatching { parseBackup(readText(uri)) }
                main.post {
                    parsed.onSuccess { confirmRestore(it, "ファイルから復元") }
                        .onFailure { toast("読み込めませんでした：${it.message ?: it.javaClass.simpleName}") }
                }
            }
        }
    }

    private fun readText(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val buf = input.readBytes()
            require(buf.size <= MAX_BACKUP_BYTES) { "ファイルが大きすぎます" }
            buf
        } ?: throw IllegalStateException("ファイルを開けません")
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun parseBackup(text: String): SettingsCodec.Parsed =
        SettingsCodec.parse(Prefs.readBackup(JSONObject(text), packageName))

    private fun confirmRestore(p: SettingsCodec.Parsed, title: String) {
        val changes = SettingsCodec.describeChanges(prefs.values(), p.values)
        val msg = buildString {
            if (changes.isEmpty()) append("現在の設定と同じです。") else append("次の${changes.size}件を変更します。\n\n" + changes.joinToString("\n"))
            if (p.errors.isNotEmpty()) append("\n\n採用しない値（範囲外など）:\n" + p.errors.joinToString("\n"))
            if (p.unknown.isNotEmpty()) append("\n\n不明な項目（無視）: " + p.unknown.joinToString(", "))
        }
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(msg).setNegativeButton("キャンセル", null)
        if (changes.isNotEmpty()) {
            b.setPositiveButton("変更する") { _, _ ->
                applyValues(p.values, Ev.RESTORE, changes)
                toast("反映しました")
                recreate()
            }
        }
        b.show()
    }

    private fun undo() {
        bg.execute {
            val parsed = runCatching { parseBackup(File(filesDir, BEFORE_FILE).readText(Charsets.UTF_8)) }
            main.post {
                parsed.onSuccess { confirmRestore(it, "直前の設定に戻す") }.onFailure { toast("戻せる設定がありません") }
            }
        }
    }

    private fun confirmReset() {
        val defaults = LinkedHashMap<String, Any>()
        defaults[Defs.MODE_KEY] = BaseMode.AUTO.key
        Defs.ints.forEach { defaults[it.key] = it.def }
        Defs.bools.forEach { defaults[it.key] = it.def }
        Defs.texts.forEach { defaults[it.key] = it.def }
        val changes = SettingsCodec.describeChanges(prefs.values(), defaults)
        if (changes.isEmpty()) {
            toast("すでに初期値です")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("初期値に戻す")
            .setMessage("次の${changes.size}件を初期値に戻します（「直前の設定に戻す」で取り消せます）。\n\n" + changes.joinToString("\n"))
            .setPositiveButton("戻す") { _, _ ->
                writeBefore()
                prefs.resetToDefaults()
                LogStore.write(this, Ev.SETTINGS, StateStore.state, result = "初期値に戻した", detail = changes.joinToString(" / "))
                notifyService()
                recreate()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("ログを消去しますか？")
            .setMessage("端末内のログ（CSV）をすべて削除します。必要ならメイン画面の「ログをCSVで保存」で先に書き出してください。")
            .setPositiveButton("消去する") { _, _ ->
                bg.execute {
                    runCatching { LogStore.clear(this) }
                    main.post {
                        LogStore.write(this, Ev.SETTINGS, StateStore.state, result = "ログを消去")
                        toast("ログを消去しました")
                        refreshFooter()
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
