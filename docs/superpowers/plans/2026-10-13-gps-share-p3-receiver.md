# GPS共享 特别版 — P3：接收端 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让没有 GPS 的平板 / 车机装同一个 APK 后能当**接收端**：连上手机（自动发现或手输 IP），解析 TCP 上的 NMEA，通过系统「模拟位置」注入定位，导航 App 直接可用。

**Architecture:** 新增 `rx` 包：`NmeaParser`（纯逻辑，与 `NmeaGenerator` 互为逆运算）、`NmeaClient`（TCP 客户端 + 断线重连）、`MockLocationInjector`（把定位写进 `LocationManager.GPS_PROVIDER` 的测试提供者）；`net/DiscoveryListener` 监听 UDP 信标维护服务器列表；`service/ReceiveService` 把三者串起来。发送端与接收端是两条互斥的流水线，同一个 `TransmitterState` 状态中心供两者写入。

**Tech Stack:** Java 17、Android Gradle Plugin 8.7.3、Gradle 8.12、JUnit 4、Material Components 3（XML 布局）

---

## 本计划的范围

四阶段中的第三阶段（P1、P2 已完成）：

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 骨架 + 传输层抽象 | 完成，分支 `p1-link-abstraction` |
| P2 | WiFi 发送端（多接收端 + 服务发现） | 完成，分支 `p2-wifi-sender` |
| **P3** | **接收端（NMEA 解析 + 模拟位置注入）** | **本文档** |
| P4 | 打磨（文档、截图） | 待写 |

**P3 的完成判据**：把 APK 装到没有 GPS 的平板 / 车机上，选「接收 GPS」，自动发现或手输手机 IP 后连上，**系统定位被接管**——打开任意导航 App 能正常定位、跟着手机走。

**分支**：从 `p2-wifi-sender` 开出 `p3-receiver`。

---

## ⚠️ 本阶段的前置条件（必须在真机上先确认）

**接收端设备必须能在「开发者选项 → 选择模拟位置信息应用」里把本应用选上。**

- 进不去开发者选项的定制车机，这条路走不通。
- 应用会检测并给出明确指引，但这道系统关卡绕不过去。

**建议在动手前先在目标设备上确认这一点**；进不去的话，本阶段的接收端对那台设备不成立。

---

## 已知限制（会写进文档）

**注入不了卫星星图。** Android 的 mock provider 不支持 `GnssStatus`，只能注入单点定位。因此：

- 导航 App 里**定位本身正常**，但**卫星数可能显示 0 / 星图空白**；
- 本应用自己的「卫星」页在接收端仍能正常显示——数据来自解析的 `GSV` 语句，不依赖系统 mock provider。

---

## 前置条件

- 工程根目录：`工作区根目录\GPS共享`
- 所有命令在该目录下执行；Gradle 统一带 `JAVA_HOME`：

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew <任务>
```

- `local.properties` 必须含 `build.dir=C\:/build`（已配好，别动）
- **基线状态**：P2 完成后 77 个单测全过、`assembleDebug` / `assembleRelease` 正常

---

## 文件结构

| 动作 | 路径 | 职责 |
|---|---|---|
| Create | `app/src/main/java/com/gpsbt/transfer/rx/NmeaParser.java` | NMEA 语句 → 定位快照（纯逻辑，可单测） |
| Create | `app/src/main/java/com/gpsbt/transfer/rx/NmeaClient.java` | TCP 客户端：连服务器、按行解析、断线重连 |
| Create | `app/src/main/java/com/gpsbt/transfer/rx/MockLocationInjector.java` | 把定位注入 `LocationManager.GPS_PROVIDER` |
| Create | `app/src/main/java/com/gpsbt/transfer/net/DiscoveryListener.java` | 监听 UDP 信标，维护服务器列表 |
| Create | `app/src/main/java/com/gpsbt/transfer/service/ReceiveService.java` | 接收端前台服务 |
| Create | `app/src/main/java/com/gpsbt/transfer/link/Role.java` | 角色枚举：共享 / 接收 |
| Create | `app/src/main/java/com/gpsbt/transfer/ui/adapter/ServerAdapter.java` | 发现的服务器列表适配器 |
| Create | `app/src/main/res/layout/item_server.xml` | 服务器列表项 |
| Create | `app/src/test/java/com/gpsbt/transfer/rx/NmeaParserTest.java` | — |
| Create | `app/src/test/java/com/gpsbt/transfer/net/DiscoveryListenerTest.java` | 只测可单测的部分（过期剔除逻辑） |
| Modify | `app/src/main/java/com/gpsbt/transfer/util/Prefs.java` | 角色、上次连接的服务器 |
| Modify | `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java` | 角色切换 + 接收面板 |
| Modify | `app/src/main/res/layout/fragment_home.xml` | 新增视图 |
| Modify | `app/src/main/res/values/strings.xml` | 新增文案 |
| Modify | `app/src/main/AndroidManifest.xml` | 接收服务与前台服务类型 |
| Modify | `app/src/main/java/com/gpsbt/transfer/MainActivity.java` | 角色相关的权限入口（若需要） |

---

## Task 1: `rx/NmeaParser` 语句解析

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/rx/NmeaParser.java`
- Test: `app/src/test/java/com/gpsbt/transfer/rx/NmeaParserTest.java`

这是本阶段最核心的可单测逻辑。**最有价值的测试是往返测试**：用现成的 `NmeaGenerator` 生成语句，喂给 `NmeaParser` 解析，比对字段是否还原——生成器与解析器互为逆运算，一个测试同时锁住两边。

### Step 1: 写失败的测试

创建 `app/src/test/java/com/gpsbt/transfer/rx/NmeaParserTest.java`：

