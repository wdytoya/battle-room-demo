# 猜拳对撞房 (BattleRoomDemo)

> **Note for non-Chinese readers:** The rest of this document is written in Chinese. For easier understanding, please use an AI tool to translate the content below into your native language before following the instructions.

基于 **Java 17 + Netty WebSocket + Redis** 的 ELO 匹配对战 Demo：排队匹配、服务端权威 1D 物理、Web 客户端本地预测与对手延迟插值。

玩法：**左右加速移动，对撞时用猜拳扣灯**（各 3 盏）。一方灯灭或倒计时结束比灯数。出生站位：**先点「开始匹配」的一方在左**。

打开 `http://localhost:8080/` 即可（Netty 同时提供静态页与 `/ws`）。

## 环境要求

| 依赖               | 建议版本            |
| ---------------- | --------------- |
| JDK              | 17+（本机可用 21）    |
| Maven            | 3.8+            |
| Docker / Compose | 启动带 AOF 的 Redis |

## 快速开始

### 1. 启动 Redis

```bash
docker compose up -d
```

数据目录：`./redis-data`（已在 `.gitignore`）。

### 2. 编译并启动服务端

```bash
mvn -q compile
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.1:java "-Dexec.mainClass=org.example.BattleServer" "-Dexec.classpathScope=compile"
```

也可在 IDEA 中运行 `org.example.BattleServer`。

### 3. 双窗口对战

1. 两个**独立浏览器窗口**打开 `http://localhost:8080/`。
2. 分别登录不同用户名。默认键位（可在页面本地改，不进服务端）：
   - **向左 / 向右**：`A` / `D`（或屏幕长按按钮）
   - **布 / 剪刀 / 石头**：`J` / `K` / `L`
3. 双方「开始匹配」→ 进入竞技场；对撞瞬间用当前手势判胜负扣灯。
4. 结算后约 10 秒或点「关闭结果」回到匹配。

缩短对局便于自测：

```bash
-Dbattle.playingMs=20000
```

## 关键配置

集中在 `org.example.config.ServerConfig`（系统属性 / 同名环境变量）：

| 项     | 默认                            | 覆盖示例                                      |
| ----- | ----------------------------- | ----------------------------------------- |
| 端口    | `8080`                        | `-Dbattle.port`                           |
| Redis | `127.0.0.1:6379`              | `battle.redis.host` / `BATTLE_REDIS_HOST` |
| Tick  | **20Hz**                      | `-Dbattle.tickHz`                         |
| 对局时长  | 30000ms                       | `-Dbattle.playingMs`                      |
| 匹配扫描  | 500ms                         | `-Dbattle.match.scanMs`                   |
| 对撞无手势 | 有手势方胜                         | `-Dbattle.noInputLoses=false`             |
| 物理常数  | 加速/限速/摩擦/半径/场地宽/三灯/碰撞冷却等      | 代码常量                                      |
| ELO   | 初始 1000；定级 10 局；K=40/32/24/16 | 代码常量                                      |

## 玩法规则

- 一维场地：A 左朝右、B 右朝左；左右为**场地绝对方向**。
- 物理：推力、无推力摩擦、撞墙反弹、对撞交换速度并分离；碰撞有短冷却。
- **对撞猜拳**：胜者使败者 **-1 灯**；平局或不判则不扣；一方无手势且 `NO_INPUT_LOSES` 开启时视为负。
- **胜负**：任一方灯归零立即结算（`LIGHTS`）；否则时间到比灯数（`TIME`），相同则平。
- ELO 按最终胜/平/负更新；满 10 局进排行榜。

手势对对手**实时可见**（Demo 取舍，存在后手优势）。

## 架构概览

```
浏览器 (app.js)
    ↕ WebSocket JSON  /ws
Netty (HTTP 静态资源 + WS)
    → GameService（单线程 gameLoop：匹配扫描 + 房间 tick）
         ├─ MatchQueue / Matchmaker
         ├─ RoomManager / BattleRoom（权威物理 + 结算）
         ├─ SessionManager
         └─ RedisStore（玩家 Hash + 排行 ZSET）
```

- **同线程**推进「匹配 → 建房 → tick」，避免半初始化房间被 tick 到。
- 推力 / 手势为**粘性输入**，写入房间后由下一 tick 快照带出，不单独广播。
- `login_ok` 带 `physics` 与 `activity`；在房细节靠随后的 `room_snapshot` / `final_result`。

### 房间状态机

`MATCHED → PLAYING → RESULT → CLOSED`（倒计时全程在 `PLAYING`）。

## 客户端同步

| 角色          | 做法                                                                                                                             |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **自己**      | `predictSelf`：用本地键位按与服务器相同的半隐式积分推进；快照到时 `reconcileSelf` 按误差门槛软拉 / 硬掰（**无**输入历史 + replay）                                       |
| **对手**      | `oppSnapBuf` 缓冲快照；`interpolateOpp` 将渲染时刻定为约 **晚 1 个 tick 周期**，在相邻两帧间按时间 lerp                                                   |
| **倒计时**     | `displayRemainingMs`：快照间按 dt 外推，快照到时对 `remainingMs` 硬对齐或 40% 平滑                                                                |
| **UI / 绘制** | 首次进入对战 `showBattlePlaying`；之后 snapshot 主要 `applySnapshot`。绘制由 **rAF 链式循环**驱动（`startRenderLoop` 有 `renderLoopId` 守卫，不每 tick 重开） |

对手缓冲：同 tick 覆盖、更小 tick **丢弃**；**新 `roomId` / 结算 / 关面板**时清空（不在运行时因 tick 回退整表清空）。

## WebSocket 消息

**客户端 → 服务端**

`login` · `match_start` · `match_cancel` · `input_hand` · `input_thrust` · `query_ranking_top100` · `query_me`

**服务端 → 客户端**

`login_ok` · `match_status` · `room_snapshot` · `final_result` · `ranking_top100` · `player_query` · `error`

快照主要字段：`tick` / `phase` / `remainingMs` / `players{x,vx,lights,hand,thrust,side}` / `lastCollision` 等。

### 常见错误码（节选）

非法手势/推力、未在 `PLAYING`（`ROOM_NOT_PLAYING`）、已在房（`ALREADY_IN_ROOM`）、同名踢线（`KICKED`）等，见 `error` 消息。

## Redis

| Key                     | 结构   | 说明                |
| ----------------------- | ---- | ----------------- |
| `rps:player:{username}` | Hash | elo / gamesPlayed |
| `rps:ranking`           | ZSET | 仅满定级局数的玩家         |

关闭 Java 不丢 Redis；`docker stop/start` 保留 `./redis-data`；`docker compose down -v` 或删数据目录才清空。  
**房间状态仅在内存**：进程重启丢对局，不丢 ELO。

## 源码导航

| 包 / 路径                   | 内容                                           |
| ------------------------ | -------------------------------------------- |
| `config`                 | `ServerConfig`                               |
| `match`                  | 队列与匹配                                        |
| `room`                   | `BattleRoom` 物理/猜拳/结算，`RoomManager` tick 与广播 |
| `service`                | `GameService` 编排                             |
| `session`                | 连接与登录会话                                      |
| `redis` / `elo`          | 持久化与积分                                       |
| `netty` / `BattleServer` | 启动与协议栈                                       |
| `msg`                    | JSON 出站构造                                    |
| `static/app.js`          | 协议、UI、预测 / 插值 / 回滚                           |

## License

MIT
