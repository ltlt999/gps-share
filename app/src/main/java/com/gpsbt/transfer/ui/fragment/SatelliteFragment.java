package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.SatelliteOrder;
import com.gpsbt.transfer.ui.adapter.SatelliteAdapter;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;
import com.gpsbt.transfer.util.Prefs;

import java.util.ArrayList;
import java.util.List;

public class SatelliteFragment extends Fragment implements TransmitterState.Listener {

    private TransmitterState state;
    private Prefs prefs;
    private SatelliteRadarView radarView;
    private RecyclerView listView;
    private TextView emptyView;
    private TextView listTitle;
    private MaterialSwitch swOnlyActive;
    private SatelliteAdapter adapter;
    private final List<SatelliteRadarView.Sat> data = new ArrayList<SatelliteRadarView.Sat>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_satellite, container, false);
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
        radarView = view.findViewById(R.id.radar_view);
        listView = view.findViewById(R.id.satellite_list);
        emptyView = view.findViewById(R.id.satellite_empty);
        listTitle = view.findViewById(R.id.satellite_list_title);
        swOnlyActive = view.findViewById(R.id.sw_only_active);
        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SatelliteAdapter(data);
        listView.setAdapter(adapter);

        // 先设初值再挂监听：setChecked 会触发回调，顺序反了会白写一次 Prefs
        swOnlyActive.setChecked(prefs.isSatelliteOnlyActive());
        swOnlyActive.setOnCheckedChangeListener(new MaterialSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                prefs.setSatelliteOnlyActive(isChecked);
                render();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        state.addListener(this);
        render();
    }

    @Override
    public void onStop() {
        state.removeListener(this);
        super.onStop();
    }

    @Override
    public void onStateChanged(TransmitterState changed) {
        render();
    }

    private void render() {
        List<SatelliteRadarView.Sat> all = state.phase() == TransmitterState.Phase.RUNNING
                ? new ArrayList<>(state.satellites())
                : new ArrayList<SatelliteRadarView.Sat>();
        // 正在用于定位的排前面，同组按信噪比从高到低——好用的先看到
        SatelliteOrder.sort(all);

        boolean onlyActive = prefs.isSatelliteOnlyActive();
        data.clear();
        for (SatelliteRadarView.Sat sat : all) {
            if (!onlyActive || isActive(sat)) {
                data.add(sat);
            }
        }

        listTitle.setText(getString(R.string.satellite_list_title_count,
                data.size(), all.size()));
        adapter.notifyDataSetChanged();
        // 雷达图始终画**全部**卫星：它是「天空视图」，不跟随列表筛选，
        // 否则打开筛选后会让人误以为天上只剩这几颗
        radarView.submit(all);
        emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 「有信号」= 正在用于定位，或已搜到（信噪比 &gt; 0）但还没参与解算。 */
    private static boolean isActive(SatelliteRadarView.Sat sat) {
        return sat.used || sat.snr > 0;
    }
}
