package com.gpsbt.transfer.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多对端注册表：维护一组已接入的 TCP 客户端，负责广播与失效剔除。
 *
 * 纯逻辑、无 Android 依赖，可在 JVM 上直接单测。核心契约：
 * 单个客户端写失败**不得**影响其余客户端，也不得抛给调用方——
 * 否则发送循环会把「一个接收端掉线」误判成「整条链路故障」而停掉服务。
 */
public final class PeerRegistry {

    /** 一个已接入的客户端。 */
    public interface Client {

        /** 通道内唯一标识，形如 "192.168.43.2:10110"。 */
        String address();

        /** 写一帧数据；失败时抛 IOException。 */
        void write(byte[] data) throws IOException;

        /** 关闭底层连接。必须幂等。 */
        void close();
    }

    // 选 CopyOnWriteArrayList 不是性能优化，是正确性：
    // broadcast() 会在遍历的同时 remove() 失败客户端，
    // 普通 ArrayList 的迭代器会立刻抛 ConcurrentModificationException。
    // CoW 的迭代器基于快照，遍历期间其他线程的增删不会干扰当前遍历。
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    public void add(Client client) {
        if (client != null) {
            clients.add(client);
        }
    }

    public boolean remove(Client client) {
        return client != null && clients.remove(client);
    }

    public int size() {
        return clients.size();
    }

    public boolean isEmpty() {
        return clients.isEmpty();
    }

    /** 当前客户端的快照副本，供界面展示。 */
    public List<Client> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(clients));
    }

    /**
     * 广播给所有客户端。**永不抛异常**：写失败的客户端被静默摘除并关闭，
     * 其余客户端照常收到数据。
     */
    public void broadcast(byte[] data) {
        for (Client client : clients) {
            try {
                client.write(data);
            } catch (Exception e) {
                // 这个对端死了，摘掉即可——不是整条链路的故障。
                // 捕获 Exception 而非 IOException：Socket 实现也可能抛
                // RuntimeException（如关闭竞态下的 IllegalStateException），
                // 而「广播永不抛」是本类的硬契约，漏掉非受检异常就形同虚设。
                clients.remove(client);
                closeQuietly(client);
            }
        }
    }

    /** 关闭并清空全部客户端。单个客户端关闭失败不影响其余。 */
    public void closeAll() {
        for (Client client : clients) {
            closeQuietly(client);
        }
        clients.clear();
    }

    /** 关闭单个客户端；任何异常都不外泄——一个坏客户端不该拖累其余。 */
    private static void closeQuietly(Client client) {
        try {
            client.close();
        } catch (Exception ignored) {
            // 关闭失败无从补救，忽略
        }
    }
}
