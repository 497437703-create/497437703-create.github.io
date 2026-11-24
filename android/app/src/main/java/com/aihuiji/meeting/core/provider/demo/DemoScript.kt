package com.aihuiji.meeting.core.provider.demo

import com.aihuiji.meeting.core.model.ContentTag
import com.aihuiji.meeting.core.model.Lang

/**
 * Scripted multi-speaker project meeting used by the demo backend. Every line exists in all five
 * M1 languages so the selected recognition language and translation targets both look real.
 * `R*` speakers are far-end participants (remote channel in a dual-channel video meeting).
 */
internal object DemoScript {
    data class Line(val speaker: String, val tags: Set<ContentTag>, val text: Map<Lang, String>)

    private fun line(speaker: String, vararg tags: ContentTag, zh: String, en: String, fr: String, de: String, ja: String) =
        Line(speaker, tags.toSet(), mapOf(Lang.ZH to zh, Lang.EN to en, Lang.FR to fr, Lang.DE to de, Lang.JA to ja))

    val lines: List<Line> = listOf(
        line(
            "S1", ContentTag.TOPIC,
            zh = "今天我们主要讨论下季度的产品规划，先看一下目前的进度和风险。",
            en = "Today we'll mainly discuss next quarter's product plan, starting with current progress and risks.",
            fr = "Aujourd'hui, nous parlons surtout de la feuille de route du prochain trimestre, en commençant par l'avancement et les risques.",
            de = "Heute besprechen wir vor allem die Produktplanung für das nächste Quartal und beginnen mit Fortschritt und Risiken.",
            ja = "今日は主に来四半期の製品計画について話し合います。まず現在の進捗とリスクを確認しましょう。",
        ),
        line(
            "S2", ContentTag.PROGRESS,
            zh = "语音转写模块已经完成联调，企业内服务的首字延迟在一秒左右。",
            en = "The transcription module has finished integration testing; first-word latency on the enterprise server is about one second.",
            fr = "Le module de transcription a terminé les tests d'intégration ; la latence du premier mot sur le serveur interne est d'environ une seconde.",
            de = "Das Transkriptionsmodul hat die Integrationstests abgeschlossen; die Latenz bis zum ersten Wort liegt intern bei etwa einer Sekunde.",
            ja = "音声文字起こしモジュールは結合テストを完了し、社内サーバーでの最初の文字までの遅延は約1秒です。",
        ),
        line(
            "R1", ContentTag.RISK,
            zh = "从技术角度来看，研发资源比较紧张，大屏适配可能会延期两周。",
            en = "From a technical perspective, R&D resources are tight, and the large-screen adaptation may slip by two weeks.",
            fr = "Sur le plan technique, les ressources R&D sont limitées et l'adaptation au grand écran pourrait prendre deux semaines de retard.",
            de = "Technisch gesehen sind die Entwicklungsressourcen knapp, die Großbildschirmanpassung könnte sich um zwei Wochen verzögern.",
            ja = "技術的に見ると、開発リソースが逼迫しており、大画面対応が2週間遅れる可能性があります。",
        ),
        line(
            "S1", ContentTag.QUESTION,
            zh = "延期的话，会不会影响月底的客户演示？",
            en = "If it slips, will it affect the customer demo at the end of the month?",
            fr = "En cas de retard, la démonstration client de fin de mois sera-t-elle affectée ?",
            de = "Würde eine Verzögerung die Kundendemo Ende des Monats beeinträchtigen?",
            ja = "遅れた場合、月末の顧客デモに影響はありますか？",
        ),
        line(
            "R1", ContentTag.VIEWPOINT,
            zh = "演示可以先用分屏模式，悬浮窗放到下一个版本。",
            en = "For the demo we can use split-screen first and move the floating window to the next release.",
            fr = "Pour la démo, nous pouvons d'abord utiliser l'écran partagé et reporter la fenêtre flottante à la prochaine version.",
            de = "Für die Demo können wir zunächst den geteilten Bildschirm nutzen und das schwebende Fenster verschieben.",
            ja = "デモはまず分割画面モードで行い、フローティングウィンドウは次のバージョンに回せます。",
        ),
        line(
            "S2", ContentTag.TOPIC,
            zh = "另外，翻译需要支持中英法德日五种语言，法语和德语的排版要单独测试。",
            en = "Also, translation must cover Chinese, English, French, German and Japanese; French and German layouts need separate testing.",
            fr = "Par ailleurs, la traduction doit couvrir cinq langues ; la mise en page du français et de l'allemand doit être testée à part.",
            de = "Außerdem muss die Übersetzung fünf Sprachen abdecken; das Layout für Französisch und Deutsch muss separat getestet werden.",
            ja = "また、翻訳は中・英・仏・独・日の5言語に対応する必要があり、フランス語とドイツ語のレイアウトは個別にテストが必要です。",
        ),
        line(
            "R2", ContentTag.VIEWPOINT,
            zh = "我建议先保证英文和日文，试点客户最需要这两种。",
            en = "I suggest we secure English and Japanese first, since the pilot customers need those two most.",
            fr = "Je propose de garantir d'abord l'anglais et le japonais, dont les clients pilotes ont le plus besoin.",
            de = "Ich schlage vor, zuerst Englisch und Japanisch sicherzustellen, die Pilotkunden brauchen sie am dringendsten.",
            ja = "試験導入の顧客が最も必要としているので、まず英語と日本語を確実にすることを提案します。",
        ),
        line(
            "S1", ContentTag.DECISION,
            zh = "好，那就定方案 B：月底演示用分屏模式，悬浮窗下个版本再做。",
            en = "OK, let's go with plan B: split-screen for the end-of-month demo, floating window in the next release.",
            fr = "Bien, retenons le plan B : écran partagé pour la démo de fin de mois, fenêtre flottante dans la prochaine version.",
            de = "Gut, dann Plan B: geteilter Bildschirm für die Demo am Monatsende, schwebendes Fenster in der nächsten Version.",
            ja = "では案Bで決定します。月末のデモは分割画面、フローティングウィンドウは次のバージョンで対応します。",
        ),
        line(
            "S1", ContentTag.DECISION,
            zh = "预算方面维持不变，下周再复核一次。",
            en = "The budget stays unchanged; we'll review it again next week.",
            fr = "Le budget reste inchangé ; nous le revérifierons la semaine prochaine.",
            de = "Das Budget bleibt unverändert; wir prüfen es nächste Woche noch einmal.",
            ja = "予算は据え置きとし、来週もう一度見直します。",
        ),
        line(
            "S2", ContentTag.TODO,
            zh = "我来负责，周五前提交五种语言的排版测试报告。",
            en = "I'll take it and submit the layout test report for all five languages by Friday.",
            fr = "Je m'en charge et je remettrai le rapport de test de mise en page des cinq langues avant vendredi.",
            de = "Ich übernehme das und reiche bis Freitag den Layout-Testbericht für alle fünf Sprachen ein.",
            ja = "私が担当し、金曜日までに5言語のレイアウトテスト報告を提出します。",
        ),
        line(
            "R1", ContentTag.TODO,
            zh = "我这边评估研发资源，下周一给出排期。",
            en = "I'll assess R&D resources and share the schedule next Monday.",
            fr = "De mon côté, j'évalue les ressources R&D et je donne le planning lundi prochain.",
            de = "Ich bewerte die Entwicklungsressourcen und lege nächsten Montag den Zeitplan vor.",
            ja = "こちらで開発リソースを評価し、来週月曜日にスケジュールを提示します。",
        ),
        line(
            "R2", ContentTag.RISK,
            zh = "还有一个风险，客户现场的网络可能不稳定，需要验证断网补齐。",
            en = "One more risk: the network at the customer site may be unstable, so we need to verify offline backfill.",
            fr = "Autre risque : le réseau chez le client peut être instable, il faut vérifier la reprise après coupure.",
            de = "Noch ein Risiko: Das Netzwerk beim Kunden könnte instabil sein, wir müssen die Nachsynchronisierung prüfen.",
            ja = "もう一つのリスクとして、顧客現場のネットワークが不安定な可能性があり、切断後の補完を検証する必要があります。",
        ),
        line(
            "S1", ContentTag.TODO,
            zh = "现场网络测试请远端团队来协调，月底前完成。",
            en = "Please have the remote team coordinate the on-site network test, done by the end of the month.",
            fr = "L'équipe à distance coordonnera le test réseau sur site, à terminer avant la fin du mois.",
            de = "Das Remote-Team koordiniert bitte den Netzwerktest vor Ort, Abschluss bis Monatsende.",
            ja = "現場のネットワークテストはリモートチームが調整し、月末までに完了してください。",
        ),
        line(
            "S1", ContentTag.TOPIC,
            zh = "今天就到这里，纪要生成后大家扫码查看。",
            en = "That's all for today; scan the code to view the minutes once they're generated.",
            fr = "C'est tout pour aujourd'hui ; scannez le code pour consulter le compte rendu une fois généré.",
            de = "Das war's für heute; scannen Sie den Code, um das Protokoll nach der Erstellung anzusehen.",
            ja = "今日はここまでです。議事録が生成されたらコードをスキャンして確認してください。",
        ),
    )

    private val byText: Map<String, Line> = buildMap {
        lines.forEach { l -> l.text.values.forEach { put(it, l) } }
    }

    fun find(text: String): Line? = byText[text]
}
