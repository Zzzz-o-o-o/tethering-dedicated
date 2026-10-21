package local.tethering.dedicated

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.TetheringManager
import android.net.wifi.SoftApConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi テザリングの開始・停止（仕様 F-37）。Android 10 には公開 API がないため、OS のシステム API をリフレクションで呼ぶ。
 * OS 側の権限チェックは、テザリングにキャリアの確認（プロビジョニング）が要らない端末では
 * 「システム設定の変更」（WRITE_SETTINGS）の許可で通る（Android 8〜15 の仕様）。
 * Android 16 からは、この許可では通らない。端末の管理アプリ（[DeviceOwner]）のときだけ、Android 16 の公開 API（TetheringManager）に
 * アクセスポイントの設定（[HotspotConfig]）を付けて切り替える（止められるのは、このアプリが始めたテザリングだけ）。
 * 使えるかは端末・OS のビルドで変わるので、[probe] で確かめてから使う。メインスレッドから呼ぶ。
 */
object TetherControl {
    /** ConnectivityManager.TETHERING_WIFI（非公開の定数） */
    private const val TETHERING_WIFI = 0

    /** SoftApConfiguration.SECURITY_TYPE_WPA2_PSK（非公開の定数） */
    private const val SECURITY_TYPE_WPA2_PSK = 1

    /** TetheringManager の結果（TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION・TETHER_ERROR_UNKNOWN_REQUEST・TETHER_ERROR_DUPLICATE_REQUEST） */
    private const val ERR_NO_PERMISSION = 14
    private const val ERR_UNKNOWN_REQUEST = 17
    private const val ERR_DUPLICATE_REQUEST = 18

    /** Android 16 で止めるとき、OS の返事を待つ上限 */
    private const val STOP_WAIT_MS = 3_000L

    /** [permanent] は、やり直しても直らない理由（許可・設定が足りない） */
    enum class Reason(private val tx: Tx, val permanent: Boolean = false) {
        NO_PERMISSION(
            Tx(
                "「システム設定の変更」が許可されていません", "\"Modify system settings\" is not allowed",
                "« Modifier les paramètres système » n'est pas autorisé",
            ),
            permanent = true,
        ),
        /** Android 15 以前用の版を Android 16 に入れた（管理アプリの受け口がない） */
        WRONG_APK(
            Tx(
                "この APK は Android 16 では切り替えられません（Android 16 用の APK を入れてください）",
                "This APK can't switch the hotspot on Android 16 (install the APK for Android 16)",
                "Cet APK ne peut pas commander le point d'accès sur Android 16 (installez l'APK pour Android 16)",
            ),
            permanent = true,
        ),
        NOT_OWNER(
            Tx(
                "Android 16 では、このアプリを端末の管理アプリにしないと切り替えられません",
                "On Android 16, this app must be the device owner to switch the hotspot",
                "Sur Android 16, l'application doit être propriétaire de l'appareil pour commander le point d'accès",
            ),
            permanent = true,
        ),
        NO_CONFIG(
            Tx(
                "アクセスポイントの名前とパスワードが設定されていません", "The hotspot name and password aren't set",
                "Le nom et le mot de passe du point d'accès ne sont pas définis",
            ),
            permanent = true,
        ),
        NOT_STARTED_BY_APP(
            Tx(
                "手で ON にしたテザリングは、Android 16 ではアプリから止められません", "On Android 16, the app can't turn off a hotspot that was turned on by hand",
                "Sur Android 16, l'application ne peut pas arrêter un point d'accès activé à la main",
            ),
        ),
        BLOCKED(Tx("OS の制限で、アプリからは呼べません", "Blocked by the system for apps", "Bloqué par le système pour les applications")),
        NOT_SUPPORTED(
            Tx(
                "この端末・回線では、アプリからテザリングを切り替えられません", "This phone or line doesn't let apps switch the hotspot",
                "Ce téléphone ou cette ligne ne permet pas aux applications de commander le point d'accès",
            ),
        ),
        REFUSED(Tx("OS がテザリングの開始を断りました", "The system refused to start the hotspot", "Le système a refusé de démarrer le point d'accès")),
        ERROR(Tx("切り替えに失敗しました", "Switching failed", "La bascule a échoué"));

        val label: String get() = tx.get()
    }