```java
package com.gpsbt.transfer.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import org.junit.Before;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

public class NmeaParserTest {

    private static final double LAT = 31.230416d;
    private static final double LON = 121.473701d;
    private static final long UTC = 1695512345000L;

    private NmeaParser parser;
    private NmeaGenerator generator;

    @Before
    public void setUp() {
        parser = new NmeaParser();
        generator = new NmeaGenerator(1.0f);
    }

    private static NmeaInput sample() {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = UTC;
        in.latitude = LAT;
        in.longitude = LON;
        in.altitudeM = 12.5d;
        in.speedMps = 10.0d;
        in.bearingDeg = 87.0d;
        in.satellitesUsed = 7;
        in.addSat(new NmeaInput.Sat(3, 45, 120, 42, true, "GP"));
        in.addSat(new NmeaInput.Sat(14, 12, 300, 28, true, "GP"));
        in.addSat(new NmeaInput.Sat(22, 70, 40, 0, false, "GP"));
        return in;
    }

    /** 生成器与解析器互为逆运算：一整轮语句喂进去，字段应当还原。 */
    @Test
    public void roundTripThroughGenerator() {
        NmeaInput source = sample();
        Set<NmeaSentenceType> all = EnumSet.allOf(NmeaSentenceType.class);
        String frame = generator.buildFrame(source, all);

        NmeaInput parsed = null;
        // 生成器的顺序是 GGA → RMC → GSA → GSV → VTG：卫星语句在位置语句**之后**，
        // 所以第一轮发布的快照还没有卫星。喂两轮，第二轮的位置快照就带上了
        // 第一轮攒下的卫星——这正是真实接收端的稳态行为（卫星滞后一轮）。
        for (int cycle = 0; cycle < 2; cycle++) {
            for (String line : frame.split("\r\n")) {
                NmeaInput snapshot = parser.accept(line);
                if (snapshot != null) {
                    parsed = snapshot;
                }
            }
        }

        assertNotNull(parsed);
        assertTrue(parsed.valid);
        assertEquals(LAT, parsed.latitude, 1e-4);
        assertEquals(LON, parsed.longitude, 1e-4);
        assertEquals(12.5d, parsed.altitudeM, 0.1d);
        // 速度以节往返，1 节 ≈ 0.5144 m/s，容差取 0.1 m/s
        assertEquals(10.0d, parsed.speedMps, 0.1d);
        assertEquals(87.0d, parsed.bearingDeg, 0.1d);
        assertEquals(7, parsed.satellitesUsed);
        assertEquals(3, parsed.satellitesInView());
    }

    @Test
    public void gsvFeedsSatelliteListWithTalker() {
        NmeaInput source = sample();
        String frame = generator.buildFrame(source, EnumSet.of(NmeaSentenceType.GSV));

        for (String line : frame.split("\r\n")) {
            parser.accept(line);
        }

        NmeaInput parsed = parser.accept(generator.gga(source));
        assertNotNull(parsed);
        assertEquals(3, parsed.satellitesInView());
        assertEquals(3, parsed.satellites().get(0).prn);
        assertEquals(42, parsed.satellites().get(0).snr);
        assertEquals("GP", parsed.satellites().get(0).talker);
    }

    @Test
    public void gsaMarksUsedSatellites() {
        NmeaInput source = sample();
        for (String line : generator.gsa(source).split("\r\n")) {
            parser.accept(line);
        }
        for (String line : generator.gsv(source)) {
            parser.accept(line);
        }

        NmeaInput parsed = parser.accept(generator.gga(source));
        assertNotNull(parsed);
        // GSA 列出的 PRN 标记为已用于定位
        assertEquals(2, countUsed(parsed));
    }

    private static int countUsed(NmeaInput input) {
        int used = 0;
        for (NmeaInput.Sat sat : input.satellites()) {
            if (sat.used) {
                used++;
            }
        }
        return used;
    }

    @Test
    public void southAndWestHemispheresAreNegative() {
        NmeaInput source = new NmeaInput();
        source.valid = true;
        source.utcMillis = UTC;
        source.latitude = -33.8688d;
        source.longitude = -151.2093d;
        source.satellitesUsed = 4;

        parser.accept(generator.gga(source));
        NmeaInput parsed = parser.accept(generator.rmc(source));

        assertNotNull(parsed);
        assertEquals(-33.8688d, parsed.latitude, 1e-4);
        assertEquals(-151.2093d, parsed.longitude, 1e-4);
    }

    @Test
    public void invalidFixIsReportedAsInvalid() {
        NmeaInput source = new NmeaInput();
        source.valid = false;
        source.utcMillis = UTC;

        NmeaInput parsed = parser.accept(generator.gga(source));

        assertNotNull(parsed);
        assertTrue(!parsed.valid);
    }

    @Test
    public void badChecksumIsRejected() {
        String good = generator.gga(sample());
        String broken = good.substring(0, good.length() - 5) + "00\r\n";

        assertNull(parser.accept(broken));
    }

    @Test
    public void unrecognisedAndEmptyLinesAreIgnored() {
        assertNull(parser.accept(""));
        assertNull(parser.accept("   "));
        assertNull(parser.accept("不是 NMEA"));
        assertNull(parser.accept(null));
        assertNull(parser.accept("$GPVTG,87.0,T,,10.0,N,18.5,K,A*05"));
    }

    @Test
    public void resetClearsAccumulatedState() {
        parser.accept(generator.gga(sample()));
        parser.reset();

        NmeaInput parsed = parser.accept(generator.gga(new NmeaInput()));
        assertNotNull(parsed);
        assertTrue(!parsed.valid);
        assertEquals(0, parsed.satellitesInView());
    }

    @Test
    public void gsvSecondCycleDoesNotAccumulateForever() {
        NmeaInput source = sample();
        for (int i = 0; i < 3; i++) {
            for (String line : generator.gsv(source)) {
                parser.accept(line);
            }
        }

        NmeaInput parsed = parser.accept(generator.gga(source));
        assertNotNull(parsed);
        // 每轮 GSV 的第一页会清空上一轮，不能累积成 9 颗
        assertEquals(3, parsed.satellitesInView());
    }
}
```

### Step 2: 跑测试，确认失败

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.rx.NmeaParserTest"
```

预期：编译失败，报找不到 `NmeaParser`。

### Step 3: 写实现

创建 `app/src/main/java/com/gpsbt/transfer/rx/NmeaParser.java`：

```java
package com.gpsbt.transfer.rx;

import com.gpsbt.transfer.nmea.NmeaChecksum;
import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * NMEA 语句解析器：把一行行语句还原成定位快照，与 {@link NmeaGenerator} 互为逆运算。
 *
 * 纯逻辑、无 Android 依赖，可单测。
 *
 * 多句合并：GGA 给位置 / 海拔 / 使用卫星数，RMC 给速度 / 航向，GSV 给可见卫星，
 * GSA 给「哪几颗用于定位」。这些语句在一个定位周期里先后到达，因此解析器**累积**
 * 状态，只在收到 GGA 或 RMC（两句都带位置与有效性）时对外发布快照。
 * 同一周期发布两次无害——注入方只是把同一个位置再写一遍。
 */
public final class NmeaParser {

    private static final double KNOTS_TO_MS = 1.0d / NmeaGenerator.MS_TO_KNOTS;
    private static final int MIN_SENTENCE_FIELD_LENGTH = 5;

    /** 卫星累积项。{@link NmeaInput.Sat} 的 used 是 final，所以先在可变结构里攒，发布时再构造。 */
    private static final class SatEntry {
        final int prn;
        final int elevation;
        final int azimuth;
        final int snr;
        final String talker;
        boolean used;

        SatEntry(int prn, int elevation, int azimuth, int snr, String talker) {
            this.prn = prn;
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.snr = snr;
            this.talker = talker;
        }
    }

    private final NmeaInput state = new NmeaInput();
    private final List<SatEntry> entries = new ArrayList<>();
    private final Set<Integer> usedPrns = new HashSet<>();

    /** 清空累积状态（断线重连时调用）。 */
    public void reset() {
        state.valid = false;
        state.utcMillis = 0L;
        state.latitude = 0d;
        state.longitude = 0d;
        state.altitudeM = 0d;
        state.speedMps = 0d;
        state.bearingDeg = 0d;
        state.accuracyM = Float.NaN;
        state.satellitesUsed = 0;
        state.clearSats();
        entries.clear();
        usedPrns.clear();
    }

