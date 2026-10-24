# 固定 bundle の不足先公開と再開

2026-10-24 に旧 `mc-publish` 経路を廃止しました。`release.yml` は同じ固定 plan と bundle を
`prepare-new` → `github-stage` → `publish` の順に扱い、公開時にビルドしません。
準備は[不変 bundle](release-bundles.md)、永続保管は[GitHub draft 保管](github-release-staging.md)を参照してください。

## 操作

`prepare-new` の summary で full source SHA、manifest SHA256、成功 run ID を保存します。
`github-stage` へその3値と、固定した `release_version` / `release_minecraft` / `channel` / `loaders` を渡します。
`github-stage` は元 run が同じ repository の成功した `workflow_dispatch` であることを API で確認し、
plan・manifest・provenance と一致する artifact だけを受け付けます。PR / fork の artifact は公開入力に使えません。

draft 全体の再ダウンロード検証が成功した後、同じ値で `operation=publish` を実行します。
`publish` は GitHub draft から同一バイナリを復元し、Modrinth の不足先だけを作成します。
`bundle_run_id` は `github-stage` だけに指定し、`github-resume` / `publish` では空にします。
artifact が期限切れでも、draft に保存済みなら `github-resume` / `publish` が利用できます。
draft の bundle も欠けている場合は停止し、無検証の再ビルド・削除・差替えはしません。

実行 ref が main へ移っても、`release_source_branch=26.2` のように**保存 plan の元 branch**を指定します。
再開は最新 catalog に対象集合を再解決せず、元 plan と明示入力の完全一致を要求します。
CurseForge を plan に指定した場合、project / required の指定も同一にします。
新規 plan のみ、その時点の catalog の唯一の公開元を使います。

ローカル CLI での同等操作（token は環境から読み、値を引数・ログへ出さない）:

```sh
python3 scripts/publish_modrinth.py reconcile --bundle "$BUNDLE_DIR" \
  --source-sha "$SOURCE_SHA" --manifest-sha256 "$MANIFEST_SHA256" \
  --state-output "$STATE_DIR/publish-state.json"

python3 scripts/publish_modrinth.py publish --bundle "$BUNDLE_DIR" \
  --source-sha "$SOURCE_SHA" --manifest-sha256 "$MANIFEST_SHA256" \
  --state-output "$STATE_DIR/publish-state.json"
```

`reconcile` は Modrinth の作成・照合まで、`publish` は全必須先の成功後に GitHub draft を解除します。
`GITHUB_TOKEN` は repository の Contents write、`MODRINTH_TOKEN` は固定 project の version 作成・照会権限が必要です。
未設定では公開処理は停止します。workflow の read-only plan / build job は Modrinth token を受け取りません。
Discord token はなく、任意の `DISCORD_WEBHOOK_URL` を確定成功後だけ使います。未設定は通知を省略します。

## 一致判定と失敗状態

Modrinth version は `<tag>-<loader>` のままです。project、version number、MC、Loader、channel、
表示名、ローダー別 notes、client-only environment、依存 metadata、通常 JAR 1個の名前・サイズ・SHA512 を照合します。
さらに SHA512 hash API で同じ version ID へ解決することを確認します。内部 SHA256 も publish-state に保存します。
Fabric は Fabric API を required、Mod Menu を optional とし、NeoForge はこの2つを要求しません。
既存が一致すれば POST せず再利用、相違・重複 version・hash API との不一致は停止します。

外部 POST の前に、draft へ `publish-state-<target>-<destination>-attempt.json` を保存し、
GET で内容を照合します。成功確認後は別の `...-verified.json` に外部 ID と artifact hash を記録します。
これらは bundle と分離した append-only asset で、既存 record の上書き・削除はしません。
manifest / JAR / notes は公開の進行によって変わりません。

POST の応答を失っても、再照会で version と hash が一致すれば成功と認識します。
attempt が保存済みで成功を証明できない場合は `unknown` として停止します。404 は再 POST の許可になりません。
ローカル state や Actions artifact が失われても、この永続 attempt によって自動二重投稿を防ぎます。
Fabric 成功後、NeoForge の POST 前に run が停止した場合、再実行は Fabric を照合して NeoForge だけを作成します。
NeoForge の POST 結果自体が不明な場合は、照会で成功を証明するまで停止を維持します。

全必須先が成功するまでは GitHub draft を解除せず、workflow summary と別 artifact に状態を表示・保存します。
必須 CurseForge が未完了なら finalize も止まります。任意先の必須性は元 plan の値を使います。
GitHub / Modrinth 間の原子的公開は保証しません。部分成功から同じ ID・同じ bundle で再開します。

## API の根拠と検証範囲

- [Create a version](https://docs.modrinth.com/api/operations/createversion/): multipart の data / file_parts、environment。
- [List project's versions](https://docs.modrinth.com/api/operations/getprojectversions/): 全 version の一覧（pagination なし）。
- [Get version from hash](https://docs.modrinth.com/api/operations/versionfromhash/): SHA1 / SHA512、SHA256 は指定不可。
- [Fabric API project](https://api.modrinth.com/v2/project/fabric-api) と
  [Mod Menu project](https://api.modrinth.com/v2/project/modmenu): 固定 project ID と依存の識別。

mock は部分成功、既存 metadata/hash 相違、応答喪失、ローカル state 消失、journal 保存失敗、
main 移行後の元 plan 再利用、必須先未完了での draft 維持を検証します。
実 project への version 作成・GitHub Release 公開・Discord 送信は行っていません。
