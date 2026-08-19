# IME Hotfix — 実装計画

Minecraft で IME(日本語入力)を「普通のテキストボックス」と同じ感覚で使えるようにする MOD。

---

## 1. 問題の根本原因(調査で確認済み)

| 事実 | 確認方法 |
|---|---|
| GLFW 3.3.1(MC 1.20.1 が同梱)の `win32_window.c` は `WM_IME_*` を一切処理せず、すべて `DefWindowProc` に渡す | GLFW 3.3.1 タグの `src/win32_window.c` を取得して grep |
| そのため未確定文字列(preedit)がアプリに届く経路が存在しない | 同上 |
| `ImmSetCompositionWindow` / `ImmSetCandidateWindow` が呼ばれないため、Windows は既定位置(ウィンドウ左上)に IME UI を描く | 同上 |
| GLFW は `VK_PROCESSKEY` を `_GLFW_KEY_INVALID` に変換して**キーイベントごと破棄**する | `translateKey()` 512 行目付近 |
| → IME が ON のままだと WASD 等が一切ゲームに届かない | 同上 |
| MC 1.20.1 は `net.java.dev.jna:jna:5.12.1` と `jna-platform:5.12.1` を標準同梱 | `1.20.1.json`(piston-meta)の libraries |
| チャット欄 / レシピ本検索窓 / JEI 検索窓はすべて `net.minecraft.client.gui.components.EditBox` | MC ソース + JEI 15.49.0.191 の `javap` |
| JEI の `GuiTextFieldFilter.renderWidget` は末尾で `super.renderWidget` を呼ぶ | `javap -c` でバイトコード確認 |

**結論**: `EditBox` 1 箇所に手を入れれば、必須 3 箇所を含む「EditBox を使うすべての入力欄」が同時に直る。

---

## 2. アーキテクチャ

マルチバージョン / マルチローダー展開を前提に、**MC に一切依存しない core** を物理的に分離する。

```
IME Hotfix/
├── core/                    ... MC 非依存。全ポートが srcDir 参照で共有する
│   └── src/main/java/jp/antimeme/mc/imehotfix/core/
│       ├── ImeSupport         (ファサード / 静的 API)
│       ├── ImeBackend         (プラットフォーム抽象)
│       ├── ImePreedit         (未確定文字列の不変スナップショット)
│       ├── PreeditStyle       (変換節の表示種別)
│       ├── ImeOptions         (設定値)
│       ├── ImeConfigFile      (.properties 読み書き。ローダー非依存)
│       ├── ImeLogger          (log4j/SLF4J 非依存のログ抽象)
│       ├── NoopImeBackend
│       └── win32/             (JNA による IMM32 / user32 バインディング)
└── forge-1.20.1/            ... MC 依存部分のみ。独立した Gradle プロジェクト
    └── src/main/java/jp/antimeme/mc/imehotfix/forge/
        ├── IMEHotfixMod
        ├── ImeClientHandler   (フレーム毎の判定・座標変換・合成イベント発行)
        └── mixin/
            ├── EditBoxMixin            (preedit 描画 / 値の報告 / 離脱時確定)
            ├── MouseHandlerMixin       (クリック時の確定)
            └── KeyboardHandlerAccessor (合成イベントの発行口)
```

- core は **Java 8 文法**で書く(record / var / switch 式を使わない)。1.12.2 など古い版へ移植する際の障壁を消すため。
- core は `getFieldOrder()` 方式の JNA `Structure` を使う(JNA 4.x でもコンパイル可能)。
- 各ポートは自前の Gradle wrapper とビルド定義を持つ完全独立プロジェクト。ForgeGradle / Loom のバージョン差で相互に壊れない。

### core が提供する契約

ポート側がやることは 4 つだけ:

1. `ImeSupport.install(nativeWindowHandle, logger)`
2. `ImeSupport.setInputActive(boolean)` — テキスト欄がフォーカスされているか
3. `ImeSupport.setCaretRect(x, y, w, h)` — ネイティブクライアント座標(物理ピクセル)
4. `ImeSupport.preedit()` を描画

