package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.nmea.NmeaSentenceType;
import com.gpsbt.transfer.state.LogBus;
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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(requireContext());
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
        about = view.findViewById(R.id.about_body);

        about.setText(getString(R.string.settings_about_body, versionName()));

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
    }

    private static String textOf(TextInputEditText field, String fallback) {
        if (field.getText() == null) {
            return fallback;
        }
        String value = field.getText().toString();
        return value.trim().isEmpty() ? fallback : value.trim();
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