    /** 失敗の理由。成功なら null */
    data class Result(val reason: Reason?, val detail: String = "") {
        val ok: Boolean get() = reason == null
        val text: String get() = reason?.let { r -> r.label + if (detail.isNotEmpty()) Fmt.paren(detail) else "" } ?: "OK"
    }

    private val OK = Result(null)

    fun permitted(ctx: Context): Boolean = missing(ctx) == null

    /** 切り替えに足りないもの。足りていれば null */
    fun missing(ctx: Context): Reason? = when {
        !DeviceOwner.required -> if (Settings.System.canWrite(ctx)) null else Reason.NO_PERMISSION
        // 管理アプリでなければ、受け口がない（Android 15 以前用の版）のか、まだ設定していないのかを分ける
        !DeviceOwner.isOwner(ctx) -> if (DeviceOwner.supported(ctx)) Reason.NOT_OWNER else Reason.WRONG_APK
        !HotspotConfig.load(ctx).valid -> Reason.NO_CONFIG
        else -> null
    }

    /** 切り替えに要るものの名前（画面の文に使う。Android 16 は端末の管理アプリ） */
    fun requirement(): String =
        if (DeviceOwner.required) {
            tr("端末の管理アプリの設定（Android 16）", "Device owner setup (Android 16)", "Configuration du propriétaire de l'appareil (Android 16)")
        } else {
            tr("「システム設定の変更」", "“Modify system settings”", "« Modifier les paramètres système »")
        }

    /** 足りないものを満たす画面を開く（Android 16 は「はじめの設定」、それより前は「システム設定の変更」の OS の画面） */
    fun openFix(a: Activity) {
        if (DeviceOwner.required) a.startActivity(Intent(a, SetupActivity::class.java)) else SystemScreens.writeSettings(a)
    }

    /** 切り替えられるかを、テザリングを止めずに確かめる（OS の isTetheringSupported は権限も確認する） */
    fun probe(ctx: Context): Result {
        missing(ctx)?.let { return Result(it) }
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Result(Reason.ERROR, "no ConnectivityManager")
        return try {
            val supported = ConnectivityManager::class.java.getMethod("isTetheringSupported").invoke(cm) as Boolean
            if (supported) OK else Result(Reason.NOT_SUPPORTED)
        } catch (e: Throwable) {
            fail(e)
        }
    }

    fun stop(ctx: Context): Result {
        if (DeviceOwner.required) return stopAsOwner(ctx)
        if (!permitted(ctx)) return Result(Reason.NO_PERMISSION)
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Result(Reason.ERROR, "no ConnectivityManager")
        return try {
            ConnectivityManager::class.java.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(cm, TETHERING_WIFI)
            OK
        } catch (e: Throwable) {
            fail(e)
        }
    }

    /**
     * 開始を依頼する。結果（OS の受け付け）は [done] にメインスレッドで返る。アクセスポイントが実際に ON になったかは放送で確かめる。
     * 呼ぶのは ConnectivityManager.startTethering(int, boolean, OnStartTetheringCallback, Handler)（Android 16 は [startAsOwner]）
     */
    fun start(ctx: Context, done: (Result) -> Unit) {
        if (DeviceOwner.required) return startAsOwner(ctx, done)
        if (!permitted(ctx)) return done(Result(Reason.NO_PERMISSION))
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return done(Result(Reason.ERROR, "no ConnectivityManager"))
        val main = Handler(Looper.getMainLooper())
        try {
            val cb = TetherStartCallback { started -> done(if (started) OK else Result(Reason.REFUSED)) }
            ConnectivityManager::class.java.getMethod(
                "startTethering", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                TetherStartCallback::class.java.superclass, Handler::class.java,
            ).invoke(cm, TETHERING_WIFI, false, cb, main)
        } catch (e: Throwable) {
            done(fail(e))
        }
    }

    /** Android 16：管理アプリとして、アクセスポイントの設定を付けた要求で始める（管理アプリでない・設定がないときは理由を返す） */
    private fun startAsOwner(ctx: Context, done: (Result) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return done(Result(Reason.ERROR))
        missing(ctx)?.let { return done(Result(it)) }
        val tm = ctx.getSystemService(TetheringManager::class.java) ?: return done(Result(Reason.ERROR, "no TetheringManager"))
        try {
            val req = ownerRequest(HotspotConfig.load(ctx)) ?: return done(Result(Reason.ERROR))
            tm.startTethering(
                req, ctx.mainExecutor,
                object : TetheringManager.StartTetheringCallback {
                    override fun onTetheringStarted() = done(OK)
                    override fun onTetheringFailed(error: Int) = done(ownerFailure(error, stopping = false))
                },
            )
        } catch (e: Throwable) {
            done(fail(e))
        }
    }

