# GPS 共享 v2.0.0：定位保真改造设计

**日期**：2026-10-22
**代码库**：`工作区根目录\GPS共享2`（独立仓库，分支 `v2-fidelity`，基线 `731d196` = v1.6.2）
**基线库**：`工作区根目录\GPS共享`（v1.6.2，**本设计全程只读，不做任何修改**）

---

## 1. 目标与动机

用户诉求：**「我希望定位在接收端也是真实、准确、高可靠、稳定、快速的。」**

对 v1.6.2 的代码走查发现：位置坐标本身在链路上是**无损**的（不经平滑、不经重采样），但围绕它的**元信息**存在多处失真与延迟。逐条对应用户的五个词：

| 用户的词 | v1.6.2 的实际问题 | 根因位置 |
|---|---|---|
| 真实 | 注入给系统的时间戳是「发送时刻」，不是「解算时刻」；真实解算年龄（0–1 秒）在协议里根本没有传输 | `TransmitEngine.java:63-66` 覆盖时间戳 |
| 真实 | 精度被截断：> 49.5 m 一律变成 49.5 m | `NmeaGenerator.java:256` 把 HDOP 夹在 [0.5, 9.9]，`NmeaParser.java:200` 再 ×5 还原 |
| 真实 | 垂直/速度/航向精度是写死的常量 5 m / 1 m/s / 5° | `MockLocationInjector.java:135-139` |
| 准确 | GGA 的使用卫星数在无星时报 1 颗 —— **经复核判定为刻意保留的防御，不改**（见 §3.5） | `NmeaGenerator.java:31` 的 `Math.max(1, ...)` |
| 稳定 | 每周期**注入两次**（一次上一周期的旧位置、一次当前） | `NmeaParser.java:112-124`（GGA 处的 leftover 发布）+ `:137-145`（GSV 处的当前发布） |
| 快速 | 发送端定频 tick，定位到了要等下一个 tick 才发，额外 0–1000 ms | `GpsOutputService.java:545-555` 定时 tick |
| 快速 | 关掉 GSV 语句时，当前周期的位置要等下一周期才发布，**整整多 1 秒** | `NmeaParser.java:137-145` 发布点绑在 GSV 上 |
| 高可靠 | 注入失败只写一行日志，用户看不到 | `MockLocationInjector.java:142-144` |
| 高可靠 | 断链后接收端位置卡住不动，界面毫无提示 | `ReceiveService` 无过期状态 |
| 高可靠 | 无解算时发送端改发 ZDA 保活帧，接收端无从知道「位置已过期」 | `GpsOutputService.java:613` |

**结论：坐标是准的，坏的是它的"元信息"。** 本设计全部围绕元信息展开，不动坐标本身。

---

## 2. 关键决策

以下 7 条是设计的前置约束，均已在设计讨论中确认：

| # | 决策 | 理由 |
|---|---|---|
| D1 | **两端必须同步升级** | 允许改写标准语句的语义，换取最干净的设计 |
| D2 | **不滤波，原样透传** | 滤波要用样本窗口换滞后，与「快速」冲突；且滤波后的路径不等于发送端手机的真实路径。抖动交给导航 App 自己的平滑 |
| D3 | **传解算年龄，接收端本地重锚** | 年龄是**时长**，与两机时钟差无关，天然免疫时钟不同步 |
| D4 | **网络兜底照发，但标明来源 + 如实精度** | 不隐藏降级；精度如实交给系统，由导航 App 自行降级处理 |
| D5 | **新解即发（事件驱动）** | 消除 0–1000 ms 的 tick 量化等待 |
| D6 | **新增一条私有元数据语句 `$GPSSHARE`** | 标准 NMEA 0183 没有能无损承载米制精度的字段 |
| D7 | **接收端不加精度劣化保护** | 精度如实给系统即可；再造人为规则会把「真实」搅浑 |

---

## 3. 协议设计

### 3.1 新增语句 `$GPSSHARE`

