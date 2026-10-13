package com.gpsbt.transfer.rx;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

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

    private static final String TAG = "MockLocationInjector";

    private final Context context;
    private LocationManager manager;
    private boolean installed;

    public MockLocationInjector(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 让系统「模拟位置应用」列表里出现本应用。
     *
     * Android 的开发者选项 → 选择模拟位置信息应用，列表是系统在运行期
     * 缓存的——光安装 APK 不会自动出现，必须有某次成功调用过
     * {@link LocationManager#addTestProvider}，系统才会把本应用登记进去。
     * 所以在应用启动时调一次——失败也没关系（未授权时 SecurityException），
     * 反正 {@link #start()} 里还会再调并捕获。
     *
     * 这是个静态方法：不需要实例，不需要状态，不依赖任何注入上下文。
     */
    public static void ensureRegistered(Context context) {
        LocationManager manager = (LocationManager)
                context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            Log.w(TAG, "ensureRegistered: LocationManager 为空（设备无定位服务？）");
            return;
        }
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            Log.i(TAG, "ensureRegistered: addTestProvider 成功，本应用已登记为 GPS 模拟提供者");
        } catch (SecurityException e) {
            // 未被选为「模拟位置信息应用」时，部分 ROM（特别是国产定制）会拒
            // 绝注册——这时只能等用户在开发者选项里选上本应用后再调一次
            Log.w(TAG, "ensureRegistered: addTestProvider 抛 SecurityException（" + e.getMessage() +
                    "）——本应用尚未被设为「模拟位置信息应用」，请到开发者选项里选上");
        } catch (IllegalArgumentException e) {
            // 重复 addTestProvider 时抛这个；已经登记过，幂等
            Log.i(TAG, "ensureRegistered: addTestProvider 重复调用（已登记）");
        }
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
