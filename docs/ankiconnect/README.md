# AnkiConnect 兼容 API（fork 功能）

> **⚠ Fork-specific.** Anki Plus 新增；上游 AnkiDroid 没有这个接口。它是桌面知名插件
> [foosoft/anki-connect](https://github.com/FooSoft/anki-connect) 的**动作子集**移植，
> 让 Yomichan 一类只认 AnkiConnect 协议的工具也能读写手机上的集合。
> 与 `anki-qt` 桌面端的 `src/ankiconnect.{h,cpp}` 是**同一份动作子集、同一套响应形状**
> （两端各自实现，不共用代码）。上游 AnkiDroid API（`CardContentProvider`，
> 设置里的「启用 AnkiDroid API」）是另一条独立通道，不受影响。

## 安全边界（先读这条）

- 只监听 **127.0.0.1**，默认端口 **8765**。局域网上的任何设备都连不上；
  能从本机访问它的只有：设备上的其他应用，以及 `adb forward tcp:8765 tcp:8765`
  之后的电脑。这与上游 AnkiConnect 桌面版的暴露面一致（它同样监听 localhost、同样无鉴权）。
- **无 API key**：`requestPermission` 恒回 `granted`。任何能在设备上打开 localhost 套接字的
  应用都能读写集合——这是上游协议的既有风险类别，不是本移植新增的。
  不想要就关掉：设置 → 高级 → **AnkiConnect 兼容 API**（默认开）。
- CORS 头恒 `*`（浏览器扩展类客户端需要），`OPTIONS` 预检回 204。
- `sync` 动作明确拒绝（`sync is not supported by Anki Plus`）：同步走 AnkiWeb /
  局域网同步各自的通道，不允许第三方工具经此触发。

## 生命周期

`AnkiConnectManager`（`pages/AnkiConnectServer.kt`）持有唯一实例：
`DeckPicker.onCreate` 启动、`onDestroy` 停止；设置页的开关/端口改动即时生效。
**偏好监听器在新值落盘之前触发**，所以改动走 `onEnabledChanged` / `onPortChanged`
显式传值，不让 `start()` 重读旧偏好（回归门：`AnkiConnectServerTest`）。
绑定失败（端口被占）只写日志，不崩、不弹窗。

## 动作子集（24 个，与 anki-qt 对齐）

`version`（=6）、`requestPermission`、`deckNames`、`deckNamesAndIds`、`modelNames`、
`modelNamesAndIds`、`modelFieldNames` / `modelFields`、`findCards`、`findNotes`、
`cardsInfo`、`notesInfo`、`addNote`、`addNotes`、`addTags`、`removeTags`、`getTags`、
`suspend`、`unsuspend`、`bury`、`getEaseOfCards`、`cardsToNotes`、`storeMediaFile`、
`sync`（拒绝）。

与上游 AnkiConnect 的**已知形状差异**（两端一致地偏）：

- `deckNamesAndIds` / `modelNamesAndIds` 回 `[{id,name}]` 数组，不是上游的 `{name:id}` 对象。
- `getEaseOfCards` 回 `{cardId: factor}` 对象，不是上游的数组。
- `cardsInfo` 的 `fields` 是 `{字段名: 文本}`，不含上游的 `question/answer/css` 渲染产物。
- `version` 只认 6；旧版 wrapped-array 形式直接拒绝。
- 空 id 列表一律报错（`cards/notes param must be a non-empty id list`），不静默成功。
- `storeMediaFile` 只收 `filename` + base64 `data`（上游的 `url`/`path`/`delete` 未做），
  回落盘后的文件名（可能被后端改名）。

未知动作回上游风格错误 `unsupported action: <name>`；所有错误都是
HTTP 200 + `{"result":null,"error":"..."}` 信封。

## 验证

```bash
just test pages.AnkiConnectDispatcherTest   # 动作语义：真集合上逐动作往返
just test pages.AnkiConnectServerTest       # HTTP 层：真 socket、CORS、大 body 读全、设置改值先于落盘
just verify                                 # 离线门（NoOutboundReporting + BuildMemoryBudget）
```

（`just test` 的 FILTER 拼在 `com.ichi2.anki.` 之后，这两个类在 `.pages` 子包里，
要带 `pages.` 前缀；只写类名会匹配不到。）

设备冒烟（debug 包）：

```bash
adb forward tcp:8765 tcp:8765
curl http://127.0.0.1:8765 -d '{"action":"version","version":6}'
curl http://127.0.0.1:8765 -d '{"action":"deckNames","version":6}'
```

注意 debug 包名是 `com.pt123123.ankiplus.debug`（release 才是 `com.pt123123.ankiplus`），
`adb forward` 不区分包名，但设备上同时装两个包时 8765 只有一个能绑上。

## 未覆盖

- 真机上 Yomichan / 浏览器扩展的实际连通（本机策略不做 UI 自动化；HTTP 层已由
  Robolectric 真 socket 门覆盖）。
- 上游其余动作（`guiBrowse`、`updateNoteFields`、`deleteNotes` 等）——按需另开批次，
  两端要一起加，保持子集一致。