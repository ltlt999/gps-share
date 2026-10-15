# ============================================================
# 混淆规则
#
# 目标：让反编译出来的代码难以阅读与修改；同时**绝不能**混淆掉任何
# 靠名字被系统或框架引用的类——那些一旦改名就会运行即崩。
# ============================================================

# ---------- 靠名字被引用的入口 ----------

# 清单里注册的组件。AGP 通常会自动 keep，这里显式写出来，
# 免得以后有人改了构建配置就悄悄失效。
-keep class com.gpsbt.transfer.MainActivity { *; }
-keep class com.gpsbt.transfer.App { *; }
-keep class com.gpsbt.transfer.service.GpsOutputService { *; }
-keep class com.gpsbt.transfer.service.ReceiveService { *; }
-keep class com.gpsbt.transfer.boot.BootReceiver { *; }
-keep class com.gpsbt.transfer.boot.ServiceWatchdog { *; }

# 布局 XML 里以**全类名**引用的自定义 View。
# 改名后 LayoutInflater 会 ClassNotFoundException 直接崩。
-keep class com.gpsbt.transfer.ui.view.PulseButtonView { *; }
-keep class com.gpsbt.transfer.ui.view.SatelliteRadarView { *; }
-keep class com.gpsbt.transfer.ui.view.StatCardView { *; }

# 自定义 View 的构造函数：XML inflate 反射调用 (Context, AttributeSet)
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------- 保留枚举常量名 ----------

# Prefs 把 Transport.BLUETOOTH.name() 存进 SharedPreferences 再 valueOf 读回。
# 如果 R8 改了枚举常量名，升级后老用户存的字符串就解析不了（会静默退回默认值）。
-keepclassmembers enum * { *; }

# ---------- 保留必要属性 ----------

-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# ---------- 行号信息 ----------

# 保留行号便于崩溃定位；不保留源文件名（避免暴露原始类名）。
# 发布版本崩溃时用户截图的行号，靠它才能对上。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