```
$GPSSHARE,1,SEQ=1234,ACC=12.5,VACC=8.0,SACC=0.8,BACC=15.0,AGE=842,SRC=GPS*hh
```

| 位置 | 键 | 类型 | 含义 | 来源 API |
|---|---|---|---|---|
| 1 | — | 整数 | 协议版本，固定 `1` | — |
| 2 | `SEQ` | 整数 | 解算序号，每次**真正的新解算** +1 | 发送端自增 |
| 3 | `ACC` | 米（1 位小数） | 水平精度 | `Location.getAccuracy()` |
| 4 | `VACC` | 米（1 位小数） | 垂直精度 | `getVerticalAccuracyMeters()`（API 26+） |
| 5 | `SACC` | 米/秒（1 位小数） | 速度精度 | `getSpeedAccuracyMetersPerSecond()`（API 26+） |
| 6 | `BACC` | 度（1 位小数） | 航向精度 | `getBearingAccuracyDegrees()`（API 26+） |
| 7 | `AGE` | 整数毫秒 | 本次解算的年龄（发帧时刻算起） | 见 §3.4 |
| 8 | `SRC` | 枚举 | `GPS` / `NET` / `NONE` | `LocationProvider` 按 provider 判定 |

**格式规则**

- 键值对形式，逗号分隔，顺序固定如上。
- **值为 NaN 或 API 不可用 → 省略整个键**（不写 `ACC=`，更不写 `ACC=NaN`）。接收端解析不到即视为「未知」，**不填默认值**。
- `SRC=NONE` 表示发送端当前**没有任何**解算。
- 校验和为标准 XOR，与其它语句一致（复用 `NmeaChecksum`）。

**为什么 HDOP 不动**：`$GPSSHARE` 是新增的，但 `$GPGGA` 的 HDOP 字段**保持现有行为不变**（`accuracy / 5`，夹在 [0.5, 9.9]）。这样外部串口工具读到的 GGA 与 v1.6.2 完全一致，不会因为协议升级而改变行为；接收端则**优先采用 `$GPSSHARE` 的 `ACC`**，仅在**整帧都没有 `$GPSSHARE`**（对 v1.6.2 发送端）时才退回 `HDOP × 5`。v2 发送端带了 `$GPSSHARE` 却省略 `ACC` 键时，那是在明确说「精度未知」，此时**保持未知**，不再退回 `HDOP × 5`（详见 §5.3）。

### 3.2 帧结构

```
有新解算：  $GPSSHARE  $GPGGA  $GPRMC  $GPGSA  $GPGSV(0..n)  $GPVTG
无新解算：  $GPSSHARE  $GPZDA
```

- **`$GPSSHARE` 永远在帧首**：元数据先于位置到达，接收端解析到位置时元数据已在手，不需要等整帧。
- 两种帧各为**一次 write** 发出。
- 保活帧**无条件每秒一发**。早期设想是「没有新解算时才发」，但事件驱动下「这一秒有没有新解算」是竞态（解算正好在定时器边界到达），无条件发既省掉竞态，又给接收端一条连续的心跳。保活帧的 `$GPSSHARE` 携带**上一次解算**的 `SEQ`（不变）、`AGE`（递增）、`SRC`（沿用），`$GPZDA` 是 UTC 时刻帧，与 v1.6.2 的保活行为一致。

### 3.3 `SEQ` 语义

- 发送端：**每次真正的新解算 +1**，从 1 开始。同一解算不重复发（见 §5.2），因此 `SEQ` 严格对应「一个新的位置」。
- 接收端：**`SEQ` 与上次不同 = 新解算**（不是「更大」）。判定用「不同」而非「更大」，是为了让发送端重启后 `SEQ` 归 1 也能被接受 —— 发送端重启必然断链，接收端重连时会重置 `SEQ` 记忆，两条路径互为兜底。
- **无 `SEQ` 的情况**（对 v1.6.2 发送端）：身份退化为 `(纬度, 经度, 解算时刻)`。身份**不同**即视为新位置。

