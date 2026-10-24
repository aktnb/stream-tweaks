# 検証済み bundle の GitHub draft 保管と復元

Issue #11 の adapter は、[prepare-new](release-bundles.md) が生成した同じバイナリを保管・復元します。
この段階では Modrinth を公開せず、GitHub Release も draft のままです。全必須公開先の照合と
`finalize_release` 呼出しは `publish` coordinator（#12）の責務です。旧 `legacy` 経路は削除済みです。

## 保管

prepare が成功した run の ID・full source SHA・summary の manifest SHA256 を控えます。
`github-stage` は同 repository の成功した `workflow_dispatch` / `release.yml` run を API で確認し、
PR／fork／別 workflow／別 SHA の artifact を拒否します。artifact を取得できなければ停止し、再ビルドしません。
新規 prepare は現在の台帳の公開元で実行します。保存 plan の保管・再開は元 source branch を
`release_source_branch` に指定でき、main 移行後も最新台帳で再計画しません。

```bash
gh workflow run release.yml --ref 26.2 \
  -f operation=github-stage -f channel=beta -f loaders=both \
  -f release_version=0.3.4-beta.1 -f release_minecraft=26.2 -f release_source_branch=26.2 \
  -f source_sha=<prepareの40桁SHA> -f manifest_sha256=<prepareの64桁hash> \
  -f bundle_run_id=<成功したprepareのrun番号>
```

write job は検証済み canonical tag 単位で排他し、途中の新 run で cancel しません。
固定計画・source・manifest・JAR・notes・properties を照合してから annotated tag を作ります。
既存 annotated tag は peeled commit SHA が同じ場合だけ再利用し、lightweight tag／異なる SHA は停止します。
draft に bundle 全ファイルを保存し、全 asset を再ダウンロードして一致を確認するまで保管成功としません。
既存同 hash は skip、相違は停止、欠落だけ upload します。削除・overwrite API は使いません。

POST 応答喪失後は再 GET し、同じ内容を確認できた場合のみ成功として継続します。
asset が見えない、`starter` 状態、不一致、重複名、不明な追加 asset がある場合は止まります。
その場で POST を再試行したり、不明状態を成功と扱ったりしません。

## draft からの復元

通常の workflow artifact が期限切れでも、保管ゲートを通過した draft から元 bundle を復元できます。

```bash
gh workflow run release.yml --ref 26.2 \
  -f operation=github-resume -f channel=beta -f loaders=both \
  -f release_version=0.3.4-beta.1 -f release_minecraft=26.2 -f release_source_branch=26.2 \
  -f source_sha=<元の40桁SHA> -f manifest_sha256=<元の64桁hash>
```

元 tag の SHA と明示した manifest hash、全 bundle の集合・hash・metadata が一致しなければ停止します。
draft が部分保存のまま artifact も失われた場合、無検証再ビルドや tag／Release 削除で修復しません。
元の bundle を回収し、元 manifest と同一 hash を証明できる場合だけ `github-stage` で不足を補完します。
同じ source から生成しただけの別 manifest（run provenance が違うものを含む）は代用できません。

## CLI と連携 API

ローカル CLI では `GITHUB_TOKEN` を環境から読みます。値を引数・ログへ表示しないでください。
保管には repository Contents write、run 照合・artifact 取得には Actions read が必要です。
token 未設定・権限不足・不明な remote 状態では停止します。secret 値を確認する必要はありません。

```bash
python3 scripts/publish_github.py validate-run --run-id <run番号> --source-sha <fullSHA>
python3 scripts/publish_github.py stage --bundle /path/to/bundle \
  --source-sha <fullSHA> --manifest-sha256 <hash> --plan /path/to/release-plan.json \
  --state-output /path/to/publish-state.json
python3 scripts/publish_github.py restore --tag v0.3.4-beta.1+mc26.2 \
  --source-sha <fullSHA> --manifest-sha256 <hash> --output /path/to/new-output
```

`publish-state.json` は immutable bundle の外に保存します。adapter の戻り値は
`schema_version/release_id/manifest_sha256/github` で、`github` に状態・Release ID・asset ID を記録します。
外部公開の成否や ID は manifest に書き込みません。

coordinator は `stage_bundle` 成功後だけ外部公開へ進み、各 target の全 required destination を独立照合します。
`finalize_release(..., required_successes={(target_id, destination), ...})` は全必須先を要求し、
GitHub tag／asset を再照合してから draft を解除します。CLI には無条件 finalize コマンドを提供しません。

GitHub API の挙動は公式の [annotated tags](https://docs.github.com/en/rest/git/tags)、
[releases](https://docs.github.com/en/rest/releases/releases)、
[release assets](https://docs.github.com/en/rest/releases/assets) に従います。
asset の API download は 200／302 を扱い、署名 URL への redirect に authorization header を転送しません。
mock tests は新規・同一／相違 tag、同 hash／相違／欠落 asset、応答喪失、draft 復元、
必須先未完了 finalize 拒否、PR／fork 入力拒否を検証します。実 tag／Release の作成は行っていません。
