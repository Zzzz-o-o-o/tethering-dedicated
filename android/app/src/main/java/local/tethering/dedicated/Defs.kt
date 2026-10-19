package local.tethering.dedicated

import java.net.URI

/** 数値の設定項目。[unit] は数字のあとにそのまま付ける（英語・フランス語は前に空白を含む） */
class IntDef(
    val key: String,
    private val name: Tx,
    val def: Int,
    val min: Int,
    val max: Int,
    private val unitTx: Tx? = null,
    private val helpTx: Tx? = null,
) {
    val label: String get() = name.get()
    val unit: String get() = unitTx?.get().orEmpty()
    val help: String get() = helpTx?.get().orEmpty()

    /** 入力できる範囲の表示（例: 5〜240分 / 5–240 min） */
    val range: String get() = tr("$min〜$max$unit", "$min–$max$unit", "$min–$max$unit")
}

/** ON/OFF の設定項目 */
class BoolDef(val key: String, private val name: Tx, val def: Boolean, private val helpTx: Tx? = null) {
    val label: String get() = name.get()
    val help: String get() = helpTx?.get().orEmpty()
}

/**
 * 文字列の設定項目。[check] は誤りの説明（正しければ null）を返す。
 * [secret] はトークン・パスワードを含みうる値（通知先の URL など）。設定のファイル保存・診断情報には含めない
 */
class TextDef(
    val key: String,
    private val name: Tx,
    val def: String,
    private val helpTx: Tx?,
    val check: (String) -> String?,
    val secret: Boolean = false,
) {
    val label: String get() = name.get()
    val help: String get() = helpTx?.get().orEmpty()
}

/** 設定の単位 */
private object U {
    val MIN = Tx("分", " min", " min")
    val SEC = Tx("秒", " s", " s")
    val CELSIUS = Tx("℃", "°C", " °C")
    val PCT = Tx("%", "%", " %")
    val DAYS = Tx("日", " days", " jours")
    val MB = Tx("MB", " MB", " Mo")
    val MB_H = Tx("MB/時", " MB/h", " Mo/h")
    val MAH = Tx("mAh", " mAh", " mAh")
    val TIMES = Tx("回", " times", " fois")
    val DBM = Tx("dBm", " dBm", " dBm")
    val DB = Tx("dB", " dB", " dB")
    val GB = Tx("GB", " GB", " Go")
    val DAY_OF_MONTH = Tx("日", "", "")
}

/**
 * 設定項目の一覧。設定画面・バックアップ／復元・入力チェックはすべてここを参照する。
 * 初期値は仕様「9. 実装前に決めること」への回答（developer-memo に根拠を記載）。
 */
object Defs {
    const val MODE_KEY = "mode"

    /** 運用モード（設定の [MODE_KEY]）の表示名 */
    val modeLabel: String get() = tr("運用モード", "Operating mode", "Mode de fonctionnement")

    val INTERVAL_NORMAL = IntDef(
        "interval_normal_min", Tx("通常時のチェック間隔", "Check interval (normal)", "Intervalle de vérification (normal)"), 15, 5, 240, U.MIN,
        Tx("給電中（自動）または「通常」のとき", "While charging (Auto) or in Normal mode", "En charge (mode Auto) ou en mode Normal"),
    )
    val INTERVAL_SAVER = IntDef(
        "interval_saver_min",
        Tx("省電力時のチェック間隔", "Check interval (power saving)", "Intervalle de vérification (économie d'énergie)"), 60, 15, 720, U.MIN,
        Tx(
            "バッテリー駆動中（自動）または「省電力」のとき", "On battery (Auto) or in Power saving mode",
            "Sur batterie (mode Auto) ou en mode Économie d'énergie",
        ),
    )
    val TIMEOUT = IntDef(
        "timeout_sec", Tx("チェック1段階あたりのタイムアウト", "Timeout per check step", "Délai d'attente par étape de vérification"), 10, 3, 60, U.SEC,
    )
    val EXPECT_CODE = IntDef(
        "expect_code",
        Tx("到達確認で期待するHTTPステータス", "Expected HTTP status for the reachability check", "Code HTTP attendu pour le test d'accès"),
        204, 100, 599, null, Tx("generate_204 なら 204", "204 for generate_204", "204 pour generate_204"),
    )
    val TEMP_WARN = IntDef(
        "temp_warn_c", Tx("電池温度の注意", "Battery temperature: high", "Température de la batterie : élevée"), 45, 30, 70, U.CELSIUS,
    )
    val TEMP_CRIT = IntDef(
        "temp_crit_c", Tx("電池温度の警告", "Battery temperature: very high", "Température de la batterie : très élevée"), 50, 30, 75, U.CELSIUS,
        Tx(
            "注意より低いときは注意と同じ値として扱う", "If set below “high”, the “high” value is used",
            "Si inférieure à « élevée », la valeur « élevée » est utilisée",
        ),
    )
    val THERMAL_WARN = IntDef(
        "thermal_warn", Tx("端末の熱状態の警告レベル", "Phone thermal alert level", "Niveau d'alerte thermique du téléphone"), 2, 1, 6, null,
        Tx(
            "1=軽度 2=中程度 3=重度 4=危険 5=緊急 6=停止寸前", "1=Light 2=Moderate 3=Severe 4=Critical 5=Emergency 6=About to shut down",
            "1=Léger 2=Modéré 3=Sévère 4=Critique 5=Urgence 6=Arrêt imminent",
        ),
    )
    val BATT_LOW = IntDef(
        "batt_low_pct", Tx("電池残量の警告（バッテリー駆動中）", "Low battery alert (on battery)", "Alerte batterie faible (sur batterie)"), 20, 5, 50, U.PCT,
    )
    val BOOT_DELAY = IntDef(
        "boot_delay_sec",
        Tx("起動後チェックまでの待ち時間", "Delay before the after-reboot check", "Délai avant la vérification après redémarrage"), 120, 30, 600, U.SEC,
        Tx(
            "回線とテザリングが落ち着くのを待つ", "Gives the line and hotspot time to settle",
            "Laisse à la ligne et au point d'accès le temps de se stabiliser",
        ),
    )
    val LOG_DAYS = IntDef("log_days", Tx("ログの保存期間", "Log retention", "Durée de conservation du journal"), 30, 1, 365, U.DAYS)
    val LOG_MB = IntDef("log_mb", Tx("ログの最大サイズ", "Maximum log size", "Taille maximale du journal"), 10, 1, 100, U.MB)

