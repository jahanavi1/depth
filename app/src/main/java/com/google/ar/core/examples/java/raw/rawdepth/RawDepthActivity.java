/*
 * Copyright 2021 Google LLC
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
 * limitations under the License.
 */

package com.google.ar.core.examples.java.raw.rawdepth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.PlaybackStatus;
import com.google.ar.core.RecordingConfig;
import com.google.ar.core.RecordingStatus;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.helloar.HelloArActivity;
import com.google.ar.core.examples.java.helloar.R;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.PlaybackFailedException;
import com.google.ar.core.exceptions.RecordingFailedException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that shows how to use ARCore Raw Depth API. The application will display
 * a 3D point cloud and allow the user control the number of points based on depth confidence.
 */
public class RawDepthActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = RawDepthActivity.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;
  private boolean depthReceived;

  private Session session;

  private int REQUEST_MP4_SELECTOR = 1;


  private TextView tvDepthApi;

  private boolean hasSetTextureNames = false;

  private RadioButton radioButton;

  private ImageView selectSec;

  private RadioGroup radioGroup1;

  private RadioGroup radioGroup2;
  private ConstraintLayout depthContainer;
  private ConstraintLayout secContainer;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  private final Renderer renderer = new Renderer();

  // This lock prevents accessing the frame images while Session is paused.
  private final Object frameInUseLock = new Object();

  /** The current raw depth image timestamp. */
  private long depthTimestamp = -1;
  private TextView tvSecTitle;

  private Long MAX_DURATION = 10000L;

  private ImageView playbackButton;
  private Button recordbackButton;
  @SuppressLint("MissingInflatedId")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_raw);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up rendering.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);


    tvDepthApi = findViewById(R.id.tvDepthApi);
