# main 移行検証の途中経過（Issue #14）

この文書は main 作成後の確認記録です。ゲート未完了のため、Issue #14 の完了や公開再開を示しません。現在の default branch は `26.2` のままで、Tag／Release の公開操作は行っていません。実行待ちの workflow 結果が揃ったらこの記録を更新します。

## Branch と保護設定

- `main` は `26.2` の確認済み最新 SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` から作成されました。2026-10-24 の readback で両 branch がこの SHA を指すことを確認しました。
- repository metadata の default branch は引き続き `26.2` です。default 切替は未実施です。
- Ruleset `23603340`（`Push Protection`）は Active、対象は `refs/heads/main` と `refs/heads/26.2` です。required status check は `CI OK`（integration ID `15368`）、strict=true、bypass actorなしです。deletion と non-fast-forward も禁止されています。Ruleset API の readback は 2026-10-24 13:58 UTC 時点でこの設定を返しました。
- repository owner の UI 確認で、`main` と `26.2` に適用される classic branch protection rule はありませんでした。Ruleset は別途上記のとおり有効です。

## main 初回 push の CI

[Build run 40099703840](https://github.com/aktnb/stream-tweaks/actions/runs/40099703840) は ref `main`、source SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` で成功しました。Plan、format、shared tests、script tests と `CI OK` は成功し、loader build matrix は skip でした。branch 作成時の push run なので、この結果は loader の単 target／全対象検証の代わりにはなりません。

GitHub Actions の 2026-10-24 13:58 UTC readback では、main の run はこの初回 push run のみでした。次の main 向け dispatch はまだ未実施です。

## main 向け dispatch 状況

GitHub Actions UI では `Use workflow from: main` を選びます。Fabric／NeoForge単target、Validate all、Release plan-newは成功済みで、prepare-newが未実施です.

| Workflow / operation | 入力 | 結果 |
| --- | --- | --- |
| Build | target `26.2-fabric` | [run 40108697819](https://github.com/aktnb/stream-tweaks/actions/runs/40108697819) 成功、source SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87`、CI OK成功。artifact `build-artifacts-26.2-fabric-da51f9d7e4217e8734ee17fcc2780164595f6e87`（ID `12565806993`、SHA256 `56cfa47ab35bb8ecf7dec35c099a396848ab897e7fe1f7a2574cf866ae7e9a07`） |
| Build | target `26.2-neoforge` | [run 40109082948](https://github.com/aktnb/stream-tweaks/actions/runs/40109082948) 成功、source SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87`、CI OK成功。artifact ID `12565359965`、SHA256 `2847534d5875c3db926941254086a8c2c48898e69e060b7fe074bacb32dc3a3d` |
| Validate supported targets | targets `all` | [run 40109129345](https://github.com/aktnb/stream-tweaks/actions/runs/40109129345) 成功。Fabric／NeoForge両方をsource SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87` で検証、All supported targets OK成功。validation-plan ID `12565857244` / SHA256 `bf3e689594d0421b27cffe551f30fbea67f15b441cceeecb8cae3f5136cc6292`。Fabric artifact ID `12566041366` / SHA256 `8d1d8c509e0224b51dc6b5fac0ef074b187c3a4155ea30d5aa278af8c418dcde`、NeoForge artifact ID `12565489667` / SHA256 `26ed6cdbf12ecc5969fca732a0794bdf16dc86d7ad4c54e9fa7009e826c06439` |
| Release / `plan-new` | version `0.3.3-beta.1`, channel `beta`, loaders `both`, CurseForge required `false` | [run 40109428132](https://github.com/aktnb/stream-tweaks/actions/runs/40109428132) 成功、source SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87`。Compute fixed plan (no publication)成功、他のbuild／staging jobはskip。artifact ID `12565169148` / SHA256 `69008f7bf418a9d20fc05371c35aec9bda90e4b54c0616988e8e002198189d12` |
| Release / `prepare-new` | version `0.3.3-beta.1`, channel `beta`, loaders `both`, CurseForge required `false` | 未実施 |

Release の2操作は計画／bundle準備までの非公開検証です。tag作成、GitHub Releases／Modrinth公開、Discord通知を実行してはいけません。plan-newは完了し、prepare-newが最後の未実施dispatchです。

## 公開状態

- main 作成前の保存済み一覧では Tag 0件、GitHub Release 0件でした。
- main 作成後の GitHub Releases API readback も空配列でした。今回の検証で公開操作はありません。
- 完了前に Tag と Release／asset の一覧を再取得し、既存状態が維持されたことを照合します。

## 完了判定

[PR #21 の main 宛て CI run 40108971814](https://github.com/aktnb/stream-tweaks/actions/runs/40108971814) は head SHA `5f16f669b82ee18649b65eba77441ed317215085` で成功しました。Plan、format、shared tests、script tests、`CI OK` が成功し、docs-only差分のためloader build matrixはskipでした。これはmain宛てPR CIの確認ですが、Loaderの単 target／全対象検証の代わりにはなりません.

この PR は引き続き draft です。残り1 dispatch