    /**
     * 喂入一行。
     *
     * @return 收到 GGA / RMC 时返回累积出的快照副本；其余情况（GSV / GSA / VTG / ZDA、
     *         校验和错误、无法识别、空行、null）返回 null。
     */
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
        // 形如 GPGGA / BDGSV：末三位是语句类型
        String type = f[0].substring(f[0].length() - 3).toUpperCase(Locale.US);
        if ("GGA".equals(type)) {
            return applyGga(f) ? publish() : null;
        }
        if ("RMC".equals(type)) {
            return applyRmc(f) ? publish() : null;
        }
        if ("GSV".equals(type)) {
            applyGsv(f);
            return null;
        }
        if ("GSA".equals(type)) {
            applyGsa(f);
            return null;
        }
        return null;
    }

    /** 去掉 $ 前缀并校验 *CS；校验失败或格式不符返回 null。 */
    private static String verifiedBody(String sentence) {
        int star = sentence.indexOf('*');
        if (star <= 1) {
            return null;
        }
        String body = sentence.substring(1, star);
        String given = sentence.substring(star + 1).trim();
        if (given.length() < 2) {
            return null;
        }
        String expected = NmeaChecksum.toHex(NmeaChecksum.xor(body));
        return expected.equalsIgnoreCase(given.substring(0, 2)) ? body : null;
    }

    /** 把累积状态与卫星列表打包成一份快照。 */
    private NmeaInput publish() {
        state.clearSats();
        for (SatEntry entry : entries) {
            state.addSat(new NmeaInput.Sat(entry.prn, entry.elevation, entry.azimuth,
                    entry.snr, entry.used, entry.talker));
        }
        return state.copy();
    }

    private boolean applyGga(String[] f) {
        if (f.length < 10) {
            return false;
        }
        state.valid = parseInt(f[6], 0) > 0;
        long time = parseUtcTime(f[1]);
        if (time > 0) {
            state.utcMillis = time;
        }
        double lat = parseCoordinate(f[2], f[3], false);
        double lon = parseCoordinate(f[4], f[5], true);
        if (!Double.isNaN(lat)) {
            state.latitude = lat;
        }
        if (!Double.isNaN(lon)) {
            state.longitude = lon;
        }
        state.satellitesUsed = Math.max(0, parseInt(f[7], 0));
        state.altitudeM = parseDouble(f[9], 0d);
        double hdop = parseDouble(f[8], Double.NaN);
        // 与生成器对称：hdop = accuracy / 5
        state.accuracyM = Double.isNaN(hdop) ? Float.NaN : (float) (hdop * 5.0d);
        return true;
    }

    private boolean applyRmc(String[] f) {
        if (f.length < 10) {
            return false;
        }
        boolean active = "A".equalsIgnoreCase(f[2]);
        state.valid = active;
        long time = parseUtcTime(f[1]);
        long date = parseUtcDate(f[9]);
        if (time > 0 && date > 0) {
            state.utcMillis = date + time;
        } else if (time > 0) {
            state.utcMillis = time;
        }
        double lat = parseCoordinate(f[3], f[4], false);
        double lon = parseCoordinate(f[5], f[6], true);
        if (!Double.isNaN(lat)) {
            state.latitude = lat;
        }
        if (!Double.isNaN(lon)) {
            state.longitude = lon;
        }
        double knots = parseDouble(f[7], 0d);
        state.speedMps = active ? Math.max(0d, knots * KNOTS_TO_MS) : 0d;
        double course = parseDouble(f[8], 0d);
        state.bearingDeg = active ? normalize360(course) : 0d;
        return true;
    }

    private void applyGsv(String[] f) {
        if (f.length < 4) {
            return;
        }
        int page = parseInt(f[2], 1);
        if (page <= 1) {
            // 新的一轮开始，丢掉上一轮的卫星，否则会越攒越多
            entries.clear();
        }
        String talker = f[0].length() >= 2 ? f[0].substring(0, 2) : null;
        for (int i = 4; i + 3 < f.length; i += 4) {
            int prn = parseInt(f[i], -1);
            if (prn <= 0) {
                continue;
            }
            SatEntry entry = new SatEntry(prn, parseInt(f[i + 1], 0),
                    parseInt(f[i + 2], 0), parseInt(f[i + 3], 0), talker);
            // GSA 可能先于 GSV 到达，用记住的「已用于定位」集合补上标记
            entry.used = usedPrns.contains(prn);
            entries.add(entry);
        }
    }

    private void applyGsa(String[] f) {
        if (f.length < 3) {
            return;
        }
        usedPrns.clear();
        // GSA 的 12 个 PRN 槽位在 f[3]..f[14]
        for (int i = 3; i < f.length && i <= 14; i++) {
            int prn = parseInt(f[i], -1);
            if (prn > 0) {
                usedPrns.add(prn);
            }
        }
        for (SatEntry entry : entries) {
            entry.used = usedPrns.contains(entry.prn);
        }
    }

    /** ddmm.mmmm + 半球字母 → 十进制度；半球缺失时按正数处理。 */
    private static double parseCoordinate(String value, String hemisphere, boolean longitude) {
        if (value == null || value.trim().isEmpty()) {
            return Double.NaN;
        }
        double raw;
        try {
            raw = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        // 整数部分就是度：纬度 2 位、经度 3 位
        double degrees = Math.floor(raw / 100.0d);
        double minutes = raw - degrees * 100.0d;
        double result = degrees + minutes / 60.0d;
        if (hemisphere != null) {
            String h = hemisphere.trim().toUpperCase(Locale.US);
            if ("S".equals(h) || "W".equals(h)) {
                result = -result;
            }
        }
        return result;
    }

    /** HHmmss.SS → UTC 当日毫秒数；无法解析返回 0。 */
    private static long parseUtcTime(String value) {
        if (value == null || value.length() < 6) {
            return 0L;
        }
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(2, 4));
            int second = Integer.parseInt(value.substring(4, 6));
            long millis = (long) hour * 3600_000L + (long) minute * 60_000L + second * 1000L;
            if (value.length() > 7) {
                double fraction = Double.parseDouble("0" + value.substring(6));
                millis += Math.round(fraction * 1000.0d);
            }
            return millis;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** ddMMyy → UTC 当日零点毫秒时间戳；无法解析返回 0。 */
    private static long parseUtcDate(String value) {
        if (value == null || value.length() < 6) {
            return 0L;
        }
        try {
            int day = Integer.parseInt(value.substring(0, 2));
            int month = Integer.parseInt(value.substring(2, 4));
            int year = 2000 + Integer.parseInt(value.substring(4, 6));
            java.util.Calendar calendar = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"), Locale.US);
            calendar.clear();
            calendar.set(year, month - 1, day, 0, 0, 0);
            return calendar.getTimeInMillis();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double normalize360(double value) {
        double v = value % 360.0d;
        return v < 0 ? v + 360.0d : v;
    }
}
```

### Step 4: 跑测试，确认通过

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.rx.NmeaParserTest"
```

预期：`BUILD SUCCESSFUL`，9 个用例通过。

> 若往返测试的坐标精度不达标，先检查 `parseCoordinate` 的度分拆分是否与 `NmeaGenerator.latitude/longitude` 的 `%02d%07.4f` 对称——两者必须严格互逆。

### Step 5: 提交

```bash
git add app/src/main/java/com/gpsbt/transfer/rx/NmeaParser.java app/src/test/java/com/gpsbt/transfer/rx/NmeaParserTest.java
git commit -m "feat: 新增 rx/NmeaParser 语句解析

与 NmeaGenerator 互为逆运算，往返测试锁住两边。"
```

---

## Task 2: `link/Role` 枚举与 `Prefs` 扩展

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/link/Role.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/util/Prefs.java`

- [ ] **Step 1: 创建 `Role` 枚举**

创建 `app/src/main/java/com/gpsbt/transfer/link/Role.java`：

```java
package com.gpsbt.transfer.link;

/** 本机在这套系统里扮演的角色。用户偏好，持久化在 `util.Prefs`。 */
public enum Role {
    /** 共享本机 GPS：蓝牙 / WiFi 两种传输方式。 */
    SHARE,
    /** 接收别处共享来的 GPS：连上发送端并注入系统定位。 */
    RECEIVE
}
```

- [ ] **Step 2: `Prefs` 增加角色与上次连接的服务器**

在 `app/src/main/java/com/gpsbt/transfer/util/Prefs.java` 中：

**2a.** import 区加上：

```java
import com.gpsbt.transfer.link.Role;
```

**2b.** 常量区加上：

```java
    /** 接收端默认连接端口，与发送端默认值一致。 */
    public static final int DEFAULT_SERVER_PORT = DEFAULT_WIFI_PORT;
```

**2c.** 键名区加上：

```java
    private static final String KEY_ROLE = "role";
    private static final String KEY_LAST_SERVER_HOST = "last_server_host";
    private static final String KEY_LAST_SERVER_PORT = "last_server_port";
```

**2d.** 在 `setDiscoveryPort` 之后插入：

```java
    public Role getRole() {
        String name = prefs.getString(KEY_ROLE, Role.SHARE.name());
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Role.SHARE;
        }
    }

    public void setRole(Role value) {
        prefs.edit().putString(KEY_ROLE, (value == null ? Role.SHARE : value).name()).apply();
    }

    /** 上次连接的接收端服务器地址；没有则返回空串。 */
    public String getLastServerHost() {
        String host = prefs.getString(KEY_LAST_SERVER_HOST, "");
        return host == null ? "" : host.trim();
    }

    public void setLastServerHost(String value) {
        prefs.edit().putString(KEY_LAST_SERVER_HOST, value == null ? "" : value.trim()).apply();
    }

    public int getLastServerPort() {
        return clampInt(MIN_PORT, MAX_PORT,
                prefs.getInt(KEY_LAST_SERVER_PORT, DEFAULT_SERVER_PORT));
    }

    public void setLastServerPort(int value) {
        prefs.edit().putInt(KEY_LAST_SERVER_PORT, clampInt(MIN_PORT, MAX_PORT, value)).apply();
    }
```

- [ ] **Step 3: 编译**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/link/Role.java app/src/main/java/com/gpsbt/transfer/util/Prefs.java
git commit -m "feat: 新增 Role 枚举与接收端设置项"
```

---

## Task 3: `net/DiscoveryListener` 服务器发现

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/net/DiscoveryListener.java`
- Test: `app/src/test/java/com/gpsbt/transfer/net/ServerTableTest.java`

发现逻辑里唯一值得单测的是**服务器列表的过期剔除**——把它抽成纯逻辑类 `ServerTable`。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/gpsbt/transfer/net/ServerTableTest.java`：

```java
package com.gpsbt.transfer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class ServerTableTest {

    private ServerTable table;

    @Before
    public void setUp() {
        table = new ServerTable(3000L);
    }

    @Test
    public void beaconUpsertsByHostAndPort() {
        table.upsert("192.168.43.1", 10110, "车机的手机", 1000L);
        table.upsert("192.168.43.1", 10110, "车机的手机", 2000L);

        assertEquals(1, table.size());
        assertEquals(2000L, table.snapshot(2000L).get(0).lastSeenAt);
    }

    @Test
    public void differentPortsAreDifferentServers() {
        table.upsert("192.168.43.1", 10110, "A", 1000L);
        table.upsert("192.168.43.1", 10111, "B", 1000L);

        assertEquals(2, table.size());
    }

    @Test
    public void staleEntriesAreDropped() {
        table.upsert("192.168.43.1", 10110, "A", 1000L);
        table.upsert("192.168.43.2", 10110, "B", 5000L);

        List<ServerTable.Server> alive = table.snapshot(7000L);

        // 3 秒没再收到信标就认为对方下线
        assertEquals(1, alive.size());
        assertEquals("192.168.43.2", alive.get(0).host);
    }

    @Test
    public void everythingExpiresWhenSilent() {
        table.upsert("192.168.43.1", 10110, "A", 1000L);

        assertTrue(table.snapshot(6000L).isEmpty());
    }

    @Test
    public void snapshotIsSortedByHostThenPort() {
        table.upsert("192.168.43.2", 10110, "B", 1000L);
        table.upsert("192.168.43.1", 10111, "A2", 1000L);
        table.upsert("192.168.43.1", 10110, "A1", 1000L);

        List<ServerTable.Server> list = table.snapshot(1000L);

        assertEquals("192.168.43.1", list.get(0).host);
        assertEquals(10110, list.get(0).port);
        assertEquals(10111, list.get(1).port);
        assertEquals("192.168.43.2", list.get(2).host);
    }

    @Test
    public void emptyHostOrBadPortIsIgnored() {
        table.upsert("", 10110, "A", 1000L);
        table.upsert("192.168.43.1", 0, "A", 1000L);
        table.upsert(null, 10110, "A", 1000L);

        assertEquals(0, table.size());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.net.ServerTableTest"
```

预期：编译失败，报找不到 `ServerTable`。

- [ ] **Step 3: 写 `ServerTable`**

创建 `app/src/main/java/com/gpsbt/transfer/net/ServerTable.java`：

```java
package com.gpsbt.transfer.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发现到的服务器表：按 host:port 去重，超过存活期没再收到信标就剔除。
 * 纯逻辑、无 Android 依赖，可单测。
 */
public final class ServerTable {

    /** 一台发现到的服务器。 */
    public static final class Server {
        public final String host;
        public final int port;
        public final String name;
        public final long lastSeenAt;

        Server(String host, int port, String name, long lastSeenAt) {
            this.host = host;
            this.port = port;
            this.name = name == null ? "" : name;
            this.lastSeenAt = lastSeenAt;
        }

        public String key() {
            return host + ":" + port;
        }
    }

    private final long ttlMillis;
    private final Map<String, Server> servers = new LinkedHashMap<>();

    public ServerTable(long ttlMillis) {
        this.ttlMillis = Math.max(1L, ttlMillis);
    }

    /** 记下一条信标；同一 host:port 覆盖更新时间。 */
    public void upsert(String host, int port, String name, long now) {
        if (host == null || host.trim().isEmpty() || port <= 0 || port > 65535) {
            return;
        }
        Server server = new Server(host.trim(), port, name, now);
        servers.put(server.key(), server);
    }

    /** 当前仍在线的服务器，按 host 再按 port 排序；同时剔除过期项。 */
    public List<Server> snapshot(long now) {
        List<Server> alive = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        for (Server server : servers.values()) {
            if (now - server.lastSeenAt > ttlMillis) {
                expired.add(server.key());
            } else {
                alive.add(server);
            }
        }
        for (String key : expired) {
            servers.remove(key);
        }
        Collections.sort(alive, new Comparator<Server>() {
            @Override
            public int compare(Server left, Server right) {
                int byHost = left.host.compareTo(right.host);
                return byHost != 0 ? byHost : Integer.compare(left.port, right.port);
            }
        });
        return alive;
    }

    public int size() {
        return servers.size();
    }

    public void clear() {
        servers.clear();
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.net.ServerTableTest"
```

预期：`BUILD SUCCESSFUL`，6 个用例通过。

- [ ] **Step 5: 写 `DiscoveryListener`**

创建 `app/src/main/java/com/gpsbt/transfer/net/DiscoveryListener.java`：

```java
package com.gpsbt.transfer.net;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.state.LogBus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * 监听 UDP 信标，维护「当前有哪些发送端在线」的列表。
 *
 * 列表每 500 毫秒对外回调一次（有变化时才回调），发送端 3 秒没发信标就认为下线。
 */
public final class DiscoveryListener {

    private static final int BUFFER = 512;
    private static final long TTL_MILLIS = 3000L;
    private static final long POLL_MILLIS = 500L;

    /** 列表变化的回调。 */
    public interface Callback {
        void onServers(List<ServerTable.Server> servers);
    }

    private final int port;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ServerTable table = new ServerTable(TTL_MILLIS);

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread receiveThread;
    private Thread pollThread;
    /** 上一次回调的服务器键列表；只在内容变化时回调，避免界面每秒重绘。 */
    private String lastKeys = "";

    public DiscoveryListener(int port, Callback callback) {
        this.port = port;
        this.callback = callback;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        try {
            DatagramSocket created = new DatagramSocket(null);
            created.setReuseAddress(true);
            created.bind(new InetSocketAddress(port));
            socket = created;
        } catch (IOException e) {
            LogBus.get().log(LogBus.Level.WARN, "服务发现监听启动失败：" + e.getMessage());
            running = false;
            return;
        }
        receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "discovery-rx");
        receiveThread.start();
        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, "discovery-poll");
        pollThread.start();
        LogBus.get().log(LogBus.Level.INFO, "开始搜索发送端（端口 " + port + "）");
    }

    /** 幂等。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[BUFFER];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running) {
                    LogBus.get().log(LogBus.Level.WARN, "服务发现监听中断：" + e.getMessage());
                }
                return;
            }
            DiscoveryBeacon.Info info = DiscoveryBeacon.decode(packet.getData(), packet.getLength());
            if (info == null || packet.getAddress() == null) {
                continue;
            }
            table.upsert(packet.getAddress().getHostAddress(), info.tcpPort, info.name,
                    System.currentTimeMillis());
        }
    }

    private void pollLoop() {
        while (running) {
            final List<ServerTable.Server> servers = table.snapshot(System.currentTimeMillis());
            // 比对键列表而不是数量：一台下线另一台上线时数量不变，
            // 只看数量会漏掉这次变化
            StringBuilder keys = new StringBuilder();
            for (ServerTable.Server server : servers) {
                keys.append(server.key()).append(';');
            }
            if (!keys.toString().equals(lastKeys)) {
                lastKeys = keys.toString();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running) {
                            callback.onServers(servers);
                        }
                    }
                });
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
```

> 说明：只在**服务器集合变化**时回调，避免界面每秒重绘。同一台换了名字不重绘——本阶段可接受。

- [ ] **Step 6: 编译并跑全部单测**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，**92 个用例**（77 + NmeaParser 9 + ServerTable 6）全过。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat: 新增服务发现监听与服务器表

ServerTable 是可单测的过期剔除逻辑；DiscoveryListener 负责 UDP 接收。"
```

---

## Task 4: `rx/NmeaClient` 与 `rx/MockLocationInjector`

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/rx/NmeaClient.java`
- Create: `app/src/main/java/com/gpsbt/transfer/rx/MockLocationInjector.java`

两者都涉及真实系统 API，**不写单测**（与原版对 socket / Android 框架层的处理一致），靠编译 + 真机验证。

- [ ] **Step 1: 写 `NmeaClient`**

创建 `app/src/main/java/com/gpsbt/transfer/rx/NmeaClient.java`：

```java
package com.gpsbt.transfer.rx;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.state.LogBus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 接收端 TCP 客户端：连上发送端，按行读 NMEA，解析后回调。
 *
 * 断线后每 5 秒自动重连（与发送端蓝牙客户端的重连节奏一致）。
 */
public final class NmeaClient {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final long RECONNECT_DELAY_MILLIS = 5000L;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    /** 回调一律在主线程。 */
    public interface Callback {
        /** 连上 / 断开（connected 为 false 时 message 说明原因）。 */
        void onConnectionChanged(boolean connected, String message);

        /** 解析出一条定位。 */
        void onLocation(NmeaInput location);
    }

    private final String host;
    private final int port;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NmeaParser parser = new NmeaParser();

    private volatile boolean running;
    private Socket socket;

    public NmeaClient(String host, int port, Callback callback) {
        this.host = host;
        this.port = port;
        this.callback = callback;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                connectLoop();
            }
        }, "nmea-client").start();
    }

    /** 幂等。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        closeSocket();
    }

    private void connectLoop() {
        while (running) {
            try {
                Socket created = new Socket();
                created.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                socket = created;
                parser.reset();
                postConnection(true, "已连接 " + host + ":" + port);
                readLoop(created);
            } catch (IOException e) {
                postConnection(false, "连接失败：" + e.getMessage());
            } finally {
                closeSocket();
            }
            if (!running) {
                return;
            }
            postConnection(false, "连接已断开，5 秒后重连");
            try {
                Thread.sleep(RECONNECT_DELAY_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void readLoop(Socket active) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(active.getInputStream(), ASCII));
        String line;
        while (running && (line = reader.readLine()) != null) {
            final NmeaInput parsed = parser.accept(line);
            if (parsed != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running) {
                            callback.onLocation(parsed);
                        }
                    }
                });
            }
        }
    }

    private void postConnection(final boolean connected, final String message) {
        if (!connected) {
            LogBus.get().log(LogBus.Level.WARN, "接收：" + message);
        } else {
            LogBus.get().log(LogBus.Level.INFO, "接收：" + message);
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onConnectionChanged(connected, message);
            }
        });
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
```

> 注意 `readLoop` 抛出的 `IOException` 由 `connectLoop` 的 catch 处理，会走重连。

- [ ] **Step 2: 写 `MockLocationInjector`**

创建 `app/src/main/java/com/gpsbt/transfer/rx/MockLocationInjector.java`：

```java
package com.gpsbt.transfer.rx;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;

import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.state.LogBus;

/**
 * 把接收到的定位注入系统，让本机看起来「有 GPS」。
 *
 * 走的是 Android 的**测试位置提供者**：必须在
 * 「开发者选项 → 选择模拟位置信息应用」里把本应用选上，否则
 * {@code addTestProvider} 会抛 SecurityException。这是系统关卡，绕不过去。
 *
 * 已知限制：测试提供者**不支持 GnssStatus**，所以只能注入单点定位，
 * 导航 App 里卫星数可能显示为 0。本应用自己的卫星页不受影响——
 * 它读的是解析出来的 GSV，不经过系统。
 */
public final class MockLocationInjector {

    private final Context context;
    private LocationManager manager;
    private boolean installed;

    public MockLocationInjector(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 尝试安装测试提供者。
     *
     * @return true 表示已就绪；false 表示本应用不是当前选定的模拟位置应用
     */
    public boolean start() {
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            LogBus.get().log(LogBus.Level.ERROR, "本机没有定位服务");
            return false;
        }
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            installed = true;
            LogBus.get().log(LogBus.Level.INFO, "模拟位置已就绪");
            return true;
        } catch (SecurityException e) {
            installed = false;
            LogBus.get().log(LogBus.Level.ERROR,
                    "未获得模拟位置授权：请在开发者选项里把本应用设为「模拟位置信息应用」");
            return false;
        } catch (IllegalArgumentException e) {
            // 个别系统上重复 addTestProvider 会抛这个；当作已就绪处理
            installed = true;
            return true;
        }
    }

    /** 卸载测试提供者。幂等。 */
    public void stop() {
        if (!installed || manager == null) {
            return;
        }
        installed = false;
        try {
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
            manager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 已经失效，忽略
        }
    }

    /** 注入一次定位。未就绪或定位无效时什么都不做。 */
    public void inject(NmeaInput input) {
        if (!installed || manager == null || input == null || !input.valid) {
            return;
        }
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(input.latitude);
        location.setLongitude(input.longitude);
        location.setAltitude(input.altitudeM);
        location.setSpeed((float) input.speedMps);
        location.setBearing((float) input.bearingDeg);
        if (!Float.isNaN(input.accuracyM) && input.accuracyM > 0f) {
            location.setAccuracy(input.accuracyM);
        }
        long time = input.utcMillis > 0 ? input.utcMillis : System.currentTimeMillis();
        location.setTime(time);
        // 不设 elapsedRealtimeNanos 会让 setTestProviderLocation 抛 IllegalArgumentException
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.setVerticalAccuracyMeters(5f);
            location.setSpeedAccuracyMetersPerSecond(1f);
            location.setBearingAccuracyDegrees(5f);
        }
        try {
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
        } catch (SecurityException | IllegalArgumentException e) {
            LogBus.get().log(LogBus.Level.WARN, "注入定位失败：" + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: 编译**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 跑全部单测**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，92 个用例全过（本任务未新增测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/rx/
git commit -m "feat: 新增接收端 TCP 客户端与模拟位置注入"
```

---

## Task 5: `service/ReceiveService` 与清单

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/service/ReceiveService.java`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 写 `ReceiveService`**

创建 `app/src/main/java/com/gpsbt/transfer/service/ReceiveService.java`：

```java
package com.gpsbt.transfer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.gpsbt.transfer.MainActivity;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.rx.MockLocationInjector;
import com.gpsbt.transfer.rx.NmeaClient;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;
import com.gpsbt.transfer.util.Formatters;
import com.gpsbt.transfer.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 接收端前台服务：连发送端 → 解析 NMEA → 注入系统定位。
 *
 * 与 {@link GpsOutputService} 是两条互斥的流水线，同一时刻只跑一条。
 */
public class ReceiveService extends Service {

    public static final String ACTION_START = "com.gpsbt.transfer.action.RECEIVE_START";
    public static final String ACTION_STOP = "com.gpsbt.transfer.action.RECEIVE_STOP";
    public static final String EXTRA_HOST = "com.gpsbt.transfer.extra.HOST";
    public static final String EXTRA_PORT = "com.gpsbt.transfer.extra.PORT";

    private static final int NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "gps_share_receive";

    private static volatile boolean running;

    private Prefs prefs;
    private TransmitterState state;
    private NmeaClient client;
    private MockLocationInjector injector;
    private String host;
    private int port;
    private long startedAt;
    /** 已接收的定位条数，供通知展示。发送端用 TransmitStats，接收端自己数。 */
    private volatile long received;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context, String host, int port) {
        Intent intent = new Intent(context, ReceiveService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_HOST, host);
        intent.putExtra(EXTRA_PORT, port);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ReceiveService.class);
        intent.setAction(ACTION_STOP);
        context.getApplicationContext().startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.get(this);
        state = TransmitterState.get();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            LogBus.get().log(LogBus.Level.INFO, "用户停止接收");
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        host = intent.getStringExtra(EXTRA_HOST);
        port = intent.getIntExtra(EXTRA_PORT, prefs.getLastServerPort());
        if (host == null || host.trim().isEmpty()) {
            LogBus.get().log(LogBus.Level.ERROR, "未指定发送端地址");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        try {
            goForeground();
        } catch (SecurityException e) {
            LogBus.get().log(LogBus.Level.ERROR, "前台服务启动被拒绝：" + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        stopExisting();
        running = true;
        startedAt = System.currentTimeMillis();
        state.setPhase(TransmitterState.Phase.RUNNING);
        state.setStatusText("正在连接 " + host + ":" + port);

        injector = new MockLocationInjector(this);
        if (!injector.start()) {
            state.setStatusText(getString(R.string.receive_mock_denied));
        } else {
            state.setStatusText("已就绪，正在连接 " + host + ":" + port);
        }

        client = new NmeaClient(host, port, callback);
        client.start();
        refreshNotification();
        return START_NOT_STICKY;
    }

    private void stopExisting() {
        if (client != null) {
            client.stop();
            client = null;
        }
        if (injector != null) {
            injector.stop();
            injector = null;
        }
    }

    private final NmeaClient.Callback callback = new NmeaClient.Callback() {
        @Override
        public void onConnectionChanged(boolean connected, String message) {
            state.setBt(connected ? TransmitterState.Bt.CONNECTED : TransmitterState.Bt.WAITING);
            state.setStatusText(message);
            if (connected) {
                state.addPeer(new Peer(host + ":" + port, host + ":" + port,
                        System.currentTimeMillis()));
            } else {
                state.setPeers(null);
            }
            refreshNotification();
        }

        @Override
        public void onLocation(NmeaInput location) {
            received++;
            if (injector != null) {
                injector.inject(location);
            }
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
            fix.fixAt = System.currentTimeMillis();
            state.setFix(fix);

            List<SatelliteRadarView.Sat> converted = new ArrayList<SatelliteRadarView.Sat>();
            for (NmeaInput.Sat sat : location.satellites()) {
                converted.add(new SatelliteRadarView.Sat(sat.prn, sat.elevationDeg,
                        sat.azimuthDeg, sat.snr, sat.used));
            }
            state.setSatellites(converted);
            refreshNotification();
        }
    };

    // ---------- 通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_receive_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_receive_desc));
        channel.setShowBadge(false);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void goForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void refreshNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        TransmitterState.Fix fix = state.fix();
        String title = state.bt() == TransmitterState.Bt.CONNECTED
                ? getString(R.string.receive_notif_receiving)
                : getString(R.string.receive_notif_waiting);
        StringBuilder text = new StringBuilder();
        text.append(host == null ? "" : host).append(":").append(port);
        if (fix != null && fix.valid) {
            text.append("　").append(Formatters.latitude(fix.latitude))
                    .append("　").append(Formatters.longitude(fix.longitude));
        }
        text.append("　").append(getString(R.string.receive_notif_stats,
                received,
                Formatters.duration(System.currentTimeMillis() - startedAt)));

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, ReceiveService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 3, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_satellite)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(content)
                .addAction(0, getString(R.string.notif_action_stop), stopAction)
                .build();
    }

    private synchronized void stopSelfSafely() {
        running = false;
        stopExisting();
        state.resetForStop();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSelfSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
```

- [ ] **Step 2: 清单里注册服务与前台服务类型**

在 `app/src/main/AndroidManifest.xml` 中：

**2a.** 权限区加上（`FOREGROUND_SERVICE_LOCATION` 之后）：

```xml
    <uses-permission
        android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"
        android:minSdkVersion="34" />
```

**2b.** 在 `GpsOutputService` 的 `<service>` 之后加上：

```xml
        <!-- 接收端：连发送端并把定位注入系统。前台服务类型 dataSync（不读本机定位）。 -->
        <service
            android:name=".service.ReceiveService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

- [ ] **Step 3: 加文案**

在 `app/src/main/res/values/strings.xml` 的通知块里加上：

```xml
    <string name="channel_receive_name">GPS共享接收</string>
    <string name="channel_receive_desc">显示接收 GPS 数据的运行状态</string>
    <string name="receive_notif_receiving">正在接收 GPS 数据</string>
    <string name="receive_notif_waiting">等待发送端…</string>
    <string name="receive_notif_stats">已接收 %1$d 条 / %2$s</string>
    <string name="receive_mock_denied">未获得模拟位置授权，定位无法注入</string>
```

- [ ] **Step 4: 编译并构建**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

预期：`BUILD SUCCESSFUL`，92 个用例全过，APK 产出。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat: 新增接收端前台服务与清单注册"
```

---

## Task 6: 界面 —— 角色切换与接收面板

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/layout/item_server.xml`
- Create: `app/src/main/java/com/gpsbt/transfer/ui/adapter/ServerAdapter.java`
- Modify: `app/src/main/res/layout/fragment_home.xml`
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java`

- [ ] **Step 1: 加文案**

在 `app/src/main/res/values/strings.xml` 的主页块里加上：

```xml
    <string name="home_role_title">角色</string>
    <string name="home_role_share">共享 GPS</string>
    <string name="home_role_receive">接收 GPS</string>
    <string name="receive_servers_title">发现的发送端</string>
    <string name="receive_servers_empty">正在搜索…请确认发送端已开启 WiFi 传输并打开了个人热点</string>
    <string name="receive_manual_title">手动连接</string>
    <string name="receive_host">地址</string>
    <string name="receive_port">端口</string>
    <string name="receive_connect">连接</string>
    <string name="receive_disconnect">断开</string>
    <string name="receive_mock_title">模拟位置授权</string>
    <string name="receive_mock_body">接收端需要把本应用设为「模拟位置信息应用」：\n\n设置 → 关于手机 → 连点「版本号」7 次开启开发者选项\n开发者选项 → 选择模拟位置信息应用 → 选「GPS共享」\n\n未授权时定位无法注入系统，导航 App 看不到位置。</string>
    <string name="receive_host_required">请先填写发送端地址</string>
    <string name="receive_satellite_note">卫星数据来自接收到的 GSV 语句；系统层面注入不了卫星星图，导航 App 里的卫星数可能显示为 0。</string>
```

- [ ] **Step 2: 建服务器列表项**

创建 `app/src/main/res/layout/item_server.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/ripple_soft"
    android:gravity="center_vertical"
    android:minHeight="52dp"
    android:orientation="horizontal"
    android:paddingHorizontal="12dp"
    android:paddingVertical="8dp">

    <ImageView
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:src="@drawable/ic_stat_satellite"
        app:tint="@color/color_accent_blue" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="10dp"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/server_name"
            style="@style/SettingsRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:maxLines="1" />

        <TextView
            android:id="@+id/server_address"
            style="@style/TextSecondary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:textSize="11sp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 3: 建适配器**

创建 `app/src/main/java/com/gpsbt/transfer/ui/adapter/ServerAdapter.java`：

```java
package com.gpsbt.transfer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.net.ServerTable;

import java.util.ArrayList;
import java.util.List;

/** 发现的发送端列表；点一下即连。 */
public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ServerViewHolder> {

    /** 选中回调。 */
    public interface OnServerSelected {
        void onSelected(ServerTable.Server server);
    }

    private final List<ServerTable.Server> servers = new ArrayList<>();
    private final OnServerSelected listener;

    public ServerAdapter(OnServerSelected listener) {
        this.listener = listener;
    }

    public void submit(List<ServerTable.Server> value) {
        servers.clear();
        if (value != null) {
            servers.addAll(value);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ServerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server, parent, false);
        return new ServerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ServerViewHolder holder, int position) {
        final ServerTable.Server server = servers.get(position);
        holder.name.setText(server.name);
        holder.address.setText(server.key());
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSelected(server);
            }
        });
    }

    @Override
    public int getItemCount() {
        return servers.size();
    }

    static final class ServerViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView address;

        ServerViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.server_name);
            address = itemView.findViewById(R.id.server_address);
        }
    }
}
```

- [ ] **Step 4: 主页布局加角色控件与接收面板**

在 `app/src/main/res/layout/fragment_home.xml` 中：

**4a.** 在「传输方式」那个 `SectionTitle` 之前，插入角色区块：

```xml
        <!-- 角色 -->
        <TextView
            style="@style/SectionTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/home_role_title" />

        <com.google.android.material.button.MaterialButtonToggleGroup
            android:id="@+id/role_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:selectionRequired="true"
            app:singleSelection="true">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/role_share"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/home_role_share"
                android:textColor="@color/mode_button_text"
                app:backgroundTint="@color/mode_button_bg"
                app:cornerRadius="14dp"
                app:strokeColor="@color/mode_button_stroke"
                app:strokeWidth="1dp" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/role_receive"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/home_role_receive"
                android:textColor="@color/mode_button_text"
                app:backgroundTint="@color/mode_button_bg"
                app:cornerRadius="14dp"
                app:strokeColor="@color/mode_button_stroke"
                app:strokeWidth="1dp" />
        </com.google.android.material.button.MaterialButtonToggleGroup>

```

**4b.** 给「传输方式」那个 `SectionTitle` 加 id（便于整块显隐）：

```xml
        <TextView
            android:id="@+id/transport_title"
            style="@style/SectionTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/home_transport_title" />
```

**4c.** 在 `peer_panel` 的结束标签之后、`<!-- 运行数据 -->` 之前，插入接收面板：

```xml
        <!-- 模拟位置授权提示 -->
        <LinearLayout
            android:id="@+id/mock_card"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp"
            android:visibility="gone">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/receive_mock_title" />

            <TextView
                android:id="@+id/mock_body"
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/receive_mock_body"
                android:textSize="12sp" />
        </LinearLayout>

        <!-- 发现的发送端 -->
        <LinearLayout
            android:id="@+id/server_panel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp"
            android:visibility="gone">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/receive_servers_title" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/server_list"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp" />

            <TextView
                android:id="@+id/server_empty"
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="@string/receive_servers_empty"
                android:textSize="12sp" />
        </LinearLayout>

        <!-- 手动连接 -->
        <LinearLayout
            android:id="@+id/manual_panel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp"
            android:visibility="gone">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/receive_manual_title" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:orientation="horizontal">

                <com.google.android.material.textfield.TextInputLayout
                    style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="2"
                    android:hint="@string/receive_host">

                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/input_host"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="text"
                        android:maxLines="1"
                        android:textColor="@color/color_text_primary" />
                </com.google.android.material.textfield.TextInputLayout>

                <com.google.android.material.textfield.TextInputLayout
                    style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:layout_weight="1"
                    android:hint="@string/receive_port">

                    <com.google.android.material.textfield.TextInputEditText
                        android:id="@+id/input_port"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:inputType="number"
                        android:maxLines="1"
                        android:textColor="@color/color_text_primary" />
                </com.google.android.material.textfield.TextInputLayout>
            </LinearLayout>
        </LinearLayout>

```

- [ ] **Step 5: 改 `HomeFragment`**

在 `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java` 中：

**5a.** import 区加上：

```java
import com.gpsbt.transfer.link.Role;
import com.gpsbt.transfer.net.DiscoveryListener;
import com.gpsbt.transfer.net.ServerTable;
import com.gpsbt.transfer.service.ReceiveService;
import com.gpsbt.transfer.ui.adapter.ServerAdapter;
```

**5b.** 类声明加上 `ServerAdapter.OnServerSelected`：

```java
public class HomeFragment extends Fragment
        implements TransmitterState.Listener, PairedDeviceAdapter.OnDeviceSelected,
        ServerAdapter.OnServerSelected {
```

**5c.** 字段区加上：

```java
    private MaterialButtonToggleGroup roleGroup;
    private TextView transportTitle;
    private View mockCard;
    private View serverPanel;
    private RecyclerView serverList;
    private TextView serverEmpty;
    private View manualPanel;
    private TextInputEditText inputHost;
    private TextInputEditText inputPort;
    private ServerAdapter serverAdapter;
    private DiscoveryListener discoveryListener;
```

**5d.** `onViewCreated` 里 `transportGroup = view.findViewById(R.id.transport_group);` 之前插入：

```java
        roleGroup = view.findViewById(R.id.role_group);
        transportTitle = view.findViewById(R.id.transport_title);
        mockCard = view.findViewById(R.id.mock_card);
        serverPanel = view.findViewById(R.id.server_panel);
        serverList = view.findViewById(R.id.server_list);
        serverEmpty = view.findViewById(R.id.server_empty);
        manualPanel = view.findViewById(R.id.manual_panel);
        inputHost = view.findViewById(R.id.input_host);
        inputPort = view.findViewById(R.id.input_port);
```

**5e.** `peerList.setAdapter(peerAdapter);` 之后插入：

```java
        serverAdapter = new ServerAdapter(this);
        serverList.setLayoutManager(new LinearLayoutManager(getContext()));
        serverList.setAdapter(serverAdapter);

        inputHost.setText(prefs.getLastServerHost());
        inputPort.setText(String.valueOf(prefs.getLastServerPort()));
```

**5f.** 在 `transportGroup.addOnButtonCheckedListener(...)` 之前插入角色监听：

```java
        roleGroup.addOnButtonCheckedListener(
                new MaterialButtonToggleGroup.OnButtonCheckedListener() {
                    @Override
                    public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                                boolean isChecked) {
                        if (!isChecked) {
                            return;
                        }
                        prefs.setRole(checkedId == R.id.role_receive
                                ? Role.RECEIVE : Role.SHARE);
                        renderPanels();
                    }
                });
```

**5g.** `onPulseClicked` 整个替换为：

```java
    private void onPulseClicked() {
        if (prefs.getRole() == Role.RECEIVE) {
            onReceivePulseClicked();
            return;
        }
        if (state.phase() == TransmitterState.Phase.RUNNING) {
            GpsOutputService.stop(requireContext());
            return;
        }
        if (prefs.getTransport() == Transport.BLUETOOTH
                && prefs.getMode() == ConnectionMode.CLIENT
                && (selectedMac == null || selectedMac.isEmpty())) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.home_devices_title)
                    .setMessage(R.string.home_devices_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (getActivity() instanceof HomeCallbacks) {
            ((HomeCallbacks) getActivity()).requestStartPermissions(new Runnable() {
                @Override
                public void run() {
                    GpsOutputService.start(requireContext(), prefs.getMode(), selectedMac);
                }
            });
        }
    }

    /** 接收角色：已连接则断开，否则按输入框的地址去连。 */
    private void onReceivePulseClicked() {
        if (ReceiveService.isRunning()) {
            ReceiveService.stop(requireContext());
            return;
        }
        String host = inputHost.getText() == null ? "" : inputHost.getText().toString().trim();
        if (host.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.receive_manual_title)
                    .setMessage(R.string.receive_host_required)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final int port = portOf(inputPort, Prefs.DEFAULT_SERVER_PORT);
        prefs.setLastServerHost(host);
        prefs.setLastServerPort(port);
        inputPort.setText(String.valueOf(port));
        final String target = host;
        // 模拟位置注入同样需要定位权限，因此走与发送端相同的授权入口
        if (getActivity() instanceof HomeCallbacks) {
            ((HomeCallbacks) getActivity()).requestStartPermissions(new Runnable() {
                @Override
                public void run() {
                    ReceiveService.start(requireContext(), target, port);
                }
            });
        }
    }

    /** 解析端口输入；非法或越界回落到默认值。 */
    private static int portOf(TextInputEditText field, int fallback) {
        if (field.getText() == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            return value < Prefs.MIN_PORT || value > Prefs.MAX_PORT ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
```

**5h.** 加上服务器选中回调：

```java
    @Override
    public void onSelected(ServerTable.Server server) {
        inputHost.setText(server.host);
        inputPort.setText(String.valueOf(server.port));
        prefs.setLastServerHost(server.host);
        prefs.setLastServerPort(server.port);
    }
```

**5i.** `render` 里，在设置传输方式开关那段之前插入角色开关同步：

```java
        int roleButton = prefs.getRole() == Role.RECEIVE ? R.id.role_receive : R.id.role_share;
        if (roleGroup.getCheckedButtonId() != roleButton) {
            roleGroup.check(roleButton);
        }
```

**5j.** `render` 里，把脉冲按钮状态改为按角色取：

```java
        pulseButton.setState(running
                ? PulseButtonView.State.RUNNING : PulseButtonView.State.IDLE);
```

改为

```java
        boolean receiveRunning = ReceiveService.isRunning();
        pulseButton.setState(running || receiveRunning
                ? PulseButtonView.State.RUNNING : PulseButtonView.State.IDLE);
```

**5k.** `renderPanels` 整个替换为：

```java
    private void renderPanels() {
        boolean receive = prefs.getRole() == Role.RECEIVE;
        boolean wifi = prefs.getTransport() == Transport.WIFI;
        boolean client = prefs.getMode() == ConnectionMode.CLIENT;

        transportTitle.setVisibility(receive ? View.GONE : View.VISIBLE);
        transportGroup.setVisibility(receive ? View.GONE : View.VISIBLE);
        modeTitle.setVisibility(receive || wifi ? View.GONE : View.VISIBLE);
        modeGroup.setVisibility(receive || wifi ? View.GONE : View.VISIBLE);
        modeHint.setVisibility(receive || wifi ? View.GONE : View.VISIBLE);

        serviceCard.setVisibility(!receive && !wifi && !client ? View.VISIBLE : View.GONE);
        devicePanel.setVisibility(!receive && !wifi && client ? View.VISIBLE : View.GONE);
        wifiCard.setVisibility(!receive && wifi ? View.VISIBLE : View.GONE);
        peerPanel.setVisibility(!receive && wifi ? View.VISIBLE : View.GONE);

        mockCard.setVisibility(receive ? View.VISIBLE : View.GONE);
        serverPanel.setVisibility(receive ? View.VISIBLE : View.GONE);
        manualPanel.setVisibility(receive ? View.VISIBLE : View.GONE);

        if (receive) {
            startDiscoveryListener();
        } else {
            stopDiscoveryListener();
            if (wifi) {
                renderWifiCard();
            } else {
                modeHint.setText(client
                        ? R.string.home_mode_client_hint : R.string.home_mode_server_hint);
            }
        }
    }

    /** 接收角色才监听服务发现；重复调用安全。 */
    private void startDiscoveryListener() {
        if (discoveryListener != null) {
            return;
        }
        discoveryListener = new DiscoveryListener(prefs.getDiscoveryPort(),
                new DiscoveryListener.Callback() {
                    @Override
                    public void onServers(java.util.List<ServerTable.Server> servers) {
                        if (!isAdded()) {
                            return;
                        }
                        serverAdapter.submit(servers);
                        serverEmpty.setVisibility(servers.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
        discoveryListener.start();
    }

    private void stopDiscoveryListener() {
        if (discoveryListener != null) {
            discoveryListener.stop();
            discoveryListener = null;
        }
    }
```

**5l.** `onStop` 里加上停掉发现监听：

```java
    @Override
    public void onStop() {
        state.removeListener(this);
        stopDiscoveryListener();
        super.onStop();
    }
```

**5m.** import 区加上 `TextInputEditText`：

```java
import com.google.android.material.textfield.TextInputEditText;
```

- [ ] **Step 6: 编译并构建**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

预期：`BUILD SUCCESSFUL`，92 个用例全过，APK 产出。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat: 主页支持角色切换与接收端连接界面"
```

---

## Task 7: P3 验收

**Files:** 无改动，纯验证。

- [ ] **Step 1: 全量干净构建**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

预期：`BUILD SUCCESSFUL`，92 个用例全过，两个 APK 都产出。

- [ ] **Step 2: 两台设备**

- **发送端**：一台有 GPS 的手机，装同一个 APK
- **接收端**：一台**没有 GPS** 的平板 / 车机，装同一个 APK

- [ ] **Step 3: 建立连接**

1. 发送端手机开「个人热点」，选角色「共享 GPS」→ 传输方式「WiFi」→ 开始传输
2. 接收端连上该热点
3. 接收端选角色「接收 GPS」→ 应能在「发现的发送端」列表里看到手机 → 点一下自动填入地址端口 → 点「连接」

预期：状态变「已连接」，接收端开始显示经纬度。

- [ ] **Step 4: 验证系统定位被接管（关键）**

在接收端上打开任意导航 App（高德 / 百度 / Google Maps），确认：

- 能正常定位，位置跟着手机移动
- **卫星数可能显示 0**——这是 Android 平台限制（mock provider 不支持 GnssStatus），不是故障

同时在接收端本应用的「卫星」页确认：**卫星列表与雷达图正常显示**（数据来自 GSV 解析，不经过系统）。

- [ ] **Step 5: 验证未授权时的表现**

先在接收端把「模拟位置信息应用」改成「无」，再点连接：

预期：应用**不闪退**，状态栏提示「未获得模拟位置授权」，界面上的授权指引卡片可见；导航 App 里看不到位置。

- [ ] **Step 6: 验证断线重连**

- 发送端断开 WiFi 传输 → 接收端应在 5 秒后自动重连
- 接收端杀掉重开 → 应从「上次连接的服务器」恢复地址

- [ ] **Step 7: 验证发送端未被破坏**

把一台手机切回「共享 GPS」，重复 P2 的蓝牙 / WiFi 发送检查。

- [ ] **Step 8: 记录验收结果**

写进本计划文档的「验收记录」一节并提交。

---

## 验收记录

**日期**：2026-10-13
**分支**：`p3-receiver`（基线 `p2-wifi-sender`）

### 自动化部分（已完成）

| 项 | 结果 |
|---|---|
| `clean` 后全量构建 | `BUILD SUCCESSFUL` |
| 单元测试 | **93 用例，0 失败，0 错误**（基线 77 → 93） |
| debug / release APK | 均产出；release 已签名 |
| 权限 | `INTERNET`、`ACCESS_FINE_LOCATION`、`FOREGROUND_SERVICE_DATA_SYNC` 等均在 release APK 中确认 |

### 执行过程中的偏离与事故

1. **Task 6 的子代理在第 5 步中途遇到 API 503 被中断**。Step 1–4 的产物（字符串、`item_server.xml`、`ServerAdapter.java`、`fragment_home.xml`）保留，Step 5 的 `HomeFragment` 改动由控制器接着完成。
2. **审查子代理同样遇到 API 503**，改由控制器人工审查 P3 全阶段。发现并修掉两处真实缺陷：
   - `ServerTable` 的 `upsert`（UDP 接收线程）与 `snapshot`（轮询线程）同时操作同一个 `LinkedHashMap`，会抛 `ConcurrentModificationException` → 四个公开方法加 `synchronized`，并补一条结构断言锁住契约
   - `NmeaClient.postConnection` 缺少 `running` 守卫，`stop()` 关 socket 会触发多余回调、把已 reset 的状态写脏 → 补守卫，与 `onLocation` 一致
3. 计划自查阶段修正四处：往返测试需喂两轮（卫星语句在位置语句之后）、发现回调改比对键列表而非数量、接收端通知自己数接收条数、接收启动走定位授权入口。

### 真机部分（待办）

**需要两台设备**（发送端手机 + 无 GPS 的平板/车机），以下尚未执行：

- 接收端自动发现并连上发送端
- **导航 App 定位被接管**（关键判据）
- 未授权模拟位置时不闪退、给出指引
- 断线 5 秒重连、记住上次服务器
- 共享角色未被破坏

> **动手前请先确认目标设备能进开发者选项并把本应用设为「模拟位置信息应用」**——进不去的话接收端对那台设备不成立。

---

## 自检

**规格覆盖**（对照设计文档 §5、§8.1、§8.3、§9、§11 P3）：

| 设计要求 | 落点 |
|---|---|
| `NmeaParser` 解析 GGA/RMC/GSA/GSV | Task 1 |
| 校验和错误的行丢弃并记日志 | Task 1（`verifiedBody` 返回 null，`NmeaClient` 忽略） |
| 度分转换与生成器互逆 | Task 1（往返测试） |
| `NmeaClient` 断线 5 秒重连 | Task 4 |
| `MockLocationInjector` 注入 `GPS_PROVIDER` | Task 4 |
| `setElapsedRealtimeNanos` 必设 | Task 4（否则抛 `IllegalArgumentException`） |
| 未授权时不静默失败，给出分步指引 | Task 4（`start()` 返回 false + 日志）、Task 6（状态卡片） |
| 注入不了卫星星图，文档说明 | Task 6（`receive_satellite_note`）、P4 文档 |
| 服务器自动发现 + 手输兜底 | Task 3（`DiscoveryListener`）、Task 6（列表 + 输入框） |
| 接收端记住上次服务器 | Task 2（`Prefs`）、Task 6（回填） |
| 角色切换 UI | Task 6 |
| 复用卫星页与日志页 | 复用现有 Fragment，接收端往同一个 `TransmitterState` 写 |
| 接收端前台服务 | Task 5（类型 `dataSync`——不读本机定位，故不用 `location`） |

**类型一致性**：`Role`（`link` 包，`SHARE`/`RECEIVE`）；`NmeaParser.accept(String)` 返回 `NmeaInput` 或 null；`NmeaClient.Callback` 的 `onConnectionChanged`/`onLocation`；`MockLocationInjector.start/stop/inject`；`ServerTable.Server` 的 `host`/`port`/`name`/`lastSeenAt`/`key()`；`DiscoveryListener.Callback.onServers(List<ServerTable.Server>)`；`Prefs` 的 `getRole/setRole/getLastServerHost/setLastServerHost/getLastServerPort/setLastServerPort`。均在定义处与使用处一致。

**测试计数**：基线 77 → Task 1 后 86（+9）→ Task 3 后 92（+6）。Task 2/4/5/6 不新增测试。
