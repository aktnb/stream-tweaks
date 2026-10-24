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

GitHub Actions UI では `Use workflow from: main` を選びます。2026-10-24 15:51 UTC時点で Fabric単target Buildは成功済み、残り4件は未実施です.

| Workflow / operation | 入力 | 結果 |
| --- | --- | --- |
| Build | target `26.2-fabric` | [run 40108697819](https://github.com/aktnb/stream-tweaks/actions/runs/40108697819) 成功、source SHA `da51f9d7e4217e8734ee17fcc2780164595f6e87`、CI OK成功。artifact `build-artifacts-26.2-fabric-da51f9d7e4217e8734ee17fcc2780164595f6e87`（ID `12565806993`、SHA256 `56cfa47ab35bb8ecf7dec35c099a396848ab897e7fe1f7a2574cf866ae7e9a07`） |
| Build | target `26.2-neoforge` | 未実施 |
| Validate supported targets | targets `all` | 未実施 |
| Release / `plan-new` | version `0.3.3-beta.1`, channel `beta`, loaders `both`, CurseForge required `false` | 未実施 |
| Release / `prepare-new` | version `0.3.3-beta.1`, channel `beta`, loaders `both`, CurseForge required `false` | 未実施 |

Release の2操作は計画／bundle準備までの非公開検証です。tag作成、GitHub Releases／Modrinth公開、Discord通知を実行してはいけません。残る4 dispatchの結果、source SHA、target、artifact ID／hash をこの文書へ追記します。

## 公開状態

- main 作成前の保存済み一覧では Tag 0件、GitHub Release 0件でした。
- main 作成後の GitHub Releases API readback も空配列でした。今回の検証で公開操作はありません。
- 完了前に Tag と Release／asset の一覧を再取得し、既存状態が維持されたことを照合します。

## 完了判定

[PR #21 の main 宛て CI run 40108736789](https://github.com/aktnb/stream-tweaks/actions/runs/40108736789) は head SHA `f1263543323c0c84892b45571265b72387342d86` で成功しました。Plan、format、shared tests、script tests、`CI OK` が成功し、docs-only差分のためloader build matrixはskipでした。これはmain宛てPR CIの確認ですが、Loaderの単 target／全対象検証の代わりにはなりません.

この PR は引き続き draft です。残り4 dispatchが成功し、artifact／source SHA を照合するまで default branch は変更しません。runbookの他ゲート、最終readback、復旧手順の確認が済むまで Issue #14 を閉じません.
