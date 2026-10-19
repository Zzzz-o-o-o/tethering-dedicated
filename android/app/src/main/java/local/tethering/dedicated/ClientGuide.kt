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

    val INTRO: String
        get() = tr(
            "接続端末（パソコン・スマホ・タブレット）の OS 更新・アプリの自動更新・写真やファイルのクラウド同期は、" +
                "テザリングの通信量と、この端末の電力消費のいちばん大きな原因になりがちです。" +
                "接続端末の側で、このテザリングを「従量制」（通信量に限りのある回線）として扱うと、大きな通信を後回しにしてくれます。",
            "On connected devices (PCs, phones, tablets), system updates, automatic app updates and cloud sync of photos and files " +
                "tend to be the biggest cause of hotspot data use and of this phone's power consumption. " +
                "If the connected devices treat this hotspot as “metered” (a connection with limited data), they postpone large transfers.",
            "Sur les appareils connectés (ordinateurs, téléphones, tablettes), les mises à jour du système, les mises à jour automatiques des applications " +
                "et la synchronisation cloud des photos et des fichiers sont souvent la principale cause du trafic du point d'accès et de la consommation de ce téléphone. " +
                "Si les appareils connectés considèrent ce point d'accès comme une connexion « limitée » (données limitées), ils reportent les gros transferts.",
        )

    /** OS ごとの手順（メニュー名は各 OS のその言語の表記） */
    val sections: List<Section>
        get() = listOf(
            Section(
                "Windows 11",
                listOf(
                    tr(
                        "設定 → ネットワークとインターネット → Wi-Fi → このテザリングのネットワーク（「○○のプロパティ」）を開く",
                        "Settings → Network & internet → Wi-Fi → open this hotspot's network (“<name> properties”)",
                        "Paramètres → Réseau et Internet → Wi-Fi → ouvrez le réseau de ce point d'accès (« Propriétés de <nom> »)",
                    ),
                    tr("「従量制課金接続」を オン にする", "Turn on “Metered connection”", "Activez « Connexion limitée »"),
                    tr(
                        "設定 → Windows Update → 詳細オプション →「従量制課金接続で更新プログラムをダウンロードする」が オフ であることを確認する",
                        "Settings → Windows Update → Advanced options → make sure “Download updates over metered connections” is off",
                        "Paramètres → Windows Update → Options avancées → vérifiez que « Télécharger les mises à jour via les connexions limitées » est désactivé",
                    ),
                ),
                tr(
                    "OneDrive は従量制課金接続のあいだ、自動で同期を一時停止します。", "OneDrive pauses syncing automatically on a metered connection.",
                    "OneDrive suspend automatiquement la synchronisation sur une connexion limitée.",
                ),
            ),
            Section(
                "Windows 10",
                listOf(
                    tr(
                        "設定 → ネットワークとインターネット → Wi-Fi → 接続中のネットワーク名を選ぶ", "Settings → Network & Internet → Wi-Fi → select the connected network",
                        "Paramètres → Réseau et Internet → Wi-Fi → sélectionnez le réseau connecté",
                    ),
                    tr("「従量制課金接続として設定する」を オン にする", "Turn on “Set as metered connection”", "Activez « Définir comme connexion limitée »"),
                ),
            ),
            Section(
                tr("Mac（macOS 13 Ventura 以降）", "Mac (macOS 13 Ventura or later)", "Mac (macOS 13 Ventura ou ultérieur)"),
                listOf(
                    tr(
                        "システム設定 → Wi-Fi → 接続中のネットワークの「詳細…」を開く", "System Settings → Wi-Fi → open “Details…” of the connected network",
                        "Réglages Système → Wi-Fi → ouvrez « Détails… » du réseau connecté",
                    ),
                    lowData(),
                ),
                tr(
                    "写真の iCloud 同期・ソフトウェアの自動アップデートなどが抑えられます。",
                    "This holds back iCloud Photos sync, automatic software updates and more.",
                    "La synchronisation de Photos iCloud, les mises à jour logicielles automatiques, etc. sont limitées.",
                ),
            ),
            Section(
                tr("iPhone・iPad（iOS / iPadOS 13 以降）", "iPhone, iPad (iOS / iPadOS 13 or later)", "iPhone, iPad (iOS / iPadOS 13 ou ultérieur)"),
                listOf(
                    tr(
                        "設定 → Wi-Fi → 接続中のネットワークの ⓘ を開く", "Settings → Wi-Fi → tap ⓘ next to the connected network",
                        "Réglages → Wi-Fi → touchez ⓘ à côté du réseau connecté",
                    ),
                    lowData(),
                ),
                tr(
                    "写真の iCloud 同期・App の自動アップデート・Appのバックグラウンド更新が抑えられます。",
                    "This holds back iCloud Photos sync, automatic App updates and Background App Refresh.",
                    "La synchronisation de Photos iCloud, les mises à jour automatiques des apps et l'actualisation en arrière-plan sont limitées.",
                ),
            ),
            Section(
                "Android",
                listOf(
                    tr(
                        "設定 → ネットワークとインターネット → Wi-Fi（インターネット）→ このネットワークを開く",
                        "Settings → Network & internet → Wi-Fi (Internet) → open this network",
                        "Paramètres → Réseau et Internet → Wi-Fi (Internet) → ouvrez ce réseau",
                    ),
                    tr(
                        "「詳細設定」または「ネットワーク使用量」の「従量制」を「従量制として扱う」にする",
                        "Under “Advanced” or “Network usage”, set “Metered” to “Treat as metered”",
                        "Dans « Paramètres avancés » ou « Utilisation du réseau », réglez « Facturé à l'usage » sur « Considérer comme facturé à l'usage »",
                    ),
                ),
                tr(
                    "Android どうしのテザリングでは、自動で従量制として扱われることがあります。",
                    "When tethering between Android devices, the network may already be treated as metered automatically.",
                    "Entre appareils Android, le partage de connexion peut déjà être considéré automatiquement comme facturé à l'usage.",
                ),
            ),
            Section(
                tr("ゲーム機・その他の機器", "Game consoles and other devices", "Consoles de jeu et autres appareils"),
                listOf(
                    tr(
                        "自動ダウンロード・自動アップデートを オフ にするか、使う前に手動で更新する",
                        "Turn off automatic downloads and updates, or update manually before use",
                        "Désactivez les téléchargements et mises à jour automatiques, ou faites les mises à jour manuellement avant usage",
                    ),
                    tr(
                        "大きな更新は、別の固定回線や Wi-Fi のある場所で行う", "Do large updates where another fixed line or Wi-Fi is available",
                        "Faites les grosses mises à jour là où une autre connexion fixe ou un autre Wi-Fi est disponible",
                    ),
                ),
            ),
        )

    private fun lowData() = tr("「省データモード」を オン にする", "Turn on “Low Data Mode”", "Activez « Mode données faibles »")

    val CHECK: String
        get() = tr(
            "設定したあとは、このアプリの「通信量（時間帯別）」で、使っていない時間帯の通信が減ったかを確かめてください。",
            "Afterwards, check in this app's “Traffic by hour” that data use dropped during the hours the hotspot isn't used.",
            "Ensuite, vérifiez dans « Trafic par heure » de cette application que le trafic a baissé pendant les heures où le point d'accès n'est pas utilisé.",
        )
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
                if (url != null) {
                    tr(
                        "このページは接続端末のブラウザでも開けます: ${url}guide", "Connected devices can also open this page in a browser: ${url}guide",
                        "Les appareils connectés peuvent aussi ouvrir cette page dans un navigateur : ${url}guide",
                    )
                } else {
                    tr(
                        "このページは、ローカル管理画面（テザリング ON のとき）の /guide でも開けます。",
                        "This page is also available at /guide on the local web page (while the hotspot is on).",
                        "Cette page est aussi disponible à l'adresse /guide de la page web locale (quand le point d'accès est activé).",
                    )
                },
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
