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