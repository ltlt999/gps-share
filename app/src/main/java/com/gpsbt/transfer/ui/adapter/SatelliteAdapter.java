package com.gpsbt.transfer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.ui.Constellations;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;

import java.util.List;
import java.util.Locale;

public class SatelliteAdapter extends RecyclerView.Adapter<SatelliteAdapter.Holder> {

    private final List<SatelliteRadarView.Sat> satellites;

    public SatelliteAdapter(List<SatelliteRadarView.Sat> satellites) {
        this.satellites = satellites;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_satellite, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SatelliteRadarView.Sat sat = satellites.get(position);
        // 星座国旗 + 名称：一眼看出这颗星属于哪个系统
        holder.flag.setImageResource(Constellations.flagResOf(sat.talker));
        holder.constellation.setText(Constellations.nameOf(holder.itemView.getContext(),
                sat.talker));
        holder.prn.setText(String.format(Locale.US, "PRN %d", sat.prn));
        holder.elevation.setText(sat.elevationDeg + "°");
        holder.azimuth.setText(sat.azimuthDeg + "°");
        holder.snr.setText(sat.snr > 0 ? String.valueOf(sat.snr) : "--");
        holder.used.setText(sat.used
                ? holder.itemView.getContext().getString(R.string.satellite_yes)
                : holder.itemView.getContext().getString(R.string.satellite_no));
        holder.used.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                sat.used ? R.color.color_primary : R.color.color_text_disabled));
    }

    @Override
    public int getItemCount() {
        return satellites.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView flag;
        final TextView constellation;
        final TextView prn;
        final TextView elevation;
        final TextView azimuth;
        final TextView snr;
        final TextView used;

        Holder(View itemView) {
            super(itemView);
            flag = itemView.findViewById(R.id.sat_flag);
            constellation = itemView.findViewById(R.id.sat_constellation);
            prn = itemView.findViewById(R.id.sat_prn);
            elevation = itemView.findViewById(R.id.sat_elevation);
            azimuth = itemView.findViewById(R.id.sat_azimuth);
            snr = itemView.findViewById(R.id.sat_snr);
            used = itemView.findViewById(R.id.sat_used);
        }
    }
}
