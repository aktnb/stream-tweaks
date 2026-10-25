# main 移行の切替後検証記録（Issue #14）

この文書はmain切替後の検証記録です。2026-10-25 05:02:25–05:02:27 UTCのrepository metadata/ref snapshotではdefault=`main`、main=`de91fd9c7761c0308f9bea095ce41f2c7a1c6914`（PR #21 merge commit）、26.2=`da51f9d7e4217e8734ee17fcc2780164595f6e87` でした。この値は当該時点の記録で、後続のmerge/push後はlive refを再取得してください。切替後のRuleset・workflow一覧・配布履歴も同時点でreadbackしました。merge後push run [40163126160](https://github.com/aktnb/stream-tweaks/actions/runs/40163126160) はsuccess、`CI OK` success、docs-only変更のためmatrix build jobはskipでした。matching tags、GitHub Releases、release assetsは0件で、検証・記録更新では公開操作を行っていません。

## Branch と保護設定

- `main` は2026-10-24に `da51f9d7e4217e8734ee17fcc2780164595f6e87` から作成され、2026-10-25にPR #21をmergeしました。2026-10-25 05:02:25–05:02:27 UTCのreadback snapshotではmain=`de91fd9c7761c0308f9bea095ce41f2c7a1c6914`、`26.2`=`da51f9d7e4217e8734ee17fcc2780164595f6e87` でした。後続のmain SHAはその後のmerge/pushで進みます。
- 2026-10-25 05:02:25–05:02:27 UTCのrepository metadata snapshotではdefault branchは `main` でした。
- Ruleset `23603340`（`Push Protection`）の2026-10-25 05:02:25–05:02:27 UTC readbackでは、対象は `refs/heads/main` と `refs/heads/26.2`、必須 `CI OK`（integration ID `15368`、strict=true）、bypassなし、deletion/non-fast-forward禁止でした。初回readbackは2026-10-24 13:58 UTCです。
- repository owner は2026-10-24のUI確認で、`main` と `26.2` に適用される classic branch protection ruleがないことを確認しました。Rulesetは別途上記のとおり有効です。
## 切替後のworkflow一覧

2026-10-25 05:02:25–05:02:27 UTCのActions API workflow一覧:

| name | path | state |
| --- | --- | --- |
| Build | `.github/workflows/build.yml` | active |
| Release | `.github/workflows/release.yml` | active |
| Target build | `.github/workflows/target-build.yml` | active |
| Validate supported targets | `.github/workflows/validate-all.yml` | active |

同時点のrepository Contents APIでは `.github/workflows` directoryに上記4ファイルが存在しました。これはファイルの存在確認で、Actions APIのname/path/state一覧とは別のreadbackです。