### 3.4 解算年龄的计算

```java
// NmeaInput 新增两个原始量
long fixTimeMillis;            // location.getTime()          —— 真实解算时刻
long fixElapsedRealtimeNanos;  // location.getElapsedRealtimeNanos() —— 与墙上时钟无关

// 年龄 = 现在 − 解算时刻，用 uptime 算
ageMillis(nowElapsedNanos) = (nowElapsedNanos − fixElapsedRealtimeNanos) / 1_000_000
```

- 用 `elapsedRealtimeNanos` 而非墙上时钟：**不受用户改时间、时区切换、NTP 校时影响**。
- 这与 Android 平台自己的新鲜度判断是同一把尺子：`Location.isFresh()` 用的正是 `elapsedRealtimeNanos`。
- `fixElapsedRealtimeNanos` 不可用时退回 `nowWallMillis − fixTimeMillis`。
- 年龄在**发帧时刻**计算（而非解算到达时刻），因此包含排队时间。

### 3.5 标准语句的语义变更

**唯一一处变更：GGA / RMC 的时间字段从「发送时刻」改为「真实解算时刻」**（`location.getTime()`）。

v1.6.2 在 `TransmitEngine.tick` 里把 `utcMillis` 覆盖为 `System.currentTimeMillis()`，导致第三方工具读到的是「我发出的时间」而不是「这个定位是什么时候测的」。

**GGA 的使用卫星数保持现状**（`Math.max(1, used)`）。经复核，这不是谎报而是必要的防御：`GnssStatus` 的 `usedInFix` 记账在解算边界上会短暂为 0，此时报 0 会让部分第三方工具直接忽略整帧 GGA。位置本身的有效性由 GGA 的质量位表达，与卫星计数无关，故不动。

> 影响：v1.6.2 的接收端拿到 2.0.0 发送端的数据时，位置会「旧」约 1 秒（因为时间是解算时刻），但时间戳本身是**正确**的，不会导致定位错误。

---

## 4. 发送端设计

### 4.1 事件驱动发送（替换定频 tick）

```
GPS 出解 → onFix → publish(解算) → 投递发送任务 → 编码成帧 → 链路写出
```

- `publish` 在主线程被调用（`LocationListener` 注册在 `Looper.getMainLooper()`），**绝不能在主线程做 socket 写**。
- 沿用现有 `ScheduledExecutorService`，改造为**单线程发送执行器**；`publish` 只投递任务。
- **连续解算不丢**：发送任务内部是 `while` 循环，取走所有待发解算（`pending` 被新解算覆盖之前先取走），而不是「只保留最后一个」。
- **保活定时器**独立存在，每 1 秒检查一次是否有新解算；没有则发保活帧。有新解算则不发保活帧（位置帧本身带 `AGE`，已经表达了新鲜度）。
- 链路写失败的处理沿用 v1.6.2：蓝牙服务端模式清理死连接、客户端模式 5 秒后重建链路。

### 4.2 同一解算不重复发

判据：`location.getTime()` 与上次发出的解算时刻相同 → 跳过。部分 ROM 会重复投递同一次解算，不过滤会白白发帧并让 `SEQ` 失真。

### 4.3 元数据采集

`LocationProvider.toInput()` 扩展为：

| 字段 | 来源 | 缺失处理 |
|---|---|---|
| `latitude` / `longitude` | `getLatitude()` / `getLongitude()` | 原有 |
| `altitudeM` | `getAltitude()` | 原有 |
| `speedMps` / `bearingDeg` | `getSpeed()` / `getBearing()` | 原有 |
| `accuracyM` | `getAccuracy()` | NaN → 省略 `ACC` |
| `verticalAccuracyM` | `getVerticalAccuracyMeters()` | API < 26 → 省略 `VACC` |
| `speedAccuracyMps` | `getSpeedAccuracyMetersPerSecond()` | API < 26 → 省略 `SACC` |
| `bearingAccuracyDeg` | `getBearingAccuracyDegrees()` | API < 26 → 省略 `BACC` |
| `fixTimeMillis` | `getTime()` | ≤ 0 → 退回 `System.currentTimeMillis()` |
| `fixElapsedRealtimeNanos` | `getElapsedRealtimeNanos()` | ≤ 0 → 年龄退回墙上时钟算法 |
| `source` | provider 是否为 `GPS_PROVIDER` | `GPS` / `NET` |

