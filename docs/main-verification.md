# main 移行の切替後検証記録（Issue #14）

この文書はmain切替後の検証記録です。2026-10-25にPR #21がmergeされ、default branchは `main` へ切替済みです。切替後のref・workflow・ruleset・配布履歴のreadbackを完了しました。merge後push run [40163126160](https://github.com/aktnb/stream-tweaks/actions/runs/40163126160) は成功し、`CI OK` も成功しました。docs-only変更のためloader build matrixはskipでした。
mainのmerge commitは `de91fd9c7761c0308f9bea095ce41f2c7a1c6914`、`26.2` は `da51f9d7e4217e8734ee17fcc2780164595f6e87` を保持しています。切替後readbackでmatching tags、GitHub Releases、release assetsはいずれも0件で、公開操作は行っていません。

## Branch と保護設定

- `main` は2026-10-24に `da51f9d7e4217e8734ee17fcc2780164595f6e87` から作成され、2026-10-25にPR #21をmergeしました。現在のmain SHAは `de91fd9c7761c0308f9bea095ce41f2c7a1c6914`、`26.2` は作成時のSHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` のままです。切替後readbackで両refを確認しました。
- repository metadata の default branch は `main` です。2026-10-25の切替後にreadbackしました。
- Ruleset `23603340`（`Push Protection`）は Active、対象は `refs/heads/main` と `refs/heads/26.2` です。required status check は `CI OK`（integration ID `15368`）、strict=true、bypass actorなしです。deletion と non-fast-forward も禁止されています。Ruleset API の初回readbackは2026-10-24 13:58 UTC時点です。2026-10-25の切替後readbackでも同じ保護を確認しました。
- repository owner は2026-10-24のUI確認で、`main` と `26.2` に適用される classic branch protection ruleがないことを確認しました。Rulesetは別途上記のとおり有効です。
- 2026-10-25のrepository Contents API readbackでは `.github/workflows` に `build.yml`、`release.yml`、`target-build.yml`、`validate-all.yml` の4ファイルがあることを確認しました。別途、切替後push run 40163126160は `Build` workflow（`.github/workflows/build.yml`）で、`CI OK` はsuccess、docs-onlyのためmatrix build jobはskipでした。

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

- 2026-10-25の切替後readbackでmatching tags 0件、GitHub Releases 0件、release assets 0件を確認しました。
- main向けdispatchおよび切替後の検証でTag、GitHub Release、Modrinth公開、Discord通知を実行していません。
- PR #21の検証内容とartifact digestは下記のとおりです。これらは切替前のmain上で実行した確認で、使用source SHAは `da51f9d7e4217e8734ee17fcc2780164595f6e87` です。

## 完了判定と切替後readback

PR #21はmerge済みで、merge commitは `de91fd9c7761c0308f9bea095ce41f2c7a1c6914` です。merge後push [run 40163126160](https://github.com/aktnb/stream-tweaks/actions/runs/40163126160) は2026-10-25 04:46:49–04:48:28 UTCに成功しました。Plan、format、shared tests、script tests、`CI OK` は成功し、docs-only差分のためloader build matrixはskipでした。

切替後readbackではrepository default=`main`、main SHA `de91fd9c7761c0308f9bea095ce41f2c7a1c6914`、`26.2` SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87`、Ruleset 23603340 active／strict必須 `CI OK`／deletion・non-fast-forward禁止／bypassなしを確認しました。repository Contents API readbackでは `.github/workflows` に `build.yml`、`release.yml`、`target-build.yml`、`validate-all.yml` があることを確認しました。push run 40163126160は `Build` workflow（`.github/workflows/build.yml`）の実行で、`CI OK` success、matrix build skipでした。matching tags、GitHub Releases、release assetsはそれぞれ0件です。

切替前の5件のmain向けdispatchはすべて成功しています。これによりIssue #14の切替後readback条件を満たしています。この検証と文書更新では公開操作を行っていません。
