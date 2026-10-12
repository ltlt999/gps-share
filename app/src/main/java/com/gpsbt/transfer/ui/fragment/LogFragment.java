package com.gpsbt.transfer.ui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.ui.adapter.LogAdapter;
import com.gpsbt.transfer.util.Formatters;
import com.gpsbt.transfer.util.LogExporter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LogFragment extends Fragment implements LogBus.Listener {

    /** 日志筛选类别：普通（连接与状态）/ 卫星（GSV、GSA）/ 数据（其它原始语句）。 */
    private enum Filter { NORMAL, SATELLITE, DATA, ALL }

    private RecyclerView listView;
    private TextView emptyView;
    private View copyButton;
    private View clearButton;
    private View exportButton;
    private LogAdapter adapter;
    private final List<LogBus.Entry> allEntries = new ArrayList<LogBus.Entry>();
    private final List<LogBus.Entry> visibleEntries = new ArrayList<LogBus.Entry>();
    private Filter filter = Filter.NORMAL;
    private Button[] filterButtons;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.log_list);
        emptyView = view.findViewById(R.id.log_empty);
        copyButton = view.findViewById(R.id.log_copy);
        clearButton = view.findViewById(R.id.log_clear);
        exportButton = view.findViewById(R.id.log_export);

        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new LogAdapter(visibleEntries);
        listView.setAdapter(adapter);

        filterButtons = new Button[]{
                view.findViewById(R.id.log_filter_normal),
                view.findViewById(R.id.log_filter_satellite),
                view.findViewById(R.id.log_filter_data),
                view.findViewById(R.id.log_filter_all)
        };
        for (int i = 0; i < filterButtons.length; i++) {
            final Filter target = Filter.values()[i];
            filterButtons[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    filter = target;
                    refreshFilterHighlight();
                    rebuild();
                }
            });
        }
        refreshFilterHighlight();

        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        });
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogBus.get().clear();
                allEntries.clear();
                visibleEntries.clear();
                adapter.notifyDataSetChanged();
                renderEmpty();
            }
        });
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        LogBus.get().addListener(this);
        allEntries.clear();
        allEntries.addAll(LogBus.get().entries());
        rebuild();
    }

    @Override
    public void onStop() {
        LogBus.get().removeListener(this);
        super.onStop();
    }

    @Override
    public void onLog(LogBus.Entry entry) {
        if (!isAdded()) {
            return;
        }
        allEntries.add(entry);
        if (!accepts(entry)) {
            return;
        }
        visibleEntries.add(entry);
        adapter.notifyItemInserted(visibleEntries.size() - 1);
        listView.scrollToPosition(visibleEntries.size() - 1);
        renderEmpty();
    }

    private boolean accepts(LogBus.Entry entry) {
        switch (filter) {
            case SATELLITE:
                return entry.level == LogBus.Level.SAT;
            case DATA:
                return entry.level == LogBus.Level.DATA;
            case ALL:
                return true;
            case NORMAL:
            default:
                return entry.level == LogBus.Level.INFO
                        || entry.level == LogBus.Level.WARN
                        || entry.level == LogBus.Level.ERROR;
        }
    }

    private void rebuild() {
        visibleEntries.clear();
        for (LogBus.Entry entry : allEntries) {
            if (accepts(entry)) {
                visibleEntries.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        if (!visibleEntries.isEmpty()) {
            listView.scrollToPosition(visibleEntries.size() - 1);
        }
        renderEmpty();
    }

    private void refreshFilterHighlight() {
        if (filterButtons == null) {
            return;
        }
        int active = ContextCompat.getColor(requireContext(), R.color.color_primary);
        int inactive = ContextCompat.getColor(requireContext(), R.color.color_text_secondary);
        for (int i = 0; i < filterButtons.length; i++) {
            filterButtons[i].setTextColor(Filter.values()[i] == filter ? active : inactive);
        }
    }

    private void renderEmpty() {
        emptyView.setVisibility(visibleEntries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void copy() {
        StringBuilder builder = new StringBuilder();
        for (LogBus.Entry entry : LogBus.get().entries()) {
            builder.append(Formatters.clockOfDay(entry.time))
                    .append(' ').append(entry.level.name())
                    .append(' ').append(entry.message).append('\n');
        }
        ClipboardManager manager = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name),
                    builder.toString()));
        }
        Toast.makeText(getContext(), R.string.log_copied, Toast.LENGTH_SHORT).show();
    }

    private void export() {
        try {
            String path = LogExporter.export(requireContext());
            Toast.makeText(getContext(), getString(R.string.log_exported, path),
                    Toast.LENGTH_LONG).show();
            LogBus.get().log(LogBus.Level.INFO, "日志已导出：" + path);
        } catch (IOException e) {
            Toast.makeText(getContext(), getString(R.string.log_export_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            LogBus.get().log(LogBus.Level.ERROR, "日志导出失败：" + e.getMessage());
        }
    }
}