    // 第 1 段階（仕様 11.5 で決めること）
    val BATT_CAPACITY = IntDef(
        "battery_capacity_mah",
        Tx("電池容量（0 = 自動で推定）", "Battery capacity (0 = estimate automatically)", "Capacité de la batterie (0 = estimation automatique)"),
        0, 0, 30_000, U.MAH,
        Tx(
            "充電カウンタに対応した端末では自動で推定します。推定できない端末では仕様上の容量を入れてください",
            "Estimated automatically on phones with a charge counter. Otherwise, enter the rated capacity",
            "Estimée automatiquement sur les téléphones dotés d'un compteur de charge. Sinon, saisissez la capacité nominale",
        ),
    )
    val RUNTIME_ALERT = IntDef(
        "runtime_alert_min",
        Tx("推定残り時間の通知（0 = 通知しない）", "Estimated time left alert (0 = off)", "Alerte d'autonomie estimée (0 = désactivée)"),
        60, 0, 720, U.MIN,
        Tx(
            "バッテリー駆動中に、推定残り時間がこれを下回ったら通知します", "On battery, notifies when the estimated time left drops below this",
            "Sur batterie, alerte quand l'autonomie estimée passe sous cette valeur",
        ),
    )
    val EXP_PHASE = IntDef(
        "exp_phase_min",
        Tx("比較テストの 1 条件あたりの計測時間", "Comparison test: time per condition", "Test comparatif : durée par condition"), 30, 5, 240, U.MIN,
        Tx("バッテリー駆動中の時間だけを数えます", "Only time on battery is counted", "Seul le temps sur batterie est compté"),
    )
    val EXP_REPEATS = IntDef(
        "exp_repeats",
        Tx("比較テストの繰り返し（A→B を何回）", "Comparison test: repeats (A→B rounds)", "Test comparatif : répétitions (cycles A→B)"), 2, 1, 4, U.TIMES,
        Tx(
            "2 回以上なら、毎回 B が少ないときだけ「効果あり」にします", "With 2 or more, “Effective” only if B uses less every time",
            "À partir de 2, « Efficace » seulement si B consomme moins à chaque fois",
        ),
    )
    val EXP_MIN_GAIN = IntDef(
        "exp_min_gain_pct",
        Tx("「効果あり」とする最小の削減率", "Minimum saving to count as “Effective”", "Économie minimale pour « Efficace »"), 3, 1, 50, U.PCT,
    )

