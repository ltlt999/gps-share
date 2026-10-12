package com.gpsbt.transfer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.util.Formatters;

import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

    private final List<LogBus.Entry> entries;
    private int errorColor;

    public LogAdapter(List<LogBus.Entry> entries) {
        this.entries = entries;
        setHasStableIds(true);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        errorColor = ContextCompat.getColor(parent.getContext(), R.color.color_error);
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LogBus.Entry entry = entries.get(position);
        holder.time.setText(Formatters.clockOfDay(entry.time));
        holder.message.setText(entry.message);
        int color = R.color.color_text_primary;
        if (entry.level == LogBus.Level.ERROR) {
            color = R.color.color_error;
        } else if (entry.level == LogBus.Level.WARN) {
            color = R.color.color_warning;
        } else if (entry.level == LogBus.Level.SAT) {
            color = R.color.color_accent_blue;
        } else if (entry.level == LogBus.Level.DATA) {
            color = R.color.color_text_secondary;
        }
        holder.message.setTextColor(
                ContextCompat.getColor(holder.itemView.getContext(), color));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @Override
    public long getItemId(int position) {
        return entries.get(position).time * 31 + position;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView message;

        Holder(View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.log_time);
            message = itemView.findViewById(R.id.log_message);
        }
    }
}
