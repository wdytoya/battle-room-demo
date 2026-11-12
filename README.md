# 猜拳对撞房 (BattleRoomDemo)

> **Note for non-Chinese readers:** The rest of this document is written in Chinese. For easier understanding, please use an AI tool to translate the content below into your native language before following the instructions.

基于 **Java 17 + Netty WebSocket + Redis** 的 ELO 匹配对战 Demo。推荐本机 **四进程**（Gateway / Logic / Match / Physics）；也可用 **monolith** 以单进程方式运行。

```
Client ──HTTP/WS──► Gateway(:8080)
                      │ internal TCP + length-prefixed JSON
                      ▼
                    Logic(:9200)
                   ╱          ╲
          Match(:9100)     Physics(:9300)
                   Redis(:6379) ← Logic 读写玩家 / 路由 / 重连
```

玩法：**左右加速移动，对撞时用猜拳扣灯**（各 3 盏）。一方灯灭或倒计时结束比灯数。出生站位：**先点「开始匹配」的一方在左**。

打开 `http://localhost:8080/`（多进程由 **Gateway** 提供静态页与 `/ws`）。

## 环境要求

| 依赖 | 建议版本 |
| ---- | -------- |
| JDK | 17+ |
| Maven | 3.8+ |
| Docker / Compose | 启动带 AOF 的 Redis |

## 快速开始（多进程）

### 1. 启动 Redis

```bash
docker compose up -d
```

### 2. 编译

```bash
mvn -q -DskipTests install
```

### 3. 按顺序启动四个进程

先 Match / Physics，再 Logic，最后 Gateway（Logic 会连 Match 与 Physics）：

```bash
mvn -q -pl match org.codehaus.mojo:exec-maven-plugin:3.5.1:java "-Dexec.classpathScope=compile"
mvn -q -pl physics org.codehaus.mojo:exec-maven-plugin:3.5.1:java "-Dexec.classpathScope=compile"
mvn -q -pl logic org.codehaus.mojo:exec-maven-plugin:3.5.1:java "-Dexec.classpathScope=compile"
mvn -q -pl gateway org.codehaus.mojo:exec-maven-plugin:3.5.1:java "-Dexec.classpathScope=compile"
```

IDEA：分别运行各模块主类 `MatchServer` / `PhysicsServer` / `LogicServer` / `GatewayServer`（同样先 Redis）。

### 4. 双窗口对战

两个独立浏览器窗口打开 `http://localhost:8080/`，登录不同用户名后匹配即可。

## 单进程模式（monolith）

同一套 `GameService` / 房间 / 匹配算法，入口为 `monolith` 的 `BattleServer`：

- `LocalNettyTransport` 本进程推 WS
- `GameService(..., remoteMatch=false, remotePhysics=false)`：匹配与物理都在本进程
- 无应用层心跳超时踢人（客户端仍可能发 `ping`，服务端不扫超时）
- 普通 WS 断线仍可走 soft-disconnect / 重连窗（若 `battle.reconnect.enabled=true`）

启动前请停掉多进程（尤其 Gateway），避免抢 `8080`：

```bash
mvn -q -DskipTests install
mvn -q -pl monolith org.codehaus.mojo:exec-maven-plugin:3.5.1:java "-Dexec.classpathScope=compile"
```

IDEA：运行 `org.example.BattleServer`。浏览器打开 `http://localhost:8080/`（端口 `battle.port` / `BATTLE_PORT`，默认 `8080`）。

## 架构要点

| 边界 | 说明 |
| ---- | ---- |
| **Gateway** | 唯一对客 HTTP/WS；`ping`→本地 `pong`；心跳扫描踢人；上行 `client.text` / 断线 `client.inactive`；下行 `client.send` / `client.close` |
| **Logic** | 登录、房间编排、结算、ELO、会话与 Redis 路由；经 `GatewayFanoutTransport` 按 `gatewayId` 回推；连远程 Match + Physics |
| **Match** | ELO 匹配队列（localhost RPC） |
| **Physics** | 固定 tick、推力/碰撞/扣灯；结果回 Logic 再快照推客户端 |
| **core** | 领域与编排共享库（房间、Matchmaker、GameService、RedisStore、Messages…） |
| **common** | `ClusterConfig`、内部 RPC 帧编解码与 Client/Server |
| **monolith** | 单进程：HTTP/WS + 本地 Match/Physics |

内部帧：`uint32 BE length` + UTF-8 JSON（`op` / `reqId` / `from` / `payload` 等）。典型 op：`sys.hello`、`client.*`、`match.*`、`phys.*`。

会话下行抽象为 `ClientTransport`：多进程用 Fanout，单进程用 `LocalNettyTransport`。