    // 第 2 段階
    val SIGNAL_WEAK_RSRP = IntDef(
        "signal_weak_rsrp", Tx("電波が弱いとみなす RSRP", "RSRP considered weak", "RSRP considéré comme faible"), -110, -140, -80, U.DBM,
        Tx(
            "定期チェックで 2 回続けてこれを下回ったら通知します", "Notifies when it's below this at 2 scheduled checks in a row",
            "Alerte s'il est en dessous lors de 2 vérifications planifiées consécutives",
        ),
    )
    val SIGNAL_DROP_DB = IntDef(
        "signal_drop_db",
        Tx(
            "設置時の記録からの悪化（0 = 比べない）", "Drop from the placement record (0 = don't compare)",
            "Baisse par rapport à l'emplacement enregistré (0 = ne pas comparer)",
        ),
        10, 0, 40, U.DB,
        Tx(
            "設置場所アシストで「ここに設置した」場所の RSRP より、これ以上悪い状態が 2 回続いたら通知します",
            "Notifies when RSRP is this much worse than at the spot marked “Placed here” in Placement assistant, twice in a row",
            "Alerte si le RSRP est inférieur d'au moins cette valeur à celui de l'emplacement marqué « Installé ici » dans l'assistant d'emplacement, 2 fois de suite",
        ),
    )
    val IDLE_TRAFFIC_MB = IntDef(
        "idle_traffic_mb_h",
        Tx("接続端末がないときの通信（0 = 通知しない）", "Data use with no devices connected (0 = off)", "Données utilisées sans appareil connecté (0 = désactivé)"),
        5, 0, 1000, U.MB_H,
        Tx(
            "テザリング OFF、またはチェック時点で接続 0 台なのに、これを超える通信があったら通知します（この端末自身のアプリ・OS の通信）",
            "Notifies when more than this is used while the hotspot is off or no devices are connected at check time (traffic of this phone's own apps and system)",
            "Alerte si plus que cette quantité est utilisée alors que le point d'accès est arrêté ou qu'aucun appareil n'est connecté lors de la vérification (trafic des applications et du système de ce téléphone)",
        ),
    )
    val QUIET_TRAFFIC_MB = IntDef(
        "quiet_traffic_mb",
        Tx("使わない時間帯の通信の合計（0 = 通知しない）", "Total data use during quiet hours (0 = off)", "Données utilisées pendant les heures d'inactivité (0 = désactivé)"),
        50, 0, 100_000, U.MB,
        Tx(
            "下の時間帯が終わったあと、その間の通信がこれを超えていたら知らせます（接続端末の自動更新・同期の発見用）",
            "When the quiet hours below end, notifies if more than this was used during them (to spot auto-updates and syncs on connected devices)",
            "À la fin des heures d'inactivité ci-dessous, alerte si plus que cette quantité a été utilisée (pour repérer les mises à jour et synchronisations automatiques des appareils connectés)",
        ),
    )
    val WEB_PORT = IntDef(
        "web_port", Tx("管理画面のポート", "Web page port", "Port de la page web"), 8080, 1024, 65535, null,
        Tx(
            "接続端末のブラウザで http://（この端末のアクセスポイントのアドレス）:ポート/ を開きます",
            "Open http://(this phone's hotspot address):port/ in a browser on a connected device",
            "Ouvrez http://(adresse du point d'accès de ce téléphone):port/ dans le navigateur d'un appareil connecté",
        ),
    )

    val SIGNAL_ALERT = BoolDef(
        "signal_alert", Tx("電波の悪化を通知する", "Notify about weak signal", "Alerter en cas de signal faible"), true,
        Tx(
            "定期チェックのときに判定します（判定のために端末を起こしません）", "Judged at scheduled checks (the phone isn't woken up for this)",
            "Évalué lors des vérifications planifiées (le téléphone n'est pas réveillé pour cela)",
        ),
    )
    val WEB = BoolDef(
        "web_enabled", Tx("ローカル管理画面（読み取り専用）", "Local web page (read-only)", "Page web locale (lecture seule)"), true,
        Tx(
            "テザリングのアクセスポイント側だけで待ち受けます。モバイル回線側からは見えません",
            "Listens only on the hotspot side. It can't be seen from the mobile network",
            "N'écoute que du côté du point d'accès. Invisible depuis le réseau mobile",
        ),
    )

    // 第 3 段階
    val AUTO_SAVER_PCT = IntDef(
        "auto_saver_pct",
        Tx(
            "自動で ON にする電池残量（100 = バッテリー駆動中は常に）", "Battery level to turn it on (100 = always on battery)",
            "Niveau de batterie pour l'activer (100 = toujours sur batterie)",
        ),
        100, 5, 100, U.PCT,
        Tx(
            "これ以下になったら ON にします（戻すのは 5% 上がったとき、または給電したとき）",
            "Turns on at or below this (restored when the level rises by 5% or when charging)",
            "S'active à ce niveau ou en dessous (rétabli quand le niveau remonte de 5 % ou en charge)",
        ),
    )
    val IDLE_INTERVAL = IntDef(
        "idle_interval_min",
        Tx("待機中のチェック間隔（0 = 使わない）", "Check interval when idle (0 = don't use)", "Intervalle de vérification en veille (0 = ne pas utiliser)"),
        180, 0, 720, U.MIN,
        Tx(
            "バッテリー駆動中に、接続 0 台が 30 分続いたとき、または下の残量以下のとき。状態の変化はこれまでどおりすぐ確認します",
            "On battery, after 30 minutes with no devices, or at or below the level below. State changes are still checked right away",
            "Sur batterie, après 30 minutes sans appareil connecté, ou au niveau ci-dessous ou moins. Les changements d'état sont toujours vérifiés immédiatement",
        ),
    )
    val IDLE_BATT = IntDef("idle_batt_pct", Tx("待機にする電池残量", "Battery level for idle mode", "Niveau de batterie pour la veille"), 30, 5, 100, U.PCT)

