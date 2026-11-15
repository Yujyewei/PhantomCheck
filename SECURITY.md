# 安全政策

## 機密管理
- 所有 API key 僅存於 local.properties（gitignore 排除），經 Gradle BuildConfig 注入。
- 程式碼、文件、issue、PR 中**永遠不出現**任何 key、token、密碼。
- 若不小心洩漏：立即在服務商端撤銷該 key → 用 git filter-repo 清除歷史 → 換新 key。GitHub 的 push protection 與 secret scanning 已開啟。

## 已啟用的防護
- Secret scanning + push protection（GitHub 內建）
- gitleaks CI 掃描（每個 PR 必跑）
- Branch protection：main 禁止直接 push，必須 PR + 通過 CI
- GitHub Actions 一律 pin 到 commit SHA，不用 branch tag

## 回報漏洞
請開 security advisory（Security → Report a vulnerability），勿直接開公開 issue。