## 心跳与重连

| 场景 | 行为 |
| ---- | ---- |
| 客户端活跃 | 默认约每 5s 发 `{"type":"ping"}`（间隔由 `login_ok` / `reconnect_ok` 的 `heartbeatIntervalMs` 下发） |
| Gateway 本地 | 回 `pong`，**不进 Logic**；刷新连接活跃时间 |
| 心跳超时 | Gateway 发 `kick` 并关连接，通知 Logic **放弃重连（abandon）**；对局可继续空转到自然结算 |
| 普通断线 | Logic → `DISCONNECTED`，保留房间与重连窗；凭 `reconnectToken` + `lastAckSeq` 可在任意 Gateway 重连（路由在 Redis） |
| 可靠投递 | `login_ok` / `reconnect_ok` / `final_result` 等带 `seq`；重连后补发环缓 + 全量 `room_snapshot` |

相关开关：`battle.heartbeat.*`、`battle.reconnect.*`（见下表）。应用层心跳踢人仅多进程 Gateway 具备。

## 对外协议（摘要）

**C→S**：`login` · `ping` · `reconnect{username,reconnectToken,lastAckSeq}` · `match_start` / `match_cancel` · `input_hand` / `input_thrust` · 排行 / 查人等  
**S→C**：`login_ok`（含 token / `serverSeq` / `heartbeatIntervalMs`）· `pong` · `kick` · `reconnect_ok` · `match_*` · `room_snapshot` · `final_result`；可靠包带 `seq`

## 关键配置

系统属性与环境变量二选一（属性优先）。集群端口见 `ClusterConfig`，玩法/Redis 见 `ServerConfig`。

| 项 | 默认 | 覆盖（sys / env） |
| -- | ---- | ----------------- |
| Gateway HTTP | `8080` | `battle.gateway.port` / `BATTLE_GATEWAY_PORT` |
| 单体 HTTP | `8080` | `battle.port` / `BATTLE_PORT` |
| Match RPC | `9100` | `battle.match.port` / `BATTLE_MATCH_PORT` |
| Logic RPC | `9200` | `battle.logic.port` / `BATTLE_LOGIC_PORT` |
| Physics RPC | `9300` | `battle.physics.port` / `BATTLE_PHYSICS_PORT` |
| 内部绑定主机 | `127.0.0.1` | `battle.bind.host` / `BATTLE_BIND_HOST` |
| Redis | `127.0.0.1:6379` | `battle.redis.host` · `battle.redis.port` |
| Tick | 20Hz | `battle.tickHz` / `BATTLE_TICK_HZ` |
| 对局时长 | 30000ms | `battle.playingMs` / `BATTLE_PLAYING_MS` |
| 心跳检测周期 | 1000ms | `battle.heartbeat.checkMs` |
| 心跳超时踢人 | 15000ms | `battle.heartbeat.timeoutMs` |
| 客户端 ping 间隔 | 5000ms | `battle.heartbeat.intervalMs` |
| 心跳开关 | true | `battle.heartbeat.enabled` |
| 重连窗口 | 60000ms | `battle.reconnect.windowMs` |
| 重连开关 | true | `battle.reconnect.enabled` |
| ONLINE 路由 lease | 45000ms | `battle.route.leaseMs` |
| 路由续期间隔 | 15000ms | `battle.route.renewMs` |
| 断线后 Redis 宽限 | 30000ms | `battle.reconnect.redisSlackMs` |

Logic 入口使用 `remoteMatch=true, remotePhysics=true`；monolith 三参构造默认两者为 `false`。不要同时起 Gateway 与 monolith 抢同一对外端口。

## Redis

| Key | 说明 |
| --- | ---- |
| `rps:player:{username}` | ELO / gamesPlayed |
| `rps:ranking` | 排行 ZSET |
| `rps:route:{username}` | 网关 / 连接 / token / 状态（TTL；ONLINE 时 Logic 周期续期） |
| `rps:reconnect:{token}` | token → username（与 route 成对维护 TTL） |

房间权威态在 Logic / Physics 内存；进程重启丢对局，不丢 ELO。

## 模块导航

| 模块 | 内容 |
| ---- | ---- |
| `common` | `ClusterConfig`、内部 TCP 帧与 RPC |
| `core` | 房间 / 匹配 / 会话 / Redis / 对外 Messages / `GameService` |
| `gateway` | HTTP 静态、WS、心跳、LogicBridge |
| `logic` | `LogicServer` + `GatewayFanoutTransport` |
| `match` / `physics` | 匹配 / 物理进程入口 |
| `monolith` | `BattleServer` + `LocalNettyTransport` |

## License

MIT
