package com.tpx1000.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

/**
 * 背景播放前台服務：讓程序在螢幕關閉後不被系統收掉，音訊得以繼續。
 *
 * Android 8 起背景程序隨時可能被凍結回收；要持續播放音訊就必須跑前台服務，
 * 而前台服務一定要有常駐通知（系統規定，拿不掉）。
 * Android 14（targetSdk 34）起還必須宣告 foregroundServiceType 與對應權限。
 * 宣告 mediaPlayback 時系統期待有 active MediaSession，所以這裡建了真的 session，
 * 順便讓鎖定畫面與耳機按鈕能控制網頁。
 *
 * WakeLock 用 PARTIAL_WAKE_LOCK：只保持 CPU，螢幕該暗還是暗。
 */
public class PlaybackService extends Service {

    public static final String ACTION_START = "com.tpx1000.app.PLAYBACK_START";
    public static final String ACTION_STOP = "com.tpx1000.app.PLAYBACK_STOP";
    public static final String ACTION_TOGGLE = "com.tpx1000.app.PLAYBACK_TOGGLE";

    private static final String CHANNEL_ID = "tpx1000_playback";
    private static final int NOTIFICATION_ID = 1001;

    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;

    private static TransportCallback transportCallback;

    public interface TransportCallback { void onTogglePlayback(); }

    public static void setTransportCallback(@Nullable TransportCallback cb) {
        transportCallback = cb;
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, PlaybackService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, PlaybackService.class).setAction(ACTION_STOP));
        } catch (Exception ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        mediaSession = new MediaSessionCompat(this, "TPX1000");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (transportCallback != null) transportCallback.onTogglePlayback();
            }

            @Override
            public void onPause() {
                if (transportCallback != null) transportCallback.onTogglePlayback();
            }
        });
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                .build());
        mediaSession.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TPX1000:playback");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_TOGGLE.equals(action)) {
            if (transportCallback != null) transportCallback.onTogglePlayback();
            return START_STICKY;
        }

        startForegroundCompat();
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(3 * 60 * 60 * 1000L);
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK),
                flags);

        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE), flags);

        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP), flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.playback_running))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setColor(0xFFC45C1A)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_pause,
                        getString(R.string.playback_toggle), toggle)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.playback_stop), stop)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.playback_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.playback_channel_desc));
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
