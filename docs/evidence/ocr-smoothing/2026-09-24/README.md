# 平滑候选实验原始证据

仅含固定合成文字，不含真实屏幕。大报告使用 gzip 无损压缩，避免重复的大段 JSON 占用仓库；archives.json 同时记录压缩文件及原文 SHA256。可以用 `gzip -dc 文件.json.gz` 查看；解压后即设备原报告，不改变评分或 SDK 输出。

summary.json 为判定摘要；各设备 analysis.json 为分组分析，lifecycle.json 为实际清理检查；full/restart 保留完整文字、块、位置、像素及模型身份。baseline-recheck 是新 APK 对旧144项的额外回归。原三语证据保留于 ../../trilingual/2026-09-24。
