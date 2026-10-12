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
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.bt.BluetoothLinkManager;
import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.service.GpsOutputService;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.adapter.PairedDeviceAdapter;
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
        implements TransmitterState.Listener, PairedDeviceAdapter.OnDeviceSelected {

    private TransmitterState state;
    private Prefs prefs;

    private PulseButtonView pulseButton;
    private TextView statusBadge;
    private TextView statusText;
    private MaterialButtonToggleGroup modeGroup;
    private TextView modeHint;
    private View serviceCard;
    private TextView serviceNameValue;
    private TextView uuidValue;
    private View devicePanel;
    private RecyclerView deviceList;
    private TextView deviceEmpty;
    private Button refreshDevices;
    private GridLayout statsGrid;

    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private final Map<String, StatCardView> cards = new LinkedHashMap<String, StatCardView>();
    private PairedDeviceAdapter deviceAdapter;
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
        modeGroup = view.findViewById(R.id.mode_group);
        modeHint = view.findViewById(R.id.mode_hint);
        serviceCard = view.findViewById(R.id.service_card);
        serviceNameValue = view.findViewById(R.id.service_name_value);
        uuidValue = view.findViewById(R.id.uuid_value);
        devicePanel = view.findViewById(R.id.device_panel);
        deviceList = view.findViewById(R.id.device_list);
        deviceEmpty = view.findViewById(R.id.device_empty);
        refreshDevices = view.findViewById(R.id.refresh_devices);
        statsGrid = view.findViewById(R.id.stats_grid);

        StatsGridBuilder.build(statsGrid, cards);

        deviceAdapter = new PairedDeviceAdapter(devices, this);
        deviceList.setLayoutManager(new LinearLayoutManager(getContext()));
        deviceList.setAdapter(deviceAdapter);

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

    private void onPulseClicked() {
        if (state.phase() == TransmitterState.Phase.RUNNING) {
            GpsOutputService.stop(requireContext());
            return;
        }
        if (prefs.getMode() == ConnectionMode.CLIENT
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
        pulseButton.setState(running
                ? PulseButtonView.State.RUNNING : PulseButtonView.State.IDLE);

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
        TransmitterState.Device device = snapshot.connectedDevice();
        set(StatsGridBuilder.KEY_DEVICE, device == null ? unknown : device.name);
    }

    private void set(String key, String value) {
        StatCardView card = cards.get(key);
        if (card != null) {
            card.setValue(value);
        }
    }

    private void renderPanels() {
        boolean client = prefs.getMode() == ConnectionMode.CLIENT;
        serviceCard.setVisibility(client ? View.GONE : View.VISIBLE);
        devicePanel.setVisibility(client ? View.VISIBLE : View.GONE);
        modeHint.setText(client
                ? R.string.home_mode_client_hint : R.string.home_mode_server_hint);
    }
}
