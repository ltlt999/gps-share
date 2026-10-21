package com.gpsbt.transfer.ui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.license.本地配置;
import com.gpsbt.transfer.nmea.NmeaSentenceType;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.util.LocationPermission;
import com.gpsbt.transfer.util.Prefs;

import java.util.EnumSet;
import java.util.Set;

public class SettingsFragment extends Fragment {

    private Prefs prefs;

    private Slider rateSlider;
    private TextView rateValue;
    private MaterialSwitch swGga;
    private MaterialSwitch swRmc;
    private MaterialSwitch swGsa;
    private MaterialSwitch swGsv;
    private MaterialSwitch swVtg;
    private TextInputEditText inputServiceName;
    private TextInputEditText inputUuid;
    private Button restoreDefaults;
    private MaterialSwitch swAllowNetwork;
    private MaterialSwitch swAutostart;
    private Slider staleSlider;
    private TextView staleValue;
    private Slider hdopSlider;
    private TextView hdopValue;
    private TextView about;
    private TextView locationPermState;
    private ActivityResultLauncher<String[]> preciseLocationLauncher;
    private Button requestPrecise;
    private TextView licenseDevice;
    private TextView licenseState;
    private TextInputEditText inputLicense;
    private TextInputEditText inputWifiPort;
    private MaterialSwitch swDiscovery;
    private TextInputEditText inputDiscoveryPort;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(requireContext());
        // 只请求 FINE 时，系统会弹「升级到精确位置」的提示
        preciseLocationLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> renderLocationPermission());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rateSlider = view.findViewById(R.id.rate_slider);
        rateValue = view.findViewById(R.id.rate_value);
        swGga = view.findViewById(R.id.sw_gga);
        swRmc = view.findViewById(R.id.sw_rmc);
        swGsa = view.findViewById(R.id.sw_gsa);
        swGsv = view.findViewById(R.id.sw_gsv);
        swVtg = view.findViewById(R.id.sw_vtg);
        inputServiceName = view.findViewById(R.id.input_service_name);
        inputUuid = view.findViewById(R.id.input_uuid);
        restoreDefaults = view.findViewById(R.id.btn_restore_defaults);
        swAllowNetwork = view.findViewById(R.id.sw_allow_network);
        staleSlider = view.findViewById(R.id.stale_slider);
        staleValue = view.findViewById(R.id.stale_value);
        hdopSlider = view.findViewById(R.id.hdop_slider);
        hdopValue = view.findViewById(R.id.hdop_value);
        inputWifiPort = view.findViewById(R.id.input_wifi_port);
        swDiscovery = view.findViewById(R.id.sw_discovery);
        inputDiscoveryPort = view.findViewById(R.id.input_discovery_port);
        about = view.findViewById(R.id.about_body);
        locationPermState = view.findViewById(R.id.location_perm_state);
        requestPrecise = view.findViewById(R.id.btn_request_precise);
        requestPrecise.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                preciseLocationLauncher.launch(LocationPermission.preciseRequest());
            }
        });
        licenseDevice = view.findViewById(R.id.license_device);
        licenseState = view.findViewById(R.id.license_state);
        inputLicense = view.findViewById(R.id.input_license);

        about.setText(getString(R.string.settings_about_body, versionName()));

        licenseDevice.setText(本地配置.deviceCode(requireContext()));
        renderLicenseState();

        view.findViewById(R.id.btn_copy_device).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyDeviceCode();
            }
        });
        view.findViewById(R.id.btn_apply_license).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyLicense();
            }
        });

        swAutostart = view.findViewById(R.id.sw_autostart);
        swAutostart.setChecked(prefs.isAutoStartOnBoot());
        swAutostart.setOnCheckedChangeListener(
                new MaterialSwitch.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(@NonNull android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        prefs.setAutoStartOnBoot(isChecked);
                        LogBus.get().log(LogBus.Level.INFO,
                                isChecked ? "已开启开机自动传输" : "已关闭开机自动传输");
                    }
                });

        rateSlider.setValue(Math.max(1, prefs.getRateHz()));
        rateValue.setText(getString(R.string.settings_rate_value, prefs.getRateHz()));
        rateSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                int hz = Math.max(1, Math.round(value));
                prefs.setRateHz(hz);
                rateValue.setText(getString(R.string.settings_rate_value, hz));
            }
        });

        Set<NmeaSentenceType> enabled = prefs.getSentences();
        swGga.setChecked(enabled.contains(NmeaSentenceType.GGA));
        swRmc.setChecked(enabled.contains(NmeaSentenceType.RMC));
        swGsa.setChecked(enabled.contains(NmeaSentenceType.GSA));
        swGsv.setChecked(enabled.contains(NmeaSentenceType.GSV));
        swVtg.setChecked(enabled.contains(NmeaSentenceType.VTG));

        MaterialSwitch.OnCheckedChangeListener sentenceListener =
                new MaterialSwitch.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(@NonNull android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        saveSentences();
                    }
                };
        swGga.setOnCheckedChangeListener(sentenceListener);
        swRmc.setOnCheckedChangeListener(sentenceListener);
        swGsa.setOnCheckedChangeListener(sentenceListener);
        swGsv.setOnCheckedChangeListener(sentenceListener);
        swVtg.setOnCheckedChangeListener(sentenceListener);

        inputServiceName.setText(prefs.getServiceName());
        inputUuid.setText(prefs.getUuid());
        inputServiceName.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    prefs.setServiceName(textOf(inputServiceName, Prefs.DEFAULT_SERVICE_NAME));
                }
            }
        });
        inputUuid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    prefs.setUuid(textOf(inputUuid, Prefs.DEFAULT_UUID));
                }
            }
        });

        restoreDefaults.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setServiceName(Prefs.DEFAULT_SERVICE_NAME);
                prefs.setUuid(Prefs.DEFAULT_UUID);
                inputServiceName.setText(prefs.getServiceName());
                inputUuid.setText(prefs.getUuid());
            }
        });

        swAllowNetwork.setChecked(prefs.isAllowNetwork());
        swAllowNetwork.setOnCheckedChangeListener(
                new MaterialSwitch.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(@NonNull android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        prefs.setAllowNetwork(isChecked);
                    }
                });

        staleSlider.setValue(prefs.getStaleMillis() / 1000f);
        staleValue.setText(getString(R.string.settings_stale_value,
                Math.round(prefs.getStaleMillis() / 1000f)));
        staleSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                prefs.setStaleMillis(Math.round(value) * 1000L);
                staleValue.setText(getString(R.string.settings_stale_value, Math.round(value)));
            }
        });

        hdopSlider.setValue(prefs.getDefaultHdop());
        hdopValue.setText(getString(R.string.settings_hdop_value, prefs.getDefaultHdop()));
        hdopSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                prefs.setDefaultHdop(value);
                hdopValue.setText(getString(R.string.settings_hdop_value, value));
            }
        });

        inputWifiPort.setText(String.valueOf(prefs.getWifiPort()));
        inputWifiPort.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    prefs.setWifiPort(portOf(inputWifiPort, Prefs.DEFAULT_WIFI_PORT));
                    inputWifiPort.setText(String.valueOf(prefs.getWifiPort()));
                }
            }
        });

        swDiscovery.setChecked(prefs.isDiscoveryEnabled());
        swDiscovery.setOnCheckedChangeListener(new MaterialSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull android.widget.CompoundButton buttonView,
                                         boolean isChecked) {
                prefs.setDiscoveryEnabled(isChecked);
            }
        });

        inputDiscoveryPort.setText(String.valueOf(prefs.getDiscoveryPort()));
        inputDiscoveryPort.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    prefs.setDiscoveryPort(
                            portOf(inputDiscoveryPort, Prefs.DEFAULT_DISCOVERY_PORT));
                    inputDiscoveryPort.setText(String.valueOf(prefs.getDiscoveryPort()));
                }
            }
        });
    }

    private static String textOf(TextInputEditText field, String fallback) {
        if (field.getText() == null) {
            return fallback;
        }
        String value = field.getText().toString();
        return value.trim().isEmpty() ? fallback : value.trim();
    }

    /** 解析端口输入；非法或越界时回落到默认值（Prefs 会再做一次钳制）。 */
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

    private void saveSentences() {
        Set<NmeaSentenceType> result = EnumSet.noneOf(NmeaSentenceType.class);
        if (swGga.isChecked()) {
            result.add(NmeaSentenceType.GGA);
        }
        if (swRmc.isChecked()) {
            result.add(NmeaSentenceType.RMC);
        }
        if (swGsa.isChecked()) {
            result.add(NmeaSentenceType.GSA);
        }
        if (swGsv.isChecked()) {
            result.add(NmeaSentenceType.GSV);
        }
        if (swVtg.isChecked()) {
            result.add(NmeaSentenceType.VTG);
        }
        prefs.setSentences(result.isEmpty() ? NmeaSentenceType.all() : result);
    }

    /** 位置权限的精细状态：精确 / 大致 / 未授权，以及升级入口。 */
    private void renderLocationPermission() {
        if (locationPermState == null) {
            return;
        }
        LocationPermission.State state = LocationPermission.state(requireContext());
        switch (state) {
            case PRECISE:
                locationPermState.setText(R.string.settings_location_precise);
                locationPermState.setTextColor(
                        getResources().getColor(R.color.color_primary, null));
                break;
            case APPROXIMATE:
                locationPermState.setText(R.string.settings_location_approximate);
                locationPermState.setTextColor(
                        getResources().getColor(R.color.color_warning, null));
                break;
            default:
                locationPermState.setText(R.string.settings_location_denied);
                locationPermState.setTextColor(
                        getResources().getColor(R.color.color_error, null));
                break;
        }
        requestPrecise.setVisibility(
                state == LocationPermission.State.PRECISE ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从系统设置里改完权限回到这里，状态要跟着更新
        renderLocationPermission();
    }

    /** 刷新授权状态那一行。 */
    private void renderLicenseState() {
        Context context = requireContext();
        switch (本地配置.state(context)) {
            case ACTIVE:
                licenseState.setText(R.string.settings_license_active);
                licenseState.setTextColor(
                        getResources().getColor(R.color.color_primary, null));
                break;
            case TRIAL:
                licenseState.setText(getString(R.string.settings_license_trial,
                        本地配置.trialDaysLeft(context)));
                licenseState.setTextColor(
                        getResources().getColor(R.color.color_accent_blue, null));
                break;
            default:
                licenseState.setText(R.string.settings_license_expired);
                licenseState.setTextColor(getResources().getColor(R.color.color_error, null));
                break;
        }
    }

    /** 复制设备码：用户要把它发到作者邮箱换取校验码。 */
    private void copyDeviceCode() {
        ClipboardManager clipboard = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("device-code",
                    licenseDevice.getText()));
        }
        Toast.makeText(requireContext(), R.string.settings_license_copied,
                Toast.LENGTH_LONG).show();
    }

    private void applyLicense() {
        String code = inputLicense.getText() == null
                ? "" : inputLicense.getText().toString();
        if (本地配置.activate(requireContext(), code)) {
            inputLicense.setText("");
            renderLicenseState();
            Toast.makeText(requireContext(), R.string.settings_license_ok,
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(requireContext(), R.string.settings_license_bad,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 从系统读取真实版本号（此前硬编码为 1.0.0，导致版本更新后界面不变）。 */
    private String versionName() {
        try {
            android.content.Context context = requireContext();
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
