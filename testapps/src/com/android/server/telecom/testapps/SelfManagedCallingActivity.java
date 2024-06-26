/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.telecom.testapps;

import static android.app.UiModeManager.DEFAULT_PRIORITY;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.UiModeManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.ConnectionRequest;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Objects;

/**
 * Provides a sample third-party calling app UX which implements the self managed connection service
 * APIs.
 */
public class SelfManagedCallingActivity extends Activity {
    private static final String TAG = "SelfMgCallActivity";
    private static final int REQUEST_ID = 1;
    private SelfManagedCallList mCallList = SelfManagedCallList.getInstance();
    private CheckBox mCheckIfPermittedBeforeCalling;
    private Button mPlaceOutgoingCallButton;
    private Button mPlaceSelfManagedOutgoingCallButton;
    private Button mPlaceSelfManagedIncomingCallButton;
    private Button mPlaceIncomingCallButton;
    private Button mHandoverFrom;
    private Button mRequestCallScreeningRole;
    private Button mEnableCarMode;
    private Button mDisableCarMode;
    private RadioButton mUseAcct1Button;
    private RadioButton mUseAcct2Button;
    private RadioButton mUseAcct3Button;
    private CheckBox mHoldableCheckbox;
    private CheckBox mVideoCallCheckbox;
    private EditText mNumber;
    private ListView mListView;
    private TextView mHasFocus;

    private SelfManagedCallListAdapter mListAdapter;