### 4.4 设置页「发送频率」改名

功能保留：它实际控制的是 `requestLocationUpdates` 的 `minTime`（`GpsOutputService.java:413`），即**向硬件请求定位的频率**。改名「定位请求频率」，旁边显示「实际收到定位：X 次/秒」，让用户看到 GPS 芯片的真实出解频率（绝大多数手机是 1 Hz），不留误解。

---

## 5. 接收端设计

### 5.1 解析器改为帧累加器

| 句子 | 到达后 |
|---|---|
| `$GPSSHARE` | 读入本帧元数据（`SEQ`/`ACC`/`VACC`/`SACC`/`BACC`/`AGE`/`SRC`） |
| `$GPGGA` | 读入位置、高度、有效性；**清空本帧卫星累加器** |
| `$GPRMC` | 读入速度、航向；**位置字段已齐 → 发布位置快照** |
| `$GPGSA` | 读入「哪几颗星用于定位」 |
| `$GPGSV` | 读入卫星明细；**发布卫星快照**（不触发注入） |

- 位置发布点从 **GSV 改为 RMC**：GGA 只有经纬度和高度，速度/航向在 RMC 里；早一个句子发布会拿到上一周期的旧速度。副作用是**关掉 GSV 语句不再慢 1 秒**。
- **发布点的兜底规则**：本帧收到 RMC → 在 RMC 发布；**本帧没有 RMC（RMC 语句被用户关闭）→ 位置拖到下一帧开头补发**。拖后发而不是在 GGA 发，是为了避免给出一份「速度/航向是上一周期、其余是本周期的」混合数据 —— 混合数据比晚 1 秒的完整数据更糟。这条兜底是必须的：若只绑 RMC，用户关掉 RMC 会导致位置永远不发布。
- **补发位置的年龄要把「等了多久」加进去**（v2 发送端）。位置在解析器里多待的那段时间（约 1 秒），它比 `$GPSSHARE` 里写的 `AGE` 更老；不补上的话，注入给系统的时间戳会比真实解算时刻**新**整整一个等待周期 —— 位置明明旧了一秒，却报成「刚刚解算」，导航 App 会以为这个点很新。解析器因此需要一个可注入的时钟（`NmeaParser.Clock`），等待时间才能被单测精确控制。
- **删除** GGA 处的 leftover 发布（v1.6.2 在 GGA 到达时会把上一周期的旧位置发一次）。删除后每周期只注入一次。
- 卫星快照只在 GSV 到达时发布；GSV 被关闭时，卫星视图保留最后一次收到的内容（v1.6.2 此时会发一个空卫星的占位快照，新行为更合理）。

### 5.2 时间重锚（两个时钟都减年龄）

```java
long age     = snapshot.ageMillis;
long nowWall = System.currentTimeMillis();
long nowUp   = SystemClock.elapsedRealtimeNanos();

location.setTime(nowWall - age);
location.setElapsedRealtimeNanos(nowUp - age * 1_000_000L);
```

**两个时钟都必须减，缺一不可**：Android 的 `Location` 同时带墙上时钟（`getTime()`）和 uptime（`getElapsedRealtimeNanos()`）。

- 只设 uptime → App 用 `System.currentTimeMillis() − getTime()` 算出的年龄是 0（假的「刚刚更新」）
- 只设墙上时钟 → 平台的 `Location.isFresh()` 判断失真
- 两个一起减 → 平台算出的年龄、App 算出的年龄、发送端发来的 `AGE` **三处完全一致**

