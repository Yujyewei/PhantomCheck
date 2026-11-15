# 幻訊 PhantomCheck

Android 智慧防詐訊息管理 App：在不觸發原 App 已讀的前提下攔截通知預覽，結合關鍵字過濾、AI 詐騙風險分析、詐騙警示中心與 SMS 自動回覆，守護日常通訊安全。

---

## 核心功能

- 📩 **通知攔截預覽**：透過 `NotificationListenerService` 即時讀取通知內容，無需開啟 LINE、IG 或簡訊等原 App，不觸發「已讀」。
- 🛡️ **多 Provider AI 詐騙分析**：支援 Mistral、OpenAI、Groq、OpenRouter、Gemini（OpenAI 相容）、DeepSeek 等多組 AI Profile 切換與逐一連線測試，自動評估訊息詐騙風險（0–100 分）。
- 🚫 **智慧通知去重與過濾**：自動過濾持續性通知、群組摘要與 10 分鐘內重複通知，支援關鍵字過濾（含指定 App）與 App 靜音清單。
- 📊 **通知欄常駐統計與統計儀表板**：通知欄即時顯示今日訊息、未讀、詐騙數與最近 5 個 App 圖示列（滑掉自動重生 + 15 分鐘心跳保活）；App 內提供 3 Tab（訊息／詐騙警示／統計）與右上角設定齒輪、7 日訊息量長條圖、AI 智慧標記分布與 Top 5 高風險 App。
- 🏷️ **AI 智慧標記與自訂標籤**：單次 AI 分析同時評估風險與自動分類（必看／行銷／垃圾通知等），支援自訂 `aiHint` 與手動標籤保護（不被 AI 覆寫），並可在首頁直接篩選。
- 💬 **App 分組與聊天式對話頁**：依 App 與寄件者分組瀏覽，支援我的最愛與 VIP 寄件者置頂、左滑氣泡刪除、平面清單多選批次已讀/星標/刪除、一鍵清除已讀與完整刪除入口。
- 🧭 **視覺化新手引導**：自製 5 步高光穿透引導，手把手帶逛權限、App 檢視、長按操作、標籤篩選與設定入口。
- 🧠 **每日 AI 重點摘要與推播**：每日排程自動彙整當日各 App 訊息與高風險節錄，可選發送低干擾摘要完成通知點擊直達統計頁。
- 🚨 **詐騙警示中心與可疑連結警示**：集中顯示高風險詐騙訊息與本週 vs 上週趨勢比較；偵測短連結、IP 網址或釣魚表單時於詳情頁顯示紅字警告。
- ⏰ **勿擾時段與 SMS 自動回覆**：支援設定勿擾起訖時間（時段內暫停推送警示通知但照常記錄）；SMS 自動回覆略過驗證碼/OTP/銀行簡訊與非台灣手機號碼。
- 📄 **CSV 匯出**：於統計頁或設定頁一鍵將攔截的訊息紀錄匯出為 CSV 檔案至手機 `Downloads` 資料夾，方便備份或稽核。
- 🧹 **訊息保留政策**：透過背景排程自動清理超過保留天數與則數上限的舊訊息（永久保留詐騙證據），避免本地資料庫無限膨脹。
- 🔑 **App 內加密金鑰管理**：支援直接在 App 內管理多組 AI Provider 與 API Key，透過 `EncryptedSharedPreferences` 本機加密儲存。
- 🎨 **S51 流體玻璃介面與深淺主題切換**：採用 Phantom 深紫品牌色系、`LiquidGlassTabBar`（水滴跟手、速度變形、fling 切頁）、`SwipeToRevealRow` 與 5 張可收合設定卡。

### v1.4.0 更新重點

