package com.gpsbt.transfer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.link.Role;
import com.gpsbt.transfer.link.Transport;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 设置持久化。 */
public final class Prefs {

    public static final String DEFAULT_SERVICE_NAME = "GPS共享";
    public static final String DEFAULT_UUID = "00001101-0000-1000-8000-00805F9B34FB";
    public static final int DEFAULT_RATE_HZ = 1;
    public static final int MAX_RATE_HZ = 10;
    public static final int MIN_RATE_HZ = 1;
    public static final long DEFAULT_STALE_MILLIS = 5000L;
    public static final float DEFAULT_HDOP = 1.0f;
    /** 开机自启默认关闭：用户不开启就不自动运行。 */
    public static final boolean DEFAULT_AUTO_START = false;
    /** WiFi 通道默认端口：NMEA over TCP 的行业惯例端口。 */
    public static final int DEFAULT_WIFI_PORT = 10110;
    /** 接收端默认连接端口，与发送端默认值一致。 */
    public static final int DEFAULT_SERVER_PORT = DEFAULT_WIFI_PORT;
    /** 服务发现默认端口。 */
    public static final int DEFAULT_DISCOVERY_PORT = 10111;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private static final String NAME = "gps_bt_prefs";
    private static final String KEY_RATE = "rate_hz";
    private static final String KEY_SERVICE_NAME = "service_name";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_ALLOW_NETWORK = "allow_network";
    private static final String KEY_STALE = "stale_millis";
    private static final String KEY_HDOP = "default_hdop";
    private static final String KEY_SENTENCES = "sentences";
    private static final String KEY_MODE = "mode";
    private static final String KEY_LAST_MAC = "last_device_mac";
    private static final String KEY_AUTO_START = "auto_start_boot";
    private static final String KEY_TRANSPORT = "transport";
    private static final String KEY_WIFI_PORT = "wifi_port";
    private static final String KEY_DISCOVERY_ENABLED = "discovery_enabled";
    private static final String KEY_DISCOVERY_PORT = "discovery_port";
    private static final String KEY_ROLE = "role";
    private static final String KEY_LAST_SERVER_HOST = "last_server_host";
    private static final String KEY_LAST_SERVER_PORT = "last_server_port";
    /** 接收端用蓝牙时要连的那台发送端；与发送端「主动连接」用的 KEY_LAST_MAC 分开存。 */
    private static final String KEY_RECEIVE_MAC = "receive_device_mac";

    private static volatile Prefs instance;
    private final SharedPreferences prefs;