    val AUTO_SAVER = BoolDef(
        "auto_saver",
        Tx(
            "バッテリー駆動中はバッテリーセーバーを自動で ON", "Turn on Battery Saver automatically on battery",
            "Activer l'économiseur de batterie automatiquement sur batterie",
        ),
        false,
        Tx(
            "給電すると元に戻します。ADB で「設定変更の権限」を付けたときだけ動きます（データセーバー・機内モード・モバイルデータは変えません）",
            "Restored when charging. Works only after granting the settings permission via ADB (Data Saver, airplane mode and mobile data are never changed)",
            "Rétabli en charge. Fonctionne seulement après avoir accordé l'autorisation de modifier les paramètres via ADB (l'économiseur de données, le mode avion et les données mobiles ne sont jamais modifiés)",
        ),
    )
    val AUTO_DOZE = BoolDef(
        "auto_doze",
        Tx("バッテリー駆動中は Doze（省電力待機）を早める", "Enter Doze (deep sleep) sooner on battery", "Entrer plus tôt en Doze (veille profonde) sur batterie"),
        false,
        Tx(
            "画面消灯・静止から約 10 分で深い省電力待機に入るようにします（標準は約 1 時間）。給電すると元に戻します",
            "Enters deep sleep about 10 minutes after the screen turns off with the phone still (normally about 1 hour). Restored when charging",
            "Passe en veille profonde environ 10 minutes après l'extinction de l'écran, téléphone immobile (normalement environ 1 heure). Rétabli en charge",
        ),
    )
    val SKIP_PROBE = BoolDef(
        "skip_probe_busy",
        Tx(
            "接続端末の通信が流れているときは到達確認を省く", "Skip the reachability check while devices are using data",
            "Ignorer le test d'accès quand des appareils utilisent des données",
        ),
        true,
        Tx(
            "OS の接続検証が OK で、前回のチェックから 256 KB 以上受信していれば、DNS・HTTP の確認を省きます",
            "If the system's connection validation is OK and at least 256 KB was received since the last check, the DNS and HTTP checks are skipped",
            "Si la validation de connexion du système est OK et qu'au moins 256 Ko ont été reçus depuis la dernière vérification, les tests DNS et HTTP sont ignorés",
        ),
    )

    // 第 4 段階
    val PLUG_LOW = IntDef("plug_low_pct", Tx("給電を始める電池残量", "Battery level to start charging", "Niveau de batterie pour démarrer la charge"), 40, 10, 90, U.PCT)
    val PLUG_HIGH = IntDef(
        "plug_high_pct", Tx("給電を止める電池残量", "Battery level to stop charging", "Niveau de batterie pour arrêter la charge"), 80, 20, 100, U.PCT,
        Tx(
            "給電を始める残量より 10% 以上高くしてください（近すぎるときは +10% として扱います）",
            "Set it at least 10% above the start level (if closer, start + 10% is used)",
            "Au moins 10 % au-dessus du niveau de démarrage (si plus proche, démarrage + 10 % est utilisé)",
        ),
    )
    val PLUG_HOT = IntDef(
        "plug_hot_c",
        Tx("給電を止める電池温度", "Battery temperature to stop charging", "Température de batterie pour arrêter la charge"), 40, 30, 60, U.CELSIUS,
        Tx(
            "これ以上になったら給電を止め、3℃ 下がるまで再開しません。ただし残量が「電池残量の警告」以下なら、「電池温度の警告」未満のあいだは給電します",
            "Stops charging at or above this and resumes only after it drops 3°C. But at or below “Low battery alert”, it keeps charging while below “Battery temperature: very high”",
            "Arrête la charge à partir de cette valeur et ne reprend qu'après une baisse de 3 °C. Mais au niveau « Alerte batterie faible » ou moins, la charge continue tant que la température reste sous « Température de la batterie : très élevée »",
        ),
    )
    val PLUG_FLIPBACK = IntDef(
        "plug_flipback_min",
        Tx(
            "OFF のあと自動で ON に戻す時間（Shelly のみ・0 = 使わない）", "Turn back ON automatically after OFF (Shelly only, 0 = don't use)",
            "Rallumage automatique après OFF (Shelly uniquement, 0 = ne pas utiliser)",
        ),
        240, 0, 1440, U.MIN,
        Tx(
            "このアプリや端末が止まっても電池切れにならないよう、プラグ自身のタイマーで ON に戻します。動いている間はタイマーを延長し続けます",
            "The plug's own timer turns it back on so the battery doesn't run out if this app or phone stops. While running, the app keeps extending the timer",
            "Le minuteur de la prise la rallume pour que la batterie ne se vide pas si cette application ou le téléphone s'arrête. Tant qu'elle fonctionne, l'application prolonge le minuteur",
        ),
    )

