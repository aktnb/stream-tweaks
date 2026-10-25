# ADR-002: Mod version とリリース識別

決定日: 2026-10-24。状態: 採用。[対応方針](support-policy.md) と組み合わせて適用する。

## 公開契約

`mod_version` と JAR 内 version は純 SemVer（例: `0.4.0-beta.1`）とし、MC / Loader を混ぜない。
公開契約はユーザー向け機能、`/twitch` コマンド、設定・資格情報の保存形式、公開すると明示した連携 API。
内部 Java クラスの変更だけでは公開 API の変更としない。
[SemVer 2.0.0](https://semver.org/spec/v2.0.0.html) を基準に、次の運用契約を採る。

| 変更 | 1.0 以降の例 | 初期開発 `0.y.z` の例 |
| --- | --- | --- |
| コマンド削除・設定を移行不能にする非互換変更 | MAJOR: `1.4.2` → `2.0.0` | MINOR: `0.3.3` → `0.4.0`、破壊内容と移行方法を明記 |
| 既存操作を保つ互換機能追加 | MINOR: `1.4.2` → `1.5.0` | MINOR: `0.3.3` → `0.4.0` |
| 互換性を保つ不具合修正 | PATCH: `1.4.2` → `1.4.3` | PATCH: `0.3.3` → `0.3.4` |

`1.0.0` で安定契約を明示する。0 系でも PATCH で利用者を破壊せず、非互換変更は MINOR にまとめる。
旧 MC だけの修正はその旧 MINOR の PATCH を進める。例: Active `0.4.x` のまま Maintenance の `0.3.3` → `0.3.4`。
Loader 固有修正も PATCH を進め、影響 Loader のみ公開できる。例: Fabric のみ `0.3.4`、NeoForge は直近正常版 `0.3.3`。
notes と台帳にこの差を記し、同じ版の JAR を差し替えない。

新 MC / Loader の追加だけなら公開契約が同等であることを検証した場合に限り Mod version を据え置ける。
例: 別 MC へ同等の `0.3.3` を移植する場合は別リリースを作る。
対応環境削除だけで自動的に MAJOR にはしない。例: 旧 MC の EOL は対応方針と notes に明記する。
機能削除や設定破壊を伴う場合は上表どおり版を更新する。
同 Mod version を複数 MC / Loader で使う場合、公開契約は同等とし、環境上の制限や実装修正の差は台帳・notes に記録する。
契約が異なる移植を同一版の対応環境と称しない。

## リリース単位と固定入力

GitHub Release は **SemVer × MC**。既存の識別子とファイル名を維持する。

| 用途 | 形式 | 例 |
| --- | --- | --- |
| JAR 内 Mod version | 純 SemVer | `0.4.0-beta.1` |
| annotated Tag / GitHub Release ID | `v<SemVer>+mc<MC>` | `v0.4.0-beta.1+mc26.2` |
| 通常 JAR | `stream-tweaks-<loader>-mc<MC>-<SemVer>.jar` | `stream-tweaks-fabric-mc26.2-0.4.0-beta.1.jar` |
| Modrinth version 識別子 | `<tag>-<loader>` | `v0.4.0-beta.1+mc26.2-fabric` |

同 MC リリースの全 Loader は1つの full source SHA からビルドする。異 MC / 別 branch は別 Tag / Release とし、
異なる commit の JAR を1つの Tag に混在させない。更新候補は MC / Loader metadata で選ぶ。
`+mc...` は環境を識別するためだけに使い、SemVer の優先順位に使わない。
外部識別子 `<tag>-<loader>` 自体も比較用 SemVer として解釈しない。

alpha / beta は `alpha.N` / `beta.N`（N は1以上、先頭ゼロなし）を事前に明示・予約する。
例: `0.4.0-alpha.2` の失敗を再実行するときも同じ番号を使い、自動的に `.3` を採番しない。
stable はプレ版接尾辞のない版。計画は台帳の supported 行から明示選択した Loader 集合、MC、
channel、純 SemVer、唯一の公開元と full source SHA を固定する。
stable でも片 Loader の選択を許可し、`both` を必須にしない。
同じ release ID の再利用は SHA と対象集合が完全に一致するときだけ認め、相違は停止する。

公開開始後の対象集合は不変。Fabric のみで `0.3.4` を公開後、後日 NeoForge を追加するときは
`0.3.5` など新 PATCH のリリースを計画する。既存 `0.3.4` の集合を拡張しない。
同 Mod version を新 MC へ公開することは別 release ID なので、この制限に抵触しない。
Modrinth は MC × Loader ごとに version 1つ・通常 JAR 1つを維持する。

## 不変成果物と移行

build / check 成功後、固定計画から生成した JAR / notes / manifest を検証・保存してから公開する。
再開は同じ ID と同じ bundle を使い、既存 Tag の SHA、asset / 配布先 version の target と hash が一致するものは再利用する。
不足先だけ補完し、相違や照合不能な応答喪失は停止する。削除・overwrite による復旧はしない。
成果物が消失した場合は停止し、固定入力から再構築した hash が元 manifest と一致するときだけ回復できる。
公開状態・外部 ID は別 publish-state に記録し、manifest 自体を公開の進行に合わせて書き換えない。
全必須先の成功後に GitHub draft を解除する。配布先間の原子的公開は保証しない。

2026-10-24切替版（[#9](https://github.com/aktnb/stream-tweaks/issues/9)〜
[#13](https://github.com/aktnb/stream-tweaks/issues/13)）の workflow は、固定番号・stable 単 Loader・既存一致再利用を実装する。
この版の導入前に新方式で公開できるとは扱わず、旧・新 workflow の同時公開を禁止する。
実行可能な手順は [release-workflow.md](release-workflow.md) で確認する。

## 固定計画の CLI（公開なし）

`scripts/release_plan.py` は純 SemVer と channel を照合し、台帳の supported 行から選んだ
MC / Loader、唯一の公開元、full source SHA、Java、ファイル名、公開先契約を固定する。
alpha / beta は必ず明示番号を渡す。通常版は `--channel release --version 0.3.4` とし、片 Loader でもよい。
現在の公開元は `main` です。通常の公開では以下の plan CLI を直接起動せず、後述の唯一の workflow 経路
`release-new` を使います。plan CLI は内部処理やローカル診断向けです。

```sh
python3 scripts/release_plan.py new \
  --version 0.3.4-beta.1 --channel beta --minecraft 26.2 \
  --source-branch main --source-sha <mainの40文字commit-SHA> \
  --loader fabric --loader neoforge --properties gradle.properties --output release-plan.json

python3 scripts/release_plan.py reuse \
  --version 0.3.4-beta.1 --channel beta --minecraft 26.2 \
  --source-branch main --source-sha <初回と同じcommit-SHA> \
  --loader fabric --loader neoforge --existing release-plan.json
```

`--output` は新規ファイルだけを作成し、既存ファイルを上書きしない。指定しなければ stdout のみ。
内部で保存済み plan を再検証する場合は `reuse` を使い、SHA、公開元、対象集合、公開先の相違で停止する。
reuse は最新台帳を読み直さないので、後から追加された supported 行を取り込まず、元の計画を保持する。
過去に作った plan を診断する場合も、その plan の元 `source_branch` と SHA を維持する。
この CLI はローカル保存のみで、同 ID の全世界での予約や remote tag の照合は後続 publish adapter の責務。
計画自体の信頼性は、将来の公開時に信頼できる同一 repository の bundle / manifest と照合する。

必須公開先は GitHub `aktnb/stream-tweaks` と Modrinth `jWHdryID`。
任意公開先は `--curseforge-project <数値ID>` で追加でき、`--curseforge-required` を付けた計画のみ必須とする。
これらも再開時に同じ値を明示する。計画 CLI 自体は公開せず、実公開は検証済みbundleと
[任意adapterの導入確認](curseforge-publication.md)を経て行う。

Release workflow の唯一の dispatch 経路は `release-new` です。`main` ref から1回実行すると、
同じ run で固定 plan／bundle を作成し、
GitHub draft に保管後、`release-publish` Environment の Required reviewers による承認を待ちます。
承認後に保存済み draft bundle を再検証し、既存 publish coordinator が不足先を照合して finalize します。
Environment の main-only branch policy と Required reviewers は[公開・復旧ガイド](release-workflow.md)に従って
事前に設定します。publish job が失敗した場合は同じ run の失敗した job だけを再実行します。