切替後push run [40163126160](https://github.com/aktnb/stream-tweaks/actions/runs/40163126160) は `Build` workflow（`.github/workflows/build.yml`）の別個の実行記録です。`CI OK` success、docs-only変更のためmatrix build jobはskipでした。

## main 初回 push の CI

[Build run 40099703840](https://github.com/aktnb/stream-tweaks/actions/runs/40099703840) は ref `main`、source SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` で成功しました。Plan、format、shared tests、script tests と `CI OK` は成功し、loader build matrix は skip でした。branch 作成時の push run なので、この結果は loader の単 target／全対象検証の代わりにはなりません。

GitHub Actions の 2026-10-24 13:58 UTC readbackは初回push後の履歴です。その後の5件のmain向けdispatch結果は以下に記録します。

## main 向け dispatch 状況

すべて GitHub Actions UI の `Use workflow from: main` で実行し、source SHA は `da51f9d7e4217e8734ee17fcc2780164595f6e87` でした。Build／Validate all は全必須jobが成功し、実JDKは Temurin 25.0.4 です。

| Workflow / operation | 入力 | 結果 |
| --- | --- | --- |
| Build | target `26.2-fabric` | [run 40108697819](https://github.com/aktnb/stream-tweaks/actions/runs/40108697819) 成功、CI OK成功 |
| Build | target `26.2-neoforge` | [run 40109082948](https://github.com/aktnb/stream-tweaks/actions/runs/40109082948) 成功、CI OK成功 |
| Validate supported targets | targets `all` | [run 40109129345](https://github.com/aktnb/stream-tweaks/actions/runs/40109129345) 成功。Fabric／NeoForge両方を検証し、All supported targets OK成功 |
| Release / `plan-new` | version `0.3.3-beta.1`, channel `beta`, loaders `both`, CurseForge required `false` | [run 40109428132](https://github.com/aktnb/stream-tweaks/actions/runs/40109428132) 成功。固定planのみを作成し、build／公開jobはskip |
| Release / `prepare-new` | version `0.3.3-beta.1`, channel `beta`, loaders `both`, CurseForge required `false` | [run 40109691495](https://github.com/aktnb/stream-tweaks/actions/runs/40109691495) 成功。2 Loader bundleをbuildし `release_bundle.py verify` が成功。GitHub staging identity／draft保存はskip |

artifact archive SHA256:
- Fabric Build run 40108697819: ID `12565806993`, `build-artifacts-26.2-fabric-da51f9d7e4217e8734ee17fcc2780164595f6e87`, SHA256 `56cfa47ab35bb8ecf7dec35c099a396848ab897e7fe1f7a2574cf866ae7e9a07`
- NeoForge Build run 40109082948: ID `12565359965`, SHA256 `2847534d5875c3db926941254086a8c2c48898e69e060b7fe074bacb32dc3a3d`
- Validate all run 40109129345: validation-plan ID `12565857244`, SHA256 `bf3e689594d0421b27cffe551f30fbea67f15b441cceeecb8cae3f5136cc6292`; Fabric artifact ID `12566041366`, SHA256 `8d1d8c509e0224b51dc6b5fac0ef074b187c3a4155ea30d5aa278af8c418dcde`; NeoForge artifact ID `12565489667`, SHA256 `26ed6cdbf12ecc5969fca732a0794bdf16dc86d7ad4c54e9fa7009e826c06439`
- Release plan-new run 40109428132: ID `12565169148`, SHA256 `69008f7bf418a9d20fc05371c35aec9bda90e4b54c0616988e8e002198189d12`
- Release prepare-new run 40109691495: plan ID `12565868357`, SHA256 `df7fb87a572a8c4da568dc8cb403cc1529db2452acb6fd6aa9e5c488d5511192`; bundle ID `12565717948`, SHA256 `d102905e156d7f62f4fc26728a96efc01346b3d80bfe2019b248ff499066276d`

Release run logs confirm `plan-new`／`prepare-new` used source branch `main`, version `0.3.3-beta.1`, channel `beta`, loaders `both`, and CurseForge required `false`. `prepare-new` verified the fixed plan and bundle; no tag, GitHub Release, Modrinth publication, or Discord notification was performed.

## 切替後の公開状態

- 2026-10-25 05:02:25–05:02:27 UTCの切替後readback snapshotでmatching tags 0件、GitHub Releases 0件、release assets 0件を確認しました。
- main向けdispatchおよび切替後の検証でTag、GitHub Release、Modrinth公開、Discord通知を実行していません。
- PR #21の検証内容とartifact digestは下記のとおりです。これらは切替前のmain上で実行した確認で、使用source SHAは `da51f9d7e4217e8734ee17fcc2780164595f6e87` です。

## 完了判定と切替後readback

PR #21はmerge済みで、merge commitは `de91fd9c7761c0308f9bea095ce41f2c7a1c6914` です。merge後push [run 40163126160](https://github.com/aktnb/stream-tweaks/actions/runs/40163126160) は2026-10-25 04:46:49–04:48:28 UTCに成功しました。Plan、format、shared tests、script tests、`CI OK` は成功し、docs-only差分のためloader build matrixはskipでした。

2026-10-25 05:02:25–05:02:27 UTC snapshotのrepository metadata/refではdefault=`main`、main=`de91fd9c7761c0308f9bea095ce41f2c7a1c6914`、`26.2`=`da51f9d7e4217e8734ee17fcc2780164595f6e87` を確認しました。後続のmerge/push後はlive refを再取得してください。Ruleset 23603340も同時点でactive、strict必須 `CI OK`、deletion/non-fast-forward禁止、bypassなしでした。workflow API一覧のname/path/stateは上記表、Contents APIは4ファイルの存在を別途確認しました。切替後push run 40163126160はBuild workflowのsuccess runで、`CI OK` success、matrix build skipです。matching tags、GitHub Releases、release assetsはそれぞれ0件です。

切替前の5件のmain向けdispatchはすべて成功しています。これによりIssue #14の切替後readback条件を満たしています。この検証と文書更新では公開操作を行っていません。
