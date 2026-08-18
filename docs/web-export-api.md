# StockExchange 网页导出接口文档（WebMarketManager）

> 版本：2026-08-09 重做版。本文档描述导出接口的完整能力、与游戏内每个菜单/按钮的对应关系、
> 检测一致性、并发模型与调用方式。

## 1. 目标与设计原则

网页端应能复刻游戏内交易市场的全部菜单与按钮功能，且**效果与游戏内一致**：

1. **检测一致**：网页操作走与游戏内相同的校验（成长等级、数量上限、价格区间/步进、停牌、
   余额/持仓、自成交、托管一致性、每日新增商品上限）。游戏内已有的检测，导出接口必须同样触发。
2. **仓库通道**：网页资金/物品走「个人仓库」——买单从货币仓库扣款、卖单从个人物品仓库扣货，
   成交/撤单/部分取回退回仓库；网页操作永不触碰在线背包；游戏内操作除显式存取外也不触碰仓库。
3. **单一撮合引擎**：导出接口复用 `OrderManager` 的同一撮合/结算引擎（价格-时间优先、卖家税、
   托管校验、成交落库、每日新买家记录），不另写第二套撮合逻辑。
4. **并发安全**：写操作按玩家 UUID 加可重入锁，并强制回服务器主线程串行执行；
   同一个人多个请求、或多个人同时操作，资产不会交叉移动。

## 2. 调用入口

### Java 调用（插件内/桥接插件）

```java
StockExchangePlugin plugin = StockExchangePlugin.getInstance();
WebMarketManager web = plugin.getWebMarketManager();
Map<String, Object> result = web.placeBuy(uuid, itemId, price, quantity);
// result: { "ok": true, "data": {...} } 或 { "ok": false, "message": "..." }
```

### 网页转发（StarCityBridge market 模块）

`StarCityBridge` 的 `MarketModule` 已把上述方法暴露为 WebSocket 动作，动作名与下文 API 表格
中的“动作名”一致。请求载荷字段见各 API 参数。响应统一为：

```json
{ "ok": true, "message": "", "data": { ... } }
```

失败时 `ok=false`，`message` 为与游戏内一致的中文提示（含 `§` 颜色码，展示层可剥离）。

## 3. 通用约定

- `player_uuid`：玩家 UUID 字符串；所有玩家级操作会校验格式与成长等级。
- `admin`：布尔标志，仅当调用方（网站后端）已确认管理员身份后才能传 `true`；
  对应游戏内 `exchange.admin` 权限检查。
- `item_base64`：物品的 Base64 序列化（`ItemSerializer` 格式），用于“指定物品上架/注册商品”。
- 金额/价格使用小数（BigDecimal），价格必须为 `price_tick` 的整数倍且在 `min_price~max_price` 内。
- 分页从 1 开始；`page_size` 超出范围时按游戏默认值处理。

## 4. 菜单/按钮 → 接口映射表

