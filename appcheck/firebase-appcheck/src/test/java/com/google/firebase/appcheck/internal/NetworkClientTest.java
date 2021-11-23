// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.appcheck.internal;

import static com.google.common.truth.Truth.assertThat;
import static com.google.firebase.appcheck.internal.AppCheckTokenResponse.ATTESTATION_TOKEN_KEY;
import static com.google.firebase.appcheck.internal.AppCheckTokenResponse.TIME_TO_LIVE_KEY;
import static com.google.firebase.appcheck.internal.HttpErrorResponse.CODE_KEY;
import static com.google.firebase.appcheck.internal.HttpErrorResponse.ERROR_KEY;
import static com.google.firebase.appcheck.internal.HttpErrorResponse.MESSAGE_KEY;
import static com.google.firebase.appcheck.internal.NetworkClient.X_ANDROID_CERT;
import static com.google.firebase.appcheck.internal.NetworkClient.X_ANDROID_PACKAGE;
import static com.google.firebase.appcheck.internal.NetworkClient.X_FIREBASE_CLIENT;
import static com.google.firebase.appcheck.internal.NetworkClient.X_FIREBASE_CLIENT_LOG_TYPE;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.core.app.ApplicationProvider;
import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.heartbeatinfo.HeartBeatInfo;
import com.google.firebase.heartbeatinfo.HeartBeatInfo.HeartBeat;
import com.google.firebase.inject.Provider;
import com.google.firebase.platforminfo.UserAgentPublisher;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for {@link NetworkClient}. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class NetworkClientTest {

  private static final String API_KEY = "apiKey";
  private static final String APP_ID = "appId";
  private static final String PROJECT_ID = "projectId";
  private static final FirebaseOptions FIREBASE_OPTIONS =
      new FirebaseOptions.Builder()
          .setApiKey(API_KEY)
          .setApplicationId(APP_ID)
          .setProjectId(PROJECT_ID)
          .build();
  private static final String SAFETY_NET_EXPECTED_URL =
      "https://firebaseappcheck.googleapis.com/v1beta/projects/projectId/apps/appId:exchangeSafetyNetToken?key=apiKey";
  private static final String DEBUG_EXPECTED_URL =
      "https://firebaseappcheck.googleapis.com/v1beta/projects/projectId/apps/appId:exchangeDebugToken?key=apiKey";
  private static final String JSON_REQUEST = "jsonRequest";
  private static final int SUCCESS_CODE = 200;
  private static final int ERROR_CODE = 404;
  private static final String ATTESTATION_TOKEN = "token";
  private static final String TIME_TO_LIVE = "3600s";
  private static final String ERROR_MESSAGE = "error message";
  private static final String USER_AGENT = "user-agent";
  private static final String HEART_BEAT_STORAGE_TAG = "fire-app-check";

  @Mock UserAgentPublisher mockUserAgentPublisher;
  @Mock HeartBeatInfo mockHeartBeatInfo;
  @Mock HttpURLConnection mockHttpUrlConnection;
  @Mock OutputStream mockOutputStream;
  @Mock RetryManager mockRetryManager;

  private NetworkClient networkClient;

  @Before
  public void setup() throws Exception {
    MockitoAnnotations.initMocks(this);

    networkClient =
        spy(
            new NetworkClient(
                ApplicationProvider.getApplicationContext(),
                FIREBASE_OPTIONS,
                new Provider<UserAgentPublisher>() {
                  @Override
                  public UserAgentPublisher get() {
                    return mockUserAgentPublisher;
                  }
                },
                new Provider<HeartBeatInfo>() {
                  @Override
                  public HeartBeatInfo get() {
                    return mockHeartBeatInfo;
                  }
                }));

    doReturn(mockHttpUrlConnection).when(networkClient).createHttpUrlConnection(any(URL.class));
    when(mockUserAgentPublisher.getUserAgent()).thenReturn(USER_AGENT);
    when(mockHeartBeatInfo.getHeartBeatCode(HEART_BEAT_STORAGE_TAG))
        .thenReturn(HeartBeat.SDK, HeartBeat.NONE);
    when(mockRetryManager.canRetry()).thenReturn(true);
  }

  @Test
  public void init_nullFirebaseApp_throwsException() {
    assertThrows(
        NullPointerException.class,
        () -> {
          new NetworkClient(null);
        });
  }

  @Test
  public void exchangeSafetyNetToken_successResponse_returnsAppCheckTokenResponse()
      throws Exception {
    JSONObject responseBodyJson = createAttestationResponse();

    when(mockHttpUrlConnection.getOutputStream()).thenReturn(mockOutputStream);
    when(mockHttpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream(responseBodyJson.toString().getBytes()));
    when(mockHttpUrlConnection.getResponseCode()).thenReturn(SUCCESS_CODE);

    AppCheckTokenResponse tokenResponse =
        networkClient.exchangeAttestationForAppCheckToken(
            JSON_REQUEST.getBytes(), NetworkClient.SAFETY_NET, mockRetryManager);
    assertThat(tokenResponse.getAttestationToken()).isEqualTo(ATTESTATION_TOKEN);
    assertThat(tokenResponse.getTimeToLive()).isEqualTo(TIME_TO_LIVE);

    URL expectedUrl = new URL(SAFETY_NET_EXPECTED_URL);
    verify(networkClient).createHttpUrlConnection(expectedUrl);
    verify(mockOutputStream)
        .write(JSON_REQUEST.getBytes(), /* off= */ 0, JSON_REQUEST.getBytes().length);
    verify(mockRetryManager, never()).updateBackoffOnFailure(anyInt());
    verify(mockRetryManager).resetBackoffOnSuccess();
    verifyRequestHeaders(/* shouldSendHeartbeat= */ true);
  }

  @Test
  public void exchangeSafetyNetToken_errorResponse_throwsException() throws Exception {
    JSONObject responseBodyJson = createHttpErrorResponse();

    when(mockHttpUrlConnection.getOutputStream()).thenReturn(mockOutputStream);
    when(mockHttpUrlConnection.getErrorStream())
        .thenReturn(new ByteArrayInputStream(responseBodyJson.toString().getBytes()));
    when(mockHttpUrlConnection.getResponseCode()).thenReturn(ERROR_CODE);

    FirebaseException exception =
        assertThrows(
            FirebaseException.class,
            () ->
                networkClient.exchangeAttestationForAppCheckToken(
                    JSON_REQUEST.getBytes(), NetworkClient.SAFETY_NET, mockRetryManager));

    assertThat(exception.getMessage()).contains(ERROR_MESSAGE);
    URL expectedUrl = new URL(SAFETY_NET_EXPECTED_URL);
    verify(networkClient).createHttpUrlConnection(expectedUrl);
    verify(mockOutputStream)
        .write(JSON_REQUEST.getBytes(), /* off= */ 0, JSON_REQUEST.getBytes().length);
    verify(mockRetryManager).updateBackoffOnFailure(ERROR_CODE);
    verify(mockRetryManager, never()).resetBackoffOnSuccess();
    verifyRequestHeaders(/* shouldSendHeartbeat= */ true);
  }

  @Test
  public void exchangeDebugToken_successResponse_returnsAppCheckTokenResponse() throws Exception {
    JSONObject responseBodyJson = createAttestationResponse();

    when(mockHttpUrlConnection.getOutputStream()).thenReturn(mockOutputStream);
    when(mockHttpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream(responseBodyJson.toString().getBytes()));
    when(mockHttpUrlConnection.getResponseCode()).thenReturn(SUCCESS_CODE);

    AppCheckTokenResponse tokenResponse =
        networkClient.exchangeAttestationForAppCheckToken(
            JSON_REQUEST.getBytes(), NetworkClient.DEBUG, mockRetryManager);
    assertThat(tokenResponse.getAttestationToken()).isEqualTo(ATTESTATION_TOKEN);
    assertThat(tokenResponse.getTimeToLive()).isEqualTo(TIME_TO_LIVE);

    URL expectedUrl = new URL(DEBUG_EXPECTED_URL);
    verify(networkClient).createHttpUrlConnection(expectedUrl);
    verify(mockOutputStream)
        .write(JSON_REQUEST.getBytes(), /* off= */ 0, JSON_REQUEST.getBytes().length);
    verify(mockRetryManager, never()).updateBackoffOnFailure(anyInt());
    verify(mockRetryManager).resetBackoffOnSuccess();
    verifyRequestHeaders(/* shouldSendHeartbeat= */ true);
  }

  @Test
  public void exchangeDebugToken_errorResponse_throwsException() throws Exception {
    JSONObject responseBodyJson = createHttpErrorResponse();

    when(mockHttpUrlConnection.getOutputStream()).thenReturn(mockOutputStream);
    when(mockHttpUrlConnection.getErrorStream())
        .thenReturn(new ByteArrayInputStream(responseBodyJson.toString().getBytes()));
    when(mockHttpUrlConnection.getResponseCode()).thenReturn(ERROR_CODE);

    FirebaseException exception =
        assertThrows(
            FirebaseException.class,
            () ->
                networkClient.exchangeAttestationForAppCheckToken(
                    JSON_REQUEST.getBytes(), NetworkClient.DEBUG, mockRetryManager));

    assertThat(exception.getMessage()).contains(ERROR_MESSAGE);
    URL expectedUrl = new URL(DEBUG_EXPECTED_URL);
    verify(networkClient).createHttpUrlConnection(expectedUrl);
    verify(mockOutputStream)
        .write(JSON_REQUEST.getBytes(), /* off= */ 0, JSON_REQUEST.getBytes().length);
    verify(mockRetryManager).updateBackoffOnFailure(ERROR_CODE);
    verify(mockRetryManager, never()).resetBackoffOnSuccess();
    verifyRequestHeaders(/* shouldSendHeartbeat= */ true);
  }

  @Test
  public void exchangeAttestation_heartbeatNone_doesNotAttachHeader() throws Exception {
    JSONObject responseBodyJson = createAttestationResponse();

    when(mockHttpUrlConnection.getOutputStream()).thenReturn(mockOutputStream);
    when(mockHttpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream(responseBodyJson.toString().getBytes()));
    when(mockHttpUrlConnection.getResponseCode()).thenReturn(SUCCESS_CODE);
    when(mockHeartBeatInfo.getHeartBeatCode(HEART_BEAT_STORAGE_TAG)).thenReturn(HeartBeat.NONE);

    // The heartbeat request header should not be attached when the heartbeat is HeartBeat.NONE.
    networkClient.exchangeAttestationForAppCheckToken(
        JSON_REQUEST.getBytes(), NetworkClient.SAFETY_NET, mockRetryManager);

    verifyRequestHeaders(/* shouldSendHeartbeat= */ false);
  }

  @Test
  public void exchangeUnknownAttestation_throwsException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            networkClient.exchangeAttestationForAppCheckToken(
                JSON_REQUEST.getBytes(), NetworkClient.UNKNOWN, mockRetryManager));

    verify(mockRetryManager, never()).updateBackoffOnFailure(anyInt());
    verify(mockRetryManager, never()).resetBackoffOnSuccess();
  }

  @Test
  public void exchangeAttestation_cannotRetry_throwsException() {
    when(mockRetryManager.canRetry()).thenReturn(false);

    FirebaseException exception =
        assertThrows(
            FirebaseException.class,
            () ->
                networkClient.exchangeAttestationForAppCheckToken(
                    JSON_REQUEST.getBytes(), NetworkClient.DEBUG, mockRetryManager));

    assertThat(exception.getMessage()).contains("Too many attempts");
    verify(mockRetryManager, never()).updateBackoffOnFailure(anyInt());
    verify(mockRetryManager, never()).resetBackoffOnSuccess();
  }

  private void verifyRequestHeaders(boolean shouldSendHeartbeat) {
    verify(mockHttpUrlConnection).setRequestProperty(X_FIREBASE_CLIENT, USER_AGENT);
    if (shouldSendHeartbeat) {
      verify(mockHttpUrlConnection)
          .setRequestProperty(
              X_FIREBASE_CLIENT_LOG_TYPE, Integer.toString(HeartBeat.SDK.getCode()));
    } else {
      verify(mockHttpUrlConnection, never())
          .setRequestProperty(eq(X_FIREBASE_CLIENT_LOG_TYPE), anyString());
    }
    verify(mockHttpUrlConnection)
        .setRequestProperty(
            X_ANDROID_PACKAGE, ApplicationProvider.getApplicationContext().getPackageName());
    verify(mockHttpUrlConnection).setRequestProperty(eq(X_ANDROID_CERT), any());
  }

  private static JSONObject createAttestationResponse() throws Exception {
    JSONObject responseBodyJson = new JSONObject();
    responseBodyJson.put(ATTESTATION_TOKEN_KEY, ATTESTATION_TOKEN);
    responseBodyJson.put(TIME_TO_LIVE_KEY, TIME_TO_LIVE);

    return responseBodyJson;
  }

  private static JSONObject createHttpErrorResponse() throws Exception {
    JSONObject responseBodyJson = new JSONObject();
    JSONObject innerJson = new JSONObject();
    innerJson.put(CODE_KEY, ERROR_CODE);
    innerJson.put(MESSAGE_KEY, ERROR_MESSAGE);
    responseBodyJson.put(ERROR_KEY, innerJson);

    return responseBodyJson;
  }
}