---

## 3. Windows バックエンドの動作 [完了]

1. `GLFWNativeWin32.glfwGetWin32Window()` で HWND を取得
2. `SetWindowLongPtrW(GWLP_WNDPROC)` で GLFW の WndProc をサブクラス化
3. `WM_IME_STARTCOMPOSITION` / `WM_IME_ENDCOMPOSITION` を消費し、`WM_IME_SETCONTEXT` から `ISC_SHOWUICOMPOSITIONWINDOW` を落として、Windows 既定の未確定文字列ウィンドウを抑止(後者だけでは足りない。罠2 を参照)
4. `WM_IME_COMPOSITION` で `ImmGetCompositionStringW` から `GCS_COMPSTR` / `GCS_CURSORPOS` / `GCS_COMPATTR` / `GCS_COMPCLAUSE` を読む
5. `ImmSetCompositionWindow(CFS_POINT)` + `ImmSetCandidateWindow(CFS_EXCLUDE)` で候補ウィンドウをキャレットに追従させる
6. テキスト欄非フォーカス時は `ImmAssociateContext(hwnd, NULL)` で IME を切り離す(WASD 問題の解決)

確定文字列の経路(`WM_IME_CHAR` → `WM_CHAR` → GLFW char callback → `EditBox.charTyped`)には一切触れない。除去するのは「未確定文字列を描画させるビット」だけ。

Win32 の定数・構造体・関数シグネチャはすべて Windows SDK 10.0.26100.0 の `um/imm.h` / `um/winuser.h` から読み取り、関数の実在は JNA で実機ロードして確認済み(推測値ゼロ)。

### 実装中に踏んだ罠(移植時は必ず同じ対処をすること)

**罠1: 候補ウィンドウ位置の設定が無限ループになる**

`ImmSetCompositionWindow` は `IMN_SETCOMPOSITIONWINDOW` を、`ImmSetCandidateWindow` は `IMN_SETCANDIDATEPOS` を、**自分自身で発生させる**。これらの通知を「位置を更新する合図」として購読すると、呼ぶ→通知→呼ぶ の無限メッセージループになり、IME が動いた瞬間にゲームがフリーズする(Java 例外ではないので crash-report が残らず、ログには接続タイムアウトしか出ない)。

対処は三重に:
- `IMN_SETCOMPOSITIONWINDOW` / `IMN_SETCANDIDATEPOS` を購読しない(購読するのは `IMN_OPENCANDIDATE` / `IMN_CHANGECANDIDATE` だけ)
- 位置更新関数に再入ガードを置く
- 変換中(composition 中)以外は位置更新しない

**罠2: `ISC_SHOWUICOMPOSITIONWINDOW` を落とすだけでは Windows の描画は止まらない**

`WM_IME_SETCONTEXT` から `ISC_SHOWUICOMPOSITIONWINDOW` を除去しても、MS-IME は未確定文字列を独自に描画し続ける。しかも候補位置をキャレットに合わせているせいで、自前描画のすぐ右に重なり「基本基本」のように二重に見える。

`WM_IME_STARTCOMPOSITION` のドキュメントには *"An application should process this message if it displays composition characters itself. Otherwise, it should send the message to the IME window."* とある。つまり自前描画するなら**このメッセージを IME ウィンドウに渡してはいけない**。

対処:
- `WM_IME_STARTCOMPOSITION` / `WM_IME_ENDCOMPOSITION` を消費する(`0` を返す)
- `WM_IME_COMPOSITION` は描画関連ビット(`GCS_COMPREADSTR` / `GCS_COMPREADATTR` / `GCS_COMPREADCLAUSE` / `GCS_COMPSTR` / `GCS_COMPATTR` / `GCS_COMPCLAUSE` / `GCS_CURSORPOS` / `GCS_DELTASTART`)を除去して転送。残りが空なら消費する。**結果文字列のビットは絶対に落とさない**