    val TETHER_SCHEDULE = BoolDef(
        "tether_schedule",
        Tx("使わない時間帯はテザリングを止める", "Turn off the hotspot during quiet hours", "Arrêter le point d'accès pendant les heures d'inactivité"), false,
        Tx(
            "上の時間帯に入ったら、接続 0 台が 10 分続いたときにテザリングを止め、時間帯の終わりに再開します（このアプリが止めたときだけ）。「システム設定の変更」の許可が必要です",
            "During the quiet hours above, turns the hotspot off after 10 minutes with no devices and back on when they end (only if this app turned it off). Requires the “Modify system settings” permission",
            "Pendant les heures d'inactivité ci-dessus, arrête le point d'accès après 10 minutes sans appareil et le redémarre à la fin (seulement si cette application l'a arrêté). Nécessite l'autorisation « Modifier les paramètres système »",
        ),
    )
    val EXT_NOTIFY = BoolDef(
        "ext_notify",
        Tx("外部へ通知する（ntfy・Discord・Slack）", "Send notifications externally (ntfy, Discord, Slack)", "Envoyer les notifications à l'extérieur (ntfy, Discord, Slack)"),
        false,
        Tx(
            "復旧（止まっていた時間）や警告を、下の通知先にも送ります。送るのは状態だけで、IP アドレス・SSID・接続端末の情報は送りません",
            "Also sends recoveries (with downtime) and alerts to the destination below. Only status is sent — never IP addresses, SSID or device details",
            "Envoie aussi les rétablissements (avec la durée de coupure) et les alertes vers la destination ci-dessous. Seul l'état est envoyé — jamais d'adresse IP, de SSID ni d'informations sur les appareils",
        ),
    )
    val EXT_RECOVERY = BoolDef(
        "ext_recovery", Tx("復旧と再起動を送る", "Send recoveries and reboots", "Envoyer les rétablissements et redémarrages"), true,
        Tx(
            "回線・テザリングが止まっていた時間、起動後チェックの結果", "How long the line or hotspot was down, and after-reboot check results",
            "Durée de coupure de la ligne ou du point d'accès, et résultats des vérifications après redémarrage",
        ),
    )
    val EXT_ALERT = BoolDef(
        "ext_alert", Tx("警告を送る", "Send alerts", "Envoyer les alertes"), true,
        Tx(
            "温度・電池・電波の警告、テザリングの停止、スケジュール・スマートプラグの失敗",
            "Temperature, battery and signal alerts, hotspot stops, schedule and smart plug failures",
            "Alertes de température, de batterie et de signal, arrêts du point d'accès, échecs de la planification et de la prise connectée",
        ),
    )
    val PLUG = BoolDef(
        "plug", Tx("スマートプラグで給電を切り替える", "Control charging with a smart plug", "Piloter la charge avec une prise connectée"), false,
        Tx(
            "電池残量が下の範囲に収まるよう、プラグを ON／OFF します（充電上限のない端末の代わり）。高温のときは給電を止めます",
            "Switches the plug on and off to keep the battery level within the range below (for phones without a charge limit). Stops charging when hot",
            "Allume et éteint la prise pour garder le niveau de batterie dans la plage ci-dessous (pour les téléphones sans limite de charge). Arrête la charge en cas de chaleur",
        ),
    )

    val HTTP = BoolDef(
        "http_check", Tx("外部サイトへの到達確認（HTTP）", "Reachability check to an external site (HTTP)", "Test d'accès à un site externe (HTTP)"), true,
        Tx("OFFにするとDNSまでの確認で判定する", "When off, checking stops at DNS", "Désactivé, la vérification s'arrête au DNS"),
    )
    val TETHER_EXPECTED = BoolDef(
        "tether_expected",
        Tx("Wi-Fiテザリングの停止を異常とする", "Treat a stopped Wi-Fi hotspot as an error", "Considérer l'arrêt du point d'accès Wi-Fi comme une erreur"), true,
    )
    val CLIENTS = BoolDef(
        "count_clients",
        Tx("接続端末数を数える（取得できる機種のみ）", "Count connected devices (supported phones only)", "Compter les appareils connectés (téléphones compatibles)"),
        true,
        Tx(
            "MACアドレスなどは記録せず、台数だけを数える", "Only the number is counted; MAC addresses etc. are not recorded",
            "Seul le nombre est compté ; les adresses MAC, etc. ne sont pas enregistrées",
        ),
    )
    /** 月の通信量の上限（v0.8.0。DataCap） */
    val DATA_CAP = BoolDef(
        "data_cap",
        Tx("月の通信量の上限を使う", "Use a monthly data cap", "Utiliser un plafond de données mensuel"), false,
        Tx(
            "今月のモバイル通信量（テザリングの分を含む。OS の「データ使用量」と同じ数え方）が上限の 80% と 100% になったら知らせます。" +
                "「使用状況へのアクセス」の許可が必要です。OS の「データ上限」（モバイルデータそのものを止める）とは別の機能です",
            "Notifies when this month's mobile data (including the hotspot, counted like the system's “Data usage”) reaches 80% and 100% of the cap. " +
                "Requires “Usage access”. This is separate from the system's “Data limit”, which turns off mobile data itself",
            "Avertit quand les données mobiles du mois (point d'accès compris, comptées comme dans « Consommation des données » du système) atteignent 80 % et 100 % du plafond. " +
                "Nécessite « Accès aux données d'utilisation ». C'est distinct de la « Limite de données » du système, qui coupe les données mobiles elles-mêmes",
        ),
    )
    val DATA_CAP_GB = IntDef(
        "data_cap_gb", Tx("月の上限", "Monthly cap", "Plafond mensuel"), 50, 1, 2000, U.GB,
        Tx("1 GB = 1024 MB", "1 GB = 1024 MB", "1 Go = 1024 Mo"),
    )
    val DATA_CAP_DAY = IntDef(
        "data_cap_day", Tx("毎月の数え始める日", "Count from this day each month", "Décompte à partir de ce jour chaque mois"), 1, 1, 28, U.DAY_OF_MONTH,
        Tx(
            "料金プランの締め日の翌日（月末締めなら 1）", "The day after your plan's billing cutoff (1 if it resets at the start of the month)",
            "Le lendemain de la date de clôture de votre forfait (1 s'il repart en début de mois)",
        ),
    )
    val DATA_CAP_STOP = BoolDef(
        "data_cap_stop",
        Tx("上限を超えたらテザリングを止める", "Turn off the hotspot when the cap is exceeded", "Arrêter le point d'accès quand le plafond est dépassé"), false,
        Tx(
            "次の期間の始まりに再開します（このアプリが止めたときだけ）。止めている間に手で ON にしたら、その期間はもう止めません。再起動しても、その期間は ON にしません。" +
                "「システム設定の変更」の許可が必要です",
            "It's turned back on when the next period starts (only if this app turned it off). If you turn it on by hand while it's off, it isn't turned off again in that period. It stays off after a reboot until the period ends. " +
                "Requires the “Modify system settings” permission",
            "Il est réactivé au début de la période suivante (seulement si cette application l'a arrêté). Si vous l'activez à la main pendant l'arrêt, il n'est plus arrêté durant cette période. Il reste arrêté après un redémarrage jusqu'à la fin de la période. " +
                "Nécessite l'autorisation « Modifier les paramètres système »",
        ),
    )

