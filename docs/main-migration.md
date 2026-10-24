# main を Active 公開元へ移す手順（Issue #14）

この runbook は移行準備であり、移行完了の記録ではありません。
現時点で main 作成・default 変更・保護設定変更は実施していません。
依存する全対象 CI と削除しない公開復旧手順が導入されるまで実行しません。
[対応方針](support-policy.md)、[対応台帳](support-targets.md)、[CI](ci-build.md) と併用します。

## 事前確認と現在の権限

2026-10-24 の接続 GitHub API による確認では、default は `26.2`、唯一の branch も `26.2`、
HEAD は `06436c1607e826b5d2dfaccb3edfebd7d23ed7f6` です。実行時は必ず取り直します。
本人 `aktnb` の repository 権限は admin ですが、接続 App の classic branch protection 読取は
`403 Resource not accessible by integration` です。本人の admin 権限だけで接続 App の管理操作が
可能とは判断しません。403 を別経路で迂回せず、管理者が正規の設定画面または管理権限付き接続で確認します。

ruleset `23603340`（Push Protection）は `~DEFAULT_BRANCH` の deletion / non_fast_forward 禁止のみでした。
この ruleset 単独では `CI OK` 必須を証明できません。また default 切替後は `26.2` がこの対象から外れます。
切替前に main と26.2の双方を明示対象とする保護を用意し、既存のレビュー要件・制限を弱めないでください。
matching-refs/tags の結果と Release 一覧はいずれも空でしたが、これも実行直前の一覧を基準にします。

| 操作 | 必要な repository 権限 | 完了の証拠 |
| --- | --- | --- |
| branch / catalog / run / Tag / Release の照会 | Contents / Actions read 等 | full SHA、取得結果、run URL |
| main 作成 | Contents write（workflow を含む場合の Workflows 権限も確認） | main ref の readback |
| 単 target・全対象・公開なし計画の dispatch | Actions write | 成功した run と immutable SHA |
| classic protection の照会 | Administration read | 実設定の readback、403 は未確認 |
| default・保護設定の変更と復元 | Administration write と対象管理権限 | 変更後 readback と復元記録 |

