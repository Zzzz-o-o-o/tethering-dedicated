package local.tethering.dedicated

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 端末の管理アプリ（デバイスオーナー）としてのテザリングの切り替え（v0.10.0 の Android 16 用の版。利用者の選択）。
 * Android 16 から、「システム設定の変更」の許可だけのアプリはテザリングを切り替えられない（OS のテザリングの部分が、
 * Android 16 以降なら常にそうする。これまでの版の APK も同じ）。管理アプリなら、アクセスポイントの設定（名前・パスワード）を
 * 付けた要求で開始でき、自分が始めたテザリングは止められる（利用者が手で ON にしたものは止められない）。
 * 管理アプリにするのは PC から（adb shell dpm set-device-owner …。そのときアカウントのない端末だけ）。やめるのはアプリから（[release]）
 */
object DeviceOwner {
    /** この OS では、テザリングの切り替えに管理アプリが要るか */
    val required: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /** 管理アプリの受け口（Android 16 用の版だけのマニフェストにある） */
    private const val ADMIN = ".AdminReceiver"

    /**
     * この版が管理アプリになれるか（受け口があるか）。受け口はロック解除前に動く部品ではないので、ロック解除前にも見つかるよう
     * MATCH_DIRECT_BOOT_UNAWARE を付けて探す（付けないと、ロック解除前は「ない」と返る）
     */
    @Suppress("DEPRECATION")
    fun supported(ctx: Context): Boolean = runCatching {
        val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        ctx.packageManager.getReceiverInfo(ComponentName(ctx.packageName, ctx.packageName + ADMIN), flags)
        true
    }.getOrDefault(false)

    fun isOwner(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(ctx.packageName) == true }.getOrDefault(false)

    /** PC で打つ命令（はじめの設定に出し、コピーできる） */
    fun command(ctx: Context): String = "adb shell dpm set-device-owner ${ctx.packageName}/$ADMIN"

    /**
     * 管理アプリをやめる（アンインストールできるようにする）。やめたら true。
     * clearDeviceOwnerApp は古い API だが、管理アプリが自分をやめる方法はこれだけ（ほかは端末の初期化）
     */
    @Suppress("DEPRECATION")
    fun release(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(DevicePolicyManager::class.java)?.clearDeviceOwnerApp(ctx.packageName)
        !isOwner(ctx)
    }.getOrDefault(false)
}

/**
 * 管理アプリとしてテザリングを始めるときの、アクセスポイントの名前とパスワード（Android 16 用の版）。
 * OS のアクセスポイントの設定はアプリから読めないので、利用者に入れてもらう（OS 側と同じにすれば、端末はそのままつながる）。
 * 起動時の ON はロック解除前に動くので、設定の写しを端末暗号化の領域に置く（設定画面の値は通常の領域。保存したら写す）。
 * 名前・パスワードは、ログ・設定のファイル保存・診断情報・管理画面には出さない
 */
object HotspotConfig {
    class Config(val ssid: String, val pass: String) {
        val valid: Boolean get() = check(ssid, pass) == null
    }

    private const val FILE = "hotspot_config"
    private const val KEY_SSID = "ssid"
    private const val KEY_PASS = "pass"

    private fun store(ctx: Context) = ctx.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 写しを読む（ロック解除前でも読める） */
    fun load(ctx: Context): Config = store(ctx).let { Config(it.getString(KEY_SSID, "").orEmpty(), it.getString(KEY_PASS, "").orEmpty()) }

    /** 設定の値を写す（設定を保存したとき・監視を始めたとき）。すぐ再起動しても読めるよう、すぐにファイルへ書く */
    @SuppressLint("ApplySharedPref")
    fun sync(ctx: Context, prefs: Prefs) {
        val ssid = prefs.text(Defs.AP_SSID)
        val pass = prefs.text(Defs.AP_PASS)
        val sp = store(ctx)
        if (sp.getString(KEY_SSID, "") != ssid || sp.getString(KEY_PASS, "") != pass) sp.edit().putString(KEY_SSID, ssid).putString(KEY_PASS, pass).commit()
    }

    /** 名前の誤り（正しければ null）。1〜32 バイト（UTF-8） */
    fun checkSsid(s: String): String? = when {
        s.isEmpty() -> tr("名前が空です", "The name is empty", "Le nom est vide")
        s.toByteArray(Charsets.UTF_8).size > 32 -> tr("名前が長すぎます（32 バイトまで）", "The name is too long (32 bytes max)", "Le nom est trop long (32 octets max)")
        else -> null
    }

    /** パスワードの誤り（正しければ null）。8〜63 文字の半角英数字・記号（WPA2） */
    fun checkPass(s: String): String? = when {
        s.length !in 8..63 -> tr("パスワードは 8〜63 文字にしてください", "The password must be 8 to 63 characters", "Le mot de passe doit faire de 8 à 63 caractères")
        s.any { it.code !in 0x20..0x7e } -> tr(
            "パスワードには半角の英数字と記号だけを使ってください", "Use only ASCII letters, digits and symbols in the password",
            "Utilisez uniquement des lettres, chiffres et symboles ASCII dans le mot de passe",
        )
        else -> null
    }

    fun check(ssid: String, pass: String): String? = checkSsid(ssid) ?: checkPass(pass)
}
