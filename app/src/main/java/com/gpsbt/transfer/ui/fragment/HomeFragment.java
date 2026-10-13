package com.gpsbt.transfer.ui.fragment;

import android.bluetooth.BluetoothDevice;
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
import com.gpsbt.transfer.link.Role;
import com.gpsbt.transfer.link.Transport;
import com.gpsbt.transfer.net.DiscoveryListener;
import com.gpsbt.transfer.net.HotspotAddresses;
import com.gpsbt.transfer.net.ServerTable;
import com.gpsbt.transfer.service.GpsOutputService;
import com.gpsbt.transfer.service.ReceiveService;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.adapter.PairedDeviceAdapter;
import com.gpsbt.transfer.ui.adapter.PeerAdapter;
import com.gpsbt.transfer.ui.adapter.ServerAdapter;
import com.gpsbt.transfer.ui.view.PulseButtonView;
import com.gpsbt.transfer.ui.view.StatCardView;
import com.gpsbt.transfer.util.Formatters;
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

        StatsGridBuilder.build(statsGrid, cards);

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
                        prefs.setRole(role);
                        // 切到接收角色时再次登记一次：有些 ROM 在 App.onCreate 那一刻
                        // 还没准备好，回主界面时再试一次
                        if (role == Role.RECEIVE) {
                            com.gpsbt.transfer.rx.MockLocationInjector.ensureRegistered(
                                    requireContext());
                        }
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
                        prefs.setTransport(transport);
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
                        prefs.setMode(mode);
                        state.setMode(mode);
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

        selectedMac = prefs.getLastDeviceMac();
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
        prefs.setLastDeviceMac(selectedMac);
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

    /** Activity 需实现的权限入口。 */
    public interface HomeCallbacks {
        void requestStartPermissions(Runnable onGranted);
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
        statusText.setText(snapshot.statusText().isEmpty()
                ? getString(statusTextRes) : snapshot.statusText());
        boolean receiveRunning = ReceiveService.isRunning();
        pulseButton.setState(running || receiveRunning
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
        set(StatsGridBuilder.KEY_RATE, String.format(Locale.US, "%.1f %s",
                running ? snapshot.stats().rateHz : 0d, getString(R.string.unit_rate)));
        List<Peer> peers = snapshot.peers();
        if (peers.isEmpty()) {
            set(StatsGridBuilder.KEY_DEVICE, unknown);
        } else if (peers.size() == 1) {
            set(StatsGridBuilder.KEY_DEVICE, peers.get(0).name);
        } else {
            set(StatsGridBuilder.KEY_DEVICE,
                    getString(R.string.value_peer_count, peers.size()));
        }
        peerAdapter.submit(peers);
        peerEmpty.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void set(String key, String value) {
        StatCardView card = cards.get(key);
        if (card != null) {
            card.setValue(value);
        }
    }

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

    /** 只有接收角色才需要监听服务发现；重复调用安全。 */
    private void startDiscoveryListener() {
        if (discoveryListener != null) {
            return;
        }
        discoveryListener = new DiscoveryListener(prefs.getDiscoveryPort(),
                new DiscoveryListener.Callback() {
                    @Override
                    public void onServers(List<ServerTable.Server> servers) {
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