    /** Android 16：管理アプリとして止める。止められるのは、このアプリが始めたテザリングだけ（手で ON にしたものは断られる） */
    private fun stopAsOwner(ctx: Context): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return Result(Reason.ERROR)
        if (!DeviceOwner.isOwner(ctx)) return Result(if (DeviceOwner.supported(ctx)) Reason.NOT_OWNER else Reason.WRONG_APK)
        val tm = ctx.getSystemService(TetheringManager::class.java) ?: return Result(Reason.ERROR, "no TetheringManager")
        val result = AtomicReference(Result(Reason.ERROR, "timeout"))
        val latch = CountDownLatch(1)
        return try {
            // 止める要求にも設定が要る（中身は問わない。OS は種類だけで照合する。名前とパスワードがなければ空の設定）
            val req = ownerRequest(HotspotConfig.load(ctx).takeIf { it.valid }) ?: return Result(Reason.ERROR)
            // 返事は OS から別のスレッドで届くので、その場で受け取って待つ（メインスレッドで待っても届く）
            tm.stopTethering(
                req, Executor { it.run() },
                object : TetheringManager.StopTetheringCallback {
                    override fun onStopTetheringSucceeded() {
                        result.set(OK)
                        latch.countDown()
                    }

                    override fun onStopTetheringFailed(error: Int) {
                        result.set(ownerFailure(error, stopping = true))
                        latch.countDown()
                    }
                },
            )
            latch.await(STOP_WAIT_MS, TimeUnit.MILLISECONDS)
            result.get()
        } catch (e: Throwable) {
            fail(e)
        }
    }

    /**
     * Android 16：アクセスポイントの設定を付けた Wi-Fi テザリングの要求（管理アプリはこの形だけ通る）。
     * 名前・パスワード・自動 OFF を決める方法は公開の API にないので、OS のシステム API を呼ぶ（Android 16 では、アプリから呼べる扱い）。
     * setSoftApConfiguration は TETHER_PRIVILEGED（システムだけの権限）が要ると宣言されているが、OS は端末の管理アプリの要求も通す
     * （OS のテザリングの部分の isRequestAllowedForDOOrCarrierApp）。権限の確認は OS 側で行われ、通らなければ開始の失敗として返る
     */
    @SuppressLint("MissingPermission")
    private fun ownerRequest(c: HotspotConfig.Config?): TetheringManager.TetheringRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        val b = SoftApConfiguration.Builder()
        if (c != null) {
            val k = b.javaClass
            k.getMethod("setSsid", String::class.java).invoke(b, c.ssid)
            k.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType).invoke(b, c.pass, SECURITY_TYPE_WPA2_PSK)
            // テザリング専用機なので、接続がないときの自動 OFF はしない
            k.getMethod("setAutoShutdownEnabled", Boolean::class.javaPrimitiveType).invoke(b, false)
        }
        return TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).setSoftApConfiguration(b.build()).build()
    }

    private fun ownerFailure(code: Int, stopping: Boolean): Result = when {
        // すでに ON（このアプリが始めたテザリングが続いている）。ON にしたい状態にはなっている
        !stopping && code == ERR_DUPLICATE_REQUEST -> OK
        code == ERR_NO_PERMISSION -> Result(Reason.NOT_OWNER, "error $code")
        stopping && code == ERR_UNKNOWN_REQUEST -> Result(Reason.NOT_STARTED_BY_APP)
        stopping -> Result(Reason.ERROR, "error $code")
        else -> Result(Reason.REFUSED, "error $code")
    }

    private fun fail(e: Throwable): Result {
        val c = (e as? InvocationTargetException)?.targetException ?: e
        return when (c) {
            is SecurityException -> Result(if (DeviceOwner.required) Reason.NOT_OWNER else Reason.NO_PERMISSION, c.message?.take(80).orEmpty())
            is NoSuchMethodException, is NoSuchFieldException, is NoSuchMethodError, is ClassNotFoundException, is IllegalAccessException ->
                Result(Reason.BLOCKED, c.javaClass.simpleName)
            else -> Result(Reason.ERROR, c.javaClass.simpleName)
        }
    }
}
