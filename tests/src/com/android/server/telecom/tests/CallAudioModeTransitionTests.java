/*
 * Copyright (C) 2018 The Android Open Source Project
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


import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioManager;
import android.os.HandlerThread;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.CallAudioCommunicationDeviceTracker;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.CallAudioModeStateMachine;
import com.android.server.telecom.CallAudioModeStateMachine.MessageArgs;
import com.android.server.telecom.SystemStateHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class CallAudioModeTransitionTests extends TelecomTestCase {
    private static class ModeTestParameters {
        public String name;
        public int initialAudioState; // One of the explicit switch focus constants in CAMSM
        public int messageType; // Any of the commands from the state machine
        public CallAudioModeStateMachine.MessageArgs externalState;
        public String expectedFinalStateName;
        public int expectedFocus; // one of the FOCUS_* constants below
        public int expectedMode; // NO_CHANGE, or an AudioManager.MODE_* constant
        public int expectedRingingInteraction; // NO_CHANGE, ON, or OFF
        public int expectedCallWaitingInteraction; // NO_CHANGE, ON, or OFF

        public ModeTestParameters(String name, int initialAudioState, int messageType,
                CallAudioModeStateMachine.MessageArgs externalState, String
                expectedFinalStateName, int expectedFocus, int expectedMode, int
                expectedRingingInteraction, int expectedCallWaitingInteraction) {
            this.name = name;
            this.initialAudioState = initialAudioState;
            this.messageType = messageType;
            this.externalState = externalState;
            this.expectedFinalStateName = expectedFinalStateName;
            this.expectedFocus = expectedFocus;
            this.expectedMode = expectedMode;
            this.expectedRingingInteraction = expectedRingingInteraction;
            this.expectedCallWaitingInteraction = expectedCallWaitingInteraction;
        }

        @Override
        public String toString() {
            return "ModeTestParameters{" +
                    "name='" + name + '\'' +
                    ", initialAudioState=" + initialAudioState +
                    ", messageType=" + messageType +
                    ", externalState=" + externalState +
                    ", expectedFinalStateName='" + expectedFinalStateName + '\'' +
                    ", expectedFocus=" + expectedFocus +
                    ", expectedMode=" + expectedMode +
                    ", expectedRingingInteraction=" + expectedRingingInteraction +
                    ", expectedCallWaitingInteraction=" + expectedCallWaitingInteraction +
                    '}';
        }
    }

    private static final int FOCUS_NO_CHANGE = 0;
    private static final int FOCUS_RING = 1;
    private static final int FOCUS_VOICE = 2;
    private static final int FOCUS_OFF = 3;

    private static final int NO_CHANGE = -1;
    private static final int ON = 0;
    private static final int OFF = 1;

    private static final int TEST_TIMEOUT = 1000;

    @Mock private SystemStateHelper mSystemStateHelper;
    @Mock private AudioManager mAudioManager;
    @Mock private CallAudioManager mCallAudioManager;
    @Mock private CallAudioCommunicationDeviceTracker mCommunicationDeviceTracker;
    private final ModeTestParameters mParams;
    private HandlerThread mTestThread;

    @Override
    @Before
    public void setUp() throws Exception {
        mTestThread = new HandlerThread("CallAudioModeStateMachineTest");
        mTestThread.start();
        super.setUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mTestThread.quit();
        mTestThread.join();
        super.tearDown();
    }

    public CallAudioModeTransitionTests(ModeTestParameters params) {
        mParams = params;
    }

    @Test
    @SmallTest
    public void modeTransitionTest() {
        CallAudioModeStateMachine sm = new CallAudioModeStateMachine(mSystemStateHelper,
                mAudioManager, mTestThread.getLooper(), mFeatureFlags, mCommunicationDeviceTracker);
        sm.setCallAudioManager(mCallAudioManager);
        sm.sendMessage(mParams.initialAudioState);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);

        resetMocks();
        when(mCallAudioManager.startRinging()).thenReturn(true);
        when(mFeatureFlags.telecomResolveHiddenDependencies()).thenReturn(false);
        if (mParams.initialAudioState
                == CallAudioModeStateMachine.ENTER_AUDIO_PROCESSING_FOCUS_FOR_TESTING) {
            when(mAudioManager.getMode())
                    .thenReturn(CallAudioModeStateMachine.NEW_AUDIO_MODE_FOR_AUDIO_PROCESSING);
        }

        sm.sendMessage(mParams.messageType, mParams.externalState);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        if (mParams.expectedFocus == FOCUS_OFF
                && mParams.messageType != CallAudioModeStateMachine.AUDIO_OPERATIONS_COMPLETE) {
            // If we expect the focus to turn off, we need to signal operations complete first
            sm.sendMessage(CallAudioModeStateMachine.AUDIO_OPERATIONS_COMPLETE);
            waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        }

        assertEquals(mParams.expectedFinalStateName, sm.getCurrentStateName());

        switch (mParams.expectedFocus) {
            case FOCUS_NO_CHANGE:
                verify(mAudioManager, never()).requestAudioFocusForCall(anyInt(), anyInt());
                break;
            case FOCUS_OFF:
                verify(mAudioManager).abandonAudioFocusForCall();
                break;
            case FOCUS_RING:
                verify(mAudioManager).requestAudioFocusForCall(
                        eq(AudioManager.STREAM_RING), anyInt());
                break;
            case FOCUS_VOICE:
                verify(mAudioManager).requestAudioFocusForCall(
                        eq(AudioManager.STREAM_VOICE_CALL), anyInt());
                break;
        }

        if (mParams.expectedMode != NO_CHANGE) {
            verify(mAudioManager).setMode(eq(mParams.expectedMode));
        } else {
            verify(mAudioManager, never()).setMode(anyInt());
        }

        switch (mParams.expectedRingingInteraction) {
            case NO_CHANGE:
                verify(mCallAudioManager, never()).startRinging();
                verify(mCallAudioManager, never()).stopRinging();
                break;
            case ON:
                verify(mCallAudioManager).startRinging();
                break;
            case OFF:
                verify(mCallAudioManager).stopRinging();
                break;
        }

        switch (mParams.expectedCallWaitingInteraction) {
            case NO_CHANGE:
                verify(mCallAudioManager, never()).startCallWaiting(nullable(String.class));
                verify(mCallAudioManager, never()).stopCallWaiting();
                break;
            case ON:
                verify(mCallAudioManager).startCallWaiting(nullable(String.class));
                break;
            case OFF:
                verify(mCallAudioManager).stopCallWaiting();
                break;
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<ModeTestParameters> generateTestCases() {
        List<ModeTestParameters> result = new ArrayList<>();
        result.add(new ModeTestParameters(
                "New active/dialing call with no other calls when unfocused",
                CallAudioModeStateMachine.ABANDON_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_ACTIVE_OR_DIALING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "New active/dialing voip call with no other calls when unfocused",
                CallAudioModeStateMachine.ABANDON_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_ACTIVE_OR_DIALING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(true)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.COMMS_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_COMMUNICATION, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "New ringing call with no other calls when unfocused",
                CallAudioModeStateMachine.ABANDON_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_RINGING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(true)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.RING_STATE_NAME, // expectedFinalStateName
                FOCUS_RING, // expectedFocus
                AudioManager.MODE_RINGTONE, // expectedMode
                ON, // expectedRingingInteraction
                OFF // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "New ringing call coming in on top of active/dialing call",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_RINGING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(true)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                NO_CHANGE, // expectedFocus
                NO_CHANGE, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                ON // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Ringing call becomes active, part 1",
                CallAudioModeStateMachine.ENTER_RING_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_ACTIVE_OR_DIALING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                OFF, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Ringing call becomes active, part 2",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_RINGING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                NO_CHANGE, // expectedFocus
                NO_CHANGE, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Active call disconnects, but tone is playing",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_ACTIVE_OR_DIALING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(true)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.TONE_HOLD_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Tone stops playing, with no active calls",
                CallAudioModeStateMachine.ENTER_TONE_OR_HOLD_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.TONE_STOPPED_PLAYING, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.UNFOCUSED_STATE_NAME, // expectedFinalStateName
                FOCUS_OFF, // expectedFocus
                AudioManager.MODE_NORMAL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Ringing call disconnects",
                CallAudioModeStateMachine.ENTER_RING_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_RINGING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.UNFOCUSED_STATE_NAME, // expectedFinalStateName
                FOCUS_OFF, // expectedFocus
                AudioManager.MODE_NORMAL, // expectedMode
                OFF, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call-waiting call disconnects",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_RINGING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(true)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_NO_CHANGE, // expectedFocus
                NO_CHANGE, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                OFF // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call is placed on hold - 1",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_ACTIVE_OR_DIALING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.TONE_HOLD_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call is placed on hold - 2",
                CallAudioModeStateMachine.ENTER_TONE_OR_HOLD_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_HOLDING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.TONE_HOLD_STATE_NAME, // expectedFinalStateName
                FOCUS_NO_CHANGE, // expectedFocus
                NO_CHANGE, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Swap between voip and sim calls - 1",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_HOLDING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(true)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.COMMS_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_COMMUNICATION, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Swap between voip and sim calls - 2",
                CallAudioModeStateMachine.ENTER_COMMS_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_HOLDING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Swap between voip and sim calls - 3",
                CallAudioModeStateMachine.ENTER_COMMS_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_ACTIVE_OR_DIALING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Swap between voip and sim calls - 4",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_HOLDING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(true)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.COMMS_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_COMMUNICATION, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call is taken off hold - 1",
                CallAudioModeStateMachine.ENTER_TONE_OR_HOLD_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_HOLDING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call is taken off hold - 2",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_ACTIVE_OR_DIALING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_NO_CHANGE, // expectedFocus
                NO_CHANGE, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Active call disconnects while there's a call-waiting call",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_ACTIVE_OR_DIALING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(true)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(true)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.RING_STATE_NAME, // expectedFinalStateName
                FOCUS_RING, // expectedFocus
                AudioManager.MODE_RINGTONE, // expectedMode
                ON, // expectedRingingInteraction
                OFF // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "New dialing call when there's a call on hold",
                CallAudioModeStateMachine.ENTER_TONE_OR_HOLD_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_ACTIVE_OR_DIALING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Ringing call disconnects with a holding call in the background",
                CallAudioModeStateMachine.ENTER_RING_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_RINGING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.TONE_HOLD_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_NORMAL, // expectedMode -- we're expecting this because
                                          // mMostRecentMode hasn't been set properly.
                OFF, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Foreground call transitions from sim to voip",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.FOREGROUND_VOIP_MODE_CHANGE, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(true)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.COMMS_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_COMMUNICATION, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Foreground call transitions from voip to sim",
                CallAudioModeStateMachine.ENTER_COMMS_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.FOREGROUND_VOIP_MODE_CHANGE, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call-waiting hangs up before being answered, with another sim call in " +
                        "foreground",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_RINGING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(true)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_NO_CHANGE, // expectedFocus
                NO_CHANGE, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                OFF // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call-waiting hangs up before being answered, with another voip call in " +
                        "foreground",
                CallAudioModeStateMachine.ENTER_COMMS_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_RINGING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setIsTonePlaying(true)
                        .setForegroundCallIsVoip(true)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.COMMS_STATE_NAME, // expectedFinalStateName
                FOCUS_NO_CHANGE, // expectedFocus
                NO_CHANGE, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                OFF // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call enters audio processing state from call screening service",
                CallAudioModeStateMachine.ABANDON_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_AUDIO_PROCESSING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.AUDIO_PROCESSING_STATE_NAME, // expectedFinalStateName
                FOCUS_NO_CHANGE, // expectedFocus
                CallAudioModeStateMachine.NEW_AUDIO_MODE_FOR_AUDIO_PROCESSING, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call enters audio processing state by manual intervention from ringing state, 1",
                CallAudioModeStateMachine.ENTER_RING_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_RINGING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.AUDIO_PROCESSING_STATE_NAME, // expectedFinalStateName
                FOCUS_OFF, // expectedFocus
                CallAudioModeStateMachine.NEW_AUDIO_MODE_FOR_AUDIO_PROCESSING, // expectedMode
                OFF, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call enters audio processing state by manual intervention from ringing state, 2",
                CallAudioModeStateMachine.ENTER_RING_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_AUDIO_PROCESSING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.AUDIO_PROCESSING_STATE_NAME, // expectedFinalStateName
                FOCUS_OFF, // expectedFocus
                CallAudioModeStateMachine.NEW_AUDIO_MODE_FOR_AUDIO_PROCESSING, // expectedMode
                OFF, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call enters audio processing state from active call, 1",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NO_MORE_ACTIVE_OR_DIALING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.AUDIO_PROCESSING_STATE_NAME, // expectedFinalStateName
                FOCUS_OFF, // expectedFocus
                CallAudioModeStateMachine.NEW_AUDIO_MODE_FOR_AUDIO_PROCESSING, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call enters audio processing state from active call, 2",
                CallAudioModeStateMachine.ENTER_CALL_FOCUS_FOR_TESTING, // initialAudioState
                CallAudioModeStateMachine.NEW_AUDIO_PROCESSING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(true)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.AUDIO_PROCESSING_STATE_NAME, // expectedFinalStateName
                FOCUS_OFF, // expectedFocus
                CallAudioModeStateMachine.NEW_AUDIO_MODE_FOR_AUDIO_PROCESSING, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Call in audio processing gets hanged up",
                CallAudioModeStateMachine.ENTER_AUDIO_PROCESSING_FOCUS_FOR_TESTING, // initialAudioS
                CallAudioModeStateMachine.NO_MORE_AUDIO_PROCESSING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.UNFOCUSED_STATE_NAME, // expectedFinalStateName
                NO_CHANGE, // expectedFocus
                AudioManager.MODE_NORMAL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Notify user of a call in audio processing by simulating ringing, 1",
                CallAudioModeStateMachine.ENTER_AUDIO_PROCESSING_FOCUS_FOR_TESTING, // initialAudioS
                CallAudioModeStateMachine.NO_MORE_AUDIO_PROCESSING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(true)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.RING_STATE_NAME, // expectedFinalStateName
                FOCUS_RING, // expectedFocus
                NO_CHANGE, // expectedMode
                ON, // expectedRingingInteraction
                // We expect a call to stopCallWaiting because it happens whenever the ringer starts
                OFF // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Notify user of a call in audio processing by simulating ringing, 2",
                CallAudioModeStateMachine.ENTER_AUDIO_PROCESSING_FOCUS_FOR_TESTING, // initialAudioS
                CallAudioModeStateMachine.NEW_RINGING_CALL, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(true)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.RING_STATE_NAME, // expectedFinalStateName
                FOCUS_RING, // expectedFocus
                NO_CHANGE, // expectedMode
                ON, // expectedRingingInteraction
                // We expect a call to stopCallWaiting because it happens whenever the ringer starts
                OFF // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Audio processing call gets set to active manually",
                CallAudioModeStateMachine.ENTER_AUDIO_PROCESSING_FOCUS_FOR_TESTING, // initialAudioS
                CallAudioModeStateMachine.NO_MORE_AUDIO_PROCESSING_CALLS, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(true)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.CALL_STATE_NAME, // expectedFinalStateName
                FOCUS_VOICE, // expectedFocus
                AudioManager.MODE_IN_CALL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "No change to focus without signaling audio ops complete",
                CallAudioModeStateMachine.ENTER_TONE_OR_HOLD_FOCUS_FOR_TESTING, // initialAudioS
                CallAudioModeStateMachine.TONE_STOPPED_PLAYING, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.UNFOCUSED_STATE_NAME, // expectedFinalStateName
                FOCUS_NO_CHANGE, // expectedFocus
                AudioManager.MODE_NORMAL, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        result.add(new ModeTestParameters(
                "Abandon focus once audio ops are complete",
                CallAudioModeStateMachine.ABANDON_FOCUS_FOR_TESTING, // initialAudioS
                CallAudioModeStateMachine.AUDIO_OPERATIONS_COMPLETE, // messageType
                new MessageArgs.Builder()
                        .setHasActiveOrDialingCalls(false)
                        .setHasRingingCalls(false)
                        .setHasHoldingCalls(false)
                        .setHasAudioProcessingCalls(false)
                        .setIsTonePlaying(false)
                        .setForegroundCallIsVoip(false)
                        .setSession(null)
                        .build(),
                CallAudioModeStateMachine.UNFOCUSED_STATE_NAME, // expectedFinalStateName
                FOCUS_OFF, // expectedFocus
                NO_CHANGE, // expectedMode
                NO_CHANGE, // expectedRingingInteraction
                NO_CHANGE // expectedCallWaitingInteraction
        ));

        return result;
    }

    private void resetMocks() {
        reset(mCallAudioManager, mAudioManager);
    }
}
