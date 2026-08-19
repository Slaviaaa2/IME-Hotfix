# IME Hotfix

Minecraft で日本語などの IME 入力を、普通のテキストボックスと同じ感覚で使えるようにする MOD。

現在の対応: **Minecraft 1.20.1 / Forge 47.x / Windows**

---

## 何が直るのか

| 症状 | 原因 | 対応 |
|---|---|---|
| 変換中の文字(未確定文字列)がテキスト欄に出ない | GLFW 3.3.1 が `WM_IME_*` を一切処理しないため、未確定文字列がゲームに届く経路が存在しない | ウィンドウプロシージャをフックして IMM32 から直接読み取り、キャレット位置にインライン描画 |
| 変換候補ウィンドウが画面の隅に出る | `ImmSetCandidateWindow` が呼ばれていないので既定位置になる | 候補ウィンドウをキャレットの真下に追従させる |
| 変換中の文字が画面の変な位置に二重に出る | Windows が独自に未確定文字列を描画している | `WM_IME_STARTCOMPOSITION` を IME ウィンドウへ渡さず、自前描画に一本化 |
| IME を ON にしたままだと WASD などで動けなくなる | IME が握ったキーは `VK_PROCESSKEY` になり、GLFW がイベントごと破棄する | テキスト欄にフォーカスが無い間は IME をウィンドウから切り離す |
| 変換の途中でクリックすると入力が消える | 未確定文字列はどこにも保存されていない | 離脱の瞬間に、入力していた欄へそのまま確定する |
| 変換途中の文字が別の検索欄に移動する | 未確定文字列はゲーム全体で 1 つの状態なので、フォーカスが移ると付いていってしまう | 同上。フォーカスが移る前に確定させる |
| 変換を確定するまで検索結果が絞り込まれない | IME のキー入力はゲームに届かないので、画面が「文字が入力された」ことに気づけない | 未確定のまま検索に反映する(下記) |
| どの文節を変換中か分からない | — | 変換対象の文節をハイライト、確定済みの文節に下線を表示 |

未確定文字列はテキスト欄の**中に**表示されるため、長い文章でも欄の横スクロールが正しく追従します。

### 未確定文字での検索フィルター

「ダークオーク」を探すとき、「ダーク」と打った**変換確定前の段階**で候補が絞り込まれます。クリエイティブインベントリの Search Items、JEI の検索窓、レシピ本の検索窓で機能します。

入力欄に保存されている値そのものは一切変更していないため、確定しても破棄しても文字が重複したり消えたりすることはありません(変換中は「未確定文字を含んだ値」を*報告*しているだけです)。

## 対応する入力欄

`net.minecraft.client.gui.components.EditBox` を使う入力欄すべてに自動的に効きます。個別対応のコードは書いていません。

- チャット欄
- クリエイティブインベントリの Search Items
- レシピ本の検索窓
- **JEI の検索窓**(`GuiTextFieldFilter` が `EditBox` を継承しているため)
- サーバー追加画面、ワールド名、コマンドブロック など

**対応していないもの**: 看板と本の編集画面。これらは `EditBox` ではなく独自の `TextFieldHelper` を使っているため、別途対応が必要です。

## 設定

`config/imehotfix.properties`(初回起動時に生成。項目が増えた場合は起動時に追記されます)

| キー | 既定 | 効果 |
|---|---|---|
| `disableImeOutsideTextFields` | `true` | テキスト欄にフォーカスが無い間 IME を切り離す。**WASD が効かなくなる問題の対策なので通常は変更しない** |
| `suppressSystemCompositionWindow` | `true` | Windows 既定の未確定文字列ウィンドウを隠す。IME が候補を出さなくなる場合はここを `false` に |
| `inlinePreedit` | `true` | 未確定文字列をテキスト欄内に描画する。`false` にすると描画を Windows 側へ返す |
| `pinCandidateWindowToCaret` | `true` | 候補ウィンドウをキャレットに追従させる |
| `filterWithComposition` | `true` | 変換確定前の文字でも検索を絞り込む |
| `commitCompositionOnBlur` | `true` | クリックやフォーカス移動で入力欄を離れるとき、未確定文字列をその欄へ確定する |
| `cancelCompositionOnFocusLoss` | `true` | 画面が閉じるなど、確定先が無くなった場合に未確定文字列を破棄する |
| `verboseLogging` | `false` | IME のウィンドウメッセージをすべてログに出す(診断用) |

## ビルド

JDK 17 が必要です。

```
cd forge-1.20.1
gradlew build          # 成果物は build/libs/
gradlew runClient      # 開発クライアントを起動
```

## 構成

```
core/            MC に一切依存しない共通実装(Java 8 文法)
forge-1.20.1/    MC 依存部分のみ。core を srcDir で共有する
```

`core` は Minecraft の API を一切参照しません。他のバージョンやローダーへ移植する際は、`core` をそのまま流用し、`EditBox` 相当への Mixin とフレームフックだけを書き直します。詳細と移植時の注意点は [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) を参照してください。

## 動作環境

- Windows のみ。macOS / Linux では何もせず、バニラの挙動のままになります(クラッシュはしません)
- 64bit JVM が必要(`SetWindowLongPtrW` を使うため)
- クライアント専用。サーバーには不要です

## ライセンス

GNU Lesser General Public License v3.0 or later ([LICENSE](LICENSE) / [LICENSE.GPL](LICENSE.GPL))
