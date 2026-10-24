# main 移行検証の途中経過（Issue #14）

この文書は main 作成後の検証記録です。main 向けのPR CIと5件のworkflow dispatchは完了しました。default branchの切替と最終readbackが済むまで、Issue #14 の完了や公開再開を示しません。現在のdefault branchは `26.2` で、Tag／Release の公開操作は行っていません。

## Branch と保護設定

- `main` は `26.2` の確認済み最新 SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` から作成されました。2026-10-24 の readback で両 branch がこの SHA を指すことを確認しました。
- repository metadata の default branch は引き続き `26.2` です。default 切替は未実施です。
- Ruleset `23603340`（`Push Protection`）は Active、対象は `refs/heads/main` と `refs/heads/26.2` です。required status check は `CI OK`（integration ID `15368`）、strict=true、bypass actorなしです。deletion と non-fast-forward も禁止されています。Ruleset API の readback は 2026-10-24 13:58 UTC 時点でこの設定を返しました。
- repository owner の UI 確認で、`main` と `26.2` に適用される classic branch protection rule はありませんでした。Ruleset は別途上記のとおり有効です。

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

## 公開状態

- main 作成前の保存済み一覧では Tag 0件、GitHub Release 0件でした。
- 5件のmain向けdispatch後、2026-10-24 16:06 UTC頃のreadbackでも matching tags と GitHub Releases はともに0件でした。
- 検証中に公開操作はありません。default切替後にTag／Release／assetを再取得し、最終照合します。

## 完了判定

[PR #21 の最新 main 宛て CI run 40110010992](https://github.com/aktnb/stream-tweaks/actions/runs/40110010992) は head SHA `2ebba9f9c41a653053f3801d4a99ff55246fa82f` で成功しました。Plan、format、shared tests、script tests、`CI OK` が成功し、docs-only差分のためloader build matrixはskipでした。

5件のmain向けdispatchはすべて成功しました。dispatch後のreadbackでTag／GitHub Releaseは0件、mainと26.2は同じSHA、Rulesetは両branchでactiveかつ `CI OK` 必須でした。defaultはまだ `26.2` です。default切替とその後のref／Ruleset／workflow discovery／Tag／Releaseの最終readbackが済むまでdraftを維持し、Issue #14を閉じません。