    /** 起動時にテザリングを ON にする（v0.7.0。BootTether） */
    val TETHER_ON_BOOT = BoolDef(
        "tether_on_boot",
        Tx("起動時に Wi-Fiテザリングを ON にする", "Turn on the Wi-Fi hotspot at startup", "Activer le point d'accès Wi-Fi au démarrage"), false,
        Tx(
            "端末の起動直後に Wi-Fiテザリングを ON にします。PIN などの画面ロックがあっても、ロック解除の前に ON にします（ON にならなければ、約 10 分のあいだ数回やり直します）。" +
                "そのあと手で OFF にしたら、次の起動まではそのままです。監視を止めていても動きます。「システム設定の変更」の許可が必要です",
            "Turns the Wi-Fi hotspot on right after the phone starts, even before a PIN or other screen lock is unlocked (if it doesn't come on, it retries a few times over about 10 minutes). " +
                "If you then turn it off by hand, it stays off until the next startup. Works even while monitoring is stopped. Requires the “Modify system settings” permission",
            "Active le point d'accès Wi-Fi juste après le démarrage du téléphone, même avant le déverrouillage d'un code PIN ou d'un autre verrouillage de l'écran (s'il ne s'active pas, quelques nouveaux essais ont lieu pendant environ 10 minutes). " +
                "Si vous l'arrêtez ensuite à la main, il reste arrêté jusqu'au prochain démarrage. Fonctionne même si la surveillance est arrêtée. Nécessite l'autorisation « Modifier les paramètres système »",
        ),
    )
    val NOTIFY_ALERT = BoolDef("notify_alert", Tx("異常・警告を通知する", "Notify about errors and alerts", "Notifier les erreurs et alertes"), true)
    val NOTIFY_RECOVERY = BoolDef("notify_recovery", Tx("復旧を通知する", "Notify about recoveries", "Notifier les rétablissements"), true)
    val NOTIFY_BOOT = BoolDef(
        "notify_boot",
        Tx("起動後チェックの結果を通知する", "Notify the after-reboot check result", "Notifier le résultat de la vérification après redémarrage"), true,
    )

    val URL = TextDef(
        "check_url", Tx("到達確認のURL", "Reachability check URL", "URL du test d'accès"), "http://connectivitycheck.gstatic.com/generate_204",
        Tx(
            "http または https。既定のURLは本文0バイトで通信量が最小", "http or https. The default URL returns an empty body, using the least data",
            "http ou https. L'URL par défaut renvoie un corps vide et consomme le moins de données",
        ),
        ::checkUrl,
    )
    val RECHECK = TextDef(
        "recheck_min",
        Tx(
            "異常時の再確認（分・カンマ区切り）", "Rechecks on error (minutes, comma-separated)",
            "Revérifications en cas d'erreur (minutes, séparées par des virgules)",
        ),
        "1,3,5",
        Tx(
            "この順に再確認し、すべて異常なら通知する（1〜5回）", "Rechecks in this order and notifies only if all fail (1–5 times)",
            "Revérifie dans cet ordre et alerte seulement si toutes échouent (1 à 5 fois)",
        ),
        ::checkRecheck,
    )
    val QUIET_HOURS = TextDef(
        "quiet_hours",
        Tx("使わない時間帯（例: 1-6。空欄 = 設定しない）", "Quiet hours (e.g. 1-6; blank = none)", "Heures d'inactivité (ex. 1-6 ; vide = aucune)"), "",
        Tx(
            "0〜23 時で「開始-終了」。1:30-6:00 のように分も指定でき、23-5 のように日をまたいでもかまいません",
            "“start-end” in hours 0–23. Minutes are allowed, like 1:30-6:00, and the range may cross midnight, like 23-5",
            "« début-fin » en heures de 0 à 23. Les minutes sont possibles, comme 1:30-6:00, et la plage peut passer minuit, comme 23-5",
        ),
        ::checkQuietHours,
    )

