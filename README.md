# 影視TV

Android TV、手機、平板都能用，支援本地檔案與網址播放。

[使用指南](https://fongmi.github.io/TV) · [討論群組](https://t.me/fongmi_official)

## 主要功能

- **瀏覽**：分類篩選、跨站搜尋、收藏、播放記錄與無痕模式。
- **播放**：倍速、字幕、彈幕、片段跳過、畫中畫與背景音訊。
- **直播**：分組選台、節目表、頻道收藏與線路切換。
- **操作**：電視遙控器、手機手勢、DLNA 投放與接收。
- **互通**：網頁遙控、檔案傳輸、記錄同步、一起看與 Android Auto。

爬蟲開發說明與 Java、Python、QuickJS 範例見 [CatVodSpider](https://github.com/FongMi/CatVodSpider)。

## 一起看

透過公開的 Syncplay 伺服器交換播放狀態，讓不同裝置看同一部片時自動對齊，不需要自架服務。

1. 播放中打開控制列，點「一起看」。
2. 選擇伺服器（預設 `syncplay.pl:8996`），填寫使用者名稱與房間號，按「連線」。
3. 同一房間的人會自動對齊：暫停、繼續、拖動進度條都會同步。

換集跟隨預設開啟：房主切到下一集時，其他人會跟著換。可在「設定 → 一起看」關閉，關閉後只提示不切集。

聊天在同一個對話框裡，連線後才會出現。單則訊息上限 150 字，只保留本次連線的最近 200 條，不寫入任何存檔。

已知限制：

- 房間內靠「劇名#集名」對齊同一集，請使用同一個來源；不同片源片頭長度不同時會存在固定偏移。
- 電視端（leanback）目前沒有入口，僅手機版提供。

協定相容 [Syncplay](https://github.com/Syncplay/syncplay)（Apache-2.0）。本專案為自行實作的協議層（`:syncplay` 模組，純 Java、無 Android 依賴），未使用其原始碼。

## Star History

<a href="https://www.star-history.com/?repos=FongMi%2FTV&amp;type=date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=FongMi/TV&amp;type=date&amp;theme=dark" />
    <img alt="FongMi/TV Star History" src="https://api.star-history.com/chart?repos=FongMi/TV&amp;type=date" />
  </picture>
</a>
