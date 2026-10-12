# 需求变更：开机自启与自动开始传输（2026-09-23）

## 变更背景

Task 1–16 完成后，用户提出需要「开机自动启动 + 启动后自动开始传输」。这与最初需求
「不能开机自启、自动运行」直接冲突，经确认后采用如下形态。

## 确认后的需求

1. **开机后直接自动开始传输**：连接方式取上次设置，设备取上次选中（客户端模式），频率取设置值。
2. **设置页提供开关，默认关闭**，用户手动打开才生效。
3. 前置条件不满足时**不启动**，改为发一条通知说明原因并写日志：
   - 未授予定位权限
   - 未授予蓝牙连接权限（Android 12+）
   - 设备没有蓝牙硬件
   - 蓝牙未开启
   - 客户端模式但未选择目标设备

## 对原设计的改动

原设计文档第 6 节「不自动运行」6 条硬约束**作废**，改为：

> 默认不自动运行；仅在用户显式开启「开机自动开始传输」开关后，开机自启才会生效。
> 开关默认为关闭，服务仍只能由 `BootReceiver`（系统 BOOT_COMPLETED）或用户点击按钮启动，
> `exported=false` + `START_NOT_STICKY` 不变。

## Android 侧已知限制（写进 README/USAGE）

1. Android 12+ 禁止后台启动前台服务，但 `BOOT_COMPLETED` 属于豁免场景，因此可行。
2. 国产 ROM（MIUI / ColorOS / EMUI 等）可能拦截，需用户在系统设置中允许本软件「自启动」
   并关闭电池优化。
3. 开机时若蓝牙关闭，无法从后台弹系统「开启蓝牙」对话框（Android 10+ 禁止后台启动 Activity），
   只能通知用户手动开启。
4. 定位/蓝牙/通知权限必须是上次重启前已授予的状态；权限未授予时同样只通知、不启动。
5. 应用未使用 direct-boot（`directBootAware=false`），开机解锁后才会收到 `BOOT_COMPLETED`。

## 涉及文件

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | 加 `RECEIVE_BOOT_COMPLETED` 权限；加 `BootReceiver`（`exported=true`，`directBootAware=false`） |
| `boot/BootReceiver.java` | 新建：开关判断 → 前置条件检查 → 启动服务或发通知 |
| `util/Prefs.java` | `isAutoStartOnBoot()` / `setAutoStartOnBoot(boolean)`，默认 `false` |
| `ui/fragment/SettingsFragment.java` | 新增开关绑定 |
| `res/layout/fragment_settings.xml` | 新增「开机自启」卡片 |
| `res/values/strings.xml` | 新增开关、说明、通知、原因文案；修正 `home_note` 与 `settings_about_body` |
| `README.md` / `docs/USAGE.md` / 设计文档第 6 节 | 同步为「默认不自动运行，开关打开后开机自启」 |

## 实现步骤（对应计划 Task 17）

1. 清单加权限 + 接收器
2. `Prefs` 加开关（默认 false）
3. 实现 `BootReceiver`（含 `blockedReason(Context, Prefs)` 与通知）
4. 设置页开关 + 文案
5. `assembleDebug` + `lintDebug`，并复核 APK 清单中确有 receiver 与权限
6. 模拟器真重启验证：开关开 → 重启后有进程/通知；开关关 → 重启后无进程
7. 文档同步