    val EXT_URL = TextDef(
        "ext_url", Tx("通知先の URL", "Destination URL", "URL de destination"), "",
        Tx(
            "ntfy は https://ntfy.sh/トピック名（自分のサーバーも可）、Discord・Slack は Webhook の URL。設定のファイル保存には含めません",
            "ntfy: https://ntfy.sh/your-topic (your own server works too). Discord, Slack: the webhook URL. Not included in saved settings files",
            "ntfy : https://ntfy.sh/votre-sujet (votre propre serveur convient aussi). Discord, Slack : l'URL du webhook. Non incluse dans les fichiers de paramètres enregistrés",
        ),
        ExtNotify::check, secret = true,
    )
    val PLUG_KIND = TextDef("plug_kind", Tx("プラグの種類", "Plug type", "Type de prise"), PlugUrls.SHELLY2, null, PlugUrls::checkKind)
    val PLUG_HOST = TextDef(
        "plug_host", Tx("プラグのアドレス（Shelly・Tasmota）", "Plug address (Shelly, Tasmota)", "Adresse de la prise (Shelly, Tasmota)"), "",
        Tx(
            "例: 192.168.43.50。このテザリングにつなぐ場合は、プラグのアプリで割り当てられたアドレスを確認して入れます（接続端末の数からは自動で除きます）。設定のファイル保存には含めません",
            "e.g. 192.168.43.50. If the plug joins this hotspot, look up its assigned address in the plug's app (it's left out of the device count automatically). Not included in saved settings files",
            "ex. 192.168.43.50. Si la prise se connecte à ce point d'accès, relevez l'adresse attribuée dans l'application de la prise (elle est exclue automatiquement du nombre d'appareils). Non incluse dans les fichiers de paramètres enregistrés",
        ),
        PlugUrls::checkHost, secret = true,
    )
    val PLUG_ON_URL = TextDef(
        "plug_on_url", Tx("ON にする URL（「URL を指定」のとき）", "URL to turn ON (for “Custom URL”)", "URL pour allumer (pour « URL personnalisée »)"), "",
        Tx(
            "例: http://192.168.43.50/relay/0?turn=on。POST で送るときは先頭に「POST 」。設定のファイル保存には含めません",
            "e.g. http://192.168.43.50/relay/0?turn=on. To send it as POST, start with “POST ”. Not included in saved settings files",
            "ex. http://192.168.43.50/relay/0?turn=on. Pour un envoi en POST, commencez par « POST ». Non incluse dans les fichiers de paramètres enregistrés",
        ),
        PlugUrls::checkSpec, secret = true,
    )
    val PLUG_OFF_URL = TextDef(
        "plug_off_url", Tx("OFF にする URL（「URL を指定」のとき）", "URL to turn OFF (for “Custom URL”)", "URL pour éteindre (pour « URL personnalisée »)"), "",
        null, PlugUrls::checkSpec, secret = true,
    )

    val ints = listOf(
        INTERVAL_NORMAL, INTERVAL_SAVER, TIMEOUT, EXPECT_CODE, TEMP_WARN, TEMP_CRIT, THERMAL_WARN, BATT_LOW, BOOT_DELAY, LOG_DAYS, LOG_MB,
        BATT_CAPACITY, RUNTIME_ALERT, EXP_PHASE, EXP_REPEATS, EXP_MIN_GAIN,
        SIGNAL_WEAK_RSRP, SIGNAL_DROP_DB, IDLE_TRAFFIC_MB, QUIET_TRAFFIC_MB, WEB_PORT,
        AUTO_SAVER_PCT, IDLE_INTERVAL, IDLE_BATT,
        PLUG_LOW, PLUG_HIGH, PLUG_HOT, PLUG_FLIPBACK, DATA_CAP_GB, DATA_CAP_DAY,
    )
    val bools = listOf(
        HTTP, TETHER_EXPECTED, CLIENTS, NOTIFY_ALERT, NOTIFY_RECOVERY, NOTIFY_BOOT, SIGNAL_ALERT, WEB, AUTO_SAVER, AUTO_DOZE, SKIP_PROBE,
        TETHER_SCHEDULE, EXT_NOTIFY, EXT_RECOVERY, EXT_ALERT, PLUG, TETHER_ON_BOOT, DATA_CAP, DATA_CAP_STOP,
    )
    val texts = listOf(URL, RECHECK, QUIET_HOURS, EXT_URL, PLUG_KIND, PLUG_HOST, PLUG_ON_URL, PLUG_OFF_URL)

    /** 設定のファイル保存・診断情報に含めない項目のキー */
    val secretKeys: Set<String> get() = texts.filter { it.secret }.map { it.key }.toSet()