### 5.3 注入判据

判据逻辑抽到新增的纯逻辑类 `rx/InjectionPolicy.java`（可单测），`MockLocationInjector` 降为 Android 胶水。

```
1. 身份判定：有 SEQ 用 SEQ；无 SEQ 用 (纬度, 经度, 解算时刻)
2. 身份与上次相同        → 不注入（同一解算重复到达）
3. valid == false        → 不注入，状态标「无定位」
4. AGE > MAX_AGE_MS(5000)→ 不注入，状态标「定位已过期 X 秒」
5. 越界校验：纬度∈[-90,90]、经度∈[-180,180]、精度∈[0.1,100000]、年龄∈[0,60000]
                          → 丢弃 + 记日志
6. 通过                  → 注入
```

**缺 `$GPSSHARE` 时（对 v1.6.2 发送端）**：`AGE` 视为 0，注入时间 = 本机当前时刻；精度退回 `HDOP × 5`。即完全退回 v1.6.2 的行为，不崩、不卡、拿不到改进。

**有 `$GPSSHARE` 但缺 `ACC` 键时（v2 发送端明说「精度未知」）**：精度**保持未知**，不再退回 `HDOP × 5`。HDOP 是几何精度因子，除以 5 只是历史上的换算（上限 49.5 m），跟真实精度不是一回事 —— 拿它编一个出来是在谎报。区分这两种情形靠「本连接里是否出现过 `$GPSSHARE`」（`NmeaParser.senderSendsShare`）：说过 v2 协议就要按 v2 的表态来，缺键就是「不知道」。

**不加精度劣化保护**（D7）：发送端降级到网络定位（精度 300 m）时，接收端照样注入，精度如实写 300 m，由导航 App 自行降级处理。

### 5.4 注入内容

```java
location.setLatitude / setLongitude / setAltitude / setSpeed / setBearing   // 原有
if (有 ACC)  location.setAccuracy(ACC);
if (API>=26) {
    if (有 VACC) location.setVerticalAccuracyMeters(VACC);
    if (有 SACC) location.setSpeedAccuracyMetersPerSecond(SACC);
    if (有 BACC) location.setBearingAccuracyDegrees(BACC);
}
location.setTime(nowWall - age);
location.setElapsedRealtimeNanos(nowUp - age * 1_000_000L);
```

**不再写死** `vertical=5f / speed=1f / bearing=5f`（v1.6.2 的 `MockLocationInjector.java:135-139`）：发送端有就传真值，没有就不设。

### 5.5 失败与过期

- `setTestProviderLocation` 抛异常 → **连续 3 次** → 状态栏与常驻通知明确报「定位注入失败」（v1.6.2 只写一行日志）。任一次注入成功后计数清零。
- 断链或长时间无新解算 → 接收端界面显示「定位已过期 X 秒」。系统里保留最后一个位置且时间戳自然变旧，App 自己会识别为陈旧。

---

## 6. 界面变化

**发送端首页**新增/修改：

| 元素 | 内容 |
|---|---|
| 数据来源 | `GPS卫星定位` / `网络定位（WiFi·基站）`（兜底时显示后者并标黄） |
| 定位延迟 | 本次解算年龄，如 `0.84 秒` |
| 定位精度 | **发送前**的真实精度（现有卡片显示的正是 `getAccuracy()`，但发出去会被截断） |

**设置页**：「发送频率 1–10 Hz」→ 「定位请求频率」+ 「实际收到定位：X 次/秒」。

**接收端首页**新增：数据来源、定位延迟、过期提示条；「定位精度」显示真实值而非截断值。

---

## 7. 测试策略

### 7.1 必要的拆分

`MockLocationInjector` 直接依赖 `LocationManager`，单元测试里 `new Location()` 是空壳、断言不了任何值。故拆为两层：

