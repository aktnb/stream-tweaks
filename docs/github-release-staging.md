# 検証済み bundle の GitHub draft 保管

Issue #11 の adapter は、`release-new` run が生成した同じバイナリを保管・復元します。
この段階では Modrinth を公開せず、GitHub Release も draft のままです。全必須公開先の照合と
`finalize_release` 呼出しは `publish` coordinator（#12）の責務です。旧 `legacy` 経路は削除済みです。

`release-new` が唯一の workflow dispatch 経路です。内部の `github-stage` job が検証済み bundle を draft に保存し、
`release-publish` Environment 承認後に `publish` job が draft から復元します。
独立した stage／resume の workflow dispatch はありません。

## 保管

`github-stage` は同じ `release-new` run の bundle artifact を検証して保管します。
PR／fork／別 workflow／別 SHA の artifact を使いません。artifact を取得できなければ停止し、再ビルドしません。
write job は検証済み canonical tag 単位で排他し、途中の新 run で cancel しません。
固定計画・source・manifest・JAR・notes・properties を照合してから annotated tag を作ります。
既存 annotated tag は peeled commit SHA が同じ場合だけ再利用し、lightweight tag／異なる SHA は停止します。
draft に bundle 全ファイルを保存し、全 asset を再ダウンロードして一致を確認するまで保管成功としません。
既存同 hash は skip、相違は停止、欠落だけ upload します。削除・overwrite API は使いません。

POST 応答喪失後は再 GET し、同じ内容を確認できた場合のみ成功として継続します。
asset が見えない、`starter` 状態、不一致、重複名、不明な追加 asset がある場合は止まります。
その場で POST を再試行したり、不明状態を成功と扱ったりしません。

## Publish job の再実行

一時エラーで `Publish <tag>` が失敗した場合は、その job だけを同じ run で再実行します。
job は draft から元 bundle を復元し、source SHA、manifest hash、全 bundle の hash・metadata を再検証します。
独立した `github-resume` dispatch はありません。draft が不完全、または同じ run を再実行できない場合は停止し、
無検証の再ビルドや tag／Release の削除・差替えをしません。

## CLI と連携 API

以下は adapter の開発・診断用 CLI です。通常の workflow 公開手順では使いません。
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