    fun checkUrl(s: String): String? {
        val uri = runCatching { URI(s.trim()) }.getOrNull() ?: return tr("URLの形式が正しくありません", "Invalid URL", "URL non valide")
        if (uri.scheme != "http" && uri.scheme != "https") {
            return tr("http:// または https:// で始めてください", "Must start with http:// or https://", "Doit commencer par http:// ou https://")
        }
        if (uri.host.isNullOrEmpty()) return tr("ホスト名がありません", "Missing host name", "Nom d'hôte manquant")
        return null
    }

    fun parseRecheck(s: String): List<Int>? {
        val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 5) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums.takeIf { list -> list.all { it in 1..60 } }
    }

    fun checkRecheck(s: String): String? =
        if (parseRecheck(s) == null) {
            tr(
                "1〜60 の数字をカンマ区切りで1〜5個（例: 1,3,5）", "1 to 5 numbers from 1–60, comma-separated (e.g. 1,3,5)",
                "1 à 5 nombres de 1 à 60, séparés par des virgules (ex. 1,3,5)",
            )
        } else {
            null
        }

    fun checkQuietHours(s: String): String? =
        if (s.isNotBlank() && QuietHours.parse(s) == null) {
            tr(
                "「開始-終了」の形で 0〜23 時を入れてください（例: 1-6、1:30-6:00）。空欄なら設定しません",
                "Enter “start-end” in hours 0–23 (e.g. 1-6, 1:30-6:00). Leave blank for none",
                "Saisissez « début-fin » en heures de 0 à 23 (ex. 1-6, 1:30-6:00). Laissez vide pour aucune",
            )
        } else {
            null
        }
}

/** バックアップ（JSON）から読んだ値の検証。範囲外・型違いは採用せず、理由を返す */
object SettingsCodec {
    const val SCHEMA = 1

    class Parsed(val values: Map<String, Any>, val errors: List<String>, val unknown: List<String>)

    fun parse(raw: Map<String, Any?>): Parsed {
        val values = LinkedHashMap<String, Any>()
        val errors = ArrayList<String>()
        val unknown = ArrayList<String>()
        for ((k, v) in raw) {
            val i = Defs.ints.firstOrNull { it.key == k }
            val b = Defs.bools.firstOrNull { it.key == k }
            val t = Defs.texts.firstOrNull { it.key == k }
            when {
                k == Defs.MODE_KEY ->
                    if (v is String && BaseMode.entries.any { it.key == v }) values[k] = v
                    else errors.add(issue(Defs.modeLabel, tr("「$v」は使えない値です", "“$v” is not a valid value", "« $v » n'est pas une valeur valide")))
                i != null -> {
                    val n = toInt(v)
                    if (n == null || n !in i.min..i.max) {
                        errors.add(issue(i.label, tr("「$v」は範囲外です", "“$v” is out of range", "« $v » est hors limites") + Fmt.paren(i.range)))
                    } else {
                        values[k] = n
                    }
                }
                b != null ->
                    if (v is Boolean) values[k] = v
                    else errors.add(issue(b.label, tr("「$v」は true / false ではありません", "“$v” is not true/false", "« $v » n'est pas true/false")))
                t != null -> {
                    val reason = if (v is String) t.check(v.trim()) else tr("文字列ではありません", "Not a string", "Pas une chaîne de caractères")
                    if (v is String && reason == null) values[k] = v.trim()
                    else errors.add(issue(t.label, reason.orEmpty()))
                }
                else -> unknown.add(k)
            }
        }
        return Parsed(values, errors, unknown)
    }

    /** 「項目: 内容」（フランス語はコロンの前に空白） */
    fun issue(label: String, text: String): String = tr("$label: $text", "$label: $text", "$label : $text")

    private fun toInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is Number -> v.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /** 現在値と比べた変更点の説明（復元前の確認画面用） */
    fun describeChanges(current: Map<String, Any>, incoming: Map<String, Any>): List<String> {
        val out = ArrayList<String>()
        for ((k, v) in incoming) {
            val old = current[k]
            if (old == v) continue
            val label = when (k) {
                Defs.MODE_KEY -> Defs.modeLabel
                else -> Defs.ints.firstOrNull { it.key == k }?.label
                    ?: Defs.bools.firstOrNull { it.key == k }?.label
                    ?: Defs.texts.firstOrNull { it.key == k }?.label
                    ?: k
            }
            // トークンを含みうる値は、画面・ログに出さない
            if (k in Defs.secretKeys) {
                out.add(issue(label, if ((v as? String).isNullOrEmpty()) tr("空欄にする", "clear", "vider") else tr("変更", "change", "modifier")))
                continue
            }
            out.add(issue(label, "${show(k, old)} → ${show(k, v)}"))
        }
        return out
    }

    private fun show(key: String, v: Any?): String = when {
        v == null -> tr("（未設定）", "(not set)", "(non défini)")
        key == Defs.MODE_KEY -> BaseMode.of(v as? String).label
        key == Defs.PLUG_KIND.key -> PlugUrls.label(v.toString())
        v is Boolean -> if (v) "ON" else "OFF"
        else -> v.toString()
    }
}