```
rx/InjectionPolicy.java      ← 纯逻辑，可单测：该不该注入 / 时间怎么重锚 / 精度怎么填
rx/MockLocationInjector.java ← Android 胶水：构造 Location、setTestProviderLocation、失败上报
```

### 7.2 用例

| 测试类 | 断言 |
|---|---|
| `NmeaGeneratorTest` | `$GPSSHARE` 字段顺序与格式；`ACC=12.5` 原样输出；NaN → 省略该键；帧内 `$GPSSHARE` 在最前 |
| `NmeaGeneratorTest` | GGA/RMC 时间 = 真实解算时刻（不再是发送时刻） |
| `NmeaParserTest` | 位置在 **RMC** 处发布且**只发布一次**；卫星在 GSV 处发布；旧 leftover 发布已删除 |
| `NmeaParserTest` | 缺 `$GPSSHARE` → 退回旧行为（精度 = HDOP×5），不崩 |
| `NmeaParserTest` | `SEQ` 解析；无 `SEQ` 时用 (纬度, 经度, 解算时刻) 作身份 |
| `InjectionPolicyTest` | `time = nowWall − age` 且 `elapsed = nowUp − age`（**两个时钟都减**） |
| `InjectionPolicyTest` | 同一身份不重复注入；`AGE > 5000` 不注入；纬度/经度/精度/年龄越界一律拒绝 |
| `InjectionPolicyTest` | 精度 12.5 m → `setAccuracy(12.5f)`（**不再截成 49.5**）；垂直/速度/航向精度有则传、无则不设 |
| `TransmitEngineTest` | `publish` 后产生一次 write；同一 `fixTime` 不重复发；无解算时只发保活帧且 `AGE` 递增 |
| 端到端 | 生成器 → 解析器 → 注入参数，精度/年龄/来源/`SEQ` 全链路无损 |

**现有 119 个用例必须全过**；其中 `NmeaParserTest` / `NmeaGeneratorTest` / `TransmitEngineTest` 因语义变更需要改造。

---

## 8. 范围边界（本次明确不做）

- 不做接收端滤波（D2）
- 不做多源融合：接收端自己的 GPS 被测试提供者覆盖，无从融合
- 不做卫星星图注入：Android 平台限制（测试提供者不支持 `GnssStatus`，导航 App 里卫星数显示 0）
- `applicationId` 保持 `com.gpsshare`，可原地升级。
- 不产出配套 PC 端工具

---

## 9. 兼容性

| 组合 | 结果 |
|---|---|
| 2.0.0 发送 → 1.6.2 接收 | 旧接收端忽略 `$GPSSHARE`，照旧按 HDOP×5 解析，位置正常，精度仍截断 |
| 1.6.2 发送 → 2.0.0 接收 | 缺 `$GPSSHARE` → 退回旧行为，位置正常，精度仍截断、延迟显示 0 |
| 2.0.0 ↔ 2.0.0 | 完整生效 |

**混用不会崩，只是拿不到改进**，允许「先发新版、慢慢升」。

> 注意：2.0.0 的接收端拿到 1.6.2 发送端的数据时，没有 `SEQ`，身份退化为 (纬度, 经度, 解算时刻)。而 1.6.2 的发送端会在同一周期发两次位置帧（GGA 的 leftover + GSV 的当前），因此 2.0.0 的接收端对 1.6.2 的数据会注入两次 —— 与 v1.6.2 接收端的行为一致，不算退步。

**（实施后修正）** 上面这句关于「注入两次」的判断在实施中被推翻：2.0.0 的解析器把位置发布点改到了 RMC，并且**删掉了 GGA 处的 leftover 发布**，GSV 处的卫星快照不再触发注入。因此 2.0.0 的接收端对 1.6.2 的数据**每周期只注入一次**（当前周期的新位置），比 1.6.2 接收端少注入一次「上一周期的旧位置」—— 这正是 §5.1「删除 GGA 处的 leftover 发布」带来的稳定性改善，不是退步。

---

