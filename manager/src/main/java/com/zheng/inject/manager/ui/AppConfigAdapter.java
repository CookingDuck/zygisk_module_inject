package com.zheng.inject.manager.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.zheng.inject.manager.R;
import com.zheng.inject.manager.model.InjectConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AppConfigAdapter extends RecyclerView.Adapter<AppConfigAdapter.ViewHolder> {

    public enum Filter { ALL, ENABLED, DISABLED }

    private final List<InjectConfig> source;
    private final List<InjectConfig> visible = new ArrayList<>();
    private final PackageManager pm;
    private final OnItemClickListener listener;

    private String query = "";
    private Filter filter = Filter.ALL;

    public interface OnItemClickListener {
        void onItemClick(InjectConfig config);
        void onToggleChange(InjectConfig config, boolean isChecked);
    }

    public AppConfigAdapter(Context context, List<InjectConfig> configs, OnItemClickListener listener) {
        this.source = configs;
        this.pm = context.getPackageManager();
        this.listener = listener;
        rebuild();
    }

    public void setQuery(String q) {
        this.query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        rebuild();
    }

    public void setFilter(Filter f) {
        this.filter = f == null ? Filter.ALL : f;
        rebuild();
    }

    public Filter getFilter() {
        return filter;
    }

    public void refresh() {
        rebuild();
    }

    public boolean isEmpty() {
        return visible.isEmpty();
    }

    private void rebuild() {
        visible.clear();
        for (InjectConfig c : source) {
            if (filter == Filter.ENABLED && !c.loadSo) continue;
            if (filter == Filter.DISABLED && c.loadSo) continue;
            if (!query.isEmpty() && !matches(c, query)) continue;
            visible.add(c);
        }
        notifyDataSetChanged();
    }

    private boolean matches(InjectConfig c, String q) {
        if (c.packageName != null && c.packageName.toLowerCase(Locale.ROOT).contains(q)) return true;
        try {
            ApplicationInfo info = pm.getApplicationInfo(c.packageName, 0);
            String label = pm.getApplicationLabel(info).toString().toLowerCase(Locale.ROOT);
            if (label.contains(q)) return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return !TextUtils.isEmpty(c.soName) && c.soName.toLowerCase(Locale.ROOT).contains(q);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_config, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InjectConfig config = visible.get(position);
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(config.packageName, 0);
            holder.appName.setText(pm.getApplicationLabel(appInfo));
            holder.appIcon.setImageDrawable(pm.getApplicationIcon(appInfo));
        } catch (PackageManager.NameNotFoundException e) {
            holder.appName.setText(config.packageName);
            holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        holder.pkgName.setText(config.packageName);

        String model = config.injectModel == null ? "memfd" : config.injectModel;
        holder.badgeModel.setText(model);

        String soName = TextUtils.isEmpty(config.soName) ? "(未设置 SO)" : config.soName;
        holder.soInfo.setText(soName);

        holder.statusDot.setBackgroundResource(
                config.loadSo ? R.drawable.dot_enabled : R.drawable.dot_disabled);

        holder.switchLoad.setOnCheckedChangeListener(null);
        holder.switchLoad.setChecked(config.loadSo);

        holder.itemView.setOnClickListener(v -> listener.onItemClick(config));
        holder.switchLoad.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.loadSo = isChecked;
            holder.statusDot.setBackgroundResource(
                    isChecked ? R.drawable.dot_enabled : R.drawable.dot_disabled);
            listener.onToggleChange(config, isChecked);
        });
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView appIcon;
        final TextView appName, pkgName, soInfo, badgeModel;
        final SwitchCompat switchLoad;
        final View statusDot;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.appIcon);
            appName = itemView.findViewById(R.id.appName);
            pkgName = itemView.findViewById(R.id.pkgName);
            soInfo = itemView.findViewById(R.id.soInfo);
            badgeModel = itemView.findViewById(R.id.badgeModel);
            switchLoad = itemView.findViewById(R.id.switchLoad);
            statusDot = itemView.findViewById(R.id.statusDot);
            if (itemView instanceof MaterialCardView) {
                ((MaterialCardView) itemView).setRippleColorResource(R.color.divider);
            }
        }
    }
}
