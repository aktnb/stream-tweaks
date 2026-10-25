# main を Active 公開元へ移す手順（Issue #14）

この runbook は main 移行手順と実施記録です。移行全体の最新状態は [main移行検証の途中経過](main-verification.md) を参照してください。
2026-10-24に準備 PR #19 を26.2へmergeし、そのmerge SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` から `main` を作成しました。2026-10-25にmain宛てdraft PR #21をmergeし、現在のmainはmerge commit `de91fd9c7761c0308f9bea095ce41f2c7a1c6914`、`26.2` は作成時のSHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` を保持しています。repository default branch は `main` です。
merge後のpush CI [run 40163126160](https://github.com/aktnb/stream-tweaks/actions/runs/40163126160) は2026-10-25 04:46:49–04:48:28 UTCに成功しました。`CI OK` も成功し、docs-only変更のためloader build matrixはskipでした。切替後のreadbackでRuleset `23603340` は両branchでactive、strictな必須 `CI OK`、deletion/non-fast-forward禁止、bypassなしを確認しました。mainでは `build.yml`、`release.yml`、`target-build.yml`、`validate-all.yml` を確認しました。
2026-10-25の切替後readbackではmatching tags 0件、GitHub Releases 0件、release assets 0件でした。この切替後検証では公開操作を行っていません。main ref、26.2 ref、default、ruleset、workflow discovery、配布履歴の最終確認を記録し、移行gateは完了しています。

[対応方針](support-policy.md)、[対応台帳](support-targets.md)、[CI](ci-build.md) と併用します。

## 実施前の事前確認と現在の権限

2026-10-24 の先行基盤 [PR #18](https://github.com/aktnb/stream-tweaks/pull/18) は26.2へmerge済みです。
2026-10-24 の実施前readbackでは default は `26.2`、確認時点のHEADは `965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d` で、mainはまだ作成されていませんでした。
feature branchも実在するため「branchは26.2のみ」とは扱いません。実行直前に必ず取り直します。

| 先行実績 | 記録した SHA の意味 | 結果 |
| --- | --- | --- |
| [PR CI 40094910231](https://github.com/aktnb/stream-tweaks/actions/runs/40094910231) | PR head `6af0e2d4dd63456103389701c7abd6b877702a16`。test merge checkout SHAとは区別する | 両Loader build／JAR検証・共有test・format・script tests・CI OK成功 |
| [merge後push CI 40095310540](https://github.com/aktnb/stream-tweaks/actions/runs/40095310540) | 実source `965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d` | 両Loader build／JAR検証・共有test・format・script tests・CI OK成功 |

台帳の build 記録はこのmerge後sourceの実績です。runbookの今回の証拠追記前の準備 PR head
`525f14996b7e1e52228fe11ecdfd17fdafa22d7a` は [PR CI 40097505222](https://github.com/aktnb/stream-tweaks/actions/runs/40097505222)
が成功し、両Loader build／JAR検証・共有test・format・script tests・`CI OK` を含む全7 jobが成功しました。
直前のhead `8617d901317141aab8625b9fb01897a425f4df47` も [PR CI 40097096849](https://github.com/aktnb/stream-tweaks/actions/runs/40097096849) が成功しています。
base同期後のPR head `1beb62dba2996ef54d1fbf80b7d1324234a355bb`（base `d5d8127b040148ff350c8d08acb70191fac45b23` と feature head `f97a0afb5b40e11af626411845648e0222ade4b4` のsynthetic merge commit）も [PR CI 40098924406](https://github.com/aktnb/stream-tweaks/actions/runs/40098924406) で全7 jobが成功しました。
runtime smoke は未確認のままです。

本人 `aktnb` の repository 権限は admin ですが、接続 App の classic branch protection 読取は
`403 Resource not accessible by integration` です。本人の admin 権限だけで接続 App の管理操作が
可能とは判断しません。この管理APIはmanaged App接続の対象外で、権限追加の再試行や別認証経路で迂回しません。
2026-10-24、repository ownerのGitHub UI確認でclassic branch protection ruleなしと確認しました。ruleset APIのreadbackと併せて、下記のbranch保護状態を記録します。

ruleset `23603340`（Push Protection）はユーザー操作で一度disabledになった後、
**2026-10-24 16:10 UTCのreadback**ではactive、対象は `refs/heads/main` と `refs/heads/26.2`、
deletion / non_fast_forward 禁止、bypassなしでした。必須status checkは
`[{"context":"CI OK","integration_id":15368}]` と確認し、CI OKの必須化も反映されています。
`strict_required_status_checks_policy=true`、`do_not_enforce_on_create=false` も確認しています。
接続Appからのclassic branch protection読取は引き続き `403 Resource not accessible by integration` ですが、repository ownerの2026-10-24 UI確認ではclassic ruleなしです。
このrulesetのreadbackで、両branchの `CI OK` 必須設定を確認しました。main作成前に再確認し、2026-10-25の切替後にも同じ設定をreadbackしました。

2026-10-24 12:51 UTC時点のmatching-refs/tagsとRelease一覧は空で、進行中publishはありませんでした。
`plan-new`（0.3.3-beta.1 / beta / both）のdispatch試行は
`403 Must have admin rights to Repository`でrun未作成でした。

その後、ユーザーUIから `Use workflow from: 26.2` で実行した Validate supported targets は成功しました。
全対象の [run 40096170430](https://github.com/aktnb/stream-tweaks/actions/runs/40096170430) は
source SHA `965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d` のFabric／NeoForge両方を検証し、
単一target `26.2-fabric` の [run 40096370197](https://github.com/aktnb/stream-tweaks/actions/runs/40096370197)
も同じsource SHAで成功しました。

続いて、Build の [単一target dispatch run 40097553287](https://github.com/aktnb/stream-tweaks/actions/runs/40097553287)
は `Use workflow from: 26.2`、source SHA `965c06ba1493d07dfda88430b6d2bcd5dc1c8b8d`、target `26.2-fabric` で成功し、
Plan／script tests／format／shared tests／Fabric build／`CI OK` がすべて成功しました。
Release の [公開なし prepare-new run 40097910669](https://github.com/aktnb/stream-tweaks/actions/runs/40097910669)
も同じbranch／source SHAで成功しました。入力は beta `0.3.3-beta.1`、loaders `both` で、Fabric／NeoForge両targetの固定plan計算とbundle buildを実行しました。
GitHub identity確認とGitHub draftへのbundle保存はこのoperationではskipされ、run logにもtag／publication／notificationを行わないことが記録されています。

prepare-new artifactの照合結果:
- plan artifact ID `12561219630` のarchive SHA256は `31eca05f4272947e9ca5b0d82553c921c943e0a3b7715bcbe1becb63ed5130e0`。
  別artifactとbundle内にある `release-plan.json` のSHA256はどちらも `417604896a6c8ddc21a9d576ab6255c11291359375772a94c147742c829bac0a` です。
- bundle artifact ID `12562205038` のarchive SHA256は `e39dae64663235c79022f82a26532668b7b9df97b6f86228cd3a8ce604e8bb59`。
  bundle内 `release-manifest.json` のSHA256は `f564edb84ecd19871bb89dffff16a0787862380259e5004f3d50c48a37532553` で、2 LoaderのJARを含みます。
- artifactを取得し、`release_bundle.py verify` をsource SHAと固定planに対して実行して成功しました。

その後、[Release の plan-new run 40098896694](https://github.com/aktnb/stream-tweaks/actions/runs/40098896694) が `workflow_dispatch` で成功しました。実行refは `26.2`、fixed source SHAは `d5d8127b040148ff350c8d08acb70191fac45b23`、release version `0.3.3-beta.1`、channel `beta`、loaders `both` です。Fabric／NeoForge両targetのplanを計算し、Compute fixed plan jobが成功、bundle build／staging／draft保存はskipされました。公開操作はありません。
plan artifact ID `12561936649`（`release-plan-40098896694-d5d8127b040148ff350c8d08acb70191fac45b23`）のarchive SHA256は `42f49de7820544150ee2190efb763088c6cdad3278cffbab5c0c78bdc7d4a007` です。artifact metadataとrun logでsource branch／SHAおよびtarget集合を照合しました。
切替前のValidate all／subset、Build single target、Release `prepare-new`、`plan-new` のdispatch gateは成功runで確認済みです。2026-10-24 13:45 UTCに、確認済みSHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` から `main` を作成し、`26.2` と同じSHAを指すことを再取得しました。初回push run 40099703840は成功し、`CI OK` も成功しましたが、loader build matrixはskipでした。
main宛てdraft [PR #21](https://github.com/aktnb/stream-tweaks/pull/21) は2026-10-25にmergeされ、merge commitは `de91fd9c7761c0308f9bea095ce41f2c7a1c6914` です。merge後push [run 40163126160](https://github.com/aktnb/stream-tweaks/actions/runs/40163126160) は成功し、`CI OK` も成功しました。docs-only変更のためloader build matrixはskipでした。PR #21にはFabric／NeoForge単target Build、Validate all、Release `plan-new`／`prepare-new` の5 dispatch成功が記録され、すべてsource SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` を使いました。Release操作ではtag／公開／通知を行っていません。runとartifact digestの詳細は [検証記録](main-verification.md) にあります。これらの一覧・公開停止・進行中runは、2026-10-24の準備mergeとmain作成直前に再確認しました。2026-10-25の切替後readbackではtags、Releases、assetsがすべて0件でした。

| 操作 | 必要な repository 権限 | 完了の証拠 |
| --- | --- | --- |
| branch / catalog / run / Tag / Release の照会 | Contents / Actions read 等 | full SHA、取得結果、run URL |
| main 作成 | Contents write（workflow を含む場合の Workflows 権限も確認） | main ref の readback |
| 単 target・全対象・公開なし計画の dispatch | Actions write | 成功した run と immutable SHA |
| classic protection の照会 | Administration read | owner UIでclassic ruleなしを確認。managed App APIは403 |
| default・保護設定の変更と復元 | Administration write と対象管理権限 | 変更後 readback と復元記録 |

権限根拠は [Git reference API](https://docs.github.com/en/rest/git/refs#create-a-reference)、
[dispatch API](https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event)、
[保護 API](https://docs.github.com/en/rest/branches/branch-protection)、
[repository 更新 API](https://docs.github.com/en/enterprise-cloud%40latest/rest/repos/repos#update-a-repository)を参照してください。
現接続には default / 保護設定の更新ツールがありません。repository ownerによる切替後、repository metadataでdefault=`main`をreadbackしました。Rulesetはreadback可能で、`CI OK` がrequired checksに含まれる実設定を切替後にも確認しました。
rulesetのreadbackは利用でき、`CI OK` がrequired checksに含まれる実設定はそこで検証します。

## 切替前に実施したゲート（2026-10-24）

以下は移行前に実施した検証と判断の記録です。切替後の完了状態は末尾の受入条件表を参照してください。

- #8 と #13 を含む先行基盤はPR #18で26.2へ導入済み。上記のPR／push CI成功に加え、
  単 target／全対象 dispatch、公開なしplan／prepareの成功runを確認する。
  **この切替前の検証は準備 PR のmerge前に、既存台帳の権威元・公開元が26.2である `--ref 26.2` で実行する。**
  GitHub UI の `Use workflow from` も `26.2` を選ぶ。Buildの単target、Validate supported targetsのall／subset、
  Releaseの `plan-new`／`prepare-new` のいずれも同じ実行refを使い、mainや準備feature branchでは実行しない。
  main作成後の手順5では、別途mainを実行refとして再検証する。切替前の成功をその代わりにしない。
- 公開担当を決め、Tag／Release／Modrinth への新規公開を停止する。実行中 publish を途中 cancel せず、
  完了または部分成功の状態と release ID／manifest hash を照合する。不明なまま切替えない。
- default、26.2 の full SHA、ruleset／classic protection、Tag と peeled SHA、Release と asset hash を保存する。
- branch 作成・dispatch・管理設定・復旧が正規の認可で実行できることを確認する。
  classic protection読取403の場合は正規UIで有無・設定を確認し、main／26.2へのactive rulesetと
  `CI OK` 必須設定をreadbackで証明するまで準備 PR を merge しない。
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

## 切替時に実施した手順（2026-10-24）

以下は実施時に使った順序の記録です。main作成後の検証と2026-10-25のdefault切替・最終readbackは上の記録および末尾の表に反映しています。

1. 26.2 を base とする準備 PR を作る。`support-targets.json` の `authority_branch` と、
   現行26.2の両 Loader 行の `source_branch` を **すべて `main`** にする。
   MC／Loaderは変えず、実施済みbuildだけsource SHA／run URL付きで記録する。runtime未検証を成功へ書き換えない。
   26.2 はまだ Maintenance にしない。この差分はdraftで準備できるが、上のgateが未達ならmergeしない。
2. 準備 PR の CI は宛先 `base.sha` の旧 catalog を使うため、引き続き26.2として検証される。
   merge SHA の properties と両 Loader build、script tests が成功した証拠を保存して merge する。
   テスト用catalogは `scripts/tests/fixtures/` に固定してあり、実台帳をmainへ変えても
   初期26.2契約テストが壊れないことを確認する。
3. merge 後の26.2 push と26.2宛て復旧 PR は、上記の限定経路で品質 CI を継続する。
   検証元は26.2の固定 SHA、公開元はmainのままであり、新規公開は許可しない。
   main が未作成の短い期間は全対象 resolver や公開計画のmain指定はまだ成功しないので、
   公開停止を維持する。旧 branch の CI 成功をmain上の検証証拠に代用しない。
4. merge 後に API から26.2の最新 full SHA を再取得し、準備 PR を含むことを確認する。
   同じSHAの26.2 push CIが成功し、必須 `CI OK` が付いていることを確認する。
   その **同じ SHA** から新規 main を作る。main が既存なら上書き／force updateせず、その由来を再確認する。
   26.2の履歴や ref を変更せず、作成した main の SHA を readback で照合する。
5. main は最初から `authority_branch=main`／`source_branch=main` の台帳を持つので、
   宛先catalogの特例なしにmain上の通常PR CIを実行できる。
   main宛て確認 PRに加え、ここからは **`--ref main`（UIの `Use workflow from: main`）** を選び、
   `26.2-fabric` と `26.2-neoforge` の単 target dispatch、全 supported dispatch、
   `plan-new` および公開なし `prepare-new` を実行する。release操作名は導入済みworkflowの入力と照合する。
   Tag／公開 adapter を起動する操作は移行確認に使用しない。
6. 成功した run の full source SHA／MC／Loader／実 JDK／結果を保存する。
   全対象の resolver が main を一度だけ解決し、同じ固定 SHA を両 Loader に使うこと、
   台帳がmain以外の同MC公開元を示さないことを確認する。
7. 切替前にmainと26.2の保護設定をreadbackし、mainの `CI OK` 必須、GitHub Actions由来のcheck context、レビュー・更新制約を確認しました。2026-10-25にdefaultをmainへ切替え、その後default／workflow discovery／保護を再取得しました。
8. 2026-10-25に26.2・Tag・Release・assetの一覧を再照合し、削除・rename・差替えがないことを確認しました。matching tags、GitHub Releases、release assetsは0件です。切替後readbackを移行gateの完了記録に含めました。この検証では公開操作を行っていません。残した26.2は休止中の履歴であり二重公開元ではありません。

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
| main上のPR／単 target／全対象CI・plan成功 | 各run URL、full SHA、target、JDK、結果、plan／bundle hash | main宛てPR #21 はmerge済み。merge後push run [40163126160](https://github.com/aktnb/stream-tweaks/actions/runs/40163126160) はmain SHA `de91fd9c7761c0308f9bea095ce41f2c7a1c6914` で成功、`CI OK` 成功、docs-onlyのためloader matrix skip。5件の事前dispatch成功とartifact digestはmain-verification.md |
| 同MCの公開元main一つ | main・26.2両catalogのSHAと検証出力 | authority/sourceはmain。main=`de91fd9c7761c0308f9bea095ce41f2c7a1c6914`、26.2=`da51f9d7e4217e8734ee17fcc2780164595f6e87`、default=`main` |
| 既存履歴／Tag／Release保存 | 前後のref・peeled SHA・asset hash一覧 | 2026-10-25の切替後readbackでmain/26.2 refsを照合し、matching tags 0件、GitHub Releases 0件、assets 0件を確認 |
| CI OK必須と対象保護 | ruleset実設定／owner UI readback | Ruleset 23603340は両branchでactive、strict必須 `CI OK`、deletion/non-fast-forward禁止、bypassなし。classic ruleなしは2026-10-24にowner UI確認 |
| default切替・失敗時復旧 | 本手順と実施者／日時／復元結果 | 2026-10-25にdefault=`main`をreadback。失敗時は本書の復旧手順に従う。切替後の復旧操作はなし |
| 切替後のworkflow・refs・保護・配布履歴readback | main上のworkflow一覧、branch SHA、ruleset、tags／Releases／assets | `build.yml`、`release.yml`、`target-build.yml`、`validate-all.yml` を確認。main/26.2 SHAとRuleset 23603340を再確認し、tags／Releases／assetsは0件 |

Issue #14 の切替後readback条件は記録済みです。この変更ではPRをmergeせず、公開操作も行っていません。