    private Prefs(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Prefs get(Context context) {
        Prefs local = instance;
        if (local == null) {
            synchronized (Prefs.class) {
                local = instance;
                if (local == null) {
                    local = new Prefs(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static boolean isReady() {
        return instance != null;
    }

    public int getRateHz() {
        return clampInt(MIN_RATE_HZ, MAX_RATE_HZ, prefs.getInt(KEY_RATE, DEFAULT_RATE_HZ));
    }

    public void setRateHz(int value) {
        prefs.edit().putInt(KEY_RATE, clampInt(MIN_RATE_HZ, MAX_RATE_HZ, value)).apply();
    }

    public String getServiceName() {
        String name = prefs.getString(KEY_SERVICE_NAME, DEFAULT_SERVICE_NAME);
        return name == null || name.trim().isEmpty() ? DEFAULT_SERVICE_NAME : name.trim();
    }

    public void setServiceName(String value) {
        prefs.edit().putString(KEY_SERVICE_NAME, value == null ? DEFAULT_SERVICE_NAME : value).apply();
    }

    public String getUuid() {
        String uuid = prefs.getString(KEY_UUID, DEFAULT_UUID);
        return isValidUuid(uuid) ? uuid : DEFAULT_UUID;
    }

    public void setUuid(String value) {
        prefs.edit().putString(KEY_UUID, isValidUuid(value) ? value : DEFAULT_UUID).apply();
    }

    public boolean isAllowNetwork() {
        return prefs.getBoolean(KEY_ALLOW_NETWORK, false);
    }

    public void setAllowNetwork(boolean value) {
        prefs.edit().putBoolean(KEY_ALLOW_NETWORK, value).apply();
    }

    public long getStaleMillis() {
        long value = prefs.getLong(KEY_STALE, DEFAULT_STALE_MILLIS);
        return Math.max(1000L, Math.min(60000L, value));
    }

    public void setStaleMillis(long value) {
        prefs.edit().putLong(KEY_STALE, Math.max(1000L, Math.min(60000L, value))).apply();
    }

    public float getDefaultHdop() {
        float value = prefs.getFloat(KEY_HDOP, DEFAULT_HDOP);
        return Math.max(0.5f, Math.min(9.9f, value));
    }

    public void setDefaultHdop(float value) {
        prefs.edit().putFloat(KEY_HDOP, Math.max(0.5f, Math.min(9.9f, value))).apply();
    }

    public Set<NmeaSentenceType> getSentences() {
        Set<NmeaSentenceType> result = EnumSet.noneOf(NmeaSentenceType.class);
        Set<String> stored = prefs.getStringSet(KEY_SENTENCES, defaultSentenceTokens());
        if (stored != null) {
            for (String token : stored) {
                try {
                    result.add(NmeaSentenceType.valueOf(token));
                } catch (IllegalArgumentException ignored) {
                    // 忽略未知值
                }
            }
        }
        return result.isEmpty() ? NmeaSentenceType.all() : result;
    }

    public void setSentences(Set<NmeaSentenceType> sentences) {
        Set<String> tokens = new HashSet<>();
        for (NmeaSentenceType type : sentences) {
            tokens.add(type.name());
        }
        prefs.edit().putStringSet(KEY_SENTENCES, tokens).apply();
    }

    public ConnectionMode getMode() {
        String name = prefs.getString(KEY_MODE, ConnectionMode.SERVER.name());
        try {
            return ConnectionMode.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ConnectionMode.SERVER;
        }
    }

    public void setMode(ConnectionMode mode) {
        prefs.edit().putString(KEY_MODE, (mode == null ? ConnectionMode.SERVER : mode).name()).apply();
    }

    public String getLastDeviceMac() {
        return prefs.getString(KEY_LAST_MAC, null);
    }

    public void setLastDeviceMac(String mac) {
        prefs.edit().putString(KEY_LAST_MAC, mac).apply();
    }

    /** 开机是否自动开始传输，默认关闭。 */
    public boolean isAutoStartOnBoot() {
        return prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START);
    }

    public void setAutoStartOnBoot(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_START, value).apply();
    }

    public Transport getTransport() {
        String name = prefs.getString(KEY_TRANSPORT, Transport.BLUETOOTH.name());
        try {
            return Transport.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Transport.BLUETOOTH;
        }
    }

    public void setTransport(Transport value) {
        prefs.edit().putString(KEY_TRANSPORT,
                (value == null ? Transport.BLUETOOTH : value).name()).apply();
    }

    public int getWifiPort() {
        return clampInt(MIN_PORT, MAX_PORT,
                prefs.getInt(KEY_WIFI_PORT, DEFAULT_WIFI_PORT));
    }

    public void setWifiPort(int value) {
        prefs.edit().putInt(KEY_WIFI_PORT, clampInt(MIN_PORT, MAX_PORT, value)).apply();
    }

    public boolean isDiscoveryEnabled() {
        return prefs.getBoolean(KEY_DISCOVERY_ENABLED, true);
    }

    public void setDiscoveryEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_DISCOVERY_ENABLED, value).apply();
    }

    public int getDiscoveryPort() {
        return clampInt(MIN_PORT, MAX_PORT,
                prefs.getInt(KEY_DISCOVERY_PORT, DEFAULT_DISCOVERY_PORT));
    }

    public void setDiscoveryPort(int value) {
        prefs.edit().putInt(KEY_DISCOVERY_PORT, clampInt(MIN_PORT, MAX_PORT, value)).apply();
    }

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

    /** 接收端用蓝牙时要连的发送端 MAC；没有则返回空串。 */
    public String getReceiveDeviceMac() {
        String mac = prefs.getString(KEY_RECEIVE_MAC, "");
        return mac == null ? "" : mac.trim();
    }

    public void setReceiveDeviceMac(String value) {
        prefs.edit().putString(KEY_RECEIVE_MAC, value == null ? "" : value.trim()).apply();
    }

    private static Set<String> defaultSentenceTokens() {
        Set<String> tokens = new HashSet<>();
        for (NmeaSentenceType type : NmeaSentenceType.values()) {
            tokens.add(type.name());
        }
        return tokens;
    }

    private static boolean isValidUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int clampInt(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }
}