## 10. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **无真机验收** | 本机 `adb` 不可用，所有「延迟多少毫秒」的结论都是代码推算 | 把**可观测的延迟指标做进界面**（定位延迟卡），上机即可一眼验证，不依赖推算 |
| 协议不兼容 | `$GPSSHARE` 需两端同版本 | 混用不崩（§9），且应用内提示版本要求 |
| 时钟不同步 | 两机墙上时钟有差 | `AGE` 是时长，天然免疫（D3） |
| 事件驱动的并发 | 发送任务可能被连续投递 | 单线程执行器 + 内部 `while` 取走全部待发解算，不丢帧 |
| `elapsedRealtimeNanos` 缺失 | 部分 ROM 的网络定位不给 | 退回墙上时钟算法，并在日志标注 |

---

## 11. 文件改动清单

### 新增

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/gpsbt/transfer/rx/InjectionPolicy.java` | 纯逻辑：注入判据、时间重锚、精度填充 |
| `app/src/test/java/com/gpsbt/transfer/rx/InjectionPolicyTest.java` | 对应单测 |

### 修改

| 文件 | 改动 |
|---|---|
| `nmea/NmeaInput.java` | 新增 `fixTimeMillis` / `fixElapsedRealtimeNanos` / `verticalAccuracyM` / `speedAccuracyMps` / `bearingAccuracyDeg` / `source` / `seq`，新增 `ageMillis(nowElapsedNanos)` |
| `nmea/NmeaGenerator.java` | 新增 `gpsShare()`；`gga`/`rmc` 用真实解算时刻 |
| `nmea/NmeaSentenceType.java` | 新增 `GPSSHARE`（**不可关闭**，始终发送） |
| `engine/LocationProvider.java` | `toInput` 采集真实精度/来源/解算时刻/elapsedRealtime |
| `engine/TransmitEngine.java` | `publish` 改为请求发送；帧首拼 `$GPSSHARE` |
| `service/GpsOutputService.java` | 事件驱动发送执行器；保活定时器；`SEQ` 自增；同一解算去重 |
| `rx/NmeaParser.java` | 解析 `$GPSSHARE`；发布点改 RMC/GSV；删除 leftover |
| `rx/MockLocationInjector.java` | 降为胶水；调用 `InjectionPolicy`；失败上报 |
| `service/ReceiveService.java` | 接收端状态：来源/延迟/过期；注入失败上报 |
| `ui/fragment/HomeFragment.java` / `ui/fragment/SettingsFragment.java` / `ui/view/StatsGridBuilder.java` | 界面卡片 |
| `res/values/strings.xml` | 新文案 |
| `util/Prefs.java` | 频率项语义与文案 |
| `app/src/test/.../NmeaGeneratorTest.java`、`NmeaParserTest.java`、`TransmitEngineTest.java` | 因语义变更改造 |

---

## 12. 工程约定

- **仓库**：本设计的所有改动只在 `GPS共享2` 进行。`GPS共享` 与 `GPS蓝牙传输` 全程只读。
- **分支**：`v2-fidelity`（基线 `731d196`）。
- **`master` 分支**：在新仓库里指向 v1.6.2 基线，作为「新版本从哪来」的锚点。
- **远端 `baseline`**：指向 `工作区根目录\GPS共享`，仅用于 `git diff baseline/p3-receiver` 对比两版差异，**不推送**。
- **构建**：`versionCode 28` / `versionName 2.0.0`，`applicationId` 保持 `com.gpsshare`（用户可原地升级），沿用同一 `release.keystore`。
- **构建产物**：导到 `构建目录`（绕开中文路径导致单测 ClassNotFoundException 的既有问题），与老仓库的 `构建目录` 隔离。
- **JDK**：Gradle 用 Android Studio 自带的 JBR 21（系统默认 `java` 是 JDK 26，Gradle 8.12 / AGP 8.7 不认），已写入用户级 `~/.gradle/gradle.properties`。