    private SelfManagedCallList.Listener mCallListListener = new SelfManagedCallList.Listener() {
        @Override
        public void onCreateIncomingConnectionFailed(ConnectionRequest request) {
            Log.i(TAG, "onCreateIncomingConnectionFailed " + request);
            Toast.makeText(SelfManagedCallingActivity.this,
                    R.string.incomingCallNotPermittedCS , Toast.LENGTH_SHORT).show();
        };

        @Override
        public void onCreateOutgoingConnectionFailed(ConnectionRequest request) {
            Log.i(TAG, "onCreateOutgoingConnectionFailed " + request);
            Toast.makeText(SelfManagedCallingActivity.this,
                    R.string.outgoingCallNotPermittedCS , Toast.LENGTH_SHORT).show();
        };

        @Override
        public void onConnectionListChanged() {
            Log.i(TAG, "onConnectionListChanged");
            mListAdapter.updateConnections();
        };

        @Override
        public void onConnectionServiceFocusLost() {
            mHasFocus.setText("\uD83D\uDC4E No Focus \uD83D\uDC4E");
        };

        @Override
        public void onConnectionServiceFocusGained() {
            mHasFocus.setText("\uD83D\uDC4D Has Focus \uD83D\uDC4D");
        };
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int flags =
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;

        getWindow().addFlags(flags);
        configureNotificationChannel();
        setContentView(R.layout.self_managed_sample_main);
        mCheckIfPermittedBeforeCalling = (CheckBox) findViewById(
                R.id.checkIfPermittedBeforeCalling);
        mPlaceOutgoingCallButton = (Button) findViewById(R.id.placeOutgoingCallButton);
        mPlaceOutgoingCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                placeOutgoingCall();
            }
        });
        mPlaceSelfManagedOutgoingCallButton = (Button) findViewById(
                R.id.placeSelfManagedOutgoingCallButton);
        mPlaceSelfManagedOutgoingCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                placeSelfManagedOutgoingCall();
            }
        });
        mPlaceSelfManagedIncomingCallButton = (Button) findViewById(
                R.id.placeSelfManagedIncomingCallButton);
        mPlaceSelfManagedIncomingCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { placeSelfManagedIncomingCall(); }
        });
        mPlaceIncomingCallButton = (Button) findViewById(R.id.placeIncomingCallButton);
        mPlaceIncomingCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                placeIncomingCall();
            }
        });
        mHandoverFrom = (Button) findViewById(R.id.handoverFrom);
        mHandoverFrom.setOnClickListener((v -> {
            initiateHandover();
        }));
        mRequestCallScreeningRole = (Button) findViewById(R.id.requestCallScreeningRole);
        mRequestCallScreeningRole.setOnClickListener((v -> {
            requestCallScreeningRole();
        }));
        mEnableCarMode = (Button) findViewById(R.id.enableCarMode);
        mEnableCarMode.setOnClickListener((v -> {
            enableCarMode();
        }));
        mDisableCarMode = (Button) findViewById(R.id.disableCarMode);
        mDisableCarMode.setOnClickListener((v -> {
            disableCarMode();
        }));
        mUseAcct1Button = findViewById(R.id.useAcct1Button);
        mUseAcct2Button = findViewById(R.id.useAcct2Button);
        mUseAcct3Button = findViewById(R.id.useAcct3Button);
        mHasFocus = findViewById(R.id.hasFocus);
        mVideoCallCheckbox = findViewById(R.id.videoCall);
        mHoldableCheckbox = findViewById(R.id.holdable);
        mNumber = (EditText) findViewById(R.id.phoneNumber);
        mListView = (ListView) findViewById(R.id.callList);
        mCallList.setListener(mCallListListener);
        mCallList.registerPhoneAccounts(this);
        mListAdapter = new SelfManagedCallListAdapter(getLayoutInflater(),
                mCallList.getConnections());
        mListView.setAdapter(mListAdapter);
        Log.i(TAG, "onCreate - mCallList id " + Objects.hashCode(mCallList));
    }

    private PhoneAccountHandle getSelectedPhoneAccountHandle() {
        if (mUseAcct1Button.isChecked()) {
            return mCallList.getPhoneAccountHandle(SelfManagedCallList.SELF_MANAGED_ACCOUNT_1);
        } else if (mUseAcct2Button.isChecked()) {
            return mCallList.getPhoneAccountHandle(SelfManagedCallList.SELF_MANAGED_ACCOUNT_2);
        } else if (mUseAcct3Button.isChecked()) {
            return mCallList.getPhoneAccountHandle(SelfManagedCallList.SELF_MANAGED_ACCOUNT_3);
        }
        return null;
    }

    private void placeOutgoingCall() {
        TelecomManager tm = this.getSystemService(TelecomManager.class);
        PhoneAccountHandle phoneAccountHandle = getSelectedPhoneAccountHandle();

        if (mCheckIfPermittedBeforeCalling.isChecked()) {
            if (!tm.isOutgoingCallPermitted(phoneAccountHandle)) {
                Toast.makeText(this, R.string.outgoingCallNotPermitted , Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                getSelectedPhoneAccountHandle());
        if (mVideoCallCheckbox.isChecked()) {
            extras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    VideoProfile.STATE_BIDIRECTIONAL);
        }
        Bundle clientExtras = new Bundle();
        clientExtras.putBoolean(SelfManagedConnectionService.EXTRA_HOLDABLE,
                mHoldableCheckbox.isChecked());
        extras.putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, clientExtras);
        tm.placeCall(Uri.parse(mNumber.getText().toString()), extras);
    }

    private void placeSelfManagedOutgoingCall() {
        TelecomManager tm = this.getSystemService(TelecomManager.class);
        PhoneAccountHandle phoneAccountHandle = getSelectedPhoneAccountHandle();

        if (mCheckIfPermittedBeforeCalling.isChecked()) {
            Toast.makeText(this, R.string.outgoingCallNotPermitted, Toast.LENGTH_SHORT).show();
            return;
        }

        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
        if (mVideoCallCheckbox.isChecked()) {
            extras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    VideoProfile.STATE_BIDIRECTIONAL);
        }
        tm.placeCall(Uri.parse(mNumber.getText().toString()), extras);
    }

    private void initiateHandover() {
        TelecomManager tm = this.getSystemService(TelecomManager.class);
        PhoneAccountHandle phoneAccountHandle = getSelectedPhoneAccountHandle();
        Uri address = Uri.parse(mNumber.getText().toString());
        tm.acceptHandover(address, VideoProfile.STATE_BIDIRECTIONAL, phoneAccountHandle);
    }

    private void placeIncomingCall() {
        TelecomManager tm = this.getSystemService(TelecomManager.class);
        PhoneAccountHandle phoneAccountHandle = getSelectedPhoneAccountHandle();

        if (mCheckIfPermittedBeforeCalling.isChecked()) {
            if (!tm.isIncomingCallPermitted(phoneAccountHandle)) {
                Toast.makeText(this, R.string.incomingCallNotPermitted , Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.parse(mNumber.getText().toString()));
        extras.putBoolean(SelfManagedConnectionService.EXTRA_HOLDABLE,
                mHoldableCheckbox.isChecked());
        if (mVideoCallCheckbox.isChecked()) {
            extras.putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE,
                    VideoProfile.STATE_BIDIRECTIONAL);
        }
        tm.addNewIncomingCall(getSelectedPhoneAccountHandle(), extras);
    }

    private void placeSelfManagedIncomingCall() {
        TelecomManager tm = this.getSystemService(TelecomManager.class);
        PhoneAccountHandle phoneAccountHandle = mCallList.getPhoneAccountHandle(
                SelfManagedCallList.SELF_MANAGED_ACCOUNT_1A);

        if (mCheckIfPermittedBeforeCalling.isChecked()) {
            if (!tm.isIncomingCallPermitted(phoneAccountHandle)) {
                Toast.makeText(this, R.string.incomingCallNotPermitted , Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.parse(mNumber.getText().toString()));
        tm.addNewIncomingCall(phoneAccountHandle, extras);
    }

    private void enableCarMode() {
        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        uiModeManager.enableCarMode(0);
        Toast.makeText(this, "Enabling car mode with priority " + DEFAULT_PRIORITY,
                Toast.LENGTH_LONG).show();
    }

    private void disableCarMode() {
        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        uiModeManager.disableCarMode(0);
        Toast.makeText(this, "Disabling car mode", Toast.LENGTH_LONG).show();
    }

    private void configureNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                SelfManagedConnection.INCOMING_CALL_CHANNEL_ID, "Incoming Calls",
                NotificationManager.IMPORTANCE_MAX);
        channel.setShowBadge(false);
        Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        channel.setSound(ringtoneUri, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        channel.enableLights(true);

        NotificationManager mgr = getSystemService(NotificationManager.class);
        mgr.createNotificationChannel(channel);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ID) {
            if (resultCode == android.app.Activity.RESULT_OK) {
                Toast.makeText(this, "Call screening role granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Call screening role NOT granted.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestCallScreeningRole() {
        RoleManager roleManager = (RoleManager) getSystemService(ROLE_SERVICE);
        Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING);
        startActivityForResult(intent, REQUEST_ID);
    }
}