package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingSyncplayBinding;
import com.fongmi.android.tv.impl.SyncPlayListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.syncplay.SyncPlay;
import com.fongmi.android.tv.syncplay.SyncPlaySetting;
import com.fongmi.syncplay.SyncPlaySession;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.SyncPlayServerDialog;
import com.fongmi.android.tv.ui.dialog.SyncPlayTextDialog;

public class SettingSyncPlayFragment extends BaseFragment implements SyncPlayListener {

    private FragmentSettingSyncplayBinding mBinding;
    private final SyncPlaySession.Observer observer = this::render;

    public static SettingSyncPlayFragment newInstance() {
        return new SettingSyncPlayFragment();
    }

    private String getRoomText() {
        String room = SyncPlaySetting.getRoom();
        return TextUtils.isEmpty(room) ? getString(R.string.none) : room;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingSyncplayBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        SyncPlay.get().addObserver(observer);
        render();
    }

    @Override
    protected void initEvent() {
        mBinding.syncplayStatus.setOnClickListener(this::onStatus);
        mBinding.syncplayServer.setOnClickListener(this::onServer);
        mBinding.syncplayUsername.setOnClickListener(this::onUsername);
        mBinding.syncplayRoom.setOnClickListener(this::onRoom);
        mBinding.syncplayFollow.setOnClickListener(this::onFollow);
        mBinding.syncplayReconnect.setOnClickListener(this::onReconnect);
    }

    @Override
    public void onDestroyView() {
        SyncPlay.get().removeObserver(observer);
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) render();
    }

    private void render() {
        if (mBinding == null) return;
        SyncPlaySession session = SyncPlay.get();
        mBinding.syncplayServerText.setText(SyncPlaySetting.getServerLabel());
        mBinding.syncplayUsernameText.setText(SyncPlaySetting.getUsername());
        mBinding.syncplayRoomText.setText(getRoomText());
        mBinding.syncplayFollowText.setText(Setting.getSwitch(SyncPlaySetting.isFollowEpisode()));
        mBinding.syncplayReconnectText.setText(Setting.getSwitch(SyncPlaySetting.isReconnect()));
        if (session.isConnected()) {
            mBinding.syncplayStatusText.setText(getString(R.string.syncplay_connected, session.getRoom(), session.getLatencyMs(), session.getMemberCount()));
        } else if (session.getState() == SyncPlaySession.State.CONNECTING) {
            mBinding.syncplayStatusText.setText(R.string.syncplay_connecting);
        } else {
            mBinding.syncplayStatusText.setText(R.string.syncplay_status_off);
        }
    }

    private void onStatus(View view) {
        if (SyncPlay.get().isConnected()) {
            SyncPlay.get().disconnect();
            render();
        }
    }

    private void onServer(View view) {
        SyncPlayServerDialog.show(this);
    }

    private void onUsername(View view) {
        SyncPlayTextDialog.show(this, SyncPlayTextDialog.Kind.USERNAME, SyncPlaySetting.getUsername());
    }

    private void onRoom(View view) {
        SyncPlayTextDialog.show(this, SyncPlayTextDialog.Kind.ROOM, SyncPlaySetting.getRoom());
    }

    private void onFollow(View view) {
        SyncPlaySetting.putFollowEpisode(!SyncPlaySetting.isFollowEpisode());
        render();
    }

    private void onReconnect(View view) {
        SyncPlaySetting.putReconnect(!SyncPlaySetting.isReconnect());
        render();
    }

    @Override
    public void setSyncPlayServer(String address) {
        render();
    }

    @Override
    public void setSyncPlayUsername(String username) {
        render();
    }

    @Override
    public void setSyncPlayRoom(String room) {
        render();
    }
}
