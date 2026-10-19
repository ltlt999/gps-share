package com.gpsbt.transfer.ui.fragment;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.bt.BluetoothLinkManager;
import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.license.本地配置;
import com.gpsbt.transfer.link.Role;
import com.gpsbt.transfer.link.Transport;
import com.gpsbt.transfer.net.DiscoveryListener;
import com.gpsbt.transfer.net.HotspotAddresses;
import com.gpsbt.transfer.net.ServerTable;
import com.gpsbt.transfer.service.GpsOutputService;
import com.gpsbt.transfer.service.ReceiveService;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.adapter.PairedDeviceAdapter;
import com.gpsbt.transfer.ui.adapter.PeerAdapter;
import com.gpsbt.transfer.ui.adapter.ServerAdapter;
import com.gpsbt.transfer.ui.view.PulseButtonView;
import com.gpsbt.transfer.ui.view.StatCardView;
import com.gpsbt.transfer.util.Formatters;
import com.gpsbt.transfer.util.LocationPermission;
import com.gpsbt.transfer.util.Prefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HomeFragment extends Fragment
        implements TransmitterState.Listener, PairedDeviceAdapter.OnDeviceSelected,
        ServerAdapter.OnServerSelected {

    private TransmitterState state;
    private Prefs prefs;

    private PulseButtonView pulseButton;
    private TextView homeSubtitle;
    private TextView statusBadge;
    private TextView statusText;
    private MaterialButtonToggleGroup roleGroup;
    private TextView transportTitle;
    private MaterialButtonToggleGroup transportGroup;
    private TextView modeTitle;
    private MaterialButtonToggleGroup modeGroup;
    private TextView modeHint;
    private View serviceCard;
    private TextView serviceNameValue;
    private TextView uuidValue;
    private View devicePanel;
    private TextView deviceTitle;
    private RecyclerView deviceList;
    private TextView deviceEmpty;
    private Button refreshDevices;
    private View wifiCard;
    private TextView wifiAddressValue;
    private TextView wifiPortValue;
    private TextView wifiHint;
    private View peerPanel;
    private RecyclerView peerList;
    private TextView peerEmpty;
    private View mockCard;
    private View serverPanel;
    private RecyclerView serverList;
    private TextView serverEmpty;
    private View manualPanel;
    private TextInputEditText inputHost;
    private TextInputEditText inputPort;
    private GridLayout statsGrid;

    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private final Map<String, StatCardView> cards = new LinkedHashMap<String, StatCardView>();
    private PairedDeviceAdapter deviceAdapter;
    private PeerAdapter peerAdapter;
    private ServerAdapter serverAdapter;
    private DiscoveryListener discoveryListener;
    private String selectedMac;
    /** 当前数据卡片是按哪个角色建的；null 表示还没建（视图刚创建）。 */
    private Boolean builtRole;
    /** 初始到期的整页遮罩。 */
    private View licenseOverlay;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = TransmitterState.get();
        prefs = Prefs.get(requireContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        pulseButton = view.findViewById(R.id.pulse_button);
        homeSubtitle = view.findViewById(R.id.home_subtitle);
        statusBadge = view.findViewById(R.id.status_badge);
        statusText = view.findViewById(R.id.status_text);
        roleGroup = view.findViewById(R.id.role_group);
        transportTitle = view.findViewById(R.id.transport_title);
        transportGroup = view.findViewById(R.id.transport_group);
        modeTitle = view.findViewById(R.id.mode_title);
        modeGroup = view.findViewById(R.id.mode_group);
        modeHint = view.findViewById(R.id.mode_hint);
        serviceCard = view.findViewById(R.id.service_card);
        serviceNameValue = view.findViewById(R.id.service_name_value);
        uuidValue = view.findViewById(R.id.uuid_value);
        devicePanel = view.findViewById(R.id.device_panel);
        deviceTitle = view.findViewById(R.id.device_title);
        deviceList = view.findViewById(R.id.device_list);
        deviceEmpty = view.findViewById(R.id.device_empty);
        refreshDevices = view.findViewById(R.id.refresh_devices);
        wifiCard = view.findViewById(R.id.wifi_card);
        wifiAddressValue = view.findViewById(R.id.wifi_address_value);
        wifiPortValue = view.findViewById(R.id.wifi_port_value);
        wifiHint = view.findViewById(R.id.wifi_hint);
        peerPanel = view.findViewById(R.id.peer_panel);
        peerList = view.findViewById(R.id.peer_list);
        peerEmpty = view.findViewById(R.id.peer_empty);
        mockCard = view.findViewById(R.id.mock_card);
        serverPanel = view.findViewById(R.id.server_panel);
        serverList = view.findViewById(R.id.server_list);
        serverEmpty = view.findViewById(R.id.server_empty);
        manualPanel = view.findViewById(R.id.manual_panel);
        inputHost = view.findViewById(R.id.input_host);
        inputPort = view.findViewById(R.id.input_port);
        statsGrid = view.findViewById(R.id.stats_grid);
        licenseOverlay = view.findViewById(R.id.license_overlay);
        // 整块遮罩和里面的按钮都跳到设置页——授权卡片在那里
        licenseOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLicenseSettings();
            }
        });
        view.findViewById(R.id.btn_overlay_activate).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openLicenseSettings();
                    }
                });

        // 数据卡片交给 renderPanels() → ensureGridForRole() 按当前角色建，
        // 这里不建，避免建两次
        builtRole = null;

        deviceAdapter = new PairedDeviceAdapter(devices, this);
        deviceList.setLayoutManager(new LinearLayoutManager(getContext()));
        deviceList.setAdapter(deviceAdapter);

        peerAdapter = new PeerAdapter();
        peerList.setLayoutManager(new LinearLayoutManager(getContext()));
        peerList.setAdapter(peerAdapter);

        serverAdapter = new ServerAdapter(this);
        serverList.setLayoutManager(new LinearLayoutManager(getContext()));
        serverList.setAdapter(serverAdapter);

        inputHost.setText(prefs.getLastServerHost());
        inputPort.setText(String.valueOf(prefs.getLastServerPort()));

        // 再次尝试登记——App.onCreate 时可能因系统尚未就绪而失败
        com.gpsbt.transfer.rx.MockLocationInjector.ensureRegistered(requireContext());

        // ⚠️ 顺序关键：先把三个开关同步到 Prefs 的当前值，再挂监听。
        // check() 会回调监听器，顺序反了会在每次进入本页时误触发一次
        // 「配置已改，重启流水线」，把正在跑的连接拆掉——对端读到 EOF，
        // 表现为反复断开重连。
        //
        // 为什么会「每次进入本页」：MainActivity.switchTo() 用 replace() 切页，
        // 本 Fragment 实例虽然被缓存，但视图每次都会重建，onViewCreated 重新
        // inflate 出来的开关一项都没选中，getCheckedButtonId() 是 NO_ID。
        syncTogglesFromPrefs();

        roleGroup.addOnButtonCheckedListener(
                new MaterialButtonToggleGroup.OnButtonCheckedListener() {
                    @Override
                    public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                                boolean isChecked) {
                        if (!isChecked) {
                            return;
                        }
                        Role role = checkedId == R.id.role_receive
                                ? Role.RECEIVE : Role.SHARE;
                        // 值没变就别动正在跑的流水线（理由同下面 transport 那条）
                        if (prefs.getRole() == role) {
                            return;
                        }
                        prefs.setRole(role);
                        Context app = getContext() == null
                                ? null : getContext().getApplicationContext();
                        // 切角色时停掉另一条流水线，否则两条会同时往
                        // 同一个 TransmitterState 里写，互相把对方的状态冲掉
                        if (role == Role.RECEIVE) {
                            if (app != null && GpsOutputService.isRunning()) {
                                GpsOutputService.stop(app);
                            }
                            // 切到接收角色时再次登记一次：有些 ROM 在 App.onCreate
                            // 那一刻还没准备好，回主界面时再试一次
                            if (app != null) {
                                com.gpsbt.transfer.rx.MockLocationInjector.ensureRegistered(app);
                            }
                        } else if (app != null && ReceiveService.isRunning()) {
                            ReceiveService.stop(app);
                        }
                        // 两个角色各自记着自己的目标蓝牙设备，切角色要换过来
                        selectedMac = role == Role.RECEIVE
                                ? prefs.getReceiveDeviceMac() : prefs.getLastDeviceMac();
                        deviceAdapter.setSelectedMac(selectedMac);
                        renderPanels();
                    }
                });

        transportGroup.addOnButtonCheckedListener(
                new MaterialButtonToggleGroup.OnButtonCheckedListener() {
                    @Override
                    public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                                boolean isChecked) {
                        if (!isChecked) {
                            return;
                        }
                        Transport transport = checkedId == R.id.transport_wifi
                                ? Transport.WIFI : Transport.BLUETOOTH;
                        // 只在真的变了才重启：check() 有可能被程序侧调用
                        // （render 里同步选中项），那时不该打断正在跑的连接
                        if (prefs.getTransport() != transport) {
                            prefs.setTransport(transport);
                            restartRunningService();
                        }
                        renderPanels();
                    }
                });

        modeGroup.addOnButtonCheckedListener(
                new MaterialButtonToggleGroup.OnButtonCheckedListener() {
                    @Override
                    public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                                boolean isChecked) {
                        if (!isChecked) {
                            return;
                        }
                        ConnectionMode mode = checkedId == R.id.mode_client
                                ? ConnectionMode.CLIENT : ConnectionMode.SERVER;
                        state.setMode(mode);
                        // 同上：只在真的变了才重启流水线
                        if (prefs.getMode() != mode) {
                            prefs.setMode(mode);
                            restartRunningService();
                        }
                        renderPanels();
                    }
                });

        refreshDevices.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadDevices();
            }
        });

        pulseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPulseClicked();
            }
        });

        selectedMac = prefs.getRole() == Role.RECEIVE
                ? prefs.getReceiveDeviceMac() : prefs.getLastDeviceMac();
        loadDevices();
    }

    @Override
    public void onStart() {
        super.onStart();
        state.addListener(this);
        render(state);
    }

    @Override
    public void onStop() {
        state.removeListener(this);
        stopDiscoveryListener();
        super.onStop();
    }

    @Override
    public void onStateChanged(TransmitterState changed) {
        if (!isAdded()) {
            return;
        }
        render(changed);
    }

    @Override
    public void onSelected(BluetoothDevice device) {
        selectedMac = device.getAddress();
        // 两个角色各自记自己的目标设备，互不覆盖
        if (prefs.getRole() == Role.RECEIVE) {
            prefs.setReceiveDeviceMac(selectedMac);
        } else {
            prefs.setLastDeviceMac(selectedMac);
        }
        deviceAdapter.setSelectedMac(selectedMac);
    }

    @Override
    public void onSelected(ServerTable.Server server) {
        inputHost.setText(server.host);
        inputPort.setText(String.valueOf(server.port));
        prefs.setLastServerHost(server.host);
        prefs.setLastServerPort(server.port);
    }

    private void onPulseClicked() {
        // 「有东西在跑」优先于角色判断：开机自启或角色切换都可能让两条流水线
        // 同时在跑，这时按停止必须一次停干净，而不是先看角色去走「启动」分支
        if (anyServiceRunning()) {
            stopAllServices();
            return;
        }
        // 初始结束且未配置时拒绝启动，并告诉用户去哪儿配置
        if (!本地配置.isUsable(requireContext())) {
            showLicenseExpiredDialog();
            return;
        }
        if (prefs.getRole() == Role.RECEIVE) {
            onReceiveStart();
            return;
        }
        onShareStart();
    }

    /**
     * 把三个分段控件同步到 Prefs 的当前值。**必须在挂监听之前调用**。
     *
     * 控件重建后一项都没选中，此时任何 check() 都是一次「变化」，会回调监听器；
     * 而监听器里带着「重启流水线」的副作用，就会把正在跑的连接拆掉。
     * 先同步好，后续 render() 里的 check() 因为选中项已经一致而不会触发回调。
     */
    private void syncTogglesFromPrefs() {
        roleGroup.check(prefs.getRole() == Role.RECEIVE
                ? R.id.role_receive : R.id.role_share);
        transportGroup.check(prefs.getTransport() == Transport.WIFI
                ? R.id.transport_wifi : R.id.transport_bluetooth);
        modeGroup.check(prefs.getMode() == ConnectionMode.CLIENT
                ? R.id.mode_client : R.id.mode_server);
    }

    /** 初始结束且未配置：说明去哪儿配置，而不是静默什么都不做。 */
    private void showLicenseExpiredDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.license_expired_title)
                .setMessage(R.string.license_expired_body)
                .setPositiveButton(R.string.license_expired_ok, null)
                .show();
    }

    /** 两个服务任一在跑就算「在传输」。 */
    private boolean anyServiceRunning() {
        return GpsOutputService.isRunning() || ReceiveService.isRunning();
    }

    /**
     * 传输方式 / 连接方式变了，若服务正在跑就按新配置重启流水线。
     *
     * 服务在启动那一刻就把传输方式读进了流水线，之后改设置**不会**自动生效：
     * 不重启的话界面已经切到 WiFi，实际跑的仍是蓝牙，而 WiFi 端口根本没在
     * 监听，接收端怎么都连不上，必须手动停一次再开。
     *
     * 服务自带「重复启动防护」——再发一次启动请求会先彻底清理旧流水线再重建，
     * 所以这里直接调 start() 即可，不需要先 stop。
     */
    private void restartRunningService() {
        Context app = getContext() == null ? null : getContext().getApplicationContext();
        if (app == null) {
            return;
        }
        if (GpsOutputService.isRunning()) {
            LogBus.get().log(LogBus.Level.INFO, "配置已改，按新设置重启发送端");
            GpsOutputService.start(app, prefs.getMode(), selectedMac);
        }
        if (ReceiveService.isRunning()) {
            LogBus.get().log(LogBus.Level.INFO, "配置已改，按新设置重启接收端");
            ReceiveService.start(app, prefs.getLastServerHost(), prefs.getLastServerPort());
        }
    }

    /** 一次停干净两个服务。没在跑的不去动——去动会把没运行的服务重新拉起来。 */
    private void stopAllServices() {
        Context app = getContext() == null ? null : getContext().getApplicationContext();
        if (app == null) {
            return;
        }
        if (GpsOutputService.isRunning()) {
            GpsOutputService.stop(app);
        }
        if (ReceiveService.isRunning()) {
            ReceiveService.stop(app);
        }
    }

    private void onShareStart() {
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
        // 权限对话框期间用户可能已经离开这个 Fragment，届时 requireContext()
        // 会抛 IllegalStateException 直接闪退。这里提前取好 applicationContext。
        final Context app = requireContext().getApplicationContext();
        if (getActivity() instanceof HomeCallbacks) {
            ((HomeCallbacks) getActivity()).requestStartPermissions(new Runnable() {
                @Override
                public void run() {
                    GpsOutputService.start(app, prefs.getMode(), selectedMac);
                }
            });
        }
    }

    /** 接收角色：按当前传输方式连上发送端。 */
    private void onReceiveStart() {
        final Context app = requireContext().getApplicationContext();

        if (prefs.getTransport() == Transport.BLUETOOTH) {
            if (selectedMac == null || selectedMac.isEmpty()) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.receive_bt_devices_title)
                        .setMessage(R.string.receive_device_required)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            prefs.setReceiveDeviceMac(selectedMac);
            if (getActivity() instanceof HomeCallbacks) {
                ((HomeCallbacks) getActivity()).requestStartPermissions(new Runnable() {
                    @Override
                    public void run() {
                        // 蓝牙方式下 host/port 用不到，服务会按传输方式自己取
                        ReceiveService.start(app, "", prefs.getLastServerPort());
                    }
                });
            }
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
        // 模拟位置注入同样需要定位权限，因此走与发送端相同的授权入口
        if (getActivity() instanceof HomeCallbacks) {
            ((HomeCallbacks) getActivity()).requestStartPermissions(new Runnable() {
                @Override
                public void run() {
                    ReceiveService.start(app, host, port);
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

    /** 跳到设置页——初始到期的遮罩点一下就到这里。 */
    private void openLicenseSettings() {
        if (getActivity() instanceof HomeCallbacks) {
            ((HomeCallbacks) getActivity()).openSettings();
        }
    }

    /** Activity 需实现的入口。 */
    public interface HomeCallbacks {
        void requestStartPermissions(Runnable onGranted);

        /** 切到底部导航的「设置」页。 */
        void openSettings();
    }

    private void loadDevices() {
        devices.clear();
        try {
            devices.addAll(BluetoothLinkManager.bondedDevices(requireContext()));
        } catch (SecurityException e) {
            deviceEmpty.setText(R.string.perm_bluetooth_rationale);
            deviceEmpty.setVisibility(View.VISIBLE);
            deviceAdapter.notifyDataSetChanged();
            return;
        }
        deviceEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        deviceAdapter.setSelectedMac(selectedMac);
        deviceAdapter.notifyDataSetChanged();
    }

    private void render(TransmitterState snapshot) {
        boolean running = snapshot.phase() == TransmitterState.Phase.RUNNING;
        TransmitterState.Bt bt = snapshot.bt();

        int statusTextRes;
        int statusColor = R.color.color_text_secondary;
        if (!running) {
            statusTextRes = R.string.state_idle;
        } else if (bt == TransmitterState.Bt.CONNECTED) {
            statusTextRes = R.string.state_connected;
            statusColor = R.color.color_primary;
        } else if (bt == TransmitterState.Bt.WAITING) {
            statusTextRes = R.string.state_waiting;
            statusColor = R.color.color_accent_blue;
        } else if (bt == TransmitterState.Bt.CONNECTING) {
            statusTextRes = R.string.state_connecting;
            statusColor = R.color.color_accent_blue;
        } else if (bt == TransmitterState.Bt.FAILED) {
            statusTextRes = R.string.state_failed;
            statusColor = R.color.color_error;
        } else if (bt == TransmitterState.Bt.DISCONNECTED) {
            statusTextRes = R.string.state_failed;
            statusColor = R.color.color_warning;
        } else {
            statusTextRes = R.string.state_idle;
        }
        statusBadge.setText(statusTextRes);
        statusBadge.setTextColor(getResources().getColorStateList(statusColor, null));
        String status = snapshot.statusText().isEmpty()
                ? getString(statusTextRes) : snapshot.statusText();
        // 共享角色要读 GPS 往外发：只有「大致位置」时拿到的是基站推算的
        // 粗位置，几百米误差，必须说出来——否则用户以为一切正常
        if (prefs.getRole() == Role.SHARE
                && !LocationPermission.isPrecise(requireContext())) {
            status = status + "\n" + getString(R.string.home_location_approximate);
        }
        statusText.setText(status);
        boolean receiveRunning = ReceiveService.isRunning();
        pulseButton.setState(running || receiveRunning || GpsOutputService.isRunning()
                ? PulseButtonView.State.RUNNING : PulseButtonView.State.IDLE);

        // 角色与传输方式都是用户偏好，事实源是 Prefs。
        // 先比再 check：check() 会回调监听器，多余调用会把用户的选择写回 Prefs
        int roleButton = prefs.getRole() == Role.RECEIVE ? R.id.role_receive : R.id.role_share;
        if (roleGroup.getCheckedButtonId() != roleButton) {
            roleGroup.check(roleButton);
        }

        int transportButton = prefs.getTransport() == Transport.WIFI
                ? R.id.transport_wifi : R.id.transport_bluetooth;
        if (transportGroup.getCheckedButtonId() != transportButton) {
            transportGroup.check(transportButton);
        }

        ConnectionMode mode = snapshot.mode() == TransmitterState.Mode.CLIENT
                ? ConnectionMode.CLIENT : ConnectionMode.SERVER;
        modeGroup.check(mode == ConnectionMode.CLIENT ? R.id.mode_client : R.id.mode_server);
        renderPanels();
        serviceNameValue.setText(prefs.getServiceName());
        uuidValue.setText(prefs.getUuid());

        TransmitterState.Fix fix = snapshot.fix();
        StatCardView fixCard = cards.get(StatsGridBuilder.KEY_FIX);
        if (!running || fix == null) {
            fixCard.setValue(getString(R.string.value_no_fix));
            fixCard.setAccent(getResources().getColor(R.color.color_text_secondary, null));
        } else if (fix.valid) {
            fixCard.setValue(getString(R.string.value_fixed));
            fixCard.setAccent(getResources().getColor(R.color.color_primary, null));
        } else {
            fixCard.setValue(getString(R.string.value_no_fix));
            fixCard.setAccent(getResources().getColor(R.color.color_warning, null));
        }

        String unknown = getString(R.string.value_unknown);
        set(StatsGridBuilder.KEY_SATELLITES, fix == null ? unknown
                : fix.satellitesUsed + " / " + fix.satellitesInView + " "
                        + getString(R.string.unit_satellites));
        set(StatsGridBuilder.KEY_LATITUDE, fix == null || !running ? unknown
                : Formatters.latitude(fix.latitude));
        set(StatsGridBuilder.KEY_LONGITUDE, fix == null || !running ? unknown
                : Formatters.longitude(fix.longitude));
        set(StatsGridBuilder.KEY_SPEED, fix == null || !running ? unknown
                : Formatters.speedKmh(fix.speedMps));
        set(StatsGridBuilder.KEY_BEARING, fix == null || !running ? unknown
                : Formatters.bearing(fix.bearingDeg));
        set(StatsGridBuilder.KEY_ALTITUDE, fix == null || !running ? unknown
                : Formatters.altitude(fix.altitudeM));
        set(StatsGridBuilder.KEY_SENTENCES,
                String.valueOf(running ? snapshot.stats().sentences : 0));
        // 两个角色都有速率卡，但量的是不同的东西，单位也不同
        set(StatsGridBuilder.KEY_RATE, String.format(Locale.US, "%.1f %s",
                running ? snapshot.stats().rateHz : 0d,
                getString(prefs.getRole() == Role.RECEIVE
                        ? R.string.unit_rate_received : R.string.unit_rate)));
        set(StatsGridBuilder.KEY_UPTIME, uptimeText(snapshot));
        List<Peer> peers = snapshot.peers();
        set(StatsGridBuilder.KEY_DEVICE, peerListText(peers));
        peerAdapter.submit(peers);
        peerEmpty.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * 「连接设备」卡的取值。
     *
     * 0 台显示 `--`；多台**逐行列出**各自的地址——这张卡占满整行且允许多行，
     * 就是为了让 WiFi 模式下的多个 IP 都能看全（半宽单行时会被硬裁，
     * 192.168.1.100:60432 只能看到 192.168.1.1）。
     */
    private String peerListText(List<Peer> peers) {
        if (peers.isEmpty()) {
            return getString(R.string.value_unknown);
        }
        StringBuilder builder = new StringBuilder();
        for (Peer peer : peers) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(peer.name);
        }
        return builder.toString();
    }

    private void set(String key, String value) {
        StatCardView card = cards.get(key);
        if (card != null) {
            card.setValue(value);
        }
    }

    private void renderPanels() {
        // 初始到期的遮罩盖住整页：核心功能已经不可用，留个入口让用户去配置，
        // 而不是让他对着一个按了没反应的按钮猜
        licenseOverlay.setVisibility(
                本地配置.isUsable(requireContext()) ? View.GONE : View.VISIBLE);

        boolean receive = prefs.getRole() == Role.RECEIVE;
        boolean wifi = prefs.getTransport() == Transport.WIFI;
        boolean client = prefs.getMode() == ConnectionMode.CLIENT;

        renderSubtitle(receive, wifi);
        ensureGridForRole(receive);

        // 传输方式对两个角色都有意义：共享端用它选发出去的方式，
        // 接收端用它选收进来的方式
        transportTitle.setVisibility(View.VISIBLE);
        transportGroup.setVisibility(View.VISIBLE);

        // 连接方式（服务端 / 客户端）只对「共享 + 蓝牙」有意义
        boolean showMode = !receive && !wifi;
        modeTitle.setVisibility(showMode ? View.VISIBLE : View.GONE);
        modeGroup.setVisibility(showMode ? View.VISIBLE : View.GONE);
        modeHint.setVisibility(showMode ? View.VISIBLE : View.GONE);

        // 共享角色
        serviceCard.setVisibility(!receive && !wifi && !client ? View.VISIBLE : View.GONE);
        wifiCard.setVisibility(!receive && wifi ? View.VISIBLE : View.GONE);
        peerPanel.setVisibility(!receive && wifi ? View.VISIBLE : View.GONE);

        // 蓝牙设备选择面板：共享端「主动连接」要用，接收端蓝牙方式也要用
        boolean showDevicePanel = !wifi && (receive || client);
        devicePanel.setVisibility(showDevicePanel ? View.VISIBLE : View.GONE);
        if (showDevicePanel) {
            deviceTitle.setText(receive
                    ? R.string.receive_bt_devices_title : R.string.home_devices_title);
        }

        // 接收角色
        mockCard.setVisibility(receive ? View.VISIBLE : View.GONE);
        serverPanel.setVisibility(receive && wifi ? View.VISIBLE : View.GONE);
        manualPanel.setVisibility(receive && wifi ? View.VISIBLE : View.GONE);

        if (receive && wifi) {
            startDiscoveryListener();
        } else {
            stopDiscoveryListener();
            if (!receive && wifi) {
                renderWifiCard();
            } else if (showMode) {
                modeHint.setText(client
                        ? R.string.home_mode_client_hint : R.string.home_mode_server_hint);
            }
        }
    }

    /**
     * 角色变了就重建数据卡片。
     *
     * 接收端少一张「发送速率」，卡片集合不同——必须**重建**而不是把那张卡
     * setVisibility(GONE)：GridLayout 仍会给 GONE 的孩子留格子，网格中间
     * 会出现一个洞，后面的卡被挤得七零八落。
     *
     * 只在角色真的变了时才重建：render() 每秒都会跑，无脑重建等于每秒造
     * 十几张卡。
     */
    private void ensureGridForRole(boolean receive) {
        if (builtRole != null && builtRole.booleanValue() == receive) {
            return;
        }
        builtRole = Boolean.valueOf(receive);
        StatsGridBuilder.build(statsGrid, cards, receive);
    }

    /** 顶部小字随角色与传输方式变化——写死「蓝牙串口输出」在 WiFi 模式下是错的。 */
    private void renderSubtitle(boolean receive, boolean wifi) {
        int res;
        if (receive) {
            res = wifi ? R.string.home_subtitle_receive_wifi : R.string.home_subtitle_receive_bt;
        } else {
            res = wifi ? R.string.home_subtitle_share_wifi : R.string.home_subtitle_share_bt;
        }
        homeSubtitle.setText(res);
    }

    /**
     * 「在线时长」：自本次连接建立起的时长。
     *
     * 一直稳定增长说明链路好；**反复归零说明在掉线重连**——比翻日志直观。
     * 未连接时显示 `--`（stats.startedAt 为 0）。
     */
    private String uptimeText(TransmitterState snapshot) {
        long startedAt = snapshot.stats().startedAt;
        if (snapshot.phase() != TransmitterState.Phase.RUNNING || startedAt <= 0) {
            return getString(R.string.value_unknown);
        }
        return Formatters.duration(System.currentTimeMillis() - startedAt);
    }

    /** 只有接收角色才需要监听服务发现；重复调用安全。 */
    private void startDiscoveryListener() {
        if (discoveryListener != null) {
            return;
        }
        discoveryListener = new DiscoveryListener(prefs.getDiscoveryPort(),
                new DiscoveryListener.Callback() {
                    @Override
                    public void onServers(List<ServerTable.Server> servers) {
                        // 发布到状态中心：接收服务重连时会把它当候选地址。
                        // 这样网段变了以后，不用等用户手点「发现的发送端」
                        // 也能自己连上新地址。
                        List<String> hosts = new ArrayList<String>();
                        for (ServerTable.Server server : servers) {
                            hosts.add(server.host);
                        }
                        state.setDiscoveredHosts(hosts);

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
        // 发现停了就把发布出去的地址也清掉：留着陈旧的会让接收服务
        // 拿着过期的地址反复重试
        state.setDiscoveredHosts(null);
    }

    /** 刷新热点地址与端口；检测不到热点时给出引导。 */
    private void renderWifiCard() {
        wifiPortValue.setText(String.valueOf(prefs.getWifiPort()));
        HotspotAddresses.Candidate candidate =
                HotspotAddresses.pickLikelyHotspot(HotspotAddresses.localIpv4());
        if (candidate == null) {
            wifiAddressValue.setText(R.string.value_unknown);
            wifiHint.setText(R.string.home_wifi_no_hotspot);
        } else {
            wifiAddressValue.setText(candidate.ip);
            wifiHint.setText(R.string.home_wifi_hint);
        }
    }
}
