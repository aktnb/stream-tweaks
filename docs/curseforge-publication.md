# 任意の CurseForge 公開（Issue #17）

CurseForge は初期設定では使用しません。plan に project がなければ `skipped` とし、クライアント生成・
API 呼出し・secret 要求を行わず GitHub / Modrinth だけで完了します。
project の作成、secret 登録、実公開はこの実装・mock 検証では行っていません。

## 導入前の確認

1. Minecraft Mod の既存 project ID と、所有者／チームのファイル upload 権限を作者画面で確認します。
   Upload token の所有者がその project に投稿できることを確認し、結果だけを記録します。
2. Upload API 用 `CURSEFORGE_TOKEN` と Core REST API 用 `CURSEFORGE_API_KEY` を別々に用意します。
   前者だけでは file ID・hash 照合はできません。API key の発行・利用条件、対象 project の読取範囲を確認してください。
3. 作者画面で既存・審査待ち・非公開ファイルを確認します。導入前の未追跡 upload がある場合は停止し、
   API で file ID・target・hash を照合できるまで新規投稿しません。以後の投稿は必ず永続 journal を経由します。
4. 固定 plan に project を指定して prepare した bundle で、次の GET-only preflight を実行します。
   値をコマンド引数に出すのは SHA とパスだけで、token / API key は環境から読みます。

```sh
python3 scripts/publish_curseforge.py preflight --bundle "$BUNDLE_DIR" \
  --source-sha "$SOURCE_SHA" --manifest-sha256 "$MANIFEST_SHA256"
```

preflight は project の Minecraft 識別、公式 version ID、file 一覧／file ID による照合を確認します。
POST はしないため作者の upload 権限の実証にはなりません。作者画面の確認と分けて記録してください。
権限不足・hash 取得不能・曖昧な ID は停止条件です。Upload API だけで成功扱いにしません。
secret は workflow の `publish` step にだけ渡し、plan / build job やログへ出しません。

## 固定 plan と公開

`release.yml` の `curseforge_project` に数値 project ID を指定すると、その plan の各 target に任意先を固定します。
`curseforge_required=true` の場合だけ成功必須にします。CLI の plan 作成は
`--curseforge-project <ID>` と、必要な場合のみ `--curseforge-required` を使います。
再開時も元の project / required をそのまま指定し、公開後に target・公開先・project を追加変更しません。

通常の workflow 公開は `operation=release-new` で始め、Environment 承認後に公開します。
固定 bundle の回復では `operation=publish` も使えますが、こちらも `release-publish` Environment の承認を通ります。
次の CLI はローカルから adapter を直接呼ぶ運用用です。

```sh
python3 scripts/publish_modrinth.py publish --bundle "$BUNDLE_DIR" \
  --source-sha "$SOURCE_SHA" --manifest-sha256 "$MANIFEST_SHA256" \
  --state-output "$STATE_DIR/publish-state.json"
```

固定 bundle を確認し、GitHub 永続保管 → Modrinth 照合 → CurseForge 照合の後、全必須先の成功で finalize します。
optional の secret 不足・審査待ち・API 障害は別 state に残し、GitHub / Modrinth の完了を妨げません。
required は `verified` のみ成功で、それ以外は draft を維持して非0終了します。
optional は失敗時も必須先が成功すれば0終了するため、workflow summary の target 別状態も確認してください。

既に固定されている optional CurseForge は、GitHub 公開後も同一 bundle で不足分を補完できます。
これは**同一計画内の optional 補完**であり、公開済み集合への target / 公開先追加ではありません。
新規 attempt を始められるのは、この固定 optional 先に限ります。required 先の公開後新規 attempt は禁止します。
過去 attempt の結果が不明な場合は optional でも再投稿せず、照会による解決を待ちます。

## 照合と状態

Upload API の一覧から MC、Fabric / NeoForge、Client の正確な ID を一意に解決し、欠落・重複は拒否します。
通常 JAR 1つを target ごとに投稿します。Core API の file ID に対して project / game、MC / Loader / Client、
channel、表示名、ファイル名、サイズと SHA1 を元バイナリから照合します。
内部 SHA256 / SHA512 も bundle と別 state に保持します。filename や表示名だけで一致と扱いません。

| state | 意味・次の行動 |
| --- | --- |
| `skipped` | plan に CurseForge がない。API を使わない |
| `verified` | 承認／公開済み・取得可能で、file ID と binary hash / target が一致 |
| `pending_review` | Core API が審査・処理中を示す。成功扱いせず GET で再確認 |
| `not_released` | 拒否・削除・その他の未公開状態。自動更新・再投稿しない |
| `mismatch` | 既存 file の metadata / hash が違う。停止し元資産を調査 |
| `unknown` | secret / 権限 / 応答喪失 / 不可視 file / 未解決 attempt。照合できるまで再投稿しない |

Core API の一覧を pagination 付きで取得し、曖昧な候補・不正／途中変化したページは停止します。
承認前は hash を取得できなくても `pending_review` を記録できますが、`verified` にはしません。
この間の file ID は照会の手掛かりであり、同一バイナリの成功証明には使いません。

外部 POST 前に GitHub へ `...-curseforge-attempt.json` を保存・再 GET 照合します。
返却された file ID は別の `...-submitted.json`、審査待ちは `...-pending_review.json`、
照合成功は `...-verified.json` に保存します。全て append-only で manifest は変更しません。
応答喪失後は一覧／file ID を再照会し、成功を証明できない場合は停止します。
404 やローカル state の消失を再 POST の許可にはしません。Tag / Release / file の削除・上書きは不要です。

## 公式 API と実証範囲

- [Upload API](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-upload-api):
  version ID と multipart upload、`X-Api-Token`。
- [Core REST API](https://docs.curseforge.com/rest-api/):
  project・file ID・pagination・SHA1、`x-api-key`。Upload token と共用しません。

mock は新規／既存一致／相違／審査待ち／応答喪失、永続 attempt、不可視 file ID の後日照会、
未設定時の通信なし、required / optional の finalize 差、公開後 optional 補完を検証しています。
実 project の権限、Core API key の利用許可、審査前後の API 可視性、実 upload は未確認です。
導入者は上記 preflight と作者権限確認の結果を記録し、照合不能な環境では有効化しないでください。
