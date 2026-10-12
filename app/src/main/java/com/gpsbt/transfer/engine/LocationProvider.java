package com.gpsbt.transfer.engine;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.nmea.NmeaInput;

import java.util.ArrayList;
import java.util.List;

/** 封装 LocationManager 与卫星状态，输出与框架无关的 NmeaInput。 */
public final class LocationProvider {

    public interface Callback {
        void onFix(NmeaInput input);

        void onStatus(String message);

        void onSatelliteUpdate(int inView, int used);
    }

    private static final String[] GPS_AND_NETWORK = {
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
    };
    private static final String[] GPS_ONLY = { LocationManager.GPS_PROVIDER };

    /** 距最近一次 GPS 定位超过该时长，即启用网络定位兜底。 */
    static final long GPS_FALLBACK_AFTER_MS = 10000L;

    private final Context context;
    private final LocationManager locationManager;
    private Callback callback;
    private boolean running;

    private final List<NmeaInput.Sat> satellites = new ArrayList<>();
    private int usedCount;

    // GPS 优先 / 网络兜底状态
    private long startedAtMillis;
    private long lastGpsFixAt;
    private boolean fallbackActive;

    public LocationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager =
                (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // 权限在进入循环前已由 hasPermission(context) 显式校验，失败即提前返回
    @SuppressLint("MissingPermission")
    public void start(boolean allowNetwork, int minTimeMillis, Callback callback) {
        this.callback = callback;
        if (running) {
            return;
        }
        if (locationManager == null) {
            callback.onStatus("系统不支持定位服务");
            return;
        }
        if (!hasPermission(context)) {
            callback.onStatus("缺少定位权限");
            return;
        }
        String[] providers = allowNetwork ? GPS_AND_NETWORK : GPS_ONLY;
        StringBuilder requested = new StringBuilder();
        for (String provider : providers) {
            if (locationManager.getProvider(provider) != null) {
                locationManager.requestLocationUpdates(
                        provider, Math.max(200L, minTimeMillis), 0f, listener, Looper.getMainLooper());
                if (requested.length() > 0) {
                    requested.append(" + ");
                }
                requested.append(provider);
            }
        }
        if (requested.length() == 0) {
            callback.onStatus("设备没有可用的 GPS 定位提供者");
            return;
        }
        startedAtMillis = System.currentTimeMillis();
        lastGpsFixAt = 0;
        fallbackActive = false;
        // minSdk 24，GnssStatus.Callback 始终可用
        if (locationManager.getProvider(LocationManager.GPS_PROVIDER) != null) {
            locationManager.registerGnssStatusCallback(
                    gnssCallback, new Handler(Looper.getMainLooper()));
        }
        running = true;
        callback.onStatus("已启动定位：" + requested
                + (allowNetwork ? "（GPS 优先，无定位 10 秒后网络兜底）" : ""));
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        callback = null;
        if (locationManager == null) {
            return;
        }
        locationManager.removeUpdates(listener);
        locationManager.unregisterGnssStatusCallback(gnssCallback);
    }

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            Callback current = callback;
            if (current == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                // GPS 永远优先；恢复时自动退出兜底
                lastGpsFixAt = now;
                if (fallbackActive) {
                    fallbackActive = false;
                    current.onStatus("GPS 已恢复，停用网络定位兜底");
                }
                current.onFix(toInput(location));
                return;
            }
            // 网络定位（WiFi/基站）：仅在 GPS 迟迟无定位时兜底发出
            if (!shouldEmitNetworkFix(lastGpsFixAt, startedAtMillis, now, GPS_FALLBACK_AFTER_MS)) {
                return;
            }
            if (!fallbackActive) {
                fallbackActive = true;
                current.onStatus("GPS 无定位，已切换网络定位兜底（精度较低，WiFi/基站）");
            }
            current.onFix(toInput(location));
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Callback current = callback;
            if (current != null) {
                current.onStatus("定位提供者已关闭：" + provider);
            }
        }
    };

    /** 网络定位兜底决策：以最近一次 GPS 定位（或启动时刻）为锚点，超过时限即兜底。 */
    static boolean shouldEmitNetworkFix(long lastGpsFixAt, long startedAtMillis,
                                        long nowMillis, long fallbackAfterMs) {
        long anchor = lastGpsFixAt != 0 ? lastGpsFixAt : startedAtMillis;
        return nowMillis - anchor > fallbackAfterMs;
    }

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            synchronized (satellites) {
                satellites.clear();
                usedCount = 0;
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    boolean used = status.usedInFix(i);
                    if (used) {
                        usedCount++;
                    }
                    satellites.add(new NmeaInput.Sat(
                            status.getSvid(i),
                            (int) status.getElevationDegrees(i),
                            (int) status.getAzimuthDegrees(i),
                            (int) status.getCn0DbHz(i),
                            used,
                            talkerOfConstellation(status.getConstellationType(i))));
                }
            }
            Callback current = callback;
            if (current != null) {
                current.onSatelliteUpdate(satellites.size(), usedCount);
            }
        }
    };

    /** Android GnssStatus 星座类型 → NMEA talker。GPS/SBAS 返回 GP，未知返回 null 由编号推断。 */
    static String talkerOfConstellation(int constellationType) {
        switch (constellationType) {
            case GnssStatus.CONSTELLATION_GLONASS:
                return "GL";
            case GnssStatus.CONSTELLATION_BEIDOU:
                return "BD";
            case GnssStatus.CONSTELLATION_GALILEO:
                return "GA";
            case GnssStatus.CONSTELLATION_QZSS:
                return "GQ";
            case GnssStatus.CONSTELLATION_IRNSS:
                return "GI";
            case GnssStatus.CONSTELLATION_GPS:
            case GnssStatus.CONSTELLATION_SBAS:
                return "GP";
            default:
                return null;
        }
    }

    /** 把 Android Location 转成 NmeaInput。 */
    public static NmeaInput toInput(Location location) {
        NmeaInput input = new NmeaInput();
        input.utcMillis = System.currentTimeMillis();
        input.valid = true;
        input.latitude = location.getLatitude();
        input.longitude = location.getLongitude();
        input.altitudeM = location.hasAltitude() ? location.getAltitude() : 0d;
        input.speedMps = location.hasSpeed() ? location.getSpeed() : 0d;
        input.bearingDeg = location.hasBearing() ? location.getBearing() : 0d;
        input.accuracyM = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        return input;
    }

    /** 取最近一次卫星快照并对定位快照补齐卫星字段。 */
    public NmeaInput withSatellites(NmeaInput input) {
        synchronized (satellites) {
            input.clearSats();
            for (NmeaInput.Sat sat : satellites) {
                input.addSat(sat);
            }
            input.satellitesUsed = usedCount;
        }
        return input;
    }

    public int satellitesInView() {
        synchronized (satellites) {
            return satellites.size();
        }
    }
}