権限根拠は [Git reference API](https://docs.github.com/en/rest/git/refs#create-a-reference)、
[dispatch API](https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event)、
[保護 API](https://docs.github.com/en/rest/branches/branch-protection)、
[repository 更新 API](https://docs.github.com/en/enterprise-cloud%40latest/rest/repos/repos#update-a-repository)を参照してください。
現接続には default / 保護設定の更新ツールもないため、この接続だけで移行完了を宣言しません。

## 切替前のゲート

- #8 と #13 を含む先行基盤を26.2へ導入し、対象 PR CI、単 target／全対象 dispatch、公開なし計画を確認する。
- 公開担当を決め、Tag／Release／Modrinth への新規公開を停止する。実行中 publish を途中 cancel せず、
  完了または部分成功の状態と release ID／manifest hash を照合する。不明なまま切替えない。
- default、26.2 の full SHA、ruleset／classic protection、Tag と peeled SHA、Release と asset hash を保存する。
- branch 作成・dispatch・管理設定・復旧が正規の認可で実行できることを確認する。
  特に classic protection 読取が403のままなら準備 PR を merge しない。
- 下記の公開元と検証 branch の分離をレビューし、設定の復元手順を確保する。
  必須 check の無効化・成功偽装や、target 行の公開元の書換えによる暗黙 alias は使わない。

## CI plan の検証元と公開元

CI の `validation_source_branch` / `validation_source_sha` は実際に検証する checkout です。
既存の `source_branch` / `source_sha` はこの2値の互換 alias で、完全一致しなければ plan を拒否します。
`publication_source_branch` と `targets[].source_branch` は台帳どおりの公開元であり、CI の都合で変更しません。
`authority_branch` はこの選択を許可した台帳の権威元です。summary には検証元と公開元を別々に表示します。

通常は検証元と公開元が一致します。例外として、台帳の権威元がmain、行の lifecycle がactive、
公開元がmain、かつ検証 branch がその行の MC と完全一致する数値 branch の場合だけ、
旧 branch の検証を継続できます。未知 branch、`mc/<MC>`、異MC、Maintenanceにはこの例外を適用しません。
MC / Java / Loader properties は元の台帳行と照合し、checkout は固定した検証 SHA を使用します。

これは品質検証だけの経路です。`release_plan.py` の公開元一致要件は変えず、
26.2 の新規公開計画はmain切替後に拒否します。全対象 resolver も公開元mainを解決するため、
旧 branch をmainの検証結果に代用しません。既存固定 plan の source branch / SHA も書き換えません。

## 公開元を先に切替えてから main を作る

1. 26.2 を base とする準備 PR を作る。`support-targets.json` の `authority_branch` と、
   現行26.2の両 Loader 行の `source_branch` を **すべて `main`** にする。
   MC／Loader／検証実績は変えず、未検証結果を成功へ書き換えない。26.2 はまだ Maintenance にしない。
2. 準備 PR の CI は宛先 `base.sha` の旧 catalog を使うため、引き続き26.2として検証される。
   merge SHA の properties と両 Loader build、script tests が成功した証拠を保存して merge する。
   テスト用catalogは `scripts/tests/fixtures/` に固定してあり、実台帳をmainへ変えても
   初期26.2契約テストが壊れないことを確認する。
3. merge 後の26.2 push と26.2宛て復旧 PR は、上記の限定経路で品質 CI を継続する。
   検証元は26.2の固定 SHA、公開元はmainのままであり、新規公開は許可しない。
   main が未作成の短い期間は全対象 resolver や公開計画のmain指定はまだ成功しないので、
   公開停止を維持する。旧 branch の CI 成功をmain上の検証証拠に代用しない。
4. merge 後に API から26.2の最新 full SHA を再取得し、準備 PR を含むことを確認する。
   その **同じ SHA** から新規 main を作る。main が既存なら上書き／force updateせず、その由来を再確認する。
   26.2の履歴や ref を変更せず、作成した main の SHA を readback で照合する。
5. main は最初から `authority_branch=main`／`source_branch=main` の台帳を持つので、
   宛先catalogの特例なしにmain上の通常PR CIを実行できる。
   main宛て確認 PR、`26.2-fabric` と `26.2-neoforge` の単 target dispatch、全 supported dispatch、
   `plan-new` および公開なし `prepare-new` を実行する。release操作名は導入済みworkflowの入力と照合する。
   Tag／公開 adapter を起動する操作は移行確認に使用しない。
6. 成功した run の full source SHA／MC／Loader／実 JDK／結果を保存する。
   全対象の resolver が main を一度だけ解決し、同じ固定 SHA を両 Loader に使うこと、
   台帳がmain以外の同MC公開元を示さないことを確認する。
7. main と26.2の保護設定を読戻し、main の `CI OK` が必須である実設定を確認する。
   GitHub Actions由来の正しい check context を選び、他のレビュー・更新制約も保存する。
   その後にだけ default をmainへ変更し、default／workflow discovery／保護を再読込する。
8. 26.2・Tag・Release・asset の保存済み一覧を再照合し、削除・rename・差替えがないことを記録する。
   成功記録が揃ったら新規公開停止を解除する。残した26.2は休止中の履歴であり二重公開元ではない。

## 失敗時の公開停止と復旧

どの段階でも照合不能や権限不足が起きたら新規公開を停止したままにします。
既存の release ID・固定 plan・bundle・publish-state を移行に合わせて書き換えません。
部分成功の再開は同じ入力で行い、Tag／Release／配布先versionの削除でやり直しません。

- 準備 PR merge 前の失敗なら、26.2 の既存設定を維持して原因を直します。
- merge 後に main 作成または main の検証が失敗した場合は、26.2宛て復旧 PR で
  `authority_branch` と当該両行の `source_branch` を26.2へ戻します。mainがあれば保持します。
  base catalog がmainを指していても限定 CI 経路で26.2の復旧 SHA を検証できるため、
  通常のレビュー・必須 `CI OK` を通して merge します。保護解除・管理者 bypass・直接 push は使いません。
  復旧後main側に古い公開元mainの台帳が残る場合は公開停止を維持し、main宛ての復旧 PR でも
  公開元を26.2に揃えます。main自身のCIはその時点のbase catalogで検証し、検証元と公開元を捏造しません。
- default 切替後の失敗なら、公開停止下でdefaultを26.2へ戻し、同じ復旧 PR と保護復元を実施します。
  `~DEFAULT_BRANCH` の移動に伴う対象漏れも再照合します。通常の保護を弱めず復元します。
- 復元後は26.2 push／単 target／全対象 CI と公開なし計画を再検証し、正常な証拠が揃うまで公開停止を解除しません。

main が将来別MCへ進む際だけ、検証済み26.2行を Maintenance とし公開元26.2を再有効化します。
同じMCをmainと26.2の両方から公開しないこと、Maintenance期限とLoader差を記録することは変わりません。

## 証拠と完了判定

`scripts/tests/test_main_migration.py` は準備 PR のbase優先、26.2 push／復旧 PR の品質 CI 継続、
限定経路の拒否条件・alias不一致拒否・properties不一致拒否、main上のPR／単 target／全対象計画、
mainだけの新規releaseと旧固定planの不変再開を検証します。
fixture／ローカル成功だけでは次のremote証拠を代替できません。

2026-10-24 の隔離 snapshot では、実台帳を初期26.2 fixture とmain切替 fixture にそれぞれ置換し、
両方で Python 全200件が成功しました。共有作業ツリーの実台帳は変更していません。
変更した2 workflow の YAML 構文と5つの shell block も検査済みです。

| #14 の受入条件 | 保存すべき証拠 | 現時点 |
| --- | --- | --- |
| main上のPR／単 target／全対象CI・plan成功 | 各run URL、full SHA、target、JDK、結果、plan／bundle hash | 未実施 |
| 同MCの公開元main一つ | main・26.2両catalogのSHAと検証出力 | 実台帳は移行前26.2のまま |
| 既存履歴／Tag／Release保存 | 前後のref・peeled SHA・asset hash一覧 | 前後比較は未実施 |
| CI OK必須と対象保護 | classic protection／rulesetの実設定readback | classic読取403、未確認 |
| default切替・失敗時復旧 | 本手順と実施者／日時／復元結果 | runbookのみ、外部切替なし |

これらが揃うまで Issue #14 を完了扱いにしません。