なお自前描画を無効化する設定(`inlinePreedit=false`)を選んだ場合は、これらの抑制もすべて無効にしないと「未確定文字列がどこにも表示されない」状態になる。両方の設定が揃ったときだけ抑制する。

---

## 4. インライン preedit 描画 [完了]

`EditBox.renderWidget` を **上書きせず**、前後で包む:

- `@Inject HEAD`: `value` をバックアップし、「キャレット位置に preedit を差し込んだ文字列」に差し替え、`setCursorPosition` / `setHighlightPos` で `displayPos` を再計算させる
- バニラの描画がそのまま走る(スクロール・formatter・suggestion・ハイライトが全部生きる)
- `@Inject RETURN`: 変換節の下線 / ハイライトを重ね描き、キャレット座標を IME に通知、`value` を復元

`value` はフィールドへ直接書くため、保存されている値は一切変化しない。確定しても破棄しても文字が重複・消失しない。

`super.renderWidget` を呼ぶサブクラスは自動的に恩恵を受けるため、JEI 用の専用コードは不要。

---

## 4.5 未確定文字での検索フィルター [完了]

### 画面は「値が変わったこと」を 2 通りの方法でしか知らない

| 画面 | 検知方法 | 確認方法 |
|---|---|---|
| `CreativeModeInventoryScreen`(Search Items) | `charTyped` / `keyPressed` の**前後で `searchBox.getValue()` を比較**し、違えば `refreshSearchResults()` | MC ソース |
| JEI | `IngredientListOverlay` のコンストラクタで **`EditBox.setResponder`** を登録 | `javap -c` で `m_94151_` の呼び出しを確認 |

そして **IME のキー入力は `charTyped` に一切届かない**(`VK_PROCESSKEY` を GLFW が破棄するため)。つまり値を書き換えるだけでは、どちらの画面も永遠に気づかない。

### 解決

保存値は変更せず、画面を通常のコードパスへ戻す:

1. 変換中、`EditBox.getValue()` が**未確定文字を含んだ値を報告する**(保存値は不変)
2. 未確定文字が変化するたび、**合成した文字入力イベントを 1 回だけ流す**。`Screen.charTyped` を直接呼ぶのではなく `KeyboardHandler.charTyped` 経由 — Forge の `ScreenEvent.CharacterTyped` が発火する経路
3. 入力欄はその合成イベントを握り潰す(何も挿入せず `true` を返す)。同時に **responder も発火**させる

**タイミングが肝**: 画面は `charTyped` の前後で値を読むので、新しい未確定文字は**その 2 回の読み取りの間**で可視化しなければならない。前後で同じ値を返すと「変化なし」と判断されて更新されない。よって公開の切り替えは合成イベントの内側で行う。

ポーリング型(Search Items)と通知型(JEI)の**両方に対応する必要がある**。片方だけでは他方が沈黙する。

---

## 4.6 離脱時の確定 [完了]

未確定文字列はゲーム全体で 1 つの状態なので、放置するとフォーカス移動に追従してしまう(JEI で入力 → 外をクリック → Search Items に文字が出現)。

離脱の瞬間に、入力していた欄へ確定させる:

- **マウス押下**(`MouseHandler.onPress`): クリックが画面へ配送される**前**に確定。クリエイティブインベントリはアイテムをクリックしても検索欄のフォーカスが外れないため、フォーカス変更だけを見ていては捕まらない
- **フォーカス喪失**(`EditBox.setFocused(false)`): Tab 移動と、JEI の `TextFieldInputHandler.unfocus()` 経由の離脱。`canLoseFocus=false` の欄(チャット)は除外

**IME に確定させてはいけない**。`CPS_COMPLETE` で確定すると文字は 1 フレーム後に `WM_CHAR` で届き、その時点ではフォーカスが移動済みで、まさに上記の「文字が別の欄へ移動する」症状になる。IME 側の変換は破棄し、画面に見えている文字列を自分で `insertText` する。