| 游戏内菜单 | 按钮/操作 | 导出方法 | 动作名 |
|---|---|---|---|
| 商品列表页（出售/求购） | 翻页、模式切换、搜索、返回 | `listItems(uuid, buyPage, query, page, pageSize)` | `list_items_page` |
| 商品列表页 | 点击商品/类别进入详情 | `itemDetail(uuid, itemId, buyPage, page, pageSize)` | `item_detail_full` |
| 商品列表页 | 出售/求购页数据（兼容旧版） | `listItems()` / `itemDetail(itemId)` | `list_items` / `item_detail` |
| 详情页-出售模式 | 左键购买 1 / Shift 整格 / 批量购买 | `directBuy(uuid, sellOrderId, quantity)` | `direct_buy` |
| 详情页-出售模式 | 快速上架该物品 | `quickSell(uuid, itemId)` | `quick_sell` |
| 详情页-求购模式 | 左键供货 1 / Shift 整格 | `directSell(uuid, buyOrderId, quantity)` | `direct_sell` |
| 详情页-求购模式 | 一键供货（按求购价从高到低） | `supplyAll(uuid, itemId)` / `supplyPlan(uuid, itemId)` | `supply_all` / `supply_plan` |
| 详情页-求购模式 | 求购该物品（输入价格数量） | `placeBuy(uuid, itemId, price, quantity)` | `place_buy` |
| 详情页-本人挂单 | 左键取回 1 / Shift 取回整格 | `withdrawOrderQuantity(uuid, orderId, quantity, admin)` | `withdraw_order` |
| 详情页-盘口 | 查看买/卖盘 | `orderBook(itemId)` | `order_book` |
| 我的交易记录 | 挂单（点击取消） | `cancel(uuid, orderId, admin)` | `cancel` |
| 我的交易记录 | 成交记录 | `myHistory(uuid, page, pageSize)` / `myTrades(uuid, page, size)` | `my_history` / `my_trades` |
| 我的交易记录 | 我的挂单 | `myOrders(uuid)` | `my_orders` |
| 仓库 | 查看仓库 | `myWarehouse(uuid)` | `my_warehouse` |
| 仓库 | 一键提取 / 提取星光点 / 提取物品 | `warehouseWithdrawAll/Money/Item(uuid, [itemBase64])` | `warehouse_withdraw_*` |
| 仓库（游戏内） | 手持物品存入 / 余额存入 | `depositHandItem(uuid, quantity)` / `depositMoney(uuid, amount)` | `warehouse_deposit_hand` / `deposit_money` |
| 仓库（网页） | 货币仓库提现到余额 | `withdrawMoney(uuid, amount)` | `withdraw_money` |
| 上架菜单 | 选择具体物品后统一上架 | `placeSell(uuid, itemId, price, quantity, itemBase64)` | `place_sell_item` |
| 上架菜单（普通品种） | 按品种上架（兼容） | `placeSell(uuid, itemId, price, quantity)` | `place_sell` |
| 市价买卖 | 市价买入 / 市价卖出 | `marketBuy(uuid, itemId, quantity)` / `marketSell(uuid, itemId, quantity)` | `market_buy` / `market_sell` |
| 添加商品 | 原版物品搜索 | `catalogSearch(query)` | `catalog_search` |
| 添加商品 | 注册商品到目录 | `registerCatalogItem(uuid, itemBase64, admin)` | `register_item` |
| 货币兑换 | 钻石→星光点 / 星光点→钻石 | `exchangeDiamondForMoney(uuid)` / `exchangeMoneyForDiamond(uuid)` | `exchange_d2m` / `exchange_m2d` |
| 公告栏 | 查看公告 | `announcements(page, pageSize)` | `announcements` |
| 管理员命令 | 停牌/复牌、税率、公告管理、重载、重连库 | `adminSuspend` / `adminSetTax` / `adminAnnouncement` / `adminReload` / `adminReconnectDb` | `admin_suspend` / `admin_set_tax` / `admin_announcement` / `admin_reload` / `admin_reconnect` |
| 行情统计 | 历史 OHLC/销量排行 | `getMarketStats(days)` | `market_stats` |

## 5. 写操作检测清单（与游戏内一致）

所有写操作在 `OrderManager` 中执行，与游戏内共用检测：

| 检测 | 覆盖接口 |
|---|---|
| 成长等级 ≥ 交易市场等级 | 全部玩家写操作 |
| 数量 1 ~ `max_order_quantity` | placeSell/placeBuy/marketBuy/marketSell/directBuy/directSell/supplyAll/withdrawOrderQuantity |
| 价格在 `min_price~max_price` 且为 `price_tick` 整数倍 | placeSell/placeBuy |
| 品种停牌 | placeSell/placeBuy/marketBuy/marketSell/directBuy/directSell/supplyPlan |
| 货币仓库余额（含 10% 交易税，创建买单立即扣税） | placeBuy/marketBuy/directBuy |
| 个人物品仓库持仓 | placeSell/marketSell/directSell/quickSell/supplyAll |
| 不能买/卖自己的订单 | directBuy/directSell/撮合引擎 |
| 订单托管数据一致性（缺托管即拦截） | cancel/withdrawOrderQuantity/撮合 |
| 订单归属（非本人需 admin） | cancel/withdrawOrderQuantity |
| 每日新增商品上限（非 admin） | registerCatalogItem |
| 卖家成交税（结算时从货款扣除） | 撮合引擎（所有成交路径） |
| 每日新买家记录 | 撮合引擎（所有成交路径） |

