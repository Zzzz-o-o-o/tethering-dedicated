package local.tethering.dedicated

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem

/**
 * 接続端末の「従量制」設定ガイド（仕様 F-34）。接続端末の OS 更新・クラウド同期を止めて、テザリングの通信量と消費を減らす。
 * 内容はアプリの画面とローカル管理画面（/guide）で共通（接続端末のブラウザでそのまま読めるように）。
 */
object ClientGuide {
    class Section(val title: String, val steps: List<String>, val note: String? = null)

    const val INTRO =
        "接続端末（パソコン・スマホ・タブレット）の OS 更新・アプリの自動更新・写真やファイルのクラウド同期は、" +
            "テザリングの通信量と、この端末の電力消費のいちばん大きな原因になりがちです。" +
            "接続端末の側で、このテザリングを「従量制」（通信量に限りのある回線）として扱うと、大きな通信を後回しにしてくれます。"

    val sections = listOf(
        Section(
            "Windows 11",
            listOf(
                "設定 → ネットワークとインターネット → Wi-Fi → このテザリングのネットワーク（「○○のプロパティ」）を開く",
                "「従量制課金接続」を オン にする",
                "設定 → Windows Update → 詳細オプション →「従量制課金接続で更新プログラムをダウンロードする」が オフ であることを確認する",
            ),
            "OneDrive は従量制課金接続のあいだ、自動で同期を一時停止します。",
        ),
        Section(
            "Windows 10",
            listOf(
                "設定 → ネットワークとインターネット → Wi-Fi → 接続中のネットワーク名を選ぶ",
                "「従量制課金接続として設定する」を オン にする",
            ),
        ),
        Section(
            "Mac（macOS 13 Ventura 以降）",
            listOf(
                "システム設定 → Wi-Fi → 接続中のネットワークの「詳細…」を開く",
                "「省データモード」を オン にする",
            ),
            "写真の iCloud 同期・ソフトウェアの自動アップデートなどが抑えられます。",
        ),
        Section(
            "iPhone・iPad（iOS / iPadOS 13 以降）",
            listOf(
                "設定 → Wi-Fi → 接続中のネットワークの ⓘ を開く",
                "「省データモード」を オン にする",
            ),
            "写真の iCloud 同期・App の自動アップデート・Appのバックグラウンド更新が抑えられます。",
        ),
        Section(
            "Android",
            listOf(
                "設定 → ネットワークとインターネット → Wi-Fi（インターネット）→ このネットワークを開く",
                "「詳細設定」または「ネットワーク使用量」の「従量制」を「従量制として扱う」にする",
            ),
            "Android どうしのテザリングでは、自動で従量制として扱われることがあります。",
        ),
        Section(
            "ゲーム機・その他の機器",
            listOf(
                "自動ダウンロード・自動アップデートを オフ にするか、使う前に手動で更新する",
                "大きな更新は、別の固定回線や Wi-Fi のある場所で行う",
            ),
        ),
    )

    const val CHECK =
        "設定したあとは、このアプリの「通信量（時間帯別）」で、使っていない時間帯の通信が減ったかを確かめてください。"
}

/** 接続端末の「従量制」設定ガイドの画面（仕様 F-34） */
class ClientGuideActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        Ui.card(page).also { c ->
            Ui.text(c, ClientGuide.INTRO, 13.5f)
            Ui.spacer(c, 4)
            val url = StateStore.state.web?.url
            Ui.small(
                c,
                if (url != null) "このページは接続端末のブラウザでも開けます: ${url}guide" else "このページは、ローカル管理画面（テザリング ON のとき）の /guide でも開けます。",
            )
        }
        for (s in ClientGuide.sections) {
            val c = Ui.card(page)
            Ui.heading(c, s.title)
            s.steps.forEachIndexed { i, step -> Ui.text(c, "${i + 1}. $step", 14f) }
            s.note?.let {
                Ui.spacer(c, 4)
                Ui.small(c, it)
            }
        }
        Ui.card(page).also { c -> Ui.small(c, ClientGuide.CHECK) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
