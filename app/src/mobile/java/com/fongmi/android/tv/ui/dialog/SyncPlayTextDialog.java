package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSingleInputBinding;
import com.fongmi.android.tv.impl.SyncPlayListener;
import com.fongmi.android.tv.syncplay.SyncPlayServers;
import com.fongmi.android.tv.syncplay.SyncPlaySetting;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** 服务器地址 / 用户名 / 房间号的单行输入。 */
public class SyncPlayTextDialog extends BaseAlertDialog {

    public enum Kind {SERVER, USERNAME, ROOM}

    private static final String ARG_KIND = "kind";
    private static final String ARG_VALUE = "value";

    private DialogSingleInputBinding binding;
    private Kind kind = Kind.USERNAME;

    public static void show(Fragment fragment, Kind kind, String value) {
        SyncPlayTextDialog dialog = new SyncPlayTextDialog();
        Bundle args = new Bundle();
        args.putString(ARG_KIND, kind.name());
        args.putString(ARG_VALUE, value == null ? "" : value);
        dialog.setArguments(args);
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    private String value(String key) {
        Bundle args = getArguments();
        String text = args == null ? null : args.getString(key);
        return text == null ? "" : text;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSingleInputBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        kind = parseKind(value(ARG_KIND));
        int title = switch (kind) {
            case SERVER -> R.string.syncplay_server_address;
            case USERNAME -> R.string.syncplay_username;
            case ROOM -> R.string.syncplay_room;
        };
        return builder().setTitle(title).setView(getBinding().getRoot()).setPositiveButton(R.string.dialog_positive, this::onPositive).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        String text = value(ARG_VALUE);
        int max = switch (kind) {
            case SERVER -> 0;
            case USERNAME -> 16;
            case ROOM -> 35;
        };
        if (max > 0) binding.text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        binding.text.setText(text);
        binding.text.setSelection(text.length());
    }

    @Override
    protected void initEvent() {
        binding.text.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive(null, 0);
            return true;
        });
    }

    private void onPositive(DialogInterface dialog, int which) {
        String text = binding.text.getText() == null ? "" : binding.text.getText().toString().trim();
        SyncPlayListener listener = listener();
        switch (kind) {
            case SERVER -> onServer(text, listener);
            case USERNAME -> {
                SyncPlaySetting.putUsername(text);
                if (listener != null) listener.setSyncPlayUsername(SyncPlaySetting.getUsername());
            }
            case ROOM -> {
                SyncPlaySetting.putRoom(text);
                if (listener != null) listener.setSyncPlayRoom(SyncPlaySetting.getRoom());
            }
        }
        dismiss();
    }

    /** 添加服务器：成功或已存在都直接选用该地址，减少一次点击。 */
    private void onServer(String address, SyncPlayListener listener) {
        if (TextUtils.isEmpty(address)) return;
        SyncPlayServers.Result result = SyncPlayServers.add("", address);
        if (result == SyncPlayServers.Result.INVALID) {
            Notify.show(R.string.syncplay_error_server);
            return;
        }
        if (result == SyncPlayServers.Result.LIMIT) {
            Notify.show(R.string.syncplay_server_limit);
            return;
        }
        SyncPlaySetting.putServer(address);
        if (listener != null) listener.setSyncPlayServer(address);
    }

    @Nullable
    private SyncPlayListener listener() {
        Fragment parent = getParentFragment();
        if (parent instanceof SyncPlayListener value) return value;
        return requireActivity() instanceof SyncPlayListener value ? value : null;
    }

    private static Kind parseKind(String name) {
        for (Kind value : Kind.values()) if (value.name().equals(name)) return value;
        return Kind.USERNAME;
    }
}
