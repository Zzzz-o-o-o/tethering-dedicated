package local.tethering.dedicated

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.lang.reflect.InvocationTargetException

/**
 * Wi-Fi テザリングの開始・停止（仕様 F-37）。Android 10 には公開 API がないため、OS のシステム API をリフレクションで呼ぶ。
 * OS 側の権限チェックは、テザリングにキャリアの確認（プロビジョニング）が要らない端末では
 * 「システム設定の変更」（WRITE_SETTINGS）の許可で通る（Android 8〜10 の ConnectivityService の仕様）。
 * 使えるかは端末・OS のビルドで変わるので、[probe] で確かめてから使う。メインスレッドから呼ぶ。
 */
object TetherControl {
    /** ConnectivityManager.TETHERING_WIFI（非公開の定数） */
    private const val TETHERING_WIFI = 0

    enum class Reason(private val tx: Tx) {
        NO_PERMISSION(
            Tx(
                "「システム設定の変更」が許可されていません", "\"Modify system settings\" is not allowed",
                "« Modifier les paramètres système » n'est pas autorisé",
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

    fun permitted(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    /** 切り替えられるかを、テザリングを止めずに確かめる（OS の isTetheringSupported は権限も確認する） */
    fun probe(ctx: Context): Result {
        if (!permitted(ctx)) return Result(Reason.NO_PERMISSION)
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Result(Reason.ERROR, "no ConnectivityManager")
        return try {
            val supported = ConnectivityManager::class.java.getMethod("isTetheringSupported").invoke(cm) as Boolean
            if (supported) OK else Result(Reason.NOT_SUPPORTED)
        } catch (e: Throwable) {
            fail(e)
        }
    }

    fun stop(ctx: Context): Result {
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
     * 呼ぶのは ConnectivityManager.startTethering(int, boolean, OnStartTetheringCallback, Handler)
     */
    fun start(ctx: Context, done: (Result) -> Unit) {
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

    private fun fail(e: Throwable): Result {
        val c = (e as? InvocationTargetException)?.targetException ?: e
        return when (c) {
            is SecurityException -> Result(Reason.NO_PERMISSION, c.message?.take(80).orEmpty())
            is NoSuchMethodException, is NoSuchFieldException, is NoSuchMethodError, is ClassNotFoundException, is IllegalAccessException ->
                Result(Reason.BLOCKED, c.javaClass.simpleName)
            else -> Result(Reason.ERROR, c.javaClass.simpleName)
        }
    }
}
