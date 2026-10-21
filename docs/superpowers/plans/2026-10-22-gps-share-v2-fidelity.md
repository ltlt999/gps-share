# GPS 共享 v2.0.0 定位保真改造 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让接收端注入给系统的定位在真实、准确、高可靠、稳定、快速五方面达标 —— 新增 `$GPSSHARE` 元数据语句、发送端新解即发、接收端按解算年龄本地重锚时间并如实注入精度。

**Architecture:** 协议层加一条私有语句承载标准 NMEA 装不下的米制精度与解算年龄；发送端从定频 tick 改为事件驱动（定位一到立刻发帧）；接收端解析器改为帧累加器，位置在 RMC 处发布，元数据由 `$GPSSHARE` 提供，注入前经纯逻辑类 `InjectionPolicy` 判定，时间按「本机时钟 − 解算年龄」重锚。

**Tech Stack:** Java 17 / Android SDK 34 / minSdk 24 / AGP 8.7.3 / Gradle 8.12 / JUnit 4（纯 JVM 单测，119→约 160 个用例）

**设计依据：** `docs/superpowers/specs/2026-10-22-gps-share-v2-fidelity-design.md`

**工作目录：** `工作区根目录\GPS共享2`（分支 `v2-fidelity`）

---

## 全局约定

### 构建与测试命令

```bash
cd "工作区根目录/GPS共享2"
./gradlew :app:testDebugUnitTest --offline      # 跑全部单测
./gradlew :app:assembleRelease --offline        # 出 release APK
```

JDK 由用户级 `~/.gradle/gradle.properties` 固定为 Android Studio 自带 JBR 21（系统默认 `java` 是 JDK 26，Gradle 8.12 不认）。若报 `Unsupported class file major version 70`，先检查该文件是否还在。

### 不许碰的东西

- `工作区根目录\GPS共享`（v1.6.2 基线库）—— **全程只读**
- `工作区根目录\GPS蓝牙传输`（v1.4.6 原版）—— **全程只读**
- `applicationId` 保持 `com.gpsshare`（保持包名不变以便原地升级）

### 术语约定

| 词 | 含义 |
|---|---|
| 解算 | 一次 GPS 定位解算结果（一个 `Location`） |
| `AGE` | 本次解算的年龄（毫秒）= 发帧时刻 − 解算时刻 |
| 身份 | 判定「是不是同一个解算」的标识：有 `SEQ` 用 `SEQ`，否则用 `(纬度, 经度, 解算时刻)` |

---

## 阶段一：协议与数据模型（纯逻辑，全部可单测）

