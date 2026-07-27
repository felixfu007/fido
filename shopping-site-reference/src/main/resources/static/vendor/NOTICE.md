# 隨附第三方函式庫

## qrcode.js / qrcode_UTF8.js

- 來源：https://github.com/kazuhikoarase/qrcode-generator（`js/dist/qrcode.js` 與
  `js/dist/qrcode_UTF8.js`，master 分支原始碼，未修改）。
- 授權：MIT License（Copyright (c) 2009 Kazuhiko Arase），見同目錄 `LICENSE-qrcode-generator.txt`。
- 用途：純前端產生跨裝置 QR 登入的 QR code 圖片（見 `../app.js` 第 4 節），僅此用途，不涉及
  WebAuthn/FIDO 密碼學邏輯本身（那部分本專案刻意不依賴任何函式庫，見 `../app.js` 檔頭說明）。
- 刻意**不**透過 CDN 載入：本專案架構決策之一是「全地端部署（非雲端）」，採用廠商的正式環境
  未必能存取外部 CDN，故隨原始碼一併提供本機靜態檔案，不依賴執行期網路連線。
