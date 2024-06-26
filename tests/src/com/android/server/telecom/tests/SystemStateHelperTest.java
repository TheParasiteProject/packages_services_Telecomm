/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.SystemStateHelper;
import com.android.server.telecom.SystemStateHelper.SystemStateListener;
import com.android.server.telecom.TelecomSystem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldSetter;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Unit tests for SystemStateHelper
 */
@RunWith(JUnit4.class)
public class SystemStateHelperTest extends TelecomTestCase {

    Context mContext;
    @Mock SystemStateListener mSystemStateListener;
    @Mock Sensor mGravitySensor;
    @Mock Sensor mProxSensor;
    @Mock UiModeManager mUiModeManager;
    @Mock SensorManager mSensorManager;
    @Mock Intent mIntentEnter;
    @Mock Intent mIntentExit;
    TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        doReturn(mSensorManager).when(mContext).getSystemService(SensorManager.class);
        when(mGravitySensor.getType()).thenReturn(Sensor.TYPE_GRAVITY);
        when(mProxSensor.getType()).thenReturn(Sensor.TYPE_PROXIMITY);
        when(mProxSensor.getMaximumRange()).thenReturn(5.0f);
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)).thenReturn(mGravitySensor);
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)).thenReturn(mProxSensor);

        doReturn(mUiModeManager).when(mContext).getSystemService(UiModeManager.class);

        mComponentContextFixture.putFloatResource(
                R.dimen.device_on_ear_xy_gravity_threshold, 5.5f);
        mComponentContextFixture.putFloatResource(
                R.dimen.device_on_ear_y_gravity_negative_threshold, -1f);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testListeners() throws Exception {
        SystemStateHelper systemStateHelper = new SystemStateHelper(mContext, mLock);

        assertFalse(systemStateHelper.removeListener(mSystemStateListener));
        systemStateHelper.addListener(mSystemStateListener);
        assertTrue(systemStateHelper.removeListener(mSystemStateListener));
        assertFalse(systemStateHelper.removeListener(mSystemStateListener));
    }

    @SmallTest
    @Test
    public void testQuerySystemForCarMode_True() {
        when(mUiModeManager.getCurrentModeType()).thenReturn(Configuration.UI_MODE_TYPE_CAR);
        assertTrue(new SystemStateHelper(mContext, mLock).isCarModeOrProjectionActive());
    }

    @SmallTest
    @Test
    public void testQuerySystemForCarMode_False() {
        when(mUiModeManager.getCurrentModeType()).thenReturn(Configuration.UI_MODE_TYPE_NORMAL);
        assertFalse(new SystemStateHelper(mContext, mLock).isCarModeOrProjectionActive());
    }

    @SmallTest
    @Test
    public void testQuerySystemForAutomotiveProjection_True() {
        when(mUiModeManager.getActiveProjectionTypes())
                .thenReturn(UiModeManager.PROJECTION_TYPE_AUTOMOTIVE);
        assertTrue(new SystemStateHelper(mContext, mLock).isCarModeOrProjectionActive());

        when(mUiModeManager.getActiveProjectionTypes())
                .thenReturn(UiModeManager.PROJECTION_TYPE_ALL);
        assertTrue(new SystemStateHelper(mContext, mLock).isCarModeOrProjectionActive());
    }

    @SmallTest
    @Test
    public void testQuerySystemForAutomotiveProjection_False() {
        when(mUiModeManager.getActiveProjectionTypes())
                .thenReturn(UiModeManager.PROJECTION_TYPE_NONE);
        assertFalse(new SystemStateHelper(mContext, mLock).isCarModeOrProjectionActive());
    }

    @SmallTest
    @Test
    public void testQuerySystemForAutomotiveProjectionAndCarMode_True() {
        when(mUiModeManager.getCurrentModeType()).thenReturn(Configuration.UI_MODE_TYPE_CAR);
        when(mUiModeManager.getActiveProjectionTypes())
                .thenReturn(UiModeManager.PROJECTION_TYPE_AUTOMOTIVE);
        assertTrue(new SystemStateHelper(mContext, mLock).isCarModeOrProjectionActive());
    }

    @SmallTest
    @Test
    public void testQuerySystemForAutomotiveProjectionOrCarMode_nullService() {
        when(mContext.getSystemService(UiModeManager.class))
                .thenReturn(mUiModeManager)  // Without this, class construction will throw NPE.
                .thenReturn(null);
        assertFalse(new SystemStateHelper(mContext, mLock).isCarModeOrProjectionActive());
    }

    @SmallTest
    @Test
    public void testPackageRemoved() {
        ArgumentCaptor<BroadcastReceiver> receiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        new SystemStateHelper(mContext, mLock).addListener(mSystemStateListener);
        verify(mContext, atLeastOnce())
                .registerReceiver(receiver.capture(), any(IntentFilter.class));
        Intent packageRemovedIntent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        packageRemovedIntent.setData(Uri.fromParts("package", "com.android.test", null));
        receiver.getValue().onReceive(mContext, packageRemovedIntent);
        verify(mSystemStateListener).onPackageUninstalled("com.android.test");
    }

    @SmallTest
    @Test
    public void testReceiverAndIntentFilter() {
        ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);
        new SystemStateHelper(mContext, mLock);
        verify(mContext, times(2)).registerReceiver(
                any(BroadcastReceiver.class), intentFilterCaptor.capture());

        Predicate<IntentFilter> carModeFilterTest = (intentFilter) ->
                2 == intentFilter.countActions()
                        && intentFilter.hasAction(UiModeManager.ACTION_ENTER_CAR_MODE_PRIORITIZED)
                        && intentFilter.hasAction(UiModeManager.ACTION_EXIT_CAR_MODE_PRIORITIZED);

        Predicate<IntentFilter> packageRemovedFilterTest = (intentFilter) ->
                1 == intentFilter.countActions()
                        && intentFilter.hasAction(Intent.ACTION_PACKAGE_REMOVED)
                        && intentFilter.hasDataScheme("package");

        List<IntentFilter> capturedFilters = intentFilterCaptor.getAllValues();
        assertEquals(2, capturedFilters.size());
        for (IntentFilter filter : capturedFilters) {
            if (carModeFilterTest.test(filter)) {
                carModeFilterTest = (i) -> false;
                continue;
            }
            if (packageRemovedFilterTest.test(filter)) {
                packageRemovedFilterTest = (i) -> false;
                continue;
            }
            String failString = String.format("Registered intent filters not correct. Got %s",
                    capturedFilters.stream().map(IntentFilter::toString)
                            .collect(Collectors.joining("\n")));
            fail(failString);
        }
    }

    @SmallTest
    @Test
    public void testOnEnterExitCarMode() {
        ArgumentCaptor<BroadcastReceiver> receiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        new SystemStateHelper(mContext, mLock).addListener(mSystemStateListener);

        verify(mContext, atLeastOnce())
                .registerReceiver(receiver.capture(), any(IntentFilter.class));

        when(mIntentEnter.getAction()).thenReturn(UiModeManager.ACTION_ENTER_CAR_MODE_PRIORITIZED);
        receiver.getValue().onReceive(mContext, mIntentEnter);
        verify(mSystemStateListener).onCarModeChanged(anyInt(), isNull(), eq(true));

        when(mIntentExit.getAction()).thenReturn(UiModeManager.ACTION_EXIT_CAR_MODE_PRIORITIZED);
        receiver.getValue().onReceive(mContext, mIntentExit);
        verify(mSystemStateListener).onCarModeChanged(anyInt(), isNull(), eq(false));

        receiver.getValue().onReceive(mContext, new Intent("invalid action"));
    }

    @SmallTest
    @Test
    public void testOnSetReleaseAutomotiveProjection() {
        SystemStateHelper systemStateHelper = new SystemStateHelper(mContext, mLock);
        // We don't care what listener is registered, that's an implementation detail, but we need
        // to call methods on whatever it is.
        ArgumentCaptor<UiModeManager.OnProjectionStateChangedListener> listenerCaptor =
                ArgumentCaptor.forClass(UiModeManager.OnProjectionStateChangedListener.class);
        verify(mUiModeManager).addOnProjectionStateChangedListener(
                eq(UiModeManager.PROJECTION_TYPE_AUTOMOTIVE), any(), listenerCaptor.capture());
        systemStateHelper.addListener(mSystemStateListener);

        String packageName1 = "Sufjan Stevens";
        String packageName2 = "The Ascension";

        // Should pay attention to automotive projection, though.
        listenerCaptor.getValue().onProjectionStateChanged(
                UiModeManager.PROJECTION_TYPE_AUTOMOTIVE, Set.of(packageName2));
        verify(mSystemStateListener).onAutomotiveProjectionStateSet(packageName2);

        // Without any automotive projection, it should see it as released.
        listenerCaptor.getValue().onProjectionStateChanged(
                UiModeManager.PROJECTION_TYPE_NONE, Set.of());
        verify(mSystemStateListener).onAutomotiveProjectionStateReleased();

        // Try the whole thing again, with different values.
        listenerCaptor.getValue().onProjectionStateChanged(
                UiModeManager.PROJECTION_TYPE_AUTOMOTIVE, Set.of(packageName1));
        verify(mSystemStateListener).onAutomotiveProjectionStateSet(packageName1);
        listenerCaptor.getValue().onProjectionStateChanged(
                UiModeManager.PROJECTION_TYPE_AUTOMOTIVE, Set.of());
        verify(mSystemStateListener, times(2)).onAutomotiveProjectionStateReleased();
    }

    @SmallTest
    @Test
    public void testDeviceOnEarCorrectlyDetected() {
        doAnswer(invocation -> {
            SensorEventListener listener = invocation.getArgument(0);
            Sensor sensor = invocation.getArgument(1);
            if (sensor.getType() == Sensor.TYPE_GRAVITY) {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{1.0f, 9.0f, 1.0f}, Sensor.TYPE_GRAVITY));
            } else {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{0.0f}, Sensor.TYPE_PROXIMITY));
            }
            return true;
        }).when(mSensorManager)
                .registerListener(any(SensorEventListener.class), any(Sensor.class), anyInt());

        assertTrue(SystemStateHelper.isDeviceAtEar(mContext));
        verify(mSensorManager).unregisterListener(any(SensorEventListener.class));
    }

    @SmallTest
    @Test
    public void testDeviceIsNotOnEarWithProxNotSensed() {
        doAnswer(invocation -> {
            SensorEventListener listener = invocation.getArgument(0);
            Sensor sensor = invocation.getArgument(1);
            if (sensor.getType() == Sensor.TYPE_GRAVITY) {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{1.0f, 9.0f, 1.0f}, Sensor.TYPE_GRAVITY));
            } else {
                // do nothing to simulate proximity sensor not reporting
            }
            return true;
        }).when(mSensorManager)
                .registerListener(any(SensorEventListener.class), any(Sensor.class), anyInt());

        assertFalse(SystemStateHelper.isDeviceAtEar(mContext));
        verify(mSensorManager).unregisterListener(any(SensorEventListener.class));
    }

    @SmallTest
    @Test
    public void testDeviceIsNotOnEarWithWrongOrientation() {
        doAnswer(invocation -> {
            SensorEventListener listener = invocation.getArgument(0);
            Sensor sensor = invocation.getArgument(1);
            if (sensor.getType() == Sensor.TYPE_GRAVITY) {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{1.0f, 1.0f, 9.0f}, Sensor.TYPE_GRAVITY));
            } else {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{0.0f}, Sensor.TYPE_PROXIMITY));
            }
            return true;
        }).when(mSensorManager)
                .registerListener(any(SensorEventListener.class), any(Sensor.class), anyInt());

        assertFalse(SystemStateHelper.isDeviceAtEar(mContext));
        verify(mSensorManager).unregisterListener(any(SensorEventListener.class));
    }

    @SmallTest
    @Test
    public void testDeviceIsNotOnEarWithMissingSensor() {
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)).thenReturn(null);
        doAnswer(invocation -> {
            SensorEventListener listener = invocation.getArgument(0);
            Sensor sensor = invocation.getArgument(1);
            if (sensor.getType() == Sensor.TYPE_GRAVITY) {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{1.0f, 9.0f, 1.0f}, Sensor.TYPE_GRAVITY));
            } else {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{0.0f}, Sensor.TYPE_PROXIMITY));
            }
            return true;
        }).when(mSensorManager)
                .registerListener(any(SensorEventListener.class), any(Sensor.class), anyInt());

        assertFalse(SystemStateHelper.isDeviceAtEar(mContext));
    }

    @SmallTest
    @Test
    public void testDeviceIsNotOnEarWithTimeout() {
        doAnswer(invocation -> {
            SensorEventListener listener = invocation.getArgument(0);
            Sensor sensor = invocation.getArgument(1);
            if (sensor.getType() == Sensor.TYPE_GRAVITY) {
                // do nothing
            } else {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{0.0f}, Sensor.TYPE_PROXIMITY));
            }
            return true;
        }).when(mSensorManager)
                .registerListener(any(SensorEventListener.class), any(Sensor.class), anyInt());

        assertFalse(SystemStateHelper.isDeviceAtEar(mContext));
    }

    @SmallTest
    @Test
    public void testDeviceIsOnEarWithMultiSensorInputs() {
        doAnswer(invocation -> {
            SensorEventListener listener = invocation.getArgument(0);
            Sensor sensor = invocation.getArgument(1);
            if (sensor.getType() == Sensor.TYPE_GRAVITY) {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{1.0f, 9.0f, 1.0f}, Sensor.TYPE_GRAVITY));
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{1.0f, -9.0f, 1.0f}, Sensor.TYPE_GRAVITY));
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{1.0f, 0.0f, 8.0f}, Sensor.TYPE_GRAVITY));
            } else {
                listener.onSensorChanged(makeSensorEvent(
                        new float[]{0.0f}, Sensor.TYPE_PROXIMITY));
            }
            return true;
        }).when(mSensorManager)
                .registerListener(any(SensorEventListener.class), any(Sensor.class), anyInt());

        assertTrue(SystemStateHelper.isDeviceAtEar(mContext));
        verify(mSensorManager).unregisterListener(any(SensorEventListener.class));
    }

    private SensorEvent makeSensorEvent(float[] values, int sensorType) throws Exception {
        SensorEvent event = mock(SensorEvent.class);
        Sensor mockSensor = mock(Sensor.class);
        when(mockSensor.getType()).thenReturn(sensorType);
        FieldSetter.setField(event, SensorEvent.class.getDeclaredField("sensor"), mockSensor);
        FieldSetter.setField(event, SensorEvent.class.getDeclaredField("values"), values);
        return event;
    }
}
