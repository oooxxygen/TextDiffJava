# 2026-11-25 v0.9.18 发布进展

## 已完成
- main 已推送：`c550f4e`（验收五项修复）+ `da0224c`（偏离度兜底契约测试修正）
- tag `v0.9.18` → `da0224c` 已推送，触发 Release

## 失败诊断（Release ×3 + CI ×2）
- 四个 run 全部落在 GitHub 平台事故窗口（githubstatus：Nov 25 00:08 UTC "impacted performance for some GitHub services"，01:15 UTC 已恢复）
- CI 失败于 Build & Test，Release 失败于 portableZip 构建，均 34~54 秒早退（正常基线 ~1m40s）
- 本地全量 `gradlew build`（382 测试）通过；本地 `portableZip` 同命令同 JDK 通过 → 非代码回归
- 根因认定：平台侧基础设施抖动（与 11-24 Windows 作业抖动先例同性质）

## 待办
- [ ] 本地 github.com 连通恢复后：删 tag 重推触发 Release（后台循环 bg_4 在跑，成功输出 RETAG-PUSHED）
- [ ] 监测新 Release run 至 success（#publish-release 出产物）
- [ ] 验证 Release 资产 5 件（portable ×3 + fat jar + source.zip）

## 备注
- 本地网络对 github.com 443 间歇 reset；api.github.com 正常
- 失败 run：45513367035 / 45513940427 / 45515666244（Release）、45512651646 / 45515651359（CI）
- 测试报告 artifact 查询返回空（事故期间 upload 未落库）
