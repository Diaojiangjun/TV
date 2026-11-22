package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.SyncPlayListener;
import com.fongmi.android.tv.syncplay.SyncPlayServers;
import com.fongmi.android.tv.syncplay.SyncPlaySetting;
import com.fongmi.syncplay.SyncPlayEndpoint;
import com.fongmi.syncplay.SyncPlayServer;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器选择/管理：点内置项直接选用；点自定义项再问「使用 / 删除」；
 * 最后一项是添加。列表来自 {@link SyncPlayServers}，用户可以自己加局域网服务端。
 */
public class SyncPlayServerDialog extends DialogFragment {

    public static void show(Fragment fragment) {
        new SyncPlayServerDialog().show(fragment.getChildFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.setting_syncplay)
                .setItems(labels(), (dialog, which) -> onPick(which))
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
    }

    private List<SyncPlayServer> servers() {
        return SyncPlayServers.all();
    }

    private String[] labels() {
        List<String> labels = new ArrayList<>();
        String current = SyncPlaySetting.getServer();
        for (SyncPlayServer server : servers()) {
            labels.add(server.getAddress().equalsIgnoreCase(current) ? "✓ " + server.getLabel() : server.getLabel());
        }
        labels.add("＋ " + getString(R.string.syncplay_server_add));
        return labels.toArray(new String[0]);
    }

    private void onPick(int which) {
        List<SyncPlayServer> list = servers();
        if (which >= list.size()) {
            Fragment parent = getParentFragment();
            if (parent != null) SyncPlayTextDialog.show(parent, SyncPlayTextDialog.Kind.SERVER, "");
            return;
        }
        SyncPlayServer server = list.get(which);
        if (server.isBuiltin()) {
            select(server.getAddress());
            return;
        }
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(server.getLabel())
                .setItems(new String[]{getString(R.string.syncplay_server_use), getString(R.string.syncplay_server_delete)}, (dialog, index) -> {
                    if (index == 0) select(server.getAddress());
                    else confirmDelete(server);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void confirmDelete(SyncPlayServer server) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setMessage(getString(R.string.syncplay_server_delete_confirm, server.getLabel()))
                .setPositiveButton(R.string.syncplay_server_delete, (dialog, which) -> {
                    boolean current = server.getAddress().equalsIgnoreCase(SyncPlaySetting.getServer());
                    SyncPlayServers.remove(server.getAddress());
                    Notify.show(R.string.syncplay_server_deleted);
                    // 删掉的正是当前使用的服务器，退回内置默认，避免设置页指向一个不存在的条目
                    if (current) select(SyncPlayEndpoint.DEFAULT);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void select(String address) {
        SyncPlaySetting.putServer(address);
        SyncPlayListener listener = listener();
        if (listener != null) listener.setSyncPlayServer(address);
        dismiss();
    }

    @Nullable
    private SyncPlayListener listener() {
        Fragment parent = getParentFragment();
        if (parent instanceof SyncPlayListener value) return value;
        return requireActivity() instanceof SyncPlayListener value ? value : null;
    }
}
