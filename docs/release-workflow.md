# 固定 bundle による公開と削除を伴わない復旧

手順切替版: **2026-10-24**（#9〜#13）。この版の workflow を公開元へ導入した後に使用します。
導入前の[旧版ガイド（06436c1）](https://github.com/aktnb/stream-tweaks/blob/06436c1607e826b5d2dfaccb3edfebd7d23ed7f6/docs/release-workflow.md)
は履歴の参照専用です。旧版の自動採番・削除による復旧は使用せず、旧 workflow と同時に公開しません。
この文書だけで remote への導入・公開済みであるとは扱いません。

Release は純 SemVer × Minecraft、同 MC の Loader は同じ full source SHA です。
最初の plan で版・source・対象集合・公開先を固定し、公開後にその集合を追加しません。
既存 tag／Release／version を削除・差替えせず、同じ bundle で不足先だけを再開します。
GitHub と Modrinth の間に原子的公開はなく、片方だけ成功している時間はあり得ます。

通常の新規公開は `release-new` を1回 dispatch します。build と GitHub draft 保管の後、
`release-publish` Environment の承認を受けてから同じ bundle を公開します。
成功時の run ID／SHA／manifest hash の転記は不要です。手動の `github-stage`／`github-resume`／`publish` は回復用に残します。

詳細は[版番号契約](versioning.md)、[bundle](release-bundles.md)、
[GitHub 保管](github-release-staging.md)、[Modrinth 公開](modrinth-publication.md)を参照してください。
CurseForge は未設定なら対象外です。任意 adapter の導入は #17 と
[CurseForge 導入手順](curseforge-publication.md)の反映後に確認してください。
設定する場合は `curseforge_project` と `curseforge_required` も最初の plan に固定し、stage／再開でも同値を渡します。
Upload 用 `CURSEFORGE_TOKEN` と project／file 照合用 `CURSEFORGE_API_KEY` は publish step だけに渡します。
必須に選んだ plan は `verified` になるまで finalize せず、optional の失敗・審査待ちは結果を記録します。
今回の CLI mock 確認は未設定時の GitHub／Modrinth 経路を対象とし、adapter の実公開・導入確認とは区別します。

## 前提と権限の確認

新規計画は `support-targets.json` の唯一の公開元と supported 行を使います。
準備台帳はmainを公開元に予約しています。main未作成・管理確認未完了の間は新規公開を停止し、
[main 移行](main-migration.md)の gate 完了後だけ以下の新規手順を使います。
移行後に元の計画を再開するときは、実行 ref を main にしても `release_source_branch=26.2` と元 SHA を維持します。

| 用途 | 必要な権限・設定 | 未設定・不足時 |
| --- | --- | --- |
| workflow の手動起動 | 操作者の Actions write | dispatch 失敗。公開は開始しない |
| plan／prepare | Contents read、対象 JDK／固定 Gradle・依存を取得可能 | build/check で停止。tag を作らない |
| GitHub 保管・再開・finalize | 自動 `GITHUB_TOKEN` の Contents write、Actions read | run／asset を照合できなければ停止 |
| publish 承認 | `release-publish` Environment の Required reviewers と main の deployment branch policy | workflow を開始する前に設定。不足なら承認ゲートとして機能しない |
| Modrinth | `MODRINTH_TOKEN`。対象 project `jWHdryID` の version 作成権限と照合 API の利用権限 | publish は失敗し draft を維持 |
| Discord | 任意の `DISCORD_WEBHOOK_URL` | 未設定なら通知だけ skip。設定時も finalize 成功後だけ通知 |

`Settings > Actions` の権限方針・対象 project の権限を管理者が確認します。
secret 名だけなら `gh secret list --repo aktnb/stream-tweaks --json name --jq '.[].name'` で確認できます。
一覧取得にも権限が必要で、名前の存在だけでは token の scope／有効性を証明しません。
`GITHUB_TOKEN` は自動提供で secret 一覧には不要です。token 値を表示・CLI 引数へ記載しないでください。
ローカル adapter は `GITHUB_TOKEN`／`MODRINTH_TOKEN` を環境からのみ読みます。
権限不足を検証無効化・別 API の迂回で通しません。

## `release-publish` Environment の一度だけ設定

GitHub repository の `Settings > Environments` で `release-publish` を作成し、次を保存します。

1. **Required reviewers** に公開を承認するユーザーまたは team を指定します。
2. **Deployment branches and tags** を「Selected branches and tags」にし、`main` のみを許可します。
3. solo 運用では `Prevent self-review` を有効にしません。別の担当者が承認する運用なら有効にできます。

Environment が存在しない、または Required reviewers が未設定の場合、workflow は承認待ちにならず進みます。
初回 dispatch 前に Settings の保存内容を確認してください。workflow から GitHub Settings は変更しません。
`release-new` は承認前に annotated tag と GitHub draft を作ります。承認を拒否した場合も削除・差替えはせず、
draft と tag は調査用に残ります。

## 1. 公開元と版を準備する

変更を `main` へ merge します。`release-new` が選択 Loader の build/check を dependency verification strict で実行します。
alpha／beta は `0.3.4-alpha.1`／`0.3.4-beta.1` のように番号まで固定します。
stable は対応する `CHANGELOG.md` の版セクションを先に用意します。

```sh
python3 scripts/changelog_tool.py release --version 0.3.4 --update-file
python3 scripts/changelog_tool.py read --version 0.3.4
```

## 2. 通常公開は1回起動

GitHub Actions の `Release` を `main` ref から1回実行します。Web UI では `operation=release-new` を選び、
version／channel／loaders を入力します。CLI の例:

```sh
gh workflow run release.yml --repo aktnb/stream-tweaks --ref main \
  -f operation=release-new -f channel=beta -f loaders=both \
  -f release_version=0.3.4-beta.1
gh run list --repo aktnb/stream-tweaks --workflow release.yml --limit 5
```

`release-new` は固定 plan → build/check → bundle 検証 → annotated tag／GitHub draft 保管を行います。
source SHA と manifest SHA256 は run 内で検証・受け渡しするため、通常時に転記する必要はありません。
stage の後、`Publish <tag>` job が `release-publish` Environment の承認待ちになります。
run summary と draft Release を確認し、GitHub Actions run の **Review deployments** から承認すると、
publish job が draft から bundle を復元・再検証して配布先を照合し、不足先だけを公開します。
必須先がすべて検証できた場合だけ GitHub draft を解除し、成功通知を行います。

`prepare-new` は従来どおり公開なしの build/check 用です。`plan-new`／`plan-reuse` は計画確認だけを行います。
新規公開が失敗した場合、同じ beta 番号を変えず同じ版の bundle を再開します。`0.3.4-beta.1` の再開で `.2` にしません。
stable 単 Loader も同じ `release-new` を使えます。公開後に別 Loader を追加するときは新 PATCH を計画します。

## 3. 失敗・部分成功からの再開

publish が一時エラーで止まった場合、Actions の **失敗した `Publish <tag>` job のみ**を再実行します。
その job は成功済み stage をやり直さず、同じ GitHub draft から元 bundle を復元して hash と plan を再検証します。
全 job の再実行は新しい build provenance を作るため使いません。GitHub tag／draft は削除・差替えしません。

手動の `github-stage`／`github-resume`／`publish` も固定 bundle の回復用に維持しています。
`publish` は直接起動しても `release-publish` Environment 承認後に公開します。
`github-resume` は bundle の復元・保管だけを行い、Modrinth へ公開しません。
これらの回復経路では、元 plan の version／MC／Loader 集合／source branch・SHA／manifest hash をそのまま使います。

各 Modrinth version の project・MC・Loader・version・契約 metadata・通常 JAR の SHA512 を再照会します。
一致する既存 version は POST せず、未 POST の不足先だけを追加します。POST 応答が不明で完全一致を証明できない場合は停止します。

## 状態を確認する

run の summary と別 artifact `publish-state-<run ID>-attempt-<attempt>` に、GitHub と target 別の状態が出ます。
stage 専用の状態は `stage-state-<run ID>-attempt-<attempt>` です。
次は読み取り・ローカル保存だけです。出力先は既存 bundle の外の新しいディレクトリにします。

```sh
PUBLISH_RUN_ID='確認するpublishのrun番号'
PUBLISH_RUN_ATTEMPT='確認するrun attempt番号'
gh run view "$PUBLISH_RUN_ID" --repo aktnb/stream-tweaks --json conclusion,status,url
gh run download "$PUBLISH_RUN_ID" --repo aktnb/stream-tweaks \
  --name "publish-state-$PUBLISH_RUN_ID-attempt-$PUBLISH_RUN_ATTEMPT" --dir ./state-snapshot
python3 -m json.tool ./state-snapshot/publish-state.json
gh release view "v${RELEASE_VERSION}+mc${RELEASE_MC}" --repo aktnb/stream-tweaks \
  --json tagName,isDraft,assets,url
```

state が作られる前に停止した run では artifact が存在しないこともあります。
`verified`／`published` の保存記録だけを remote の現在状態の証明にはしません。次の publish が fresh 照合します。
外部 ID・attempt／verified receipt は draft の別 `publish-state-*` asset として保存し、manifest は変更しません。

Modrinth の公開済み JAR は、信頼済み manifest にある target の SHA512 で読み取り専用照会ができます。
返った project／version／MC／Loader／file hash を元の契約と比較します。404 や通信失敗は再 POST の許可になりません。

```sh
JAR_SHA512='信頼済みmanifestにある対象JARの128桁SHA512'
curl --fail --silent --show-error --get \
  "https://api.modrinth.com/v2/version_file/$JAR_SHA512" --data-urlencode algorithm=sha512
```

## 不明状態・期限切れ・不一致での停止と回復

| 条件 | 対応 |
| --- | --- |
| wrong source SHA／manifest hash／target 集合 | 停止。元の信頼済み plan と記録を確認し、正しい同入力だけで再開 |
| 既存 tag の別 SHA、asset／version の hash・metadata 相違 | 停止。既存物を削除・overwrite せず原因調査。別バイナリを同 ID に差し替えない |
| POST の応答喪失 | 再 GET で完全一致を証明できれば成功として続行。不明なら停止 |
| attempt 記録あり・version が見えない | 一覧の404／反映遅延だけでは未公開と判断せず、再 POST しない。照会のみの診断で解消できるまで停止 |
| prepare artifact 期限切れ、draft 完全保管済み | `github-resume` または `publish` で同 bundle を復元。再ビルドしない |
| draft 部分保存＋artifact 消失 | 停止。元 bundle の回収または下記の hash 一致回復ができるまで公開しない |

外部公開せず保管状態だけを再検証・復元する場合は、上の publish コマンドの
`operation=publish` を `operation=github-resume` に変更します。元 SHA・manifest hash・集合は同じです。
結果が不明な間は version 番号を変えて新規投稿することも避け、同じ ID の remote を再照会します。

ローカルで元 bundle を回収できた場合の復旧 CLI は次のとおりです。

```sh
python3 scripts/release_bundle.py verify --bundle /path/to/recovered-bundle \
  --source-sha "$RELEASE_SOURCE_SHA" --manifest-sha256 "$RELEASE_MANIFEST_SHA256" \
  --plan /path/to/trusted-release-plan.json
python3 scripts/publish_github.py stage --bundle /path/to/recovered-bundle \
  --source-sha "$RELEASE_SOURCE_SHA" --manifest-sha256 "$RELEASE_MANIFEST_SHA256" \
  --plan /path/to/trusted-release-plan.json --state-output /path/to/recovery-state.json
```

固定 source／JDK／依存で再構築する場合も、**元 manifest と notes／properties を保持し、全通常 JAR の
size・SHA256・SHA512 が元 manifest と一致した場合だけ**欠落ファイルの回復候補にできます。
元 manifest 自体の既知 hash と固定 plan を含む全 bundle 検証を通す必要があります。
新しい run provenance を持つ別 manifest を元 manifest の代用にはしません。
元 manifest／信頼済み hash がなく一致を証明できなければ、同 ID の公開を停止したまま調査します。
CLI stage の前にも元成功 run／source の出所を確認し、任意の再ビルドや fork artifact を持ち込みません。

## ローカル CLI・mock による照合

`reconcile` は状態照会だけのコマンドではありません。不足先への POST と journal 保存を行い得ます。
`publish` はその照合に加えて全必須先成功後の finalize を実行します。実接続時には公開操作として扱います。

```sh
python3 scripts/publish_modrinth.py reconcile --bundle /path/to/verified-bundle \
  --source-sha "$RELEASE_SOURCE_SHA" --manifest-sha256 "$RELEASE_MANIFEST_SHA256" \
  --state-output /path/to/publish-state.json
python3 scripts/publish_modrinth.py publish --bundle /path/to/verified-bundle \
  --source-sha "$RELEASE_SOURCE_SHA" --manifest-sha256 "$RELEASE_MANIFEST_SHA256" \
  --state-output /path/to/publish-state.json
```

公開なしで引数と実際の再開動作を確認できます。

```sh
python3 scripts/release_plan.py --help
python3 scripts/release_bundle.py verify --help
python3 scripts/publish_github.py stage --help
python3 scripts/publish_modrinth.py --help
python3 -m unittest scripts.tests.test_release_plan scripts.tests.test_release_bundle \
  scripts.tests.test_publish_github scripts.tests.test_publish_modrinth
```

mock は同 ID の再開、未 POST target の追加、応答喪失、永続 attempt、改変・欠落・異 SHA 停止と
required 未完了での finalize 拒否を検証します。これらは実 token scope・実公開・Discord 配信の確認ではありません。
