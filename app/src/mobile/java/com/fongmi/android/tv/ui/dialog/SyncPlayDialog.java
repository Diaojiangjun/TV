package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSyncplayBinding;
import com.fongmi.android.tv.impl.SyncPlayListener;
import com.fongmi.android.tv.syncplay.SyncPlay;
import com.fongmi.android.tv.syncplay.SyncPlayServers;
import com.fongmi.android.tv.syncplay.SyncPlaySetting;
import com.fongmi.syncplay.SyncPlayEndpoint;
import com.fongmi.syncplay.SyncPlayServer;
import com.fongmi.syncplay.SyncPlaySession;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class SyncPlayDialog extends BaseAlertDialog implements SyncPlayListener {

    private DialogSyncplayBinding binding;
    private final SyncPlaySession.Observer observer = this::render;

    public static void show(FragmentActivity activity) {
        new SyncPlayDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSyncplayBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.syncplay).setView(getBinding().getRoot()).setPositiveButton(R.string.syncplay_connect, null).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        binding.server.setText(SyncPlaySetting.getServer());
        binding.username.setText(SyncPlaySetting.getUsername());
        binding.room.setText(SyncPlaySetting.getRoom());
        SyncPlay.get().addObserver(observer);
        render();
    }

    @Override
    protected void initEvent() {
        binding.create.setOnClickListener(view -> {
            binding.room.setText(SyncPlaySetting.createRoom());
            onConnect();
        });
        binding.choose.setOnClickListener(this::onChooseServer);
        binding.chatSend.setOnClickListener(view -> sendChat());
        binding.chatInput.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) sendChat();
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog alert) {
            Button positive = alert.getButton(AlertDialog.BUTTON_POSITIVE);
            Button negative = alert.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (positive != null) positive.setOnClickListener(view -> onPositive());
            if (negative != null) negative.setOnClickListener(view -> dismiss());
        }
        render();
    }

    @Override
    public void onDestroyView() {
        SyncPlay.get().removeObserver(observer);
        super.onDestroyView();
    }

    private void onPositive() {
        if (SyncPlay.get().isConnected()) {
            SyncPlay.get().disconnect();
            render();
            return;
        }
        onConnect();
    }

    private void onConnect() {
        String server = text(binding.server.getText() == null ? "" : binding.server.getText().toString());
        String username = text(binding.username.getText() == null ? "" : binding.username.getText().toString());
        String room = text(binding.room.getText() == null ? "" : binding.room.getText().toString());
        if (SyncPlayEndpoint.parse(server) == null) {
            Notify.show(R.string.syncplay_error_server);
            return;
        }
        if (room.isEmpty()) {
            Notify.show(R.string.syncplay_error_room);
            return;
        }
        SyncPlay.get().connect(server, username, room, () -> Notify.show(R.string.syncplay_error_server));
        render();
    }

    /** 服务器下拉：内置项直接填，最后一项转到输入框添加自定义地址。 */
    private void onChooseServer(View view) {
        List<SyncPlayServer> servers = SyncPlayServers.all();
        List<String> labels = new ArrayList<>();
        String current = text(binding.server.getText() == null ? "" : binding.server.getText().toString());
        for (SyncPlayServer server : servers) {
            labels.add(server.getAddress().equalsIgnoreCase(current) ? "✓ " + server.getLabel() : server.getLabel());
        }
        labels.add("＋ " + getString(R.string.syncplay_server_add));
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.syncplay_server)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < servers.size()) binding.server.setText(servers.get(which).getAddress());
                    else SyncPlayTextDialog.show(this, SyncPlayTextDialog.Kind.SERVER, "");
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void sendChat() {
        String message = binding.chatInput.getText() == null ? "" : binding.chatInput.getText().toString().trim();
        if (message.isEmpty()) return;
        SyncPlay.get().sendChat(message);
        binding.chatInput.setText("");
    }

    private void render() {
        if (binding == null) return;
        SyncPlaySession session = SyncPlay.get();
        boolean connected = session.isConnected();
        boolean busy = connected || session.getState() == SyncPlaySession.State.CONNECTING;
        binding.server.setEnabled(!busy);
        binding.username.setEnabled(!busy);
        binding.room.setEnabled(!busy);
        binding.create.setEnabled(!busy);
        binding.choose.setEnabled(!busy);
        if (connected) {
            binding.status.setText(getString(R.string.syncplay_connected, session.getRoom(), session.getLatencyMs(), session.getMemberCount()));
        } else if (session.getState() == SyncPlaySession.State.CONNECTING) {
            binding.status.setText(R.string.syncplay_connecting);
        } else {
            binding.status.setText(R.string.syncplay_hint);
        }
        List<String> members = session.getMembers();
        binding.members.setVisibility(connected && !members.isEmpty() ? View.VISIBLE : View.GONE);
        if (connected && !members.isEmpty()) binding.members.setText(getString(R.string.syncplay_members, TextUtils.join("、", members)));
        binding.chatBox.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (connected) renderChat(session.getMessages());
        Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog alert) {
            Button positive = alert.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) positive.setText(connected ? R.string.syncplay_disconnect : R.string.syncplay_connect);
        }
    }

    private void renderChat(List<SyncPlaySession.Message> messages) {
        if (messages.isEmpty()) {
            binding.chat.setText(R.string.syncplay_chat_empty);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (SyncPlaySession.Message message : messages) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(message.getUser()).append("：").append(message.getText());
        }
        binding.chat.setText(builder);
        binding.chatScroll.post(() -> binding.chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public void setSyncPlayServer(String address) {
        if (binding != null) binding.server.setText(address);
    }

    @Override
    public void setSyncPlayUsername(String username) {
        if (binding != null) binding.username.setText(username);
    }

    @Override
    public void setSyncPlayRoom(String room) {
        if (binding != null) binding.room.setText(room);
    }
}