//    tvResoulation = findViewById(R.id.tvResolution);
    selectSec = findViewById(R.id.selectSec);
    radioGroup1 = findViewById(R.id.radioGroup1);
    radioGroup2 = findViewById(R.id.radioGroup2);
    playbackButton = findViewById(R.id.record_button);
    recordbackButton = findViewById(R.id.playback_button);

    depthContainer = findViewById(R.id.depthContainer);
    secContainer = findViewById(R.id.secContainer);
    tvSecTitle = findViewById(R.id.tvSecTitle);

    // Set up confidence threshold slider.
    SeekBar seekBar = findViewById(R.id.slider);
    seekBar.setProgress((int) (renderer.getPointAmount() * seekBar.getMax()));
    seekBar.setOnSeekBarChangeListener(seekBarChangeListener);

    tvSecTitle.setText("10 Sec");
    selectSec.setOnClickListener(view -> {
      depthContainer.setVisibility(View.GONE);
      secContainer.setVisibility(View.VISIBLE);
      secondsSelections();
    });

    tvDepthApi.setText("  Raw  ");
    tvDepthApi.setOnClickListener(view -> {
      depthContainer.setVisibility(View.VISIBLE);
      secContainer.setVisibility(View.GONE);
      depthSelections();
    });

    playbackButton.setOnClickListener(this::onClickRecord);
    recordbackButton.setOnClickListener(this::onClickPlayback);


    installRequested = false;
    depthReceived = false;
  }

  public void onClickPlayback(View view) {
    Log.d(TAG, "onClickPlayback");

    switch (appState) {

      // If the app is not playing back, open the file picker.
      case Idle: {
        boolean hasStarted = selectFileToPlayback();
        Log.d(TAG, String.format("onClickPlayback start: selectFileToPlayback %b", hasStarted));
        break;
      }

      // If the app is playing back, stop playing back.
      case Playingback: {
        boolean hasStopped = stopPlayingback();
        Log.d(TAG, String.format("onClickPlayback stop: hasStopped %b", hasStopped));
        break;
      }

      default:
        // Recording - do nothing.
        break;
    }

    // Update the UI for the "Record" and "Playback" buttons.
    updateRecordButton();
    updatePlaybackButton();
  }

  private void updatePlaybackButton() {
    View buttonView = findViewById(R.id.playback_button);
    Button button = (Button)buttonView;

    switch (appState) {

      // The app is neither recording nor playing back. The "Playback" button is visible.
      case Idle:
        button.setText("Playback");
        button.setVisibility(View.VISIBLE);
        break;

      // While playing back, the "Playback" button is visible and says "Stop".
      case Playingback:
        button.setText("Stop");
        button.setVisibility(View.VISIBLE);
        break;

      // During recording, the "Playback" button is not visible.
      case Recording:
        button.setVisibility(View.INVISIBLE);
        break;
    }
  }

  private boolean stopPlayingback() {
    // Correctness check, only stop playing back when the app is playing back.
    if (appState != HelloArActivity.AppState.Playingback)
      return false;

    pauseARCoreSession();

    // Close the current session and create a new session.
    session.close();
    try {
      session = new Session(this);
    } catch (UnavailableArcoreNotInstalledException
             |UnavailableApkTooOldException
             |UnavailableSdkTooOldException
             |UnavailableDeviceNotCompatibleException e) {
      Log.e(TAG, "Error in return to Idle state. Cannot create new ARCore session", e);
      return false;
    }

    boolean canResume = resumeARCoreSession();
    if (!canResume)
      return false;

    // A new session will not have a camera texture name.
    // Manually set hasSetTextureNames to false to trigger a reset.
    hasSetTextureNames = false;

    // Reset appState to Idle, and update the "Record" and "Playback" buttons.
    appState = HelloArActivity.AppState.Idle;
    updateRecordButton();
    updatePlaybackButton();

    return true;
  }

  // Begin playback once the user has selected the file.
  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    // Check request status. Log an error if the selection fails.
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_MP4_SELECTOR) {
      Log.e(TAG, "onActivityResult select file failed");
      return;
    }

    Uri mp4FileUri = data.getData();
    Log.d(TAG, String.format("onActivityResult result is %s", mp4FileUri));

    // Begin playback.
    startPlayingback(mp4FileUri);
  }

  private boolean startPlayingback(Uri mp4FileUri) {
    if (mp4FileUri == null)
      return false;

    Log.d(TAG, "startPlayingback at:" + mp4FileUri);

    pauseARCoreSession();

    try {
      session.setPlaybackDatasetUri(mp4FileUri);
    } catch (PlaybackFailedException e) {
      Log.e(TAG, "startPlayingback - setPlaybackDataset failed", e);
    }

    // The session's camera texture name becomes invalid when the
    // ARCore session is set to play back.
    // Workaround: Reset the Texture to start Playback
    // so it doesn't crashes with AR_ERROR_TEXTURE_NOT_SET.
    hasSetTextureNames = false;

    boolean canResume = resumeARCoreSession();
    if (!canResume)
      return false;

    PlaybackStatus playbackStatus = session.getPlaybackStatus();
    Log.d(TAG, String.format("startPlayingback - playbackStatus %s", playbackStatus));


    if (playbackStatus != PlaybackStatus.OK) { // Correctness check
      return false;
    }

    appState = HelloArActivity.AppState.Playingback;
    updateRecordButton();
    updatePlaybackButton();

    return true;
  }

  private boolean selectFileToPlayback() {
    // Start file selection from Movies directory.
    // Android 10 and above requires VOLUME_EXTERNAL_PRIMARY to write to MediaStore.
    Uri videoCollection;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      videoCollection = MediaStore.Video.Media.getContentUri(
              MediaStore.VOLUME_EXTERNAL_PRIMARY);
    } else {
      videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    // Create an Intent to select a file.
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

    // Add file filters such as the MIME type, the default directory and the file category.
    intent.setType(MP4_VIDEO_MIME_TYPE); // Only select *.mp4 files
    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, videoCollection); // Set default directory
    intent.addCategory(Intent.CATEGORY_OPENABLE); // Must be files that can be opened

    this.startActivityForResult(intent, REQUEST_MP4_SELECTOR);

    return true;
  }

  public enum AppState {
    Idle,
    Recording,
    Playingback
  }

  // Tracks app's specific state changes.
  private HelloArActivity.AppState appState = HelloArActivity.AppState.Idle;
  private void updateRecordButton() {

    switch (appState) {
      case Idle:
        playbackButton.setImageResource(R.drawable.ic_start);
        break;
      case Recording:
        playbackButton.setImageResource(R.drawable.ic_stop);
        break;
    }
  }

  // Handle the "Record" button click event.
  public void onClickRecord(View view) {
    Log.d(TAG, "onClickRecord");

    // Check the app's internal state and switch to the new state if needed.
    switch (appState) {
      // If the app is not recording, begin recording.
      case Idle: {
        boolean hasStarted = startRecording();
        Log.d(TAG, String.format("onClickRecord start: hasStarted %b", hasStarted));


        new Handler().postDelayed(() -> {
          // Intent is used to switch from one activity to another.
          boolean hasStopped = stopRecording();
          if (hasStopped)
            appState = HelloArActivity.AppState.Idle;
          updateRecordButton();
        }, MAX_DURATION);

        if (hasStarted)
          appState = HelloArActivity.AppState.Recording;
        break;
      }

      // If the app is recording, stop recording.
      case Recording: {
        boolean hasStopped = stopRecording();

        Log.d(TAG, String.format("onClickRecord stop: hasStopped %b", hasStopped));

        if (hasStopped)
          appState = HelloArActivity.AppState.Idle;

        break;
      }

      default:
        // Do nothing.

        break;
    }

    updateRecordButton();
  }

  private boolean startRecording() {
    Uri mp4FileUri = createMp4File();
    if (mp4FileUri == null)
      return false;

    Log.d(TAG, "startRecording at: " + mp4FileUri);

    pauseARCoreSession();

    // Configure the ARCore session to start recording.
    RecordingConfig recordingConfig = new RecordingConfig(session)
            .setMp4DatasetUri(mp4FileUri)
            .setAutoStopOnPause(true);

    try {
      // Prepare the session for recording, but do not start recording yet.
      session.startRecording(recordingConfig);
    } catch (RecordingFailedException e) {
      Log.e(TAG, "startRecording - Failed to prepare to start recording", e);
      return false;
    }

    boolean canResume = resumeARCoreSession();
    if (!canResume)
      return false;

    // Correctness checking: check the ARCore session's RecordingState.
    RecordingStatus recordingStatus = session.getRecordingStatus();
    Log.d(TAG, String.format("startRecording - recordingStatus %s", recordingStatus));
    return recordingStatus == RecordingStatus.OK;
  }


  private void pauseARCoreSession() {
    // Pause the GLSurfaceView so that it doesn't update the ARCore session.
    // Pause the ARCore session so that we can update its configuration.
    // If the GLSurfaceView is not paused,
    //   onDrawFrame() will try to update the ARCore session
    //   while it's paused, resulting in a crash.
    surfaceView.onPause();
    session.pause();
  }

  private boolean resumeARCoreSession() {
    // We must resume the ARCore session before the GLSurfaceView.
    // Otherwise, the GLSurfaceView will try to update the ARCore session.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      Log.e(TAG, "CameraNotAvailableException in resumeARCoreSession", e);
      return false;
    }

    surfaceView.onResume();
    return true;
  }

  private boolean stopRecording() {
    try {
      session.stopRecording();
    } catch (RecordingFailedException e) {
      Log.e(TAG, "stopRecording - Failed to stop recording", e);
      return false;
    }

    // Correctness checking: check if the session stopped recording.
    return session.getRecordingStatus() == RecordingStatus.NONE;
  }

  private final String MP4_VIDEO_MIME_TYPE = "video/mp4";

  private Uri createMp4File() {
    // Since we use legacy external storage for Android 10,
    // we still need to request for storage permission on Android 10.
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
      if (!checkAndRequestStoragePermission()) {
        Log.i(TAG, String.format(
                "Didn't createMp4File. No storage permission, API Level = %d",
                Build.VERSION.SDK_INT));
        return null;
      }
    }

    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
    String mp4FileName = "arcore-" + dateFormat.format(new Date()) + ".mp4";

    ContentResolver resolver = this.getContentResolver();

    Uri videoCollection = null;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      videoCollection = MediaStore.Video.Media.getContentUri(
              MediaStore.VOLUME_EXTERNAL_PRIMARY);
    } else {
      videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    // Create a new Media file record.
    ContentValues newMp4FileDetails = new ContentValues();
    newMp4FileDetails.put(MediaStore.Video.Media.DISPLAY_NAME, mp4FileName);
    newMp4FileDetails.put(MediaStore.Video.Media.MIME_TYPE, MP4_VIDEO_MIME_TYPE);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // The Relative_Path column is only available since API Level 29.
      newMp4FileDetails.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
    } else {
      // Use the Data column to set path for API Level <= 28.
      File mp4FileDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
      String absoluteMp4FilePath = new File(mp4FileDir, mp4FileName).getAbsolutePath();
      newMp4FileDetails.put(MediaStore.Video.Media.DATA, absoluteMp4FilePath);
    }

    Uri newMp4FileUri = resolver.insert(videoCollection, newMp4FileDetails);

    // Ensure that this file exists and can be written.
    if (newMp4FileUri == null) {
      Log.e(TAG, String.format("Failed to insert Video entity in MediaStore. API Level = %d", Build.VERSION.SDK_INT));
      return null;
    }

    // This call ensures the file exist before we pass it to the ARCore API.
    if (!testFileWriteAccess(newMp4FileUri)) {
      return null;
    }

    Log.d(TAG, String.format("createMp4File = %s, API Level = %d", newMp4FileUri, Build.VERSION.SDK_INT));

    return newMp4FileUri;
  }

  // Test if the file represented by the content Uri can be open with write access.
  private boolean testFileWriteAccess(Uri contentUri) {
    try (java.io.OutputStream mp4File = this.getContentResolver().openOutputStream(contentUri)) {
      Log.d(TAG, String.format("Success in testFileWriteAccess %s", contentUri.toString()));
      return true;
    } catch (java.io.FileNotFoundException e) {
      Log.e(TAG, String.format("FileNotFoundException in testFileWriteAccess %s", contentUri.toString()), e);
    } catch (java.io.IOException e) {
      Log.e(TAG, String.format("IOException in testFileWriteAccess %s", contentUri.toString()), e);
    }

    return false;
  }

  private final int REQUEST_WRITE_EXTERNAL_STORAGE = 1;
  public boolean checkAndRequestStoragePermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this,
              new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
              REQUEST_WRITE_EXTERNAL_STORAGE);
      return false;
    }

    return true;
  }

  private void secondsSelections() {
    radioGroup1.setOnCheckedChangeListener((group, checkedId) -> {
      if (checkedId == R.id.radio10Sec) {
        MAX_DURATION = 10000L;
        tvSecTitle.setText("10 Sec");
        secContainer.setVisibility(View.GONE);
      } else if (R.id.radio20Sec == checkedId) {
        MAX_DURATION = 20000L;
        tvSecTitle.setText("20 Sec");
        secContainer.setVisibility(View.GONE);
      } else {
        MAX_DURATION = 30000L;
        tvSecTitle.setText("30 Sec");
        secContainer.setVisibility(View.GONE);
      }
    });
  }


  private void depthSelections() {
    radioGroup2.setOnCheckedChangeListener((group, checkedId) -> {
      radioButton = (RadioButton) findViewById(checkedId);

      if (checkedId == R.id.radioRawdepth) {
        tvDepthApi.setText("  Raw  ");
//        radioButton.isChecked();
        Intent intent = new Intent(getApplicationContext(), RawDepthActivity.class);
        startActivity(intent);
        finish();
        depthContainer.setVisibility(View.GONE);
      } else {
        Intent intent = new Intent(getApplicationContext(), HelloArActivity.class);
        startActivity(intent);
        tvDepthApi.setText("  Full  ");
        finish();
      }
    });
  }

  private SeekBar.OnSeekBarChangeListener seekBarChangeListener =
      new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
          float progressNormalized = (float) progress / seekBar.getMax();
          renderer.setPointAmount(progressNormalized);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
      };

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (RuntimeException e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (!session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
        message = "This device does not support the ARCore Raw Depth API.";
        session = null;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      // Wait until the frame is no longer being processed.
      synchronized (frameInUseLock) {
        // Enable raw depth estimation and auto focus mode while ARCore is running.
        Config config = session.getConfig();
        config.setDepthMode(Config.DepthMode.RAW_DEPTH_ONLY);
        session.configure(config);
        session.resume();
      }
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();

    messageSnackbarHelper.showMessage(this, "No depth yet. Try moving the device.");
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - see note in onResume().
      // GLSurfaceView is paused before pausing the ARCore session, to prevent onDrawFrame() from
      // calling session.update() on a paused session.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      renderer.createOnGlThread(/*context=*/ this);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }

    // Synchronize prevents session.update() call while paused, see note in onPause().
    synchronized (frameInUseLock) {
      // Notify ARCore that the view size changed so that the perspective matrix can be adjusted.
      displayRotationHelper.updateSessionIfNeeded(session);

      try {
        session.setCameraTextureNames(new int[] {0});

        Frame frame = session.update();
        Camera camera = frame.getCamera();

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

        if (camera.getTrackingState() != TrackingState.TRACKING) {
          // If motion tracking is not available but previous depth is available, notify the user
          // that the app will resume with tracking.
          if (depthReceived) {
            messageSnackbarHelper.showMessage(
                this, TrackingStateHelper.getTrackingFailureReasonString(camera));
          }

          // If not tracking, do not render the point cloud.
          return;
        }

        // Check if the frame contains new depth data or a 3D reprojection of the previous data. See
        // documentation of acquireRawDepthImage16Bits for more details.
        boolean containsNewDepthData;
        try (Image depthImage = frame.acquireRawDepthImage16Bits()) {
          containsNewDepthData = depthTimestamp == depthImage.getTimestamp();
          depthTimestamp = depthImage.getTimestamp();
        } catch (NotYetAvailableException e) {
          // This is normal at the beginning of session, where depth hasn't been estimated yet.
          containsNewDepthData = false;
        }

        if (containsNewDepthData) {
          // Get Raw Depth data of the current frame.
          final DepthData depth = DepthData.create(session, frame);

          // Skip rendering the current frame if an exception arises during depth data processing.
          // For example, before depth estimation finishes initializing.
          if (depth != null) {
            depthReceived = true;
            renderer.update(depth);
          }
        }

        float[] projectionMatrix = new float[16];
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
        float[] viewMatrix = new float[16];
        camera.getViewMatrix(viewMatrix, 0);

        // Visualize depth points.
        renderer.draw(viewMatrix, projectionMatrix);

        // Hide all user notifications when the frame has been rendered successfully.
        messageSnackbarHelper.hide(this);
      } catch (Throwable t) {
        // Avoid crashing the application due to unhandled exceptions.
        Log.e(TAG, "Exception on the OpenGL thread", t);
      }
    }
  }
}
