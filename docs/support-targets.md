# 対応台帳と target 選択

`support-targets.json` を CI・公開計画の対象選択の入口にする。現行の2 target は
`26.2-fabric` / `26.2-neoforge`。ModMenu は Fabric 限定で、NeoForge でも設定画面自体は利用できる。
台帳の権威元と両行の公開元は `main` です。mainは2026-10-24に `da51f9d7e4217e8734ee17fcc2780164595f6e87` から作成され、2026-10-25にPR #21がmergeされました。2026-10-25 05:02:25–05:02:27 UTCのreadback snapshotではmain=`de91fd9c7761c0308f9bea095ce41f2c7a1c6914`（PR #21 merge commit）、`26.2`=`da51f9d7e4217e8734ee17fcc2780164595f6e87`、default=`main` でした。後続merge後のrefはlive readbackで確認してください。
2026-10-25 05:02:25–05:02:27 UTCの切替後readback snapshotでmain/26.2 refs、default、Rulesetを照合しました。Contents APIでは `.github/workflows` に4ファイルが存在することを確認しました。Actions APIのworkflow name/path/state一覧は [切替後検証記録](main-verification.md) に記録しています。push run 40163126160は別途 `Build` workflow（`.github/workflows/build.yml`）のsuccess runで、`CI OK` success、matrix build skipでした。
matching tags、GitHub Releases、release assetsはいずれも0件で、この検証では公開していません。移行の実施記録は [main-migration.md](main-migration.md) を参照してください。
方針は [support-policy.md](support-policy.md)、版番号は [versioning.md](versioning.md) を参照。

## スキーマと権威元

schema version は1。トップレベルは `schema_version` / `authority_branch` / `targets`。
現在の台帳は `authority_branch=main` です。各targetの `source_branch` も `main` です。2026-10-25 05:02:25–05:02:27 UTCのref/default snapshotではmain=`de91fd9c7761c0308f9bea095ce41f2c7a1c6914`、`26.2`=`da51f9d7e4217e8734ee17fcc2780164595f6e87`、default=`main` でした。これらは記録時点の値で、後続merge後のcurrent refはlive readbackで確認します。台帳の文字列だけではdefault切替を宣言せず、repository metadataとrefを別途readbackします。
これは全体台帳の権威元であり、各 target の `source_branch` とは用途が異なる。
各 source checkout の `gradle.properties` が実際のビルド値の真実で、台帳を使って上書きしない。

| 行のキー | 意味 |
| --- | --- |
| `id` | `<minecraft>-<loader>`。重複不可 |
| `minecraft`, `loader` | 正確な MC 版、`fabric` / `neoforge` |
| `lifecycle` | 系列の保守段階 `active` / `maintenance` / `eol` |
| `support` | 行の提供可否 `supported` / `testing`（検証中）/ `unsupported`（未対応） |
| `source_branch` | 唯一の公開元。`main`、MC 数値 branch、`mc/<MC>` のみ |
| `java_version` | 必要 Java major（正の整数）。properties と照合 |
| `features`, `limitations` | 機能と環境固有制限の文字列配列 |
| `verification` | `build` と `runtime_smoke` の独立した検証記録 |

同 MC の行は1つの公開元と Java を共有する。supported な Active / Maintenance は各最大1 MC。
EOL は `lifecycle=eol, support=unsupported` とし、選択から除外する。
新規 target を testing から supported に昇格するときは build/check と client smoke の証拠を確認する。

検証記録は `status`（`unverified` / `passed` / `failed`）、`date`（ISO日付または null）、`evidence`（証拠配列）。
実施済み結果は日付と証拠が必須。未実施は date=null / evidence=[] とし、日付を捏造しない。
初期 supported 行は既存実装を表し、runtime 検証済みの宣言ではない。各検証記録を別途参照する。
Loader や依存の版を台帳へ重複転記せず、対象 checkout の properties / build 設定に委ねる。

## CLI

リポジトリルートから Python 標準ライブラリだけで実行できる。

```sh
python3 scripts/validate_targets.py --all-supported
python3 scripts/validate_targets.py --target 26.2-fabric
python3 scripts/validate_targets.py --loader neoforge
python3 scripts/validate_targets.py --minecraft 26.2
python3 scripts/validate_targets.py --target 26.2-fabric --target 26.2-neoforge
python3 scripts/validate_targets.py --all-supported --properties gradle.properties --source-branch main
```

出力は `{schema_version, authority_branch, targets}` の決定的 JSON（ID 順、キー順固定）。
フィルタ省略は全 supported、`--all-supported` は明示表記で他フィルタとは併用しない。
単一 target・loader・MC の選択でもカタログ全体を検証する。未知 target / 不正 ref / 重複 /
同 MC 公開元競合 / 型不正 / 空選択は失敗する。
testing / unsupported / EOL は全選択から除外し、明示 target 指定はエラーとする。

properties 照合には `--properties` / `--source-branch` を必ず対で渡す。
上の main 指定は台帳と properties の値の照合であり、main ref の存在確認ではない。
選択行すべての MC / Java / source branch、Loader 設定の存在を照合するため、
複数 branch の全体台帳を1 checkout の properties だけで照合しない。
全対象 CI では各 source branch を SHA へ固定してから、当該 checkout ごとに照合する。
このスクリプトは ref をネットワーク解決せず、branch の存在・CI成功・runtime 動作は別の証拠で確認する。
秘密情報は入力にしない。

## Python API と検証

`scripts/validate_targets.py` は `load_catalog(path)` / `validate_catalog(data)`、
`select_targets(catalog, target_ids=None, loader=None, minecraft=None)`、
`load_properties(path)` / `validate_properties(targets, properties, source_branch=...)` を公開する。
選択フィルタと source_branch はキーワード引数。
失敗は `ValidationError`、正常時の台帳・選択行は元を変更しないコピー。
後続 CI / release resolver はこの API を再利用し、独自に異なる support 判定を実装しない。

```sh
python3 -m py_compile scripts/validate_targets.py
python3 -m unittest discover scripts/tests
```
