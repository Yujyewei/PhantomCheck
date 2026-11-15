# AGENTS.md — AI 協作開發規則

本倉庫由多位 AI agent 協作開發。所有 agent（包括人類協作者）必須遵守以下規則。

## 角色分工

| Agent | 模型 | 負責範圍 | Branch 前綴 |
|---|---|---|---|
| Google Antigravity | Gemini / Claude | 主要功能開發、大型重構、新模組 | feature/ |
| Mistral Code (GLM Flash) | GLM-5.3 Flash | 小型修改、bug 修正、文件、樣式微調 | fix/、docs/ |
| Vibe（管理對話） | GLM | 任務分派、code review、PR 合併、倉庫管理 | —（不直接寫程式） |

## 鐵則（違反 = PR 直接退回）

1. **絕不提交機密**：API key、密碼、token 一律不得寫入任何檔案。
   機密只存在 local.properties（已 gitignore），程式經 BuildConfig 讀取。
2. **一個任務一個 branch**：不同 agent 不得推到彼此的 branch，更不得直接推 main。
3. **所有變更走 PR**：PR 標題格式 [feature|fix|docs] 簡述，填寫 PR template。
4. **開工前先看 issue**：每個任務先在 GitHub issue 建立/認領，branch 名對應 issue 編號，如 feature/12-scam-alert。
5. **不留未完成合併**：PR 描述必須註明「完成」或「WIP（缺什麼）」，交接資訊寫在 PR 或 issue。

## 工作流程

1. issue 建立（Vibe 分派）
2. agent 認領 → 開 branch → 實作 → push → 開 PR
3. CI（gitleaks + build）通過
4. review（Vibe / 另一 agent）
5. squash merge 進 main → 關閉 issue

## 交接清單（PR 必填）
- 做了什麼、為什麼
- 動了哪些檔案
- 沒做完的部分與下一步建議
- 已通過的測試/驗證方式

## 衝突預防
- 兩個 agent 同時開工時，任務範圍不得重疊（Vibe 分派時保證）。
- 開工前先 git pull origin main rebase。
- 共用檔案（build.gradle.kts、AndroidManifest.xml、AppDatabase.kt）的修改屬「小型修改」以上，需在 issue 註明。
