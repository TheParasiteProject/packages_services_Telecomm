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

package com.android.server.telecom.tests;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.UserHandle;
import android.telecom.VideoProfile;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.HandoverState;
import com.android.server.telecom.ui.IncomingCallNotifier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

/**
 * Tests for the {@link com.android.server.telecom.ui.IncomingCallNotifier} class.
 */
@RunWith(JUnit4.class)
public class IncomingCallNotifierTest extends TelecomTestCase {

    @Mock private IncomingCallNotifier.CallsManagerProxy mCallsManagerProxy;
    @Mock private Call mAudioCall;
    @Mock private Call mVideoCall;
    @Mock private Call mRingingCall;
    private IncomingCallNotifier mIncomingCallNotifier;
    private NotificationManager mNotificationManager;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        ApplicationInfo info = new ApplicationInfo();
        info.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        doReturn(info).when(mContext).getApplicationInfo();
        doReturn(null).when(mContext).getTheme();
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mIncomingCallNotifier = new IncomingCallNotifier(mContext);
        mIncomingCallNotifier.setCallsManagerProxy(mCallsManagerProxy);

        when(mAudioCall.getVideoState()).thenReturn(VideoProfile.STATE_AUDIO_ONLY);
        when(mAudioCall.getTargetPhoneAccountLabel()).thenReturn("Bar");
        when(mAudioCall.getAssociatedUser()).
                thenReturn(UserHandle.CURRENT);
        when(mVideoCall.getVideoState()).thenReturn(VideoProfile.STATE_BIDIRECTIONAL);
        when(mVideoCall.getTargetPhoneAccountLabel()).thenReturn("Bar");
        when(mRingingCall.isSelfManaged()).thenReturn(true);
        when(mRingingCall.isIncoming()).thenReturn(true);
        when(mRingingCall.getState()).thenReturn(CallState.RINGING);
        when(mRingingCall.getVideoState()).thenReturn(VideoProfile.STATE_AUDIO_ONLY);
        when(mRingingCall.getTargetPhoneAccountLabel()).thenReturn("Foo");
        when(mRingingCall.getAssociatedUser()).
                thenReturn(UserHandle.CURRENT);
        when(mRingingCall.getHandoverState()).thenReturn(HandoverState.HANDOVER_NONE);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Add a call that isn't ringing.
     */
    @SmallTest
    @Test
    public void testSingleCall() {
        mIncomingCallNotifier.onCallAdded(mAudioCall);
        verify(mNotificationManager, never()).notifyAsUser(
                eq(IncomingCallNotifier.NOTIFICATION_TAG),
                eq(IncomingCallNotifier.NOTIFICATION_INCOMING_CALL), any(),
                eq(UserHandle.CURRENT));
    }

    /**
     * Add a ringing call when there is no other ongoing call.
     */
    @SmallTest
    @Test
    public void testIncomingDuringOngoingCall() {
        when(mCallsManagerProxy.hasUnholdableCallsForOtherConnectionService(any())).thenReturn(false);
        mIncomingCallNotifier.onCallAdded(mRingingCall);
        verify(mNotificationManager, never()).notifyAsUser(
                eq(IncomingCallNotifier.NOTIFICATION_TAG),
                eq(IncomingCallNotifier.NOTIFICATION_INCOMING_CALL), any(),
                eq(UserHandle.CURRENT));
    }

    /**
     * Add a ringing call with another call ongoing, not from a different phone account.
     */
    @SmallTest
    @Test
    public void testIncomingDuringOngoingCall2() {
        when(mCallsManagerProxy.hasUnholdableCallsForOtherConnectionService(any())).thenReturn(false);
        when(mCallsManagerProxy.getNumUnholdableCallsForOtherConnectionService(any())).thenReturn(0);
        when(mCallsManagerProxy.getActiveCall()).thenReturn(mAudioCall);

        mIncomingCallNotifier.onCallAdded(mAudioCall);
        mIncomingCallNotifier.onCallAdded(mRingingCall);
        verify(mNotificationManager, never()).notifyAsUser(
                eq(IncomingCallNotifier.NOTIFICATION_TAG),
                eq(IncomingCallNotifier.NOTIFICATION_INCOMING_CALL), any(),
                eq(UserHandle.CURRENT));
    }

    /**
     * Remove ringing call with another call ongoing.
     */
    @SmallTest
    @Test
    public void testCallRemoved() {
        when(mCallsManagerProxy.hasUnholdableCallsForOtherConnectionService(any())).thenReturn(true);
        when(mCallsManagerProxy.getNumUnholdableCallsForOtherConnectionService(any())).thenReturn(1);
        when(mCallsManagerProxy.getActiveCall()).thenReturn(mAudioCall);

        mIncomingCallNotifier.onCallAdded(mAudioCall);
        mIncomingCallNotifier.onCallAdded(mRingingCall);
        verify(mNotificationManager).notifyAsUser(
                eq(IncomingCallNotifier.NOTIFICATION_TAG),
                eq(IncomingCallNotifier.NOTIFICATION_INCOMING_CALL), any(),
                eq(UserHandle.CURRENT));
        mIncomingCallNotifier.onCallRemoved(mRingingCall);
        verify(mNotificationManager).cancelAsUser(eq(IncomingCallNotifier.NOTIFICATION_TAG),
                eq(IncomingCallNotifier.NOTIFICATION_INCOMING_CALL), eq(UserHandle.CURRENT));
    }

    /**
     * Ensure notification doesn't show during handover.
     */
    @SmallTest
    @Test
    public void testDontShowDuringHandover1() {
        when(mCallsManagerProxy.hasUnholdableCallsForOtherConnectionService(any())).thenReturn(true);
        when(mCallsManagerProxy.getNumUnholdableCallsForOtherConnectionService(any())).thenReturn(1);
        when(mCallsManagerProxy.getActiveCall()).thenReturn(mAudioCall);
        when(mRingingCall.getHandoverState()).thenReturn(HandoverState.HANDOVER_FROM_STARTED);

        mIncomingCallNotifier.onCallAdded(mAudioCall);
        mIncomingCallNotifier.onCallAdded(mRingingCall);

        // Incoming call is in the middle of a handover, don't expect to be notified.
        verify(mNotificationManager, never()).notifyAsUser(
                eq(IncomingCallNotifier.NOTIFICATION_TAG),
                eq(IncomingCallNotifier.NOTIFICATION_INCOMING_CALL), any(),
                eq(UserHandle.CURRENT));
    }

    /**
     * Ensure notification doesn't show during handover.
     */
    @SmallTest
    @Test
    public void testDontShowDuringHandover2() {
        when(mCallsManagerProxy.hasUnholdableCallsForOtherConnectionService(any())).thenReturn(true);
        when(mCallsManagerProxy.getNumUnholdableCallsForOtherConnectionService(any())).thenReturn(1);
        when(mCallsManagerProxy.getActiveCall()).thenReturn(mAudioCall);
        when(mRingingCall.getHandoverState()).thenReturn(HandoverState.HANDOVER_COMPLETE);

        mIncomingCallNotifier.onCallAdded(mAudioCall);
        mIncomingCallNotifier.onCallAdded(mRingingCall);

        // Incoming call is done a handover, don't expect to be notified.
        verify(mNotificationManager, never()).notifyAsUser(
                eq(IncomingCallNotifier.NOTIFICATION_TAG),
                eq(IncomingCallNotifier.NOTIFICATION_INCOMING_CALL), any(),
                eq(UserHandle.CURRENT));
    }
}