---

## 4.7 看板・本と羽ペン [完了]

どちらも `EditBox` を使わず、`TextFieldHelper` を直接操作して**テキストを自前で描画**する。既存の Mixin は一切効かず、さらに「テキスト欄にフォーカスが無い」と判定されて IME が切り離されるため、**日本語入力そのものができない**状態だった。

確定先を `ImeTextTarget` インターフェースへ抽象化したうえで、個別に対応:

| | 未確定文字の差し込み先 | キャレット座標 |
|---|---|---|
| 看板 (`AbstractSignEditScreen`) | `messages[line]`(描画中のみ) | 中央揃え・変換済み pose のため、現在の変換行列を通して画面座標へ |
| 本 (`BookEditScreen`) | `pages.get(currentPage)` または `title`、レイアウトキャッシュを再構築 | 内部型が package-private のため、カーソル描画の呼び出しを `@Redirect` で捕捉 |

**保存値は描画中だけ差し替えて即座に戻す。** 看板は画面を閉じるときに `messages` 配列がそのままサーバーへ送信されるため、ここを汚すと未確定文字が本当に書き込まれてしまう。

**本の描画位置は行分割を自前で再計算して求める。** `DisplayCache` / `Pos2i` は package-private で参照できないが、`StringSplitter.splitLines(String, int, Style, boolean, LinePosConsumer)` は public なので、バニラと同じ引数(ページ幅 114)で呼べば同じ行分割が得られる。これにより下線・ハイライト・溢れ表示をすべて正しい行位置に描ける。アクセストランスフォーマーで可視性を変える必要はない。

### 罠3: 合成した文字入力イベントが、それを求めていない画面に届く

4.5 の合成イベント(半角スペース)は `EditBox` が握り潰す前提だった。看板・本には `EditBox` が無いため、**スペースが本物の入力として入り続けた**。症状は 3 つに見えたが原因は 1 つ:

- 確定時に半角空白が付く
- 字数超過時に大量の空白が生まれる(幅制限は日本語を弾くがスペースは通す)
- 本の折り返しが崩れる(混入したスペースが改行位置になる)

対処:
- `ImeTextTarget#imehotfix$reportsCompositionInValue()` で、合成イベントを**必要とする対象にだけ送る**。看板・本は `false`
- 看板・本の `charTyped` 側でも合成イベントを拒否する(二重の防御)

### 罠4: `TextFieldHelper` は入りきらない挿入を丸ごと拒否する

看板の行は「表示幅」で、本のページは文字数と高さで制限される。`insertText` は制限を超えると**何も挿入しない**ため、確定した変換文字が丸ごと消えていた。1 文字ずつ挿入して、入る分だけは残すようにした。

---

## 4.8 Textbox Improvements と溢れ可視化 [完了]

IME とは独立した、テキスト入力そのものの改善。**全文字種**が対象で、文字確定時に働く。

| 機能 | 実装 |
|---|---|
| 看板の自動折り返し | `charTyped` 内の `TextFieldHelper.charTyped` を `@Redirect`。値が変わらなければ「幅に入らなかった」ので次の行へ送る |
| 本の自動次ページ | `charTyped` 内の `TextFieldHelper.insertText` を `@Redirect`。同様に `pageForward()` で次ページへ送る |

**`charTyped` 自体を乗っ取らないこと。** バニラの `BookEditScreen.charTyped` は先に `super.charTyped` を呼んで widget へ配送している。HEAD で奪うとその経路が消える。挿入呼び出しだけを差し替えれば、判定に必要な「値が変わったか」も同じ場所で取れる。

### 溢れの可視化

確定時に入りきらない範囲を着色する。上限の求め方が対象ごとに違う:

- 看板: `font.plainSubstrByWidth(text, sign.getMaxTextLineWidth()).length()`
- 本: 行分割の結果が `128 / 9 = 14` 行を超えた位置、および 1024 文字目

