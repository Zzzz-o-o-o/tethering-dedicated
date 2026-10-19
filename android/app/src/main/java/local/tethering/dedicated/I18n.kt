package local.tethering.dedicated

import android.content.res.Resources
import java.util.Locale

/** 表示の言語。端末の言語（の優先順）で日本語・フランス語が選ばれればそれ、ほかは英語 */
enum class Lang(val code: String) {
    JA("ja"),
    EN("en"),
    FR("fr"),
}

/**
 * 表示の言語の切り替え（日本語・英語・フランス語）。
 *
 * 画面はコードで組み立て、判定の多くは Android に依存しないオブジェクトにあるので、文字列リソースではなく
 * 使う場所に 3 か国語を並べる（[tr]・[Tx]）。端末の言語を変えたときも、プロセスを再起動せずに切り替わる。
 * ログ・電力の記録のうちアプリが読み戻す値（イベント名など）は、言語によらず同じ値で保存し、表示のときに訳す。
 */
object I18n {
    /** 今の言語。アプリの起動時と、端末の言語が変わったときに合わせる（単体テストでは日本語） */
    @Volatile
    var lang: Lang = Lang.JA

    /**
     * 文字列リソース（values / values-ja / values-fr）と同じ言語にする。リソースの選び方（端末の言語の優先順を見る）に任せるので、
     * アプリ名・通知チャンネル名と画面の言語が食い違わない
     */
    fun sync(res: Resources) {
        lang = Lang.entries.firstOrNull { it.code == res.getString(R.string.lang_code) } ?: Lang.EN
    }

    /** 数値の表示に使うロケール（フランス語は小数点がカンマ）。ログ・CSV に書く数値には使わない */
    val locale: Locale get() = when (lang) {
        Lang.JA -> Locale.JAPAN
        Lang.EN -> Locale.US
        Lang.FR -> Locale.FRANCE
    }

    /**
     * フランス語の約物の前後の空白を、改行しない空白にする（« » の内側、: ; ? ! % °C の前）。
     * 文中で « だけが行末に残ったり、: が行頭に来たりしないように
     */
    fun frenchSpacing(s: String): String {
        if (s.indexOf(' ') < 0) return s
        return s.replace("« ", "«\u00A0").replace(" »", "\u00A0»").replace(" :", "\u00A0:").replace(" ;", "\u00A0;")
            .replace(" ?", "\u00A0?").replace(" !", "\u00A0!").replace(" %", "\u00A0%").replace(" °C", "\u00A0°C")
    }
}

/** 今の言語の文字列 */
fun tr(ja: String, en: String, fr: String): String = when (I18n.lang) {
    Lang.JA -> ja
    Lang.EN -> en
    Lang.FR -> I18n.frenchSpacing(fr)
}

/** 表示のときに言語を選ぶ文字列。長く残るもの（設定項目の定義・列挙型の名前など）に使う */
class Tx(val ja: String, val en: String, val fr: String) {
    fun get(): String = tr(ja, en, fr)

    /** すべての言語の値（保存された文字列の照合に使う） */
    val all: Set<String> get() = setOf(ja, en, fr, I18n.frenchSpacing(fr))

    override fun toString(): String = get()
}
