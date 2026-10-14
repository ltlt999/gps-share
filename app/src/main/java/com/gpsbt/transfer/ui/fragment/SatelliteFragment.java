package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.SatelliteOrder;
import com.gpsbt.transfer.ui.adapter.SatelliteAdapter;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;

import java.util.ArrayList;
import java.util.List;

public class SatelliteFragment extends Fragment implements TransmitterState.Listener {

    private TransmitterState state;
    private SatelliteRadarView radarView;
    private RecyclerView listView;
    private TextView emptyView;
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
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        radarView = view.findViewById(R.id.radar_view);
        listView = view.findViewById(R.id.satellite_list);
        emptyView = view.findViewById(R.id.satellite_empty);
        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SatelliteAdapter(data);
        listView.setAdapter(adapter);
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
        data.clear();
        if (state.phase() == TransmitterState.Phase.RUNNING) {
            data.addAll(state.satellites());
        }
        // 正在用于定位的排前面，同组按信噪比从高到低——好用的先看到。
        // 雷达图按方位/仰角画点，与顺序无关，一起排不影响它。
        SatelliteOrder.sort(data);
        adapter.notifyDataSetChanged();
        radarView.submit(data);
        emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