色は「送り先があるか」で決まる(水色 = 折り返される / 赤 = 捨てられる)。看板の最終行や自動折り返し無効時は赤になる。

---

## 5. 進捗

### [完了] Forge 1.20.1
- [完了] プロジェクト構成 / ForgeGradle 6 + MixinGradle 0.7.38
- [完了] core(Win32 IMM32 バックエンド、preedit モデル、設定)
- [完了] `EditBoxMixin` によるインライン描画とキャレット通知
- [完了] `ImeClientHandler` によるフレーム単位のフォーカス判定と座標変換
- [完了] `build` 通過 / reobf で `@Shadow` が SRG 名へ正しく変換されることを確認
- [完了] 開発クライアント起動確認
- [完了] 未確定文字での検索フィルター(Search Items / JEI 双方の検知方式に対応)
- [完了] 離脱時の確定処理
- [完了] 実機での動作確認(チャット / Search Items / JEI / レシピ本)
- [完了] 看板(通常・吊り)と本・羽ペンへの対応
- [完了] 候補ウィンドウの除外領域(テキスト領域全体を通知し、複数行の候補が本文を覆わないように)
- [完了] Textbox Improvements(看板の自動折り返し / 本の自動次ページ)
- [完了] 溢れ範囲の可視化と、色・機能の設定化

### 今後: 横展開
core は無変更で流用できるため、各ポートで必要なのは「`EditBox` 相当への Mixin」と「フレームフック」だけ。

| 対象 | 状態 | 想定される差分 |
|---|---|---|
| Forge 1.20.1 | 完了 | — |
| NeoForge 1.20.4 / 1.21.x | 未着手 | イベント名(`ClientTickEvent` → `ClientTickEvent.Post` 等)、`EditBox` の内部フィールド名 |
| Fabric 1.20.1 / 1.21.x | 未着手 | Loom + `ClientTickEvents`、`FabricLoader.getConfigDir()` |
| Forge 1.19.2 / 1.18.2 / 1.16.5 | 未着手 | `renderWidget` → `renderButton`、`GuiGraphics` → `PoseStack` |
| Forge 1.12.2 | 未着手 | Java 8 / JNA 4.4(`Memory` が `Closeable` でない)/ `GuiTextField` は `EditBox` ではない |

移植時の要注意点:
- 1.19.4 以前は描画メソッドが `renderButton(PoseStack, int, int, float)`。`GuiGraphics.fill` も `AbstractWidget.fill` / `GuiComponent.fill` になる。
- 1.12.2 系はテキスト欄が `GuiTextField` で、`EditBox` とは別クラス。core は流用できるが Mixin は書き直し。
- macOS / Linux 対応が必要になった場合は `ImeBackend` の別実装を core に追加する(Mixin 側は無変更)。

---

## 6. 設定

`config/imehotfix.properties`(ローダー非依存の自前フォーマット)

| キー | 既定 | 効果 |
|---|---|---|
| `disableImeOutsideTextFields` | true | テキスト欄非フォーカス時に IME を切り離す(WASD が効かなくなる問題の対策) |
| `suppressSystemCompositionWindow` | true | Windows 既定の未確定文字列ウィンドウを隠す |
| `inlinePreedit` | true | 未確定文字列をテキスト欄内に描画 |
| `pinCandidateWindowToCaret` | true | 候補ウィンドウをキャレットに追従 |
| `filterWithComposition` | true | 変換確定前の文字でも検索を絞り込む |
| `highlightOverflow` | true | 確定時に入りきらない部分に色を付ける |
| `signAutoWrap` / `bookAutoPage` | true | Textbox Improvements |
| 各種色 (`targetTint` 等) | — | `0xAARRGGBB` で指定 |
| `commitCompositionOnBlur` | true | 離脱時に未確定文字列をその欄へ確定 |
| `cancelCompositionOnFocusLoss` | true | 確定先が無い場合(画面が閉じた等)に破棄 |
| `verboseLogging` | false | IME ウィンドウメッセージを全部ログに出す(診断用) |
