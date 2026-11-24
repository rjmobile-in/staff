package com.rjmobile.staff;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {
    // --- Security & FileChooser Constants ---
    private static final int PERMISSION_REQ_CODE = 1001;
    private static final int FILE_CHOOSER_REQ = 2001;
    private static final int PHOTO_PICK_REQ = 3001;
    private ValueCallback<Uri[]> mFilePathCallback;

    // --- Audio & Media Constants ---
    private static final String CHANNEL_ID = "rj_music_channel";
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Bitmap logoBitmap;
    private String currentTitle = "RJ Mobile Songs";
    private boolean currentPlaying = false;
    private long currentDurationMs = 0;
    private long currentPositionMs = 0;
    private boolean wasPausedByCall = false;
    private long appLaunchTimeMs = 0;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                // Incoming call ringing or minor chime - DO NOT PAUSE while ringing
                return;
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                // Check if this is an actual phone call (MODE_IN_CALL or MODE_IN_COMMUNICATION)
                int mode = (audioManager != null) ? audioManager.getMode() : AudioManager.MODE_NORMAL;
                boolean isCall = (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION);

                // Ignore startup focus fluctuations within 4s of app opening
                if (!isCall && (SystemClock.elapsedRealtime() - appLaunchTimeMs < 4000)) {
                    return;
                }

                // Call picked up (answered)
                if (isCall && currentPlaying) {
                    wasPausedByCall = true;
                    triggerWeb("pauseTrack");
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                // Call ended / audio focus returned - resume automatically
                if (wasPausedByCall) {
                    wasPausedByCall = false;
                    triggerWeb("playTrack");
                }
            }
        }
    };

    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.endsWith(".PLAY_PAUSE")) {
                    triggerWeb("togglePlay");
                } else if (action.endsWith(".NEXT")) {
                    triggerWeb("nextTrack");
                } else if (action.endsWith(".PREV")) {
                    triggerWeb("prevTrack");
                }
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appLaunchTimeMs = SystemClock.elapsedRealtime();

        checkAndRequestPermissions();

        WebView webView = getBridge().getWebView();
        // Security: Lock out Chrome DevTools debugging in production release
        WebView.setWebContentsDebuggingEnabled(false);

        webView.getSettings().setGeolocationEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                    mFilePathCallback = null;
                }
                mFilePathCallback = filePathCallback;

                try {
                    Intent intent = fileChooserParams.createIntent();
                    intent.setType("image/*");
                    startActivityForResult(intent, FILE_CHOOSER_REQ);
                    return true;
                } catch (Exception e) {
                    try {
                        Intent pickPhoto = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                        pickPhoto.setType("image/*");
                        startActivityForResult(pickPhoto, PHOTO_PICK_REQ);
                        return true;
                    } catch (Exception ex) {
                        mFilePathCallback = null;
                        return false;
                    }
                }
            }
        });

        // Register both Javascript Interfaces
        webView.addJavascriptInterface(new SecurityBridge(), "RJSecurityNative");
        webView.addJavascriptInterface(new WebAppInterface(), "RJMediaNative");

        // --- Audio Notification & Focus Setup ---
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        }

        try {
            logoBitmap = BitmapFactory.decodeStream(getAssets().open("public/logo.png"));
        } catch (Exception e) {
            logoBitmap = null;
        }

        mediaSession = new MediaSession(this, "RJStaffSongsSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() { triggerWeb("playTrack"); }
            @Override
            public void onPause() { triggerWeb("pauseTrack"); }
            @Override
            public void onSkipToNext() { triggerWeb("nextTrack"); }
            @Override
            public void onSkipToPrevious() { triggerWeb("prevTrack"); }
            @Override
            public void onSeekTo(long pos) { triggerWeb("seekTo:" + pos); }
        });
        mediaSession.setActive(true);

        IntentFilter filter = new IntentFilter();
        filter.addAction(getPackageName() + ".PLAY_PAUSE");
        filter.addAction(getPackageName() + ".NEXT");
        filter.addAction(getPackageName() + ".PREV");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(actionReceiver, filter);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Store Music Controls",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Controls on lock screen and notification tray");
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void updatePlaybackState(boolean isPlaying, long positionMs) {
        this.currentPlaying = isPlaying;
        this.currentPositionMs = positionMs;

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                       PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                       PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO;

        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
            .setActions(actions)
            .setState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                      positionMs >= 0 ? positionMs : PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                      isPlaying ? 1.0f : 0.0f,
                      SystemClock.elapsedRealtime());

        if (mediaSession != null) {
            mediaSession.setPlaybackState(stateBuilder.build());
        }
    }

    private void updateMetadata(String title, long durationMs) {
        this.currentTitle = title;
        this.currentDurationMs = durationMs;

        MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "RJ Mobile Jaipur")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Store Broadcaster");

        if (durationMs > 0) {
            metaBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        }

        if (logoBitmap != null) {
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, logoBitmap);
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, logoBitmap);
        }
        if (mediaSession != null) {
            mediaSession.setMetadata(metaBuilder.build());
        }
    }

    public void showNotification(String title, boolean isPlaying, long positionMs, long durationMs) {
        updateMetadata(title, durationMs);
        updatePlaybackState(isPlaying, positionMs);

        Intent prevIntent = new Intent(getPackageName() + ".PREV");
        PendingIntent pPrev = PendingIntent.getBroadcast(this, 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent playIntent = new Intent(getPackageName() + ".PLAY_PAUSE");
        PendingIntent pPlay = PendingIntent.getBroadcast(this, 2, playIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent nextIntent = new Intent(getPackageName() + ".NEXT");
        PendingIntent pNext = PendingIntent.getBroadcast(this, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int playIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
               .setContentText("RJ Mobile Jaipur")
               .setSmallIcon(R.mipmap.ic_launcher)
               .setContentIntent(pOpen)
               .setVisibility(Notification.VISIBILITY_PUBLIC)
               .setOngoing(isPlaying)
               .setStyle(new Notification.MediaStyle()
                   .setMediaSession(mediaSession != null ? mediaSession.getSessionToken() : null)
                   .setShowActionsInCompactView(0, 1, 2))
               .addAction(android.R.drawable.ic_media_previous, "Previous", pPrev)
               .addAction(playIcon, isPlaying ? "Pause" : "Play", pPlay)
               .addAction(android.R.drawable.ic_media_next, "Next", pNext);

        if (logoBitmap != null) {
            builder.setLargeIcon(logoBitmap);
        }

        if (notificationManager != null) {
            notificationManager.notify(101, builder.build());
        }
    }

    private void triggerWeb(String action) {
        runOnUiThread(() -> {
            try {
                getBridge().getWebView().evaluateJavascript("window.onMediaAction('" + action + "')", null);
            } catch (Exception ignored) {}
        });
    }

    // --- Media Controls Bridge ---
    public class WebAppInterface {
        @JavascriptInterface
        public void updatePlayback(String title, boolean isPlaying, double positionSec, double durationSec) {
            runOnUiThread(() -> {
                long posMs = positionSec >= 0 ? (long) (positionSec * 1000) : 0;
                long durMs = durationSec > 0 ? (long) (durationSec * 1000) : 0;
                showNotification(title, isPlaying, posMs, durMs);
            });
        }

        @JavascriptInterface
        public void updatePosition(boolean isPlaying, double positionSec, double durationSec) {
            runOnUiThread(() -> {
                long posMs = positionSec >= 0 ? (long) (positionSec * 1000) : 0;
                long durMs = durationSec > 0 ? (long) (durationSec * 1000) : 0;
                if (durMs > 0 && durMs != currentDurationMs) {
                    updateMetadata(currentTitle, durMs);
                }
                updatePlaybackState(isPlaying, posMs);
            });
        }

        @JavascriptInterface
        public void updateTrack(String title, boolean isPlaying) {
            runOnUiThread(() -> showNotification(title, isPlaying, 0, 0));
        }

        @JavascriptInterface
        public void stopNotification() {
            runOnUiThread(() -> {
                if (notificationManager != null) {
                    notificationManager.cancel(101);
                }
            });
        }
    }

    // --- FileChooser Activity Result ---
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQ || requestCode == PHOTO_PICK_REQ) {
            if (mFilePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
        }
    }

    private void checkAndRequestPermissions() {
        List<String> list = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!list.isEmpty()) {
            ActivityCompat.requestPermissions(this, list.toArray(new String[0]), PERMISSION_REQ_CODE);
        }
    }

    // --- Hardware Anti-Mock & Security Bridge ---
    public class SecurityBridge {
        @JavascriptInterface
        public String getVerifiedLocation() {
            JSONObject res = new JSONObject();
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) {
                    res.put("success", false);
                    res.put("error", "NO_LOCATION_SERVICE");
                    return res.toString();
                }

                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    res.put("success", false);
                    res.put("error", "PERMISSION_DENIED");
                    res.put("message", "Location permission denied. Please allow location in Settings.");
                    return res.toString();
                }

                Location gpsLoc = null;
                Location netLoc = null;
                try { gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (SecurityException ignored) {}
                try { netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (SecurityException ignored) {}

                Location bestLocation = null;
                if (gpsLoc != null && netLoc != null) {
                    bestLocation = (gpsLoc.getTime() > netLoc.getTime()) ? gpsLoc : netLoc;
                } else if (gpsLoc != null) {
                    bestLocation = gpsLoc;
                } else {
                    bestLocation = netLoc;
                }

                if (bestLocation == null) {
                    res.put("success", false);
                    res.put("error", "NO_FIX");
                    res.put("message", "Acquiring GPS fix... Please ensure you are under open sky.");
                    return res.toString();
                }

                // Hardware Mock Check
                boolean isMock = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    isMock = bestLocation.isMock();
                } else {
                    isMock = bestLocation.isFromMockProvider();
                }

                if (!isMock && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    try {
                        isMock = !Settings.Secure.getString(getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION).equals("0");
                    } catch (Exception ignored) {}
                }

                if (isMock) {
                    res.put("success", false);
                    res.put("isMock", true);
                    res.put("error", "MOCK_LOCATION_DETECTED");
                    res.put("message", "SECURITY ALERT: Mock Location / Fake GPS detected! Attendance is blocked. Please disable any spoofing apps.");
                    return res.toString();
                }

                res.put("success", true);
                res.put("isMock", false);
                res.put("latitude", bestLocation.getLatitude());
                res.put("longitude", bestLocation.getLongitude());
                res.put("accuracy", bestLocation.getAccuracy());
                res.put("time", bestLocation.getTime());
                return res.toString();

            } catch (Exception e) {
                try {
                    res.put("success", false);
                    res.put("error", e.getMessage());
                } catch (Exception ignored) {}
                return res.toString();
            }
        }

        @JavascriptInterface
        public void vibrateSuccess() {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(120);
                    }
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean isSecurityEnforced() {
            return true;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(actionReceiver);
        } catch (Exception ignored) {}
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(focusChangeListener);
            }
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        if (notificationManager != null) {
            notificationManager.cancel(101);
        }
    }
}
