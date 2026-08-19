# StockExchange（交易市场）更新说明

## 本版本内容

- 保留并完善服务端全功能导出接口（`WebMarketManager`）：行情、盘口、挂单/撤单、市价、仓库存取、兑换、公告、税务、停牌、管理端统计等。
- 所有导出写操作由内部强制调度到服务器主线程串行执行，与游戏内操作互斥，避免网页与游戏同时操作导致数据异常。
- 新增云端构建流水线（GitHub Actions）：Residence 依赖从 Zrips/Residence 源码先行构建，公开依赖自动拉取，服务器私有 GMZC 插件用 ABI 桩对齐签名，构建产物不包含桩代码。

## 运行要求

- Paper 1.21.11+，Java 25。
- 运行时依赖与之前一致：Residence 6.0.2.x、Vault、Floodgate/Geyser、Slimefun（可选）、GMZCMail、MGActivitys、GMZCTitles。
- 将 `StockExchange-1.0.0-gmzc.jar` 部署到服务器 `plugins/` 目录后重启。