## 6. 并发模型

- **写操作**：`withPlayerLock(uuid, task)` 先获取该玩家 15 秒内可用的可重入锁；
  然后 `callSyncMethod` 回主线程执行，30 秒超时。锁保证同一玩家的多个请求串行；
  `OrderManager` 方法本身 `synchronized`，保证不同玩家的写操作也互斥（单一写入者）。
- **读操作**：无锁；存储层自带同步，可并发读取。
- **同玩家双开**：同一玩家同时提交两笔买入时，第二笔会等待第一笔完成，
  超过 15 秒返回“你有一笔交易操作正在进行，请稍后再试”。
- **幂等性**：接口不提供幂等键；重复提交会创建多笔订单（与游戏内重复点击一致）。
  网页端如需防重复提交，应在自身层加按钮防抖/幂等键。

## 7. 关键差异说明（网页 vs 游戏）

- 资产载体：游戏内下单从背包/余额扣，网页从**个人仓库**扣；成交物品/退款均入个人仓库
  （在线玩家成交时仍按游戏规则：物品优先入背包、货款优先入余额）。
- 货币兑换：游戏内使用背包钻石/余额；网页版使用**仓库钻石**（d2m）与**余额**（m2d），
  税率与金额完全相同。
- 提取类操作（仓库→背包/余额）需要玩家在线，与游戏内一致。
- 注册商品只登记目录、不消耗物品，与游戏内“搜索添加”一致。

## 8. 安全说明

- `admin` 标志由调用方认证后传入；导出接口本身不做网页登录，身份认证由网站后端/StarCityBridge 负责。
- 任何写操作都不会凭空产生物品：物品只能从个人仓库扣减；`registerCatalogItem` 仅登记目录。
- 主线程串行执行 + 托管一致性校验，防止并发下单造成资产重复扣减/发放。

## 9. 构建与部署

```powershell
# StockExchange
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\java-server\dev-plugins\stockexchange-patched\build.ps1
# StarCityBridge（Maven 构建，systemPath 已指向当前仓库构建产物）
$env:JAVA_HOME='D:\java-server\StarCIty\runtime\jdk25\jdk-25.0.3'
D:\java-server\dev\work\apache-maven-3.9.11\bin\mvn.cmd -f D:\java-server\dev-plugins\StarCityBridge\pom.xml package
```

产物：`stockexchange-patched\build\StockExchange-1.0.0-gmzc.jar`、
`StarCityBridge\target\starcity-bridge.jar`。下一次 `start.bat` 重启自动部署并生效；
重启后需按“验证清单”做玩家级验收。

## 10. 验证清单（重启后）

- [ ] 插件启用日志无 `WebMarketManager`/`MarketModule` 异常。
- [ ] `market_info`、`list_items_page`、`item_detail_full`、`order_book` 返回数据与游戏 GUI 一致。
- [ ] 网页挂买/卖单 → 游戏内可见；游戏内挂单 → 网页可见。
- [ ] 网页下单与游戏内下单可互相撮合，托管/税额/成交记录一致。
- [ ] 网页撤单/部分取回 → 资产回到个人仓库；游戏内仓库可见。
- [ ] 停牌、余额不足、成长等级不足等检测返回与游戏内相同提示。
- [ ] 同一玩家并发提交两笔写操作，第二笔等待或返回忙碌提示，资产不重复扣减。