- **常駐通知強化**：被滑掉即時重生（限流 3 秒）＋15 分鐘 WorkManager 心跳保活＋今日最近 ≤5 個 App 小圖示列。
- **完整刪除與批次操作**：App 長按刪除、平面清單點擊/長按操作與多選（上限 100 則）批次已讀/星標/刪除、對話氣泡左滑刪除（`SwipeToRevealRow`）、一鍵清除已讀、對話總覽長按刪除與 VIP 置頂。
- **資訊架構與玻璃手感**：底部改 3 Tab＋右上齒輪開設定；設定頁重編排為 5 張可收合玻璃卡；完整移植 S49~S51 `LiquidGlassTabBar`、`GlassCard`、`FluidSwitch`、`SwipeToRevealRow`。
- **AI 智慧標記與視覺化引導**：Room Migration 4→5 新增 `aiHint`/`isAiManaged`/`source`；單次 API 同時回傳風險＋標籤；5 步可跳過穿透式新手引導；勿擾時段、摘要完成推播、URL 風險警告、詐騙週比較與搜尋高亮。

---

## 安裝指南（免編譯直接安裝）

1. **從 GitHub Actions 下載 APK**
   - 開啟本倉庫的 GitHub 頁面，點選上方 **Actions** 分頁。
   - 在左側選擇 **Android Build Check**，點入最新一筆 `main` 分支且狀態為綠燈（✓）的執行紀錄。
   - 捲動至頁面底部的 **Artifacts** 區塊，下載 `phantomcheck-debug-<編號>` 壓縮檔並解壓縮，取得 `app-debug.apk`。

2. **在 Android 裝置安裝 APK**
   - 將 `app-debug.apk` 傳至 Android 手機（或直接在手機瀏覽器下載）。
   - 點擊 APK 檔案進行安裝；若系統提示「為了安全起見，您的手機目前不允許安裝來自這個來源的未知應用程式」，請依畫面引導開啟「允許來自此來源的應用程式」後完成安裝。

3. **開啟通知讀取權限**
   - 首次開啟「幻訊 PhantomCheck」後，點擊畫面上方的權限引導（或前往手機「設定 → 應用程式 → 特殊應用程式存取權 → 通知存取權」）。
   - 找到 **幻訊 PhantomCheck** 並開啟開關，授權 App 讀取通知以進行預覽與防詐分析。

4. **在 App 內輸入 Mistral API Key**
   - 回到 App 主畫面，點擊右上角 **API Key 設定**（鑰匙圖示）進入設定頁。
   - 輸入你的 Mistral API Key 並點擊儲存。金鑰會透過 `EncryptedSharedPreferences` 加密儲存於本機，不會上傳至任何第三方伺服器。
   - 完成後即可開始接收通知並進行 AI 詐騙偵測。

---

## 技術架構

| 層級 | 技術選型 | 說明 |
|---|---|---|
| **語言與 UI** | Kotlin + Jetpack Compose（Material 3） | 宣告式 UI、Phantom 深淺主題系統與響應式狀態管理 |
| **本地資料庫** | Room Database | 儲存攔截訊息（`messages`）與過濾規則（`filter_rules`），含版本 Migration |
| **背景排程** | WorkManager | 定期執行訊息保留政策清理過期資料 |
| **網路與 AI** | Retrofit / OkHttp + Mistral API | 呼叫 Mistral 模型進行詐騙語意分析與風險評分 |
| **機密與安全** | EncryptedSharedPreferences（AndroidX Security Crypto） | 本機加密儲存使用者輸入的 API Key；無硬編碼機密 |
| **CI 管線** | GitHub Actions | 每個 PR 與 `main` push 自動執行 `gitleaks` 機密掃描與 `./gradlew :app:assembleDebug` 建置，並於 `main` 上傳 APK artifact |

---

## 安全政策

- 所有機密（API key、token、密碼）**永不寫入程式碼、文件或 Git 歷史**。
- 在地端開發時可將測試用 key 放於 `local.properties`（已由 `.gitignore` 排除），或直接透過 App 內設定頁輸入。
- 完整資安規範與漏洞回報方式請見 [SECURITY.md](SECURITY.md)。

---

## 開發協作

本專案由多位 AI agent 與人類協作者共同維護，詳細規範請參閱：

- 協作規則與角色分工：[AGENTS.md](AGENTS.md)
- 貢獻指南與 PR 流程：[CONTRIBUTING.md](CONTRIBUTING.md)
- 本地開發環境設定：[docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md)
