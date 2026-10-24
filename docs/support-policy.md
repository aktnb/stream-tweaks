# ADR-001: Minecraft / Loader の対応・保守方針

決定日: 2026-10-24。状態: 採用。根拠: [Epic #2](https://github.com/aktnb/stream-tweaks/issues/2)。
版番号と公開単位は [versioning.md](versioning.md) に従う。

## 現状と決定

現行は Minecraft 26.2 / Java 25、`settings.gradle` が含める `fabric` / `neoforge` の2モジュール。
両モジュールはルート `src/main/java` / `src/main/resources` を sourceSet として取り込む。
Forge は対応対象ではなく、独立 common JAR もない。

最大2つの MC 系列を、MC ごとのブランチと既存 Loader モジュールで保守する。
Active は1系列、Maintenance は最大1系列。Loader ごとの恒常ブランチは作らない。
現行 `26.2` は保持し、main 導入後は main を Active 開発と全体台帳・全対象検証の入口とする。
同じ MC の公開元は常に1ブランチだけとし、main と `26.2` の二重公開を禁止する。

この ADR の採用だけではブランチ作成・default 変更・対応昇格は実施しない。
main 移行は対象 resolver / CI / release dry-run の整備後に実施する（[#14](https://github.com/aktnb/stream-tweaks/issues/14)）。
移行準備の台帳は唯一の公開元をmainに予約する。main未作成から検証完了までは新規公開を停止し、
準備 PR は26.2へ送る。branch作成・default切替の完了は台帳値から推測せず、[移行手順](main-migration.md)の証拠で確認する。
初期対象は実装済みの 26.2 × Fabric / NeoForge のみ。通常 build 成功と client smoke 成功は区別し、
実行検証日が不明な行は不明のまま記録する。未検証 MC の範囲対応を metadata へ追加しない。
初期の既存実装行を supported と記録しても、それは client 起動実証と同義ではない。
build / check と runtime smoke の状態・日付・証拠を別に記録し、未実施は `unverified` / 日付なしとする。

## 依存境界と方式選定

`application` / `twitch/*` / `config` の業務ロジックへ MC / Loader import を増やさない。
「共通 sourceSet」は「MC 非依存」を意味しない。共有していても `presentation` と GUI / bootstrap は MC 依存であり、
Mixin・フォント・描画・MC 内部 API の差は `presentation` / `mod` 側で吸収する。
Loader 差は `mod/platform/ClientPlatform.java` と `TwitchCommandRegistrar.java` および各 Loader モジュールへ閉じ込める。
共通配線は `TwitchClientBootstrap`、定数・ロガーは `StreamTweaksCommon` が担う。
テストはルート `src/test/java` を Fabric が1回実行するが、一部は MC に依存するため純 Java テストとは扱わない。

既存の境界で2 Loader を扱えるため Architectury API の実行時依存や Loom の置換を追加しない。
全面的な common モジュール化・独立 core リポジトリも公開順序と版管理を増やすため見送る。
Stonecutter は source 変換・生成プロジェクト・IDE・Loader 間の build 調停を増やすため現時点では導入しない。
3 MC 以上で同時機能提供が必要になった場合、backport 競合が継続する場合、同じ MC adapter の移植が反復する場合は、
実差分を使う小規模実験で工数と Mixin / GUI / フォント互換を比較し、この ADR と保守上限を再審議する。
ブランチ方式では backport 作業が残ることを受容する。

## 状態と期限

対応状態は MC × Loader 行ごとに記録する。台帳は状態・公開元・Java・機能制限・検証日・証拠を持ち、
各 checkout の `gradle.properties` が実ビルド値の真実となる。台帳との矛盾は検証エラーとする。
機械値は系列の保守段階（`active` / `maintenance` / `eol`）と行の提供可否
（`supported` / `testing` / `unsupported`）を区別する。EOL 行は公開・全対象検証の対象にしない。
supported という値だけで build や runtime の検証成功を推測せず、それぞれの検証記録を参照する。

| 状態 | 対応内容 | 期限・移行 |
| --- | --- | --- |
| Active | 新機能、互換修正、不具合・security 修正 | 唯一の開発 MC 系列。次 Active 昇格時に Maintenance へ移す |
| Maintenance | 重大不具合、security、接続互換修正 | 新 Active 昇格日から6か月、または次の Active 昇格日の早い方まで |
| EOL | 通常の修正・公開・全対象 CI の対象外 | ブランチ・Tag・Release を保持し履歴を参照可能にする |
| 未対応 / 検証中 | 対応を約束しない | build と client smoke の証拠を揃えるまで supported へ昇格しない |

Maintenance の新機能は、利用需要があり、分岐・依存更新が小さく Active の修正を遅らせない場合だけ例外とする。
延長は理由・担当・明示的な終了日・対象 Loader を台帳と Issue に記録し、最大2系列を守る。
新 Active 昇格時、既存 Maintenance は EOL とする。期限を過ぎたまま黙って保守を継続しない。
需要不在、upstream 保守終了、検証不能、移植費用の継続的増大でも終了理由と日付を告知して EOL にできる。
Loader の upstream 遅延・未対応・制限・終了日は行ごとに記し、片 Loader の遅延で他方の公開を止めない。

## 開発・backport

通常は Active 公開元から `feature/<topic>` を作り、Active へ PR を送る。MC / Loader ブランチへ直接 push しない。
共通修正はまず Active に一度実装・検証し、対象 Maintenance ごとに次の手順を使う。

1. 対象保守ブランチから `backport/<MC>/<issue>` を作る。
2. `git cherry-pick -x <元commitのfull-SHA>` で由来を残す。MC 差分の衝突は adapter 側で解消する。
3. PR の base を対象保守ブランチとし、元 Issue / commit / Active PR、衝突解消・機能差を記す。
4. 対象 MC × 影響 Loader の CI、共通テスト、必要な client smoke を実行してからマージする。
   全ブランチ HEAD の定期検証だけでは当該修正の横展開を証明できない。

旧 MC 専用 API の修正など Active に適用不能な場合は、先に元 Issue と保守 PR に理由・影響範囲・
Active 側の非該当根拠を記録する。適用不能な commit を作るためだけに Active を変更しない。
別保守先へ実装を移す場合は、その元 commit を `cherry-pick -x` し対象別 PR / CI で追跡する。

## MC / Loader の追加と終了

新 MC は需要と公式 toolchain の互換性を調べ、1候補を選定してから `feature/port-<MC>` で Active を移植する。
旧 Active を保持し、将来の新規保守ブランチは `mc/<version>`、既存 `26.2` は rename / 削除しない。
台帳・properties・metadata・CI と、起動 / 5 Mixin / チャット表示・削除 / 静止・動画 emote / 切断・終了を検証する。
認証 smoke はテストアカウントを使い秘密情報を記録せず結果だけ残す。検証できた Loader 行だけを昇格させる。

Loader 追加は対象 MC の既存ブランチへ module / adapter / metadata / 台帳行 / CI を追加し、業務ロジックを複製しない。
Forge は需要と公式 toolchain が成立する場合のみ別途判断する。
EOL 時は対象行と notes に日付・理由・最終正常版・代替先を残し、書込み・公開対象から外す。
既存 branch / Tag / Release / 配布済み JAR は削除・差替えしない。
恒常 release ブランチは作らず、必要な準備用 `release/<version>-mc<MC>` は公開元へマージしてから公開する。