### Task 1: `NmeaInput` 扩展元信息字段

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/nmea/NmeaInput.java`
- Create: `app/src/test/java/com/gpsbt/transfer/nmea/NmeaInputTest.java`

- [x] **Step 1: 写失败测试**

Create `app/src/test/java/com/gpsbt/transfer/nmea/NmeaInputTest.java`:

```java
package com.gpsbt.transfer.nmea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NmeaInputTest {

    @Test
    public void copyCarriesNewMetadata() {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = 1_700_000_000_000L;
        in.seq = 42L;
        in.ageMillis = 842L;
        in.fixElapsedRealtimeNanos = 5_000_000_000L;
        in.verticalAccuracyM = 8.5f;
        in.speedAccuracyMps = 0.8f;
        in.bearingAccuracyDeg = 15f;
        in.source = NmeaInput.Source.NET;

        NmeaInput copy = in.copy();

        assertEquals(42L, copy.seq);
        assertEquals(842L, copy.ageMillis);
        assertEquals(5_000_000_000L, copy.fixElapsedRealtimeNanos);
        assertEquals(8.5f, copy.verticalAccuracyM, 0.001f);
        assertEquals(0.8f, copy.speedAccuracyMps, 0.001f);
        assertEquals(15f, copy.bearingAccuracyDeg, 0.001f);
        assertEquals(NmeaInput.Source.NET, copy.source);
    }

    @Test
    public void agePrefersElapsedRealtime() {
        NmeaInput in = new NmeaInput();
        in.fixElapsedRealtimeNanos = 10_000_000_000L;
        in.utcMillis = 1_700_000_000_000L;
        // uptime 差 842 ms → 年龄 842；墙上时钟会给出完全不同的值
        assertEquals(842L, in.computeAgeMillis(10_842_000_000L, 9_999_999_999L));
    }

    @Test
    public void ageFallsBackToWallClock() {
        NmeaInput in = new NmeaInput();
        in.fixElapsedRealtimeNanos = 0L;
        in.utcMillis = 1_700_000_000_000L;
        assertEquals(1500L, in.computeAgeMillis(0L, 1_700_000_001_500L));
    }

    @Test
    public void ageIsZeroWhenNothingKnown() {
        NmeaInput in = new NmeaInput();
        assertEquals(0L, in.computeAgeMillis(0L, 0L));
    }

    @Test
    public void ageNeverNegative() {
        NmeaInput in = new NmeaInput();
        in.utcMillis = 1_700_000_000_000L;
        assertTrue(in.computeAgeMillis(0L, 1_699_999_000_000L) >= 0L);
    }

    @Test
    public void sourceWireRoundTrip() {
        assertEquals("GPS", NmeaInput.Source.GPS.wire());
        assertEquals("NET", NmeaInput.Source.NET.wire());
        assertEquals("NONE", NmeaInput.Source.NONE.wire());
        assertEquals(NmeaInput.Source.GPS, NmeaInput.Source.fromWire("gps"));
        assertEquals(NmeaInput.Source.NET, NmeaInput.Source.fromWire("NET"));
        assertEquals(NmeaInput.Source.NONE, NmeaInput.Source.fromWire("??"));
        assertEquals(NmeaInput.Source.NONE, NmeaInput.Source.fromWire(null));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*NmeaInputTest"`
Expected: FAIL —— 编译错误「找不到符号: 方法 copy 之外的字段 seq / ageMillis / computeAgeMillis / Source」

- [x] **Step 3: 实现**

Modify `app/src/main/java/com/gpsbt/transfer/nmea/NmeaInput.java` —— 在 `accuracyM` 字段之后插入：

```java
    /** 数据来源：卫星定位 / 网络定位 / 无。 */
    public enum Source {
        NONE, GPS, NET;

        public String wire() {
            switch (this) {
                case GPS:
                    return "GPS";
                case NET:
                    return "NET";
                default:
                    return "NONE";
            }
        }

        public static Source fromWire(String value) {
            if (value == null) {
                return NONE;
            }
            String normalized = value.trim().toUpperCase(Locale.US);
            if ("GPS".equals(normalized)) {
                return GPS;
            }
            if ("NET".equals(normalized)) {
                return NET;
            }
            return NONE;
        }
    }

    /** 数据来源。发送端按 provider 判定，接收端从 $GPSSHARE 解析。 */
    public Source source = Source.NONE;
    /** 解算序号：发送端每次真正的新解算 +1。接收端用它判定「是不是同一个解算」。 */
    public long seq;
    /** 本次解算的年龄（毫秒）。发送端在发帧时刻算出，接收端从 $GPSSHARE 解析得到。 */
    public long ageMillis;
    /** 解算时刻对应的 uptime（纳秒）。发送端填；接收端不用。 */
    public long fixElapsedRealtimeNanos;
    /** 垂直精度（米）。NaN = 未知。 */
    public float verticalAccuracyM = Float.NaN;
    /** 速度精度（米/秒）。NaN = 未知。 */
    public float speedAccuracyMps = Float.NaN;
    /** 航向精度（度）。NaN = 未知。 */
    public float bearingAccuracyDeg = Float.NaN;
    /** 这份快照是否携带卫星明细。GSV 发布点为 true，位置发布点为 false。 */
    public transient boolean withSatellites;
```

文件头补 import：

```java
import java.util.Locale;
```

在 `copy()` 方法末尾（`return copy;` 之前）补：

```java
        copy.source = source;
        copy.seq = seq;
        copy.ageMillis = ageMillis;
        copy.fixElapsedRealtimeNanos = fixElapsedRealtimeNanos;
        copy.verticalAccuracyM = verticalAccuracyM;
        copy.speedAccuracyMps = speedAccuracyMps;
        copy.bearingAccuracyDeg = bearingAccuracyDeg;
        copy.withSatellites = withSatellites;
```

在类末尾（`copy()` 之后）加方法：

```java
    /**
     * 算出本次解算的年龄（毫秒）。
     *
     * 优先用 uptime：elapsedRealtimeNanos 不受用户改时间、时区切换、NTP 校时
     * 影响，而且与 Android 平台判断定位新鲜度（{@code Location.isFresh()}）
     * 用的是同一把尺子。拿不到时退回墙上时钟。
     */
    public long computeAgeMillis(long nowElapsedRealtimeNanos, long nowWallMillis) {
        if (fixElapsedRealtimeNanos > 0 && nowElapsedRealtimeNanos > fixElapsedRealtimeNanos) {
            return (nowElapsedRealtimeNanos - fixElapsedRealtimeNanos) / 1_000_000L;
        }
        if (utcMillis > 0) {
            return Math.max(0L, nowWallMillis - utcMillis);
        }
        return 0L;
    }
```

- [x] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*NmeaInputTest"`
Expected: PASS，6 个用例全过

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/nmea/NmeaInput.java app/src/test/java/com/gpsbt/transfer/nmea/NmeaInputTest.java
git commit -m "feat(NmeaInput): 扩展元信息字段（来源/序号/年龄/垂直·速度·航向精度）"
```

---

### Task 2: `NmeaGenerator` 生成 `$GPSSHARE` 与保活帧

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/nmea/NmeaGenerator.java`
- Modify: `app/src/test/java/com/gpsbt/transfer/nmea/NmeaGeneratorTest.java`

- [x] **Step 1: 写失败测试**

在 `app/src/test/java/com/gpsbt/transfer/nmea/NmeaGeneratorTest.java` 的类末尾追加：

```java
    @Test
    public void gpsShareCarriesMetadata() {
        NmeaInput in = sample();
        in.accuracyM = 12.5f;
        in.verticalAccuracyM = 8.0f;
        in.speedAccuracyMps = 0.8f;
        in.bearingAccuracyDeg = 15.0f;
        in.source = NmeaInput.Source.GPS;
        in.seq = 1234L;

        String line = generator.gpsShare(in, 1234L, 842L);

        assertTrue(line.startsWith("$GPSSHARE,1,SEQ=1234"));
        assertTrue(line.contains("ACC=12.5"));
        assertTrue(line.contains("VACC=8.0"));
        assertTrue(line.contains("SACC=0.8"));
        assertTrue(line.contains("BACC=15.0"));
        assertTrue(line.contains("AGE=842"));
        assertTrue(line.contains("SRC=GPS"));
        assertTrue(line.endsWith("*"));
    }

    @Test
    public void gpsShareOmitsUnknownAccuracy() {
        NmeaInput in = sample();
        in.accuracyM = Float.NaN;
        in.verticalAccuracyM = Float.NaN;
        in.speedAccuracyMps = Float.NaN;
        in.bearingAccuracyDeg = Float.NaN;
        in.source = NmeaInput.Source.NET;

        String line = generator.gpsShare(in, 7L, 100L);

        assertFalse(line.contains("ACC="));
        assertFalse(line.contains("VACC="));
        assertFalse(line.contains("SACC="));
        assertFalse(line.contains("BACC="));
        assertTrue(line.contains("SRC=NET"));
    }

    @Test
    public void gpsShareHasValidChecksum() {
        NmeaInput in = sample();
        String line = generator.gpsShare(in, 1L, 0L);
        String body = line.substring(1, line.indexOf('*'));
        String given = line.substring(line.indexOf('*') + 1).trim();
        assertEquals(NmeaChecksum.toHex(NmeaChecksum.xor(body)), given);
    }

    @Test
    public void buildFrameStartsWithGpsShare() {
        NmeaInput in = sample();
        in.source = NmeaInput.Source.GPS;
        in.seq = 5L;
        Set<NmeaSentenceType> all = EnumSet.allOf(NmeaSentenceType.class);
        String frame = generator.buildFrame(in, all, 300L);
        assertTrue(frame.startsWith("$GPSSHARE,"));
        assertTrue(frame.indexOf("$GPSSHARE") < frame.indexOf("$GPGGA"));
    }

    @Test
    public void keepaliveFrameCarriesLastFixMetadata() {
        NmeaInput last = sample();
        last.accuracyM = 7.0f;
        last.source = NmeaInput.Source.GPS;
        last.seq = 9L;

        String frame = generator.keepaliveFrame(last, 5000L, 1_700_000_000_000L);

        assertTrue(frame.startsWith("$GPSSHARE,"));
        assertTrue(frame.contains("SEQ=9"));
        assertTrue(frame.contains("AGE=5000"));
        assertTrue(frame.contains("$GPZDA"));
    }
```

同时在该测试文件顶部补 import（若尚未有）：

```java
import static org.junit.Assert.assertFalse;
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*NmeaGeneratorTest"`
Expected: FAIL —— 编译错误「找不到符号: 方法 gpsShare / keepaliveFrame，buildFrame 参数不匹配」

- [x] **Step 3: 实现**

Modify `app/src/main/java/com/gpsbt/transfer/nmea/NmeaGenerator.java`：

类顶部加常量：

```java
    /** $GPSSHARE 的协议版本。 */
    public static final int PROTOCOL_VERSION = 1;
```

新增两个方法（放在 `zda` 方法之后）：

```java
    /**
     * $GPSSHARE：承载标准 NMEA 装不下的元信息 —— 米制精度、解算年龄、来源。
     *
     * 帧首发送，接收端解析到位置时元数据已在手。精度值不可用（NaN）时
     * **省略整个键**，不写 0 也不写默认值 —— 「未知」和「0 米」是两回事。
     */
    public String gpsShare(NmeaInput in, long seq, long ageMillis) {
        NmeaInput source = in == null ? new NmeaInput() : in;
        StringBuilder body = new StringBuilder();
        body.append(PROTOCOL_VERSION).append(",SEQ=").append(Math.max(0L, seq));
        appendAccuracy(body, "ACC", source.accuracyM);
        appendAccuracy(body, "VACC", source.verticalAccuracyM);
        appendAccuracy(body, "SACC", source.speedAccuracyMps);
        appendAccuracy(body, "BACC", source.bearingAccuracyDeg);
        body.append(",AGE=").append(Math.max(0L, ageMillis));
        body.append(",SRC=").append(source.source.wire());
        return frame("GPS", "SHARE", Collections.singletonList(body.toString()));
    }

    /** 无新解算时的保活帧：$GPSSHARE（带上一次解算的元数据）+ $GPZDA。 */
    public String keepaliveFrame(NmeaInput last, long ageMillis, long nowMillis) {
        NmeaInput source = last == null ? new NmeaInput() : last;
        StringBuilder sb = new StringBuilder();
        sb.append(gpsShare(source, source.seq, ageMillis));
        sb.append(zda(nowMillis));
        return sb.toString();
    }

    private static void appendAccuracy(StringBuilder body, String key, float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return;
        }
        body.append(',').append(key).append('=')
                .append(String.format(Locale.US, "%.1f", value));
    }
```

把 `buildFrame` 改成带 `ageMillis` 参数，并把 `$GPSSHARE` 拼在帧首：

```java
    /** 按固定顺序拼装一帧（各语句均带 *CS 与 \r\n）。$GPSSHARE 恒在帧首。 */
    public String buildFrame(NmeaInput in, Set<NmeaSentenceType> enabled, long ageMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append(gpsShare(in, in.seq, ageMillis));
        if (enabled.contains(NmeaSentenceType.GGA)) {
            sb.append(gga(in));
        }
        if (enabled.contains(NmeaSentenceType.RMC)) {
            sb.append(rmc(in));
        }
        if (enabled.contains(NmeaSentenceType.GSA)) {
            sb.append(gsa(in));
        }
        if (enabled.contains(NmeaSentenceType.GSV)) {
            for (String sentence : gsv(in)) {
                sb.append(sentence);
            }
        }
        if (enabled.contains(NmeaSentenceType.VTG)) {
            sb.append(vtg(in));
        }
        return sb.toString();
    }
```

- [x] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*NmeaGeneratorTest"`
Expected: PASS

注意：`buildFrame` 签名变了，全项目还有旧调用点，**这一步先只让测试编译过**。旧调用点在 Task 5 修；若编译报其它模块的错误，先把旧调用点改成 `buildFrame(in, enabled, 0L)` 临时兜住，Task 5 再统一改。

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/nmea/NmeaGenerator.java app/src/test/java/com/gpsbt/transfer/nmea/NmeaGeneratorTest.java
git commit -m "feat(NmeaGenerator): 新增 \$GPSSHARE 元数据语句与保活帧"
```

---

### Task 3: `NmeaParser` 解析 `$GPSSHARE` 并改造发布时机

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/rx/NmeaParser.java`
- Modify: `app/src/test/java/com/gpsbt/transfer/rx/NmeaParserTest.java`

**发布时机的新规则（务必理解清楚再动手）：**

- 位置在 **RMC** 处发布：RMC 带着本周期的速度与航向，而 GGA（经纬度、高度）就在它前一句，此刻发布 = 位置、速度、航向、高度**全是本周期的新值**。
- 若某一帧**没有 RMC**（用户关了 RMC 语句），位置在**下一帧开头**（`$GPSSHARE` 或 `GGA` 到达时）补发 —— 宁可晚 1 秒，也要给完整的一份数据。
- 卫星在 **GSV** 处发布（与 v1.6.2 一致）。
- **删除** v1.6.2 在 GGA 处的 leftover 发布（它会把上一周期的旧位置再发一次）。

- [x] **Step 1: 写失败测试**

在 `app/src/test/java/com/gpsbt/transfer/rx/NmeaParserTest.java` 类末尾追加：

```java
    private static String share(NmeaInput in, long seq, long ageMillis, NmeaInput.Source src) {
        in.seq = seq;
        in.source = src;
        return new NmeaGenerator(1.0f).gpsShare(in, seq, ageMillis);
    }

    @Test
    public void parsesGpsShareMetadata() {
        NmeaInput in = sample();
        in.accuracyM = 12.5f;
        in.verticalAccuracyM = 8.0f;
        in.speedAccuracyMps = 0.8f;
        in.bearingAccuracyDeg = 15.0f;

        parser.accept(share(in, 77L, 842L, NmeaInput.Source.NET));

        NmeaInput snap = parser.accept(generator.rmc(in));
        assertNotNull(snap);
        assertEquals(77L, snap.seq);
        assertEquals(842L, snap.ageMillis);
        assertEquals(NmeaInput.Source.NET, snap.source);
        assertEquals(12.5f, snap.accuracyM, 0.001f);
        assertEquals(8.0f, snap.verticalAccuracyM, 0.001f);
        assertEquals(0.8f, snap.speedAccuracyMps, 0.001f);
        assertEquals(15.0f, snap.bearingAccuracyDeg, 0.001f);
    }

    @Test
    public void gpsShareAccuracyBeatsHdopFallback() {
        NmeaInput in = sample();
        in.accuracyM = 12.5f;
        parser.accept(share(in, 1L, 100L, NmeaInput.Source.GPS));
        parser.accept(generator.gga(in));
        NmeaInput snap = parser.accept(generator.rmc(in));
        assertNotNull(snap);
        // GGA 的 HDOP×5 是 5.0，$GPSSHARE 的 12.5 才是真值
        assertEquals(12.5f, snap.accuracyM, 0.001f);
    }

    @Test
    public void hdopFallbackStillAppliesWithoutShare() {
        NmeaInput in = sample();
        in.accuracyM = 5.0f;
        parser.accept(generator.gga(in));
        NmeaInput snap = parser.accept(generator.rmc(in));
        assertNotNull(snap);
        assertEquals(5.0f, snap.accuracyM, 0.001f);
    }

    @Test
    public void positionPublishedOnRmcExactlyOnce() {
        NmeaInput in = sample();
        Set<NmeaSentenceType> all = EnumSet.allOf(NmeaSentenceType.class);
        String frame = new NmeaGenerator(1.0f).buildFrame(in, all, 100L);

        int publishes = 0;
        NmeaInput last = null;
        for (String line : frame.split("\r\n")) {
            NmeaInput snap = parser.accept(line);
            if (snap != null) {
                publishes++;
                last = snap;
            }
        }
        // 一帧两次发布：RMC（位置）+ GSV（卫星明细）
        assertEquals(2, publishes);
        assertNotNull(last);
        assertTrue(last.withSatellites);
    }

    @Test
    public void ggaDoesNotPublishLeftoverAnyMore() {
        NmeaInput in = sample();
        // 先喂一帧 GGA —— v1.6.2 在这里会发布上一周期的位置，现在不应发布
        assertNull(parser.accept(generator.gga(in)));
    }

    @Test
    public void positionWithoutRmcIsPublishedAtNextFrameStart() {
        NmeaInput in = sample();
        // 只有 GGA，没有 RMC
        assertNull(parser.accept(generator.gga(in)));
        // 下一帧开头（$GPSSHARE）到达时补发
        NmeaInput snap = parser.accept(share(in, 2L, 50L, NmeaInput.Source.GPS));
        assertNotNull(snap);
        assertEquals(in.latitude, snap.latitude, 1e-4);
    }
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*NmeaParserTest"`
Expected: FAIL —— `seq` / `ageMillis` / `source` 字段不存在（Task 1 已加，但 `withSatellites` 与新发布点未实现）

- [x] **Step 3: 实现**

Modify `app/src/main/java/com/gpsbt/transfer/rx/NmeaParser.java`。

新增实例字段：

```java
    /** 本帧的位置是否已具备、但还没发布（等 RMC 补齐速度与航向）。 */
    private boolean positionPending;
    /**
     * $GPSSHARE 带来的真实水平精度。NaN = 本帧没有（或发送端是旧版，没有该语句）。
     *
     * 单独存一份、不直接写进 {@code state.accuracyM}：GGA 的 HDOP×5 兜底会写
     * state.accuracyM，两者互相覆盖就会把真实精度冲掉。发布时再择优。
     */
    private float metaAccuracyM = Float.NaN;
    /** 自上一个 GGA 以来是否见过 $GPSSHARE。用来判断该不该重置元数据。 */
    private boolean seenShareInFrame;
```

`reset()` 里补三行：

```java
        positionPending = false;
        metaAccuracyM = Float.NaN;
        seenShareInFrame = false;
```

`accept()` 改为：

```java
    public NmeaInput accept(String line) {
        if (line == null) {
            return null;
        }
        String text = line.trim();
        if (text.isEmpty() || text.charAt(0) != '$') {
            return null;
        }
        String body = verifiedBody(text);
        if (body == null) {
            return null;
        }
        String[] f = body.split(",", -1);
        if (f.length == 0 || f[0].length() < MIN_SENTENCE_FIELD_LENGTH) {
            return null;
        }
        String head = f[0].toUpperCase(Locale.US);
        if ("GPSSHARE".equals(head)) {
            // 帧边界：上一帧若因缺 RMC 而没发出去，此刻补发
            NmeaInput deferred = flushPendingPosition();
            // 新的一帧：元数据重置，再由 applyShare 填上这一帧的
            seenShareInFrame = true;
            metaAccuracyM = Float.NaN;
            applyShare(f);
            return deferred;
        }
        // 形如 GPGGA / BDGSV：末三位是语句类型
        String type = head.substring(head.length() - 3);
        if ("GGA".equals(type)) {
            // 帧边界：同上（对没有 $GPSSHARE 的旧版发送端，GGA 就是帧边界）
            NmeaInput deferred = flushPendingPosition();
            // 本帧见过 $GPSSHARE 就不用清元数据（它就在 GGA 之前，清了会把真值冲掉）
            if (!seenShareInFrame) {
                metaAccuracyM = Float.NaN;
            }
            seenShareInFrame = false;
            entries.clear();
            usedPrns.clear();
            wireBytes = 0L;
            wireBytes += text.length() + 2;
            if (applyGga(f)) {
                hasFix = true;
            }
            // 不在这里发布：RMC 紧跟其后，带着本周期的速度与航向
            positionPending = true;
            return deferred;
        }
        if ("RMC".equals(type)) {
            wireBytes += text.length() + 2;
            if (applyRmc(f)) {
                hasFix = true;
            }
            positionPending = false;
            return publishPosition();
        }
        if ("GSA".equals(type)) {
            wireBytes += text.length() + 2;
            applyGsa(f);
            return null;
        }
        if ("GSV".equals(type)) {
            // 不在这里清 entries：多星座的 GSV 是按 talker 分组的，每组
            // 都有自己的 page 1，若按 page<=1 清会把前一个星座的累积数据全清掉。
            wireBytes += text.length() + 2;
            applyGsv(f);
            // GSV 到了：连同当前累积的卫星列表一起发布。多星座多页会发布多次，
            // 最后一次携带完整卫星列表——把中间值覆盖掉。
            return publishSatellites();
        }
        return null;
    }
```

新增/改写发布方法：

```java
    /** 位置发布点：只带位置字段，卫星明细留给 GSV 发布点。 */
    private NmeaInput publishPosition() {
        if (!hasFix) {
            return null;
        }
        NmeaInput snap = state.copy();
        snap.wireBytes = wireBytes;
        snap.clearSats();
        snap.withSatellites = false;
        applyMetaAccuracy(snap);
        return snap;
    }

    /** 卫星发布点：带完整卫星列表。 */
    private NmeaInput publishSatellites() {
        if (!hasFix) {
            return null;
        }
        NmeaInput snap = state.copy();
        snap.wireBytes = wireBytes;
        snap.clearSats();
        for (SatEntry entry : entries) {
            snap.addSat(new NmeaInput.Sat(entry.prn, entry.elevationDeg, entry.azimuthDeg,
                    entry.snr, entry.used, entry.talker));
        }
        snap.withSatellites = true;
        applyMetaAccuracy(snap);
        return snap;
    }

    /** $GPSSHARE 的真实精度优先于 GGA 的 HDOP×5 兜底。 */
    private void applyMetaAccuracy(NmeaInput snap) {
        if (!Float.isNaN(metaAccuracyM)) {
            snap.accuracyM = metaAccuracyM;
        }
    }

    /** 上一帧没有 RMC 时，位置拖到这一帧开头才发。 */
    private NmeaInput flushPendingPosition() {
        if (!positionPending) {
            return null;
        }
        positionPending = false;
        return publishPosition();
    }
```

`applyGga` 里精度那一行改为（**只写兜底值，不碰 `metaAccuracyM`**）：

```java
        double hdop = parseDouble(f[8], Double.NaN);
        // HDOP×5 只是兜底：它把 >49.5 m 的精度截断。真值由 $GPSSHARE 提供，
        // 发布时择优（见 applyMetaAccuracy）。
        state.accuracyM = Double.isNaN(hdop) ? Float.NaN : (float) (hdop * 5.0d);
```

新增 `applyShare`：

```java
    /**
     * 解析 $GPSSHARE：$GPSSHARE,1,SEQ=..,ACC=..,VACC=..,SACC=..,BACC=..,AGE=..,SRC=..
     *
     * 键值对形式，缺的键就是「未知」——不填默认值。
     */
    private void applyShare(String[] f) {
        for (int i = 2; i < f.length; i++) {
            String field = f[i];
            if (field == null || field.isEmpty()) {
                continue;
            }
            int eq = field.indexOf('=');
            if (eq <= 0 || eq == field.length() - 1) {
                continue;
            }
            String key = field.substring(0, eq).trim().toUpperCase(Locale.US);
            String value = field.substring(eq + 1).trim();
            if ("SEQ".equals(key)) {
                state.seq = (long) parseDouble(value, 0d);
            } else if ("AGE".equals(key)) {
                state.ageMillis = (long) parseDouble(value, 0d);
            } else if ("SRC".equals(key)) {
                state.source = NmeaInput.Source.fromWire(value);
            } else if ("ACC".equals(key)) {
                metaAccuracyM = (float) parseDouble(value, Float.NaN);
            } else if ("VACC".equals(key)) {
                state.verticalAccuracyM = (float) parseDouble(value, Float.NaN);
            } else if ("SACC".equals(key)) {
                state.speedAccuracyMps = (float) parseDouble(value, Float.NaN);
            } else if ("BACC".equals(key)) {
                state.bearingAccuracyDeg = (float) parseDouble(value, Float.NaN);
            }
        }
    }
```

`reset()` 里补 `state.seq = 0L; state.ageMillis = 0L; state.source = NmeaInput.Source.NONE; state.verticalAccuracyM = Float.NaN; state.speedAccuracyMps = Float.NaN; state.bearingAccuracyDeg = Float.NaN;`（`state.accuracyM` 已有）。

- [x] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*NmeaParserTest"`
Expected: PASS

若旧的 `NmeaParserTest` 用例失败（它们断言 GSV 才发布 / GGA 会发 leftover），按新规则改写断言：
- `roundTripThroughGenerator` 里改成「喂完整帧，取最后一次发布」即可
- `gsvFeedsSatelliteListWithTalker` / `gsaMarksUsedSatellites` 里在喂 GSV 前先喂 GGA+RMC，让 `hasFix` 为真

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/rx/NmeaParser.java app/src/test/java/com/gpsbt/transfer/rx/NmeaParserTest.java
git commit -m "feat(NmeaParser): 解析 \$GPSSHARE，位置改在 RMC 处发布，删掉旧位置的重复发布"
```

---

## 阶段二：发送端

### Task 4: `LocationProvider` 采集真实元信息

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/engine/LocationProvider.java`
- Modify: `app/src/test/java/com/gpsbt/transfer/engine/LocationProviderTest.java`

- [x] **Step 1: 写失败测试**

在 `app/src/test/java/com/gpsbt/transfer/engine/LocationProviderTest.java` 类末尾追加（需要 `android.location.Location` 桩；该测试文件已 import）：

```java
    @Test
    public void toInputKeepsRealFixTime() {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(31.2);
        location.setLongitude(121.4);
        location.setTime(1_700_000_000_000L);

        NmeaInput input = LocationProvider.toInput(location, NmeaInput.Source.GPS);

        assertEquals(1_700_000_000_000L, input.utcMillis);
        assertEquals(NmeaInput.Source.GPS, input.source);
    }

    @Test
    public void toInputFallsBackToNowWhenFixTimeMissing() {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(31.2);
        location.setLongitude(121.4);

        NmeaInput input = LocationProvider.toInput(location, NmeaInput.Source.NET);

        assertTrue(input.utcMillis > 0L);
    }
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*LocationProviderTest"`
Expected: FAIL —— `toInput(Location, Source)` 这个签名不存在

- [x] **Step 3: 实现**

Modify `app/src/main/java/com/gpsbt/transfer/engine/LocationProvider.java`。

把 `toInput` 改成带 `Source` 参数并采集真实元信息：

```java
    /** 把 Android Location 转成 NmeaInput。source 由调用方按 provider 判定。 */
    public static NmeaInput toInput(Location location, NmeaInput.Source source) {
        NmeaInput input = new NmeaInput();
        // 真实解算时刻。v1.6.2 用的是「当前时刻」，于是接收端无法知道
        // 这个位置是什么时候测的 —— 解算年龄在协议里根本没得传。
        long fixTime = location.getTime();
        input.utcMillis = fixTime > 0L ? fixTime : System.currentTimeMillis();
        input.fixElapsedRealtimeNanos = location.getElapsedRealtimeNanos();
        input.source = source == null ? NmeaInput.Source.NONE : source;
        input.valid = true;
        input.latitude = location.getLatitude();
        input.longitude = location.getLongitude();
        input.altitudeM = location.hasAltitude() ? location.getAltitude() : 0d;
        input.speedMps = location.hasSpeed() ? location.getSpeed() : 0d;
        input.bearingDeg = location.hasBearing() ? location.getBearing() : 0d;
        input.accuracyM = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        input.verticalAccuracyM = verticalAccuracy(location);
        input.speedAccuracyMps = speedAccuracy(location);
        input.bearingAccuracyDeg = bearingAccuracy(location);
        return input;
    }

    /** 垂直精度：API 26 起才有；更低的系统上没有这个概念，返回 NaN（未知）。 */
    private static float verticalAccuracy(Location location) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Float.NaN;
        }
        return location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : Float.NaN;
    }

    /** 速度精度（米/秒）：API 26 起才有。 */
    private static float speedAccuracy(Location location) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Float.NaN;
        }
        return location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : Float.NaN;
    }

    /** 航向精度（度）：API 26 起才有。 */
    private static float bearingAccuracy(Location location) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Float.NaN;
        }
        return location.hasBearingAccuracy() ? location.getBearingAccuracyDegrees() : Float.NaN;
    }
```

文件头补 import：

```java
import android.os.Build;
```

listener 里两处 `toInput(location)` 改为带来源：

```java
                current.onFix(toInput(location, NmeaInput.Source.GPS));
```
（GPS 分支）与
```java
            current.onFix(toInput(location, NmeaInput.Source.NET));
```
（网络兜底分支）。

- [x] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*LocationProviderTest"`
Expected: PASS

若单元测试里 `location.getTime()` 等返回 0（Android 桩不实现真实逻辑），把断言放宽为 `assertTrue(input.utcMillis > 0L)` 并删掉精确值断言 —— 具体以实际运行结果为准，**不要为了让测试通过而删掉实现**。

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/engine/LocationProvider.java app/src/test/java/com/gpsbt/transfer/engine/LocationProviderTest.java
git commit -m "feat(LocationProvider): 采集真实解算时刻、来源与三维精度"
```

---

### Task 5: `TransmitEngine` 改为队列 + 事件驱动

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/engine/TransmitEngine.java`
- Modify: `app/src/test/java/com/gpsbt/transfer/engine/TransmitEngineTest.java`

- [x] **Step 1: 写失败测试**

在 `app/src/test/java/com/gpsbt/transfer/engine/TransmitEngineTest.java` 类末尾追加：

```java
    private static NmeaInput fix(long fixTimeMillis, double lat) {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = fixTimeMillis;
        in.latitude = lat;
        in.longitude = 121.4;
        in.accuracyM = 12.5f;
        in.source = NmeaInput.Source.GPS;
        return in;
    }

    @Test
    public void publishesOneFramePerFix() throws Exception {
        RecordingSink sink = new RecordingSink();
        TransmitEngine engine = new TransmitEngine(sink, new NmeaGenerator(1.0f),
                NmeaSentenceType.all(), 5000L);

        engine.publish(fix(1_700_000_000_000L, 31.1));
        List<String> sentences = engine.tick(1_700_000_000_100L, 10_000_000_000L);

        assertEquals(1, sink.frames.size());
        assertTrue(sink.frames.get(0).startsWith("$GPSSHARE,"));
        assertTrue(sink.frames.get(0).contains("$GPGGA"));
        assertFalse(sentences.isEmpty());
    }

    @Test
    public void queueKeepsEveryFix() throws Exception {
        RecordingSink sink = new RecordingSink();
        TransmitEngine engine = new TransmitEngine(sink, new NmeaGenerator(1.0f),
                NmeaSentenceType.all(), 5000L);

        engine.publish(fix(1_700_000_000_000L, 31.1));
        engine.publish(fix(1_700_000_001_000L, 31.2));
        engine.tick(1_700_000_001_100L, 10_000_000_000L);
        engine.tick(1_700_000_001_200L, 10_000_000_000L);

        assertEquals(2, sink.frames.size());
    }

    @Test
    public void tickWithoutFixWritesNothing() throws Exception {
        RecordingSink sink = new RecordingSink();
        TransmitEngine engine = new TransmitEngine(sink, new NmeaGenerator(1.0f),
                NmeaSentenceType.all(), 5000L);

        assertTrue(engine.tick(1_700_000_000_100L, 10_000_000_000L).isEmpty());
        assertEquals(0, sink.frames.size());
    }

    @Test
    public void keepaliveCarriesLastFixMetadata() throws Exception {
        RecordingSink sink = new RecordingSink();
        TransmitEngine engine = new TransmitEngine(sink, new NmeaGenerator(1.0f),
                NmeaSentenceType.all(), 5000L);

        engine.publish(fix(1_700_000_000_000L, 31.1));
        engine.tick(1_700_000_000_100L, 10_000_000_000L);
        engine.tickKeepalive(1_700_000_005_000L, 10_005_000_000L);

        assertEquals(2, sink.frames.size());
        String keepalive = sink.frames.get(1);
        assertTrue(keepalive.startsWith("$GPSSHARE,"));
        assertTrue(keepalive.contains("$GPZDA"));
        assertTrue(keepalive.contains("AGE="));
    }
```

若该测试文件里没有 `RecordingSink`，补一个：

```java
    private static final class RecordingSink implements TransmitEngine.Sink {
        final List<String> frames = new ArrayList<>();

        @Override
        public void write(byte[] data, int length) {
            frames.add(new String(data, 0, length, java.nio.charset.StandardCharsets.US_ASCII));
        }
    }
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*TransmitEngineTest"`
Expected: FAIL —— `tick` / `tickKeepalive` 签名不匹配，`publish` 语义不同

- [x] **Step 3: 实现**

Modify `app/src/main/java/com/gpsbt/transfer/engine/TransmitEngine.java` —— 整体替换为：

```java
package com.gpsbt.transfer.engine;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 把定位快照转成 NMEA 帧写入链路。
 *
 * v2.0.0 起改为**事件驱动**：定位一到就入队，由调用方立刻 tick 取走，
 * 不再等定频时钟 —— 那段 0～1000 ms 的等待是纯粹的白白延迟。
 */
public final class TransmitEngine {

    public interface Sink {
        /** 写一帧数据；链路未连接等 IO 失败时抛 IOException，调用方据此停止。 */
        void write(byte[] data, int length) throws IOException;
    }

    private final Sink sink;
    private final NmeaGenerator generator;
    private final Set<NmeaSentenceType> enabled;
    private final long staleMillis;

    /** 待发解算。先进先出：连续多个解算时一个都不能丢。 */
    private final Deque<NmeaInput> queue = new ArrayDeque<>();
    /** 最后一次写出的解算：保活帧的元数据来源。 */
    private volatile NmeaInput lastSent;

    private volatile long sentences;
    private volatile long frames;
    private volatile long bytes;
    private volatile long startedAt;
    private volatile long lastFrameAt;

    public TransmitEngine(Sink sink, NmeaGenerator generator,
                          Set<NmeaSentenceType> enabled, long staleMillis) {
        this.sink = sink;
        this.generator = generator;
        this.enabled = enabled;
        this.staleMillis = staleMillis;
    }

    /** 后台线程调用：把解算放进队列，不写流。 */
    public void publish(NmeaInput input) {
        if (input == null) {
            return;
        }
        synchronized (queue) {
            queue.addLast(input);
        }
    }

    public boolean hasPending() {
        synchronized (queue) {
            return !queue.isEmpty();
        }
    }

    /**
     * 取走队首的解算并写成一帧。
     *
     * @return 本帧已写入的语句列表；队列为空时为空列表
     * @throws IOException 写入失败（例如链路已断开）
     */
    public List<String> tick(long nowMillis, long nowElapsedNanos) throws IOException {
        NmeaInput input;
        synchronized (queue) {
            input = queue.pollFirst();
        }
        if (input == null) {
            return Collections.emptyList();
        }
        long age = input.computeAgeMillis(nowElapsedNanos, nowMillis);
        if (age > staleMillis) {
            // 太旧的解算（比如某些 ROM 会投递陈旧定位）不算数：
            // 不发位置，只把它丢掉，让调用方改发保活帧
            return Collections.emptyList();
        }
        String frame = generator.buildFrame(input, enabled, age);
        byte[] data = frame.getBytes(StandardCharsets.US_ASCII);
        if (data.length > 0) {
            sink.write(data, data.length);
        }
        if (startedAt == 0) {
            startedAt = nowMillis;
        }
        lastFrameAt = nowMillis;
        lastSent = input;
        frames++;
        sentences += countSentences(frame);
        bytes += data.length;

        List<String> result = new ArrayList<>();
        for (String line : frame.split("\r\n")) {
            if (!line.isEmpty()) {
                result.add(line + "\r\n");
            }
        }
        return result;
    }

    /**
     * 无新解算时的链路保活：写一帧 $GPSSHARE（带上一次解算的元数据）+ $GPZDA。
     *
     * 让链路上始终有数据流动——否则发送端拿不到定位时长时间静默，
     * 接收端断开后发送端永远感知不到。接收端靠它知道「多久没有新定位了」。
     *
     * @return 已写入的帧文本；未发送时返回 null
     * @throws IOException 写入失败（链路已死）
     */
    public String tickKeepalive(long nowMillis, long nowElapsedNanos) throws IOException {
        NmeaInput last = lastSent;
        long age = last == null ? 0L : last.computeAgeMillis(nowElapsedNanos, nowMillis);
        String frame = generator.keepaliveFrame(last, age, nowMillis);
        byte[] data = frame.getBytes(StandardCharsets.US_ASCII);
        sink.write(data, data.length);
        sentences += countSentences(frame);
        bytes += data.length;
        lastFrameAt = nowMillis;
        return frame.trim();
    }

    public long sentences() {
        return sentences;
    }

    public long frames() {
        return frames;
    }

    public long bytes() {
        return bytes;
    }

    /** 累计平均发送速率（帧/秒，1 帧 = 一个发送周期内的全部语句）。 */
    public double rateHz(long nowMillis) {
        if (startedAt == 0 || nowMillis <= startedAt) {
            return 0d;
        }
        return frames * 1000.0 / (nowMillis - startedAt);
    }

    public void resetCounters() {
        sentences = 0;
        frames = 0;
        bytes = 0;
        startedAt = 0;
        lastFrameAt = 0;
    }

    private static int countSentences(String frame) {
        int count = 0;
        for (String line : frame.split("\r\n")) {
            if (!line.isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*TransmitEngineTest"`
Expected: PASS

旧的 `TransmitEngineTest` 用例会因签名变化编译失败 —— 按新签名改写：`tick(now)` → `tick(now, nowElapsed)`；「时间取自当前时钟」那条改成断言「时间取自解算时刻」。

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/engine/TransmitEngine.java app/src/test/java/com/gpsbt/transfer/engine/TransmitEngineTest.java
git commit -m "feat(TransmitEngine): 改为队列 + 事件驱动，保活帧带上一次解算的元数据"
```

---

### Task 6: `GpsOutputService` 新解即发 + 序号 + 去重

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java`

**这一步没有独立单测**（Service 依赖 Android 运行时），靠 Task 14 的全量测试与真机验收覆盖。改动前先跑一次全量测试确认基线是绿的。

- [x] **Step 1: 确认基线绿**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL（Task 1–5 的改动已合入）

- [x] **Step 2: 替换调度器**

把 `GpsOutputService` 里与「定频 tick」相关的成员替换掉：

```java
    private ScheduledExecutorService sendExecutor;
    private ScheduledFuture<?> keepaliveFuture;
    /** 发送任务是否已在队列里：避免连续多个解算投递出多个任务。 */
    private final java.util.concurrent.atomic.AtomicBoolean sendScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 解算序号：每次真正的新解算 +1。 */
    private volatile long fixSeq;
    /** 上一次发出过的解算时刻：用来识别「同一个解算被重复投递」。 */
    private volatile long lastFixTimeMillis;
    /** 上一次写出位置帧的时刻：保活定时器据此判断该不该发。 */
    private volatile long lastPositionAt;
```

- [x] **Step 3: 替换调度器的启动与停止**

`startScheduler(int rateHz)` 整个方法替换为：

```java
    /**
     * 启动发送流水线。
     *
     * v2.0.0 起不再定频 tick：定位一到就发（{@link #requestSend()}），
     * 另有一个 1 秒一次的保活定时器负责「没有新定位时也让链路有数据流动」。
     */
    private void startScheduler() {
        stopScheduler();
        sendExecutor = Executors.newSingleThreadScheduledExecutor();
        keepaliveFuture = sendExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                tickKeepalive();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
```

`stopScheduler()` 替换为：

```java
    private void stopScheduler() {
        if (keepaliveFuture != null) {
            keepaliveFuture.cancel(false);
            keepaliveFuture = null;
        }
        if (sendExecutor != null) {
            sendExecutor.shutdownNow();
            sendExecutor = null;
        }
        sendScheduled.set(false);
    }
```

同时把 `startTransmission()` 里的调用点 `startScheduler(prefs.getRateHz());` 改成 `startScheduler();`。

- [x] **Step 4: 替换 tickInternal 为事件驱动的发送**

把 `tickInternal()` 整个方法替换为下面三个方法：

```java
    /**
     * 请求发送一次。定位一到就调。
     *
     * 投递到发送执行器（单线程）—— 调用方是主线程，绝不能在这里做 socket 写。
     */
    private void requestSend() {
        if (!running) {
            return;
        }
        if (sendScheduled.compareAndSet(false, true)) {
            ScheduledExecutorService executor = sendExecutor;
            if (executor == null) {
                sendScheduled.set(false);
                return;
            }
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        drainSends();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                sendScheduled.set(false);
            }
        }
    }

    /** 把队列里的解算全部写出去。 */
    private void drainSends() {
        try {
            while (running) {
                TransmitEngine current = engine;
                Link currentLink = link;
                if (current == null || currentLink == null || !currentLink.isConnected()) {
                    return;
                }
                long nowMillis = System.currentTimeMillis();
                long nowElapsed = SystemClock.elapsedRealtimeNanos();
                List<String> sentences;
                try {
                    sentences = current.tick(nowMillis, nowElapsed);
                } catch (IOException e) {
                    handleWriteFailure(e);
                    return;
                }
                if (sentences.isEmpty()) {
                    // 队列空了（或是解算太旧被丢掉）
                    if (!current.hasPending()) {
                        return;
                    }
                    continue;
                }
                lastPositionAt = nowMillis;
                stats.rateHz = current.rateHz(nowMillis);
                maybeUpdateNotification(nowMillis);
                for (String sentence : sentences) {
                    logSentence(sentence);
                }
                stats.sentences = current.sentences();
                stats.bytes = current.bytes();
            }
        } finally {
            sendScheduled.set(false);
            // 收尾竞态：刚清标志位就来了新解算，而它的请求被并进了本次任务
            TransmitEngine current = engine;
            if (running && current != null && current.hasPending()) {
                requestSend();
            }
        }
    }

    /** 保活定时器：没有新定位时也让链路上有数据流动。 */
    private void tickKeepalive() {
        long nowMillis = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtimeNanos();
        if (fromBoot && !uiStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !fixReceived && nowMillis - startedAt > BG_FIX_TIMEOUT_MS) {
            // 后台启动的服务拿不到定位：降级成一键开始
            notifyLocationRestricted();
            stopSelfSafely(false);
            return;
        }
        TransmitEngine current = engine;
        Link currentLink = link;
        if (current == null || currentLink == null || !currentLink.isConnected()) {
            if (nowMillis - linkNotReadyLoggedAt > 30000L) {
                linkNotReadyLoggedAt = nowMillis;
                LogBus.get().log(LogBus.Level.WARN,
                        linkLabel() + "未就绪，暂停发送（等待接收端连接）");
            }
            return;
        }
        linkNotReadyLoggedAt = 0;
        if (!prerequisiteSatisfied(this)) {
            LogBus.get().log(LogBus.Level.WARN, "前置条件不满足，停止传输");
            state.setStatusText(getString(R.string.license_expired_title));
            stopSelfSafely(false);
            return;
        }
        try {
            String keepalive = current.tickKeepalive(nowMillis, nowElapsed);
            if (keepalive != null) {
                logSentence(keepalive);
            }
        } catch (IOException e) {
            handleWriteFailure(e);
        }
    }

    /** 写失败：服务端模式清理死连接，客户端模式 5 秒后重建链路。 */
    private void handleWriteFailure(IOException e) {
        LogBus.get().log(LogBus.Level.ERROR, "发送失败：" + e.getMessage());
        if (mode == ConnectionMode.SERVER) {
            Link currentLink = link;
            if (currentLink != null) {
                currentLink.resetConnection();
            }
            state.setPeers(null);
            state.setBt(TransmitterState.Bt.WAITING);
            state.setStatusText("链路中断，等待接收端重新连接");
            refreshNotification();
        } else {
            scheduleRelink();
        }
    }
```

文件头补 import：

```java
import android.os.SystemClock;
```

- [x] **Step 5: 改造 onFix：去重 + 编号 + 立即发送**

把 `locationCallback.onFix` 改成：
```java
        @Override
        public void onFix(NmeaInput input) {
            fixReceived = true;
            LocationProvider provider = locationProvider;
            final NmeaInput full = provider == null ? input : provider.withSatellites(input);
            // 同一个解算被重复投递（部分 ROM 的常态）不重发，也不占用序号
            if (full.utcMillis > 0L && full.utcMillis == lastFixTimeMillis) {
                return;
            }
            lastFixTimeMillis = full.utcMillis;
            full.seq = ++fixSeq;
            TransmitEngine current = engine;
            if (current != null) {
                current.publish(full);
                requestSend();
            }

            TransmitterState.Fix fix = new TransmitterState.Fix();
            fix.valid = full.valid;
            fix.satellitesInView = full.satellitesInView();
            fix.satellitesUsed = full.satellitesUsed;
            fix.latitude = full.latitude;
            fix.longitude = full.longitude;
            fix.altitudeM = full.altitudeM;
            fix.speedMps = full.speedMps;
            fix.bearingDeg = full.bearingDeg;
            fix.accuracyM = full.accuracyM;
            fix.source = full.source == null ? "" : full.source.wire();
            fix.ageMillis = full.computeAgeMillis(SystemClock.elapsedRealtimeNanos(),
                    System.currentTimeMillis());
            fix.fixAt = System.currentTimeMillis();
            state.setFix(fix);

            List<SatelliteRadarView.Sat> converted = new ArrayList<SatelliteRadarView.Sat>();
            for (NmeaInput.Sat sat : full.satellites()) {
                converted.add(new SatelliteRadarView.Sat(sat.prn, sat.elevationDeg,
                        sat.azimuthDeg, sat.snr, sat.used, sat.talker));
            }
            state.setSatellites(converted);
        }
```

- [x] **Step 6: 编译并跑全量测试**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL

若 `TransmitterState.Fix` 还没有 `source` / `ageMillis` 字段，编译会报错 —— Task 10 会加。**本步先在 `Fix` 里临时加两个字段**：

```java
        public volatile String source = "";
        public volatile long ageMillis = -1L;
```

Task 10 会把它们连同 UI 一起补完整。

- [x] **Step 7: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java
git commit -m "feat(GpsOutputService): 新解即发，去重并编号，保活定时器接管无定位时的链路"
```

---

## 阶段三：接收端

### Task 7: `InjectionPolicy` —— 注入判据与参数计算（纯逻辑）

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/rx/InjectionPolicy.java`
- Create: `app/src/test/java/com/gpsbt/transfer/rx/InjectionPolicyTest.java`

- [x] **Step 1: 写失败测试**

Create `app/src/test/java/com/gpsbt/transfer/rx/InjectionPolicyTest.java`:

```java
package com.gpsbt.transfer.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.nmea.NmeaInput;

import org.junit.Before;
import org.junit.Test;

public class InjectionPolicyTest {

    private InjectionPolicy policy;

    @Before
    public void setUp() {
        policy = new InjectionPolicy();
    }

    private static NmeaInput fix(long seq, long ageMillis) {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.seq = seq;
        in.ageMillis = ageMillis;
        in.utcMillis = 1_700_000_000_000L;
        in.latitude = 31.2;
        in.longitude = 121.4;
        in.altitudeM = 12.5;
        in.speedMps = 3.0;
        in.bearingDeg = 87.0;
        in.accuracyM = 12.5f;
        in.source = NmeaInput.Source.GPS;
        return in;
    }

    @Test
    public void injectsFirstFix() {
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(fix(1L, 100L)));
    }

    @Test
    public void sameSeqIsDuplicate() {
        policy.decide(fix(1L, 100L));
        assertEquals(InjectionPolicy.Decision.DUPLICATE, policy.decide(fix(1L, 200L)));
    }

    @Test
    public void newSeqIsInjected() {
        policy.decide(fix(1L, 100L));
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(fix(2L, 100L)));
    }

    @Test
    public void invalidFixIsRejected() {
        NmeaInput in = fix(1L, 100L);
        in.valid = false;
        assertEquals(InjectionPolicy.Decision.INVALID, policy.decide(in));
    }

    @Test
    public void expiredFixIsRejected() {
        assertEquals(InjectionPolicy.Decision.EXPIRED, policy.decide(fix(1L, 6000L)));
    }

    @Test
    public void outOfRangeCoordinatesAreRejected() {
        NmeaInput in = fix(1L, 100L);
        in.latitude = 91.0;
        assertEquals(InjectionPolicy.Decision.OUT_OF_RANGE, policy.decide(in));
    }

    @Test
    public void negativeAgeIsRejected() {
        assertEquals(InjectionPolicy.Decision.OUT_OF_RANGE, policy.decide(fix(1L, -1L)));
    }

    @Test
    public void fallbackIdentityUsesPositionAndTime() {
        NmeaInput a = fix(0L, 100L);
        a.seq = 0L;
        NmeaInput b = fix(0L, 100L);
        b.seq = 0L;
        b.latitude = 31.3;
        policy.decide(a);
        assertEquals(InjectionPolicy.Decision.DUPLICATE, policy.decide(a));
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(b));
    }

    @Test
    public void payloadAnchorsTimeToNowMinusAge() {
        NmeaInput in = fix(1L, 842L);
        InjectionPolicy.Payload p = InjectionPolicy.payloadOf(in, 1_700_000_000_000L, 10_000_000_000L);

        assertEquals(1_699_999_999_158L, p.timeMillis);
        assertEquals(10_000_000_000L - 842_000_000L, p.elapsedRealtimeNanos);
        assertEquals(12.5f, p.accuracyM, 0.001f);
        assertEquals(31.2, p.latitude, 0.0001);
    }

    @Test
    public void payloadKeepsAllAccuracies() {
        NmeaInput in = fix(1L, 0L);
        in.verticalAccuracyM = 8.0f;
        in.speedAccuracyMps = 0.8f;
        in.bearingAccuracyDeg = 15.0f;
        InjectionPolicy.Payload p = InjectionPolicy.payloadOf(in, 0L, 0L);
        assertEquals(8.0f, p.verticalAccuracyM, 0.001f);
        assertEquals(0.8f, p.speedAccuracyMps, 0.001f);
        assertEquals(15.0f, p.bearingAccuracyDeg, 0.001f);
    }

    @Test
    public void resetForgetsIdentity() {
        policy.decide(fix(1L, 100L));
        policy.reset();
        assertEquals(InjectionPolicy.Decision.INJECT, policy.decide(fix(1L, 100L)));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*InjectionPolicyTest"`
Expected: FAIL —— 找不到类 `InjectionPolicy`

- [x] **Step 3: 实现**

Create `app/src/main/java/com/gpsbt/transfer/rx/InjectionPolicy.java`:

```java
package com.gpsbt.transfer.rx;

import com.gpsbt.transfer.nmea.NmeaInput;

import java.util.Locale;

/**
 * 注入判据与参数计算：**纯逻辑，不碰任何 Android API**，因此可单测。
 *
 * 回答三个问题：
 * 1. 这份数据该不该注入？（同一解算重复到达 / 无效 / 太旧 / 数值越界）
 * 2. 注入的时间戳应该是多少？（本机时钟 − 解算年龄）
 * 3. 精度怎么填？（有就传真值，没有就不设，绝不编默认值）
 *
 * 时间戳的算法是这里最要紧的一处：Android 的 {@code Location} 同时带墙上
 * 时钟（{@code getTime()}）和 uptime（{@code getElapsedRealtimeNanos()}），
 * **两个都必须减去解算年龄**——只改一个的话，平台算出的年龄和 App 算出的
 * 年龄会互相打架（一个说 0 秒，一个说 1 秒）。
 */
public final class InjectionPolicy {

    /** 超过这个年龄就不注入：位置太旧，App 会当成陈旧数据丢掉。 */
    public static final long MAX_AGE_MILLIS = 5000L;

    /** 精度的合理区间（米）。超出即视为异常数据。 */
    private static final float MIN_ACCURACY_M = 0.1f;
    private static final float MAX_ACCURACY_M = 100000f;

    /** 年龄的合理区间（毫秒）。 */
    private static final long MAX_PLAUSIBLE_AGE_MILLIS = 60000L;

    public enum Decision {
        /** 可以注入。 */
        INJECT,
        /** 同一个解算重复到达。 */
        DUPLICATE,
        /** 定位本身无效。 */
        INVALID,
        /** 太旧了。 */
        EXPIRED,
        /** 数值越界。 */
        OUT_OF_RANGE
    }

    /** 注入所需的全部参数。都是基本类型，不含 Android 类型，便于断言。 */
    public static final class Payload {
        public double latitude;
        public double longitude;
        public double altitudeM;
        public double speedMps;
        public double bearingDeg;
        public float accuracyM = Float.NaN;
        public float verticalAccuracyM = Float.NaN;
        public float speedAccuracyMps = Float.NaN;
        public float bearingAccuracyDeg = Float.NaN;
        /** 本机墙上时钟减去解算年龄。 */
        public long timeMillis;
        /** 本机 uptime 减去解算年龄。 */
        public long elapsedRealtimeNanos;
    }

    private String lastIdentity;

    /** 断线重连时调用：忘记上一个解算的身份。 */
    public void reset() {
        lastIdentity = null;
    }

    /** 这个解算的身份标识。有序号用序号，否则退回「位置 + 时刻」。 */
    public static String identityOf(NmeaInput in) {
        if (in.seq > 0L) {
            return "seq:" + in.seq;
        }
        return String.format(Locale.US, "fallback:%.6f,%.6f,%d",
                in.latitude, in.longitude, in.utcMillis);
    }

    /**
     * 判定这份数据该不该注入。
     *
     * 注意顺序：先判重复（同一个解算的卫星快照也会走这里），再判有效性。
     */
    public Decision decide(NmeaInput in) {
        if (in == null) {
            return Decision.INVALID;
        }
        String identity = identityOf(in);
        if (identity.equals(lastIdentity)) {
            return Decision.DUPLICATE;
        }
        if (!in.valid) {
            return Decision.INVALID;
        }
        if (in.ageMillis > MAX_AGE_MILLIS) {
            return Decision.EXPIRED;
        }
        if (!inRange(in)) {
            return Decision.OUT_OF_RANGE;
        }
        lastIdentity = identity;
        return Decision.INJECT;
    }

    private static boolean inRange(NmeaInput in) {
        if (Double.isNaN(in.latitude) || in.latitude < -90d || in.latitude > 90d) {
            return false;
        }
        if (Double.isNaN(in.longitude) || in.longitude < -180d || in.longitude > 180d) {
            return false;
        }
        if (!Float.isNaN(in.accuracyM)
                && (in.accuracyM < MIN_ACCURACY_M || in.accuracyM > MAX_ACCURACY_M)) {
            return false;
        }
        return in.ageMillis >= 0L && in.ageMillis <= MAX_PLAUSIBLE_AGE_MILLIS;
    }

    /** 算出注入用的参数：时间戳按「本机时钟 − 解算年龄」重锚。 */
    public static Payload payloadOf(NmeaInput in, long nowWallMillis, long nowElapsedNanos) {
        Payload payload = new Payload();
        payload.latitude = in.latitude;
        payload.longitude = in.longitude;
        payload.altitudeM = in.altitudeM;
        payload.speedMps = in.speedMps;
        payload.bearingDeg = in.bearingDeg;
        payload.accuracyM = in.accuracyM;
        payload.verticalAccuracyM = in.verticalAccuracyM;
        payload.speedAccuracyMps = in.speedAccuracyMps;
        payload.bearingAccuracyDeg = in.bearingAccuracyDeg;
        long age = Math.max(0L, in.ageMillis);
        payload.timeMillis = nowWallMillis - age;
        payload.elapsedRealtimeNanos = nowElapsedNanos - age * 1_000_000L;
        return payload;
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "*InjectionPolicyTest"`
Expected: PASS，11 个用例全过

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/rx/InjectionPolicy.java app/src/test/java/com/gpsbt/transfer/rx/InjectionPolicyTest.java
git commit -m "feat(InjectionPolicy): 注入判据与时间重锚的纯逻辑实现"
```

---

### Task 8: `MockLocationInjector` 改为胶水层

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/rx/MockLocationInjector.java`

- [x] **Step 1: 改造 inject 方法**

Modify `app/src/main/java/com/gpsbt/transfer/rx/MockLocationInjector.java`。

类字段里加：

```java
    /** 注入判据：纯逻辑，可单测（见 InjectionPolicy）。 */
    private final InjectionPolicy policy = new InjectionPolicy();
    /** 连续注入失败次数：到 3 次就明确报错给用户看。 */
    private int consecutiveFailures;
    /** 失败回调：连续失败达到阈值时通知外部（界面 / 通知）。 */
    public interface FailureListener {
        void onInjectionFailed(String message);
    }
    private FailureListener failureListener;

    public void setFailureListener(FailureListener listener) {
        this.failureListener = listener;
    }
```

`stop()` 里补一行 `policy.reset();`。

把 `inject(NmeaInput input)` 整个方法替换为：

```java
    /**
     * 注入一次定位。
     *
     * 判据与参数计算交给 {@link InjectionPolicy}（纯逻辑、可单测），
     * 这里只负责把参数搬进 {@code Location} 并写进系统。
     */
    public void inject(NmeaInput input) {
        if (!installed || manager == null || input == null) {
            return;
        }
        InjectionPolicy.Decision decision = policy.decide(input);
        if (decision != InjectionPolicy.Decision.INJECT) {
            if (decision == InjectionPolicy.Decision.EXPIRED) {
                LogBus.get().log(LogBus.Level.WARN, "定位已过期，未注入");
            } else if (decision == InjectionPolicy.Decision.OUT_OF_RANGE) {
                LogBus.get().log(LogBus.Level.WARN, "定位数值异常，已丢弃");
            }
            return;
        }
        InjectionPolicy.Payload payload = InjectionPolicy.payloadOf(input,
                System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos());

        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(payload.latitude);
        location.setLongitude(payload.longitude);
        location.setAltitude(payload.altitudeM);
        location.setSpeed((float) payload.speedMps);
        location.setBearing((float) payload.bearingDeg);
        // 精度：有就传真值，没有就不设 —— 不写死 5 m / 1 m/s / 5°，
        // 那是 v1.6.2 的做法，会让 App 以为精度比实际好得多
        if (!Float.isNaN(payload.accuracyM) && payload.accuracyM > 0f) {
            location.setAccuracy(payload.accuracyM);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!Float.isNaN(payload.verticalAccuracyM) && payload.verticalAccuracyM > 0f) {
                location.setVerticalAccuracyMeters(payload.verticalAccuracyM);
            }
            if (!Float.isNaN(payload.speedAccuracyMps) && payload.speedAccuracyMps > 0f) {
                location.setSpeedAccuracyMetersPerSecond(payload.speedAccuracyMps);
            }
            if (!Float.isNaN(payload.bearingAccuracyDeg) && payload.bearingAccuracyDeg > 0f) {
                location.setBearingAccuracyDegrees(payload.bearingAccuracyDeg);
            }
        }
        // 两个时钟都减去解算年龄：平台算出的年龄与 App 算出的年龄才会一致
        location.setTime(payload.timeMillis);
        location.setElapsedRealtimeNanos(payload.elapsedRealtimeNanos);
        try {
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
            consecutiveFailures = 0;
        } catch (SecurityException | IllegalArgumentException e) {
            consecutiveFailures++;
            LogBus.get().log(LogBus.Level.WARN, "注入定位失败：" + e.getMessage());
            if (consecutiveFailures >= 3 && failureListener != null) {
                failureListener.onInjectionFailed(String.valueOf(e.getMessage()));
            }
        }
    }
```

- [x] **Step 2: 编译并跑全量测试**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/rx/MockLocationInjector.java
git commit -m "feat(MockLocationInjector): 判据交给 InjectionPolicy，时间双时钟重锚，精度如实传递"
```

---

### Task 9: `ReceiveService` 接收端状态与失败上报

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/service/ReceiveService.java`

- [x] **Step 1: 接线失败监听**

在 `ReceiveService` 的字段区加：

```java
    /** 注入连续失败时对外报告。 */
    private final MockLocationInjector.FailureListener injectionFailureListener =
            new MockLocationInjector.FailureListener() {
                @Override
                public void onInjectionFailed(String message) {
                    LogBus.get().log(LogBus.Level.ERROR, "定位注入连续失败：" + message);
                    state.setStatusText(getString(R.string.receive_inject_failed));
                    refreshNotification();
                }
            };
```

在 `injector = new MockLocationInjector(this);` 之后加一行：

```java
            injector.setFailureListener(injectionFailureListener);
```

- [x] **Step 2: 改造 onLocation 回调**

把 `ReceiveService` 里 `callback.onLocation` 的方法体改成：

```java
        @Override
        public void onLocation(NmeaInput location) {
            // 前置条件在运行期间也可能变化——只在启动时查一次的话，
            // 跨过到期点的会话会一直跑下去
            if (!prerequisiteSatisfied(ReceiveService.this)) {
                LogBus.get().log(LogBus.Level.WARN, "前置条件不满足，停止接收");
                stopSelfSafely(false);
                return;
            }
            stats.sentences++;
            stats.bytes += location.wireBytes;
            updateReceiveRate(System.currentTimeMillis());

            if (injector != null) {
                injector.inject(location);
            }

            // 定位状态：每个解算更新一次。卫星明细只在带卫星的快照里更新
            TransmitterState.Fix fix = new TransmitterState.Fix();
            fix.valid = location.valid;
            fix.satellitesInView = location.satellitesInView();
            fix.satellitesUsed = location.satellitesUsed;
            fix.latitude = location.latitude;
            fix.longitude = location.longitude;
            fix.altitudeM = location.altitudeM;
            fix.speedMps = location.speedMps;
            fix.bearingDeg = location.bearingDeg;
            fix.accuracyM = location.accuracyM;
            fix.source = location.source == null ? "" : location.source.wire();
            fix.ageMillis = location.ageMillis;
            fix.fixAt = System.currentTimeMillis();
            state.setFix(fix);

            if (location.withSatellites) {
                List<SatelliteRadarView.Sat> converted = new ArrayList<SatelliteRadarView.Sat>();
                for (NmeaInput.Sat sat : location.satellites()) {
                    converted.add(new SatelliteRadarView.Sat(sat.prn, sat.elevationDeg,
                            sat.azimuthDeg, sat.snr, sat.used, sat.talker));
                }
                state.setSatellites(converted);
            }
            refreshNotification();
        }
```

- [x] **Step 3: 断链时清掉注入记忆**

在 `ReceiveService` 的 `onConnectionChanged` 里，`connected == false` 的分支补一行：

```java
                state.setPeers(null);
                stats.startedAt = 0L;
                if (injector != null) {
                    injector.resetPolicy();
                }
```

在 `MockLocationInjector` 里加：

```java
    /** 断线重连：忘掉上一个解算的身份，新链路的第一个解算要能注入。 */
    public void resetPolicy() {
        policy.reset();
    }
```

- [x] **Step 4: 编译并跑全量测试**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/service/ReceiveService.java app/src/main/java/com/gpsbt/transfer/rx/MockLocationInjector.java
git commit -m "feat(ReceiveService): 接收端记录来源与延迟，注入连续失败上报"
```

---

## 阶段四：界面

### Task 10: 新增「数据来源」与「定位延迟」卡片

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/StatsGridBuilder.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java`
- Modify: `app/src/main/res/values/strings.xml`

- [x] **Step 1: `TransmitterState.Fix` 补字段**

把 `Fix` 类里的字段补齐（Task 6 可能已临时加过，这里统一成正式形态）：

```java
        /** 数据来源："GPS" / "NET" / ""（未知）。 */
        public volatile String source = "";
        /** 定位年龄（毫秒）：解算到现在有多旧。-1 = 未知。 */
        public volatile long ageMillis = -1L;
```

- [x] **Step 2: `StatsGridBuilder` 加两张卡**

在 `StatsGridBuilder` 的常量区加：

```java
    static final String KEY_SOURCE = "source";
    static final String KEY_LATENCY = "latency";
```

在 `build()` 里，`KEY_ACCURACY` 那张卡之后插入：

```java
        // 数据来源：卫星定位还是网络兜底。用户一眼就能看出「现在这个位置
        // 值不值得信」——网络定位的精度可能差几十倍
        add(grid, target, KEY_SOURCE, R.string.stat_source, R.drawable.ic_stat_location,
                R.string.value_unknown, 1);
        // 定位延迟：解算到现在有多旧。这张卡直接回答「共享有多快」
        add(grid, target, KEY_LATENCY, R.string.stat_latency, R.drawable.ic_stat_speed,
                R.string.value_unknown, 1);
```

> 注意：卡片总数是奇数会让 GridLayout 末尾留一格空位。`KEY_ACCURACY` 之后插两张，正好补成偶数。

- [x] **Step 3: 加字符串资源**

在 `app/src/main/res/values/strings.xml` 加：

```xml
    <string name="stat_source">数据来源</string>
    <string name="stat_latency">定位延迟</string>
    <string name="stat_latency_value">%1$s 秒</string>
    <string name="stat_latency_stale">已过期 %1$s 秒</string>
    <string name="value_source_gps">GPS卫星定位</string>
    <string name="value_source_net">网络定位</string>
    <string name="receive_inject_failed">定位注入失败，请检查开发者选项里的模拟位置授权</string>
```

- [x] **Step 4: `HomeFragment` 渲染新卡片**

在 `HomeFragment` 的卡片渲染处（`set(StatsGridBuilder.KEY_ACCURACY, ...)` 之后）插入：

```java
        // 数据来源
        set(StatsGridBuilder.KEY_SOURCE, sourceText(fix));
        // 定位延迟：新鲜时用协议给的解算年龄；过期后改用「距上次收到」的时间，
        // 那个数会一直往上走，用户能看出位置已经陈旧
        set(StatsGridBuilder.KEY_LATENCY, latencyText(fix, running));
```

并在 `HomeFragment` 里加两个私有方法：

```java
    /** 「数据来源」卡的取值。 */
    private String sourceText(TransmitterState.Fix fix) {
        if (fix == null || fix.source == null || fix.source.isEmpty()) {
            return getString(R.string.value_unknown);
        }
        if ("NET".equals(fix.source)) {
            return getString(R.string.value_source_net);
        }
        return getString(R.string.value_source_gps);
    }

    /** 「定位延迟」卡的取值。 */
    private String latencyText(TransmitterState.Fix fix, boolean running) {
        if (fix == null || !running || !fix.valid) {
            return getString(R.string.value_unknown);
        }
        long sinceArrival = System.currentTimeMillis() - fix.fixAt;
        if (sinceArrival > 5000L) {
            return getString(R.string.stat_latency_stale,
                    String.format(Locale.US, "%.0f", sinceArrival / 1000.0d));
        }
        long age = fix.ageMillis >= 0L ? fix.ageMillis : sinceArrival;
        return getString(R.string.stat_latency_value,
                String.format(Locale.US, "%.2f", age / 1000.0d));
    }
```

- [x] **Step 5: 编译并跑全量测试**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/ui/fragment/StatsGridBuilder.java app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java app/src/main/res/values/strings.xml
git commit -m "feat(ui): 新增「数据来源」与「定位延迟」卡片"
```

---

### Task 11: 设置页频率项改名并显示实际速率

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/SettingsFragment.java`

- [x] **Step 1: 改文案**

`strings.xml` 里把：

```xml
    <string name="settings_rate">发送频率</string>
```

改成：

```xml
    <string name="settings_rate">定位请求频率</string>
    <string name="settings_rate_hint">向手机请求定位的频率。GPS 芯片通常只按 1 Hz 出解，实际收到的定位以设备为准。</string>
```

- [x] **Step 2: 显示实际速率**

`SettingsFragment` 的频率滑块监听器（`rateSlider.addOnChangeListener`）里，在 `prefs.setRateHz(hz)` 之后补一行，把实际收到的定位速率显示出来：

```java
                prefs.setRateHz(hz);
                rateValue.setText(getString(R.string.settings_rate_value, hz));
                updateActualRate();
```

并新增方法：

```java
    /**
     * 「实际收到定位」：滑块控制的是**请求**频率，GPS 芯片能给多少是另一回事。
     * 不把这两件事分开显示，用户会以为滑块拉到 10 Hz 就能拿到 10 Hz 的定位。
     */
    private void updateActualRate() {
        TransmitterState.Fix fix = TransmitterState.get().fix();
        TextView actual = view.findViewById(R.id.rate_actual);
        if (actual == null) {
            return;
        }
        if (fix == null || fix.fixAt <= 0L) {
            actual.setText(getString(R.string.settings_rate_actual_unknown));
            return;
        }
        long age = System.currentTimeMillis() - fix.fixAt;
        actual.setText(getString(R.string.settings_rate_actual,
                String.format(java.util.Locale.US, "%.1f", 1000.0d / Math.max(1L, age))));
    }
```

- [x] **Step 3: 布局里加「实际速率」那一行**

在 `app/src/main/res/layout/fragment_settings.xml` 里，频率滑块所在的那一项下面加一个 `TextView`：

```xml
                <TextView
                    android:id="@+id/rate_actual"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_rate_actual_unknown"
                    android:textSize="12sp"
                    android:textColor="@color/color_text_secondary" />
```

并补字符串：

```xml
    <string name="settings_rate_actual">实际收到定位：%1$s 次/秒</string>
    <string name="settings_rate_actual_unknown">实际收到定位：--</string>
```

- [x] **Step 4: 编译并跑全量测试**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL

- [x] **Step 5: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/fragment_settings.xml app/src/main/java/com/gpsbt/transfer/ui/fragment/SettingsFragment.java
git commit -m "feat(ui): 频率项改名为「定位请求频率」并显示实际收到的定位速率"
```

---

## 阶段五：收尾

### Task 12: README 与使用教程同步

**Files:**
- Modify: `README.md`
- Modify: `docs/USAGE.md`
- Modify: `docs/使用教程.md`

- [x] **Step 1: README 更新协议与兼容性说明**

在 README 的功能表里补两行：

```markdown
| 定位元数据透传 | 新增 `$GPSSHARE` 语句，携带**米制精度**（水平/垂直/速度/航向）、**解算年龄**与**数据来源**；接收端按年龄在**本机时钟**上重锚时间，两台手机时钟不同步也不受影响 |
| 事件驱动发送 | 定位一到立刻发帧（不再等定频时钟），端到端延迟只取决于链路本身 |
```

并补一段兼容性说明：

```markdown
### 与 1.6.2 的兼容性

2.0.0 与 1.6.2 **混用不会崩，只是拿不到改进**：

- 2.0.0 发送 → 1.6.2 接收：旧接收端忽略 `$GPSSHARE`，照旧按 HDOP×5 解析，位置正常，精度仍被截断到 49.5 m。
- 1.6.2 发送 → 2.0.0 接收：缺 `$GPSSHARE` → 退回旧行为，位置正常，精度仍截断、「定位延迟」显示 0。

想要完整效果，**发送端与接收端都要升级到 2.0.0**。
```

- [x] **Step 2: 使用教程同步**

在 `docs/USAGE.md` 与 `docs/使用教程.md` 里补一节：

```markdown
### 「定位延迟」这张卡怎么看

它显示的是**本次定位解算到现在有多旧**：

- 正常户外 GPS，一般 0.5～1.5 秒。这是 GNSS 芯片本身的解算延迟，不是共享造成的。
- 数值突然变大（比如 5 秒以上），说明发送端暂时拿不到新的定位（进了隧道、高楼遮挡），此时接收端的位置会逐秒变旧。
- 「数据来源」显示「网络定位」时，发送端已经切到 WiFi/基站定位了，精度会明显变差（卡片里的「定位精度」会如实显示几十到几百米）。
```

- [x] **Step 3: 提交**

```bash
git add README.md docs/USAGE.md docs/使用教程.md
git commit -m "docs: 补充 v2.0.0 的协议、兼容性说明与「定位延迟」解读"
```

---

### Task 13: 全量测试 + release 构建

**Files:** 无（只验证）

- [x] **Step 1: 跑全部单测**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: BUILD SUCCESSFUL，且用例数从 119 增加到 150 以上

- [x] **Step 2: 出 release 包**

Run: `./gradlew :app:assembleRelease --offline`
Expected: BUILD SUCCESSFUL

产物在 `构建目录/GpsShare2/app/outputs/apk/release/`（构建目录被 `local.properties` 的 `build.dir` 重定向过）。

- [x] **Step 3: 确认版本号与签名**

Run: `aapt dump badging <apk> | head -3`（若 `aapt` 不可用，用 Android Studio 的 Build 面板查看）
Expected: `package: name='com.gpsshare' versionCode='28' versionName='2.0.0'`

- [x] **Step 4: 确认老仓库未受影响**

Run: `git -C "工作区根目录/GPS共享" status --short && git -C "工作区根目录/GPS蓝牙传输" status --short`
Expected: 两个命令都**没有任何输出**（老版本一个字节都没动）

- [x] **Step 5: 提交（若构建脚本有改动）**

```bash
git status --short
# 无改动则跳过本步
```

---

### Task 14: 真机验收清单（无设备，留给上机时执行）

**Files:** 无（本任务只产出待办清单，不改代码）

把下面这份清单写进 `docs/superpowers/plans/2026-10-22-gps-share-v2-fidelity.md` 的末尾，或另存为 `docs/真机验收清单-v2.0.0.md`：

- [ ] 发送端与接收端都是 2.0.0，连接成功，接收端「定位延迟」显示 0.5～1.5 秒
- [ ] 接收端「定位精度」与发送端「定位精度」**一致**（不再出现 49.5 m 的截断值）
- [ ] 接收端「数据来源」显示「GPS卫星定位」
- [ ] 把发送端拿进隧道/室内，10 秒后接收端「数据来源」变为「网络定位」，「定位精度」变成几十～几百米
- [ ] 断链后接收端「定位延迟」变成「已过期 N 秒」并持续增长
- [ ] 关掉 GSV 语句，接收端延迟**不应**增加 1 秒（v1.6.2 的老毛病）
- [ ] 拔掉发送端电源，接收端在 20 秒内提示链路断开
- [ ] 在开发者选项里取消本应用的「模拟位置信息应用」，接收端应明确提示注入失败
- [ ] 把发送端手机时间调偏 5 分钟，两端定位**不应**受影响（验证时钟免疫）

---

## 附：设计偏离记录

| spec 原文 | 实施时的调整 | 原因 |
|---|---|---|
| 「本帧没有 RMC → 在 GGA 发布，速度/航向沿用上一周期的值」 | 改为「在**下一帧开头**补发」 | 在 GGA 发布会给出一份速度/航向是上一周期、其余是本周期的数据 —— 混合数据比晚 1 秒的完整数据更糟。等 RMC 的代价只有 1 秒，且只在用户主动关掉 RMC 时才发生 |
| 「保活定时器：没有新解算时才发」 | 改为**无条件每秒一发** | 「这一秒有没有新解算」在事件驱动下是竞态（解算正好在定时器边界到达）。无条件发既省掉竞态，又给接收端一条连续的心跳 |
| GGA 使用卫星数去掉 `Math.max(1, …)` | **保留** | 那不是谎报，是防 `GnssStatus` 记账短暂为 0 的防御；报 0 会让第三方工具直接忽略整帧 |
| `nmea/NmeaSentenceType.java` 新增 `GPSSHARE` 枚举值 | **不改这个文件** | `NmeaSentenceType` 是「用户可开关的语句」清单，而 `$GPSSHARE` 必须**永远发送**（它是元数据，不是可选语句）。加进枚举反而会多出一个可以关掉的开关 |
| `util/Prefs.java` 改频率项语义 | **不改这个文件** | 频率偏好的**键和值都没变**，它控制的本来就是 `requestLocationUpdates` 的 `minTime`。要改的只是设置页的**文案**（Task 11）与旁边的说明 |

---

## 附二：实施偏离记录（相对本计划）

本计划里的代码片段是**实施前的设想**，与最终代码有出入的地方一并记在这里。
以代码与提交记录为准。

| 计划原文 | 实际实现 | 原因 |
|---|---|---|
| `gpsShare(NmeaInput in, long seq, long ageMillis)`（三参） | `gpsShare(NmeaInput in, long ageMillis)`（两参，序号取 `in.seq`） | 两个真相源会静默分叉，而 SEQ 是接收端的去重键。Task 2 的 review 已定此形 |
| `keepaliveFrame(null, …)` 发一个 `$GPSSHARE` 空壳 | 只发 `$GPZDA` | `AGE=0` 在接收端正是「把时间戳重锚到本机此刻」的信号，会把「从来没有过定位」表达成「一个 0 毫秒前刚解算出来的定位」；`SEQ=0` 还会把去重计数器倒回 0 |
| 精度取整用 `Math.round(value * 10f) / 10f` | 先判非有限，再用 `double` 取整 | `float` 的 `value*10` 会先溢出成 Infinity，`Math.round` 再饱和成 `Integer.MAX_VALUE`，印出「214748364.7」——比真值小了四个数量级 |
| `InjectionPolicy.Decision` 只有 5 档 | 增加 `SATELLITES_ONLY` | 卫星快照与位置快照是**同一个解算**，身份相同。若让卫星快照先占住身份，随后到达的位置快照会被判成重复 —— 用户关掉 RMC 语句时位置将永远注入不了 |
| 越界校验只查纬度/经度/精度/年龄 | 另外把**精度为 0** 也算异常 | 没有哪台设备真能精确到 0 米，0 是谎报；但精度**未知**（NaN）不算非法 |
| `payloadOf` 直接算 `nowElapsed − age` | 拿不到本机 uptime 时留 0 | 负的 uptime 会让平台算出的年龄变成负数，比不设还糟 |
| Task 4：`toInput` 直接读 Android `Location` | 元信息映射抽成纯逻辑的 `applyFixMetadata` | 单测里的 `android.location.Location` 是空壳桩，`getTime()` 恒为 0，直接断言等于什么也没测 |
| Task 5/6 分两个任务 | 合并为一个提交 | 新引擎的公开 API 一变，`GpsOutputService` 的调用点就编译不过；拆成两个提交只能造出一个「队列化但仍定频 tick」的半成品状态 |
| Task 10：过期秒数靠界面刷新时顺带更新 | 加 1 秒定时器单独刷这张卡 | 界面平时只在收到新数据时才重画，数据断流时恰好不刷新，过期秒数会永远停在那一帧上 |
| Task 11：实际速率用「1000 ÷ 距上次到达」 | 用 `FixRateMeter` 的滑动窗口测量 | 前者在两次定位之间从 0 跳到 1 秒再跳回来，数字一直在跳；窗口法是真实速率 |
| Task 11：实际速率在服务里算 | 同左，但测量器独立成可单测的纯逻辑类 | 与 `InjectionPolicy` 同一原则：能单测的逻辑不要埋在 Service 里 |

### 独立审查后的修正（第二轮）

独立审查 Agent 判定第一轮实现为 FAIL，报了 4 条重要 + 3 条次要问题，均已确认成立并修掉：

| 问题 | 修法 | 原因 |
|---|---|---|
| **保活帧时接收端计数整个冻住**（只有保活帧时解析器一个快照都不发布，计数没有通路） | 字节数与语句数改在**传输层**累计：新增 `NmeaLineReader`（按行读 + 数线上真实字节）与 `NmeaStreamPump`（TCP / 蓝牙共用的读流骨架），通过 `ReceiveLink.Callback.onProgress` 独立上报 | README 明确承诺「链路看着连着但这个数不动 = 假连接」。计数跟着快照走就分不清「发送端没定位」和「链路断了」 |
| **重连后两端口径对不上**（接收端归零、发送端不归零） | 接收端计数在**客户端生命周期内累加**（跨重连不清零），只在新建流水线时归零；发送端同理从引擎创建那刻起算 | 两边都从各自服务启动那刻开始数，重连不打断 |
| **v2 发送端说「精度未知」，接收端却用 HDOP×5 编出 5 m 精度** | 解析器新增 `senderSendsShare`：连接里出现过 `$GPSSHARE` 就只认 `ACC` 键，缺键即「未知」，不再退回 HDOP×5 | HDOP 是几何精度因子，除以 5 只是历史换算（上限 49.5 m），编一个出来是在谎报 |
| **补发位置的时间戳比真实新了 1 秒**（关掉 RMC 时 AGE 还是上一帧的值） | 补发时把「在解析器里等了多久」加进年龄；解析器注入可测的 `NmeaParser.Clock` | 设计 §5.2 要求「三处年龄完全一致」；不补的话位置明明旧了一秒却报成「刚刚解算」 |
| 实际速率喂的是解算时刻 | 改喂**到达时刻** | 网络兜底时解算时刻可能来自几分钟前的缓存，拿它算速率会偏小 |
| 稳态下两端语句数差「帧尾尾巴」 | 随传输层计数一并解决 | 传输层数的是**读到的行**，帧尾那句也数；解析器只数「发布过快照的句子」，天然少一条 |
| 关掉 GGA 且精度未知时注入的 `Location` 精度为默认 0.0 m | **不改** | 与设计 §5.4「有才设」一致；不设即 Android 语义上的「未设置」，比填 0 更诚实 |

### 与计划相关的结论修正

- 设计文档 §9 原本写「2.0.0 接收端对 1.6.2 的数据会注入两次」。实施后不成立：
  位置发布点改到 RMC、删掉了 GGA 处的 leftover、GSV 的卫星快照不再触发注入，
  现在**每周期只注入一次**。已在设计文档中就地标注修正。
- 单元测试从 119 个增加到 **201** 个（新增 `$GPSSHARE` 协议、注入判据、
  时间重锚、速率测量、传输层计数等用例）。
