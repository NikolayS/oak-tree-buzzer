package com.example.redbutton;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Foreground service that keeps the buzzer alive all day.
 * Android will not kill a foreground service (it shows a persistent notification).
 * The actual network listening still happens in MainActivity — this service just
 * prevents the process from being killed.
 */
public class BuzzerService extends Service {

    static final String CHANNEL_ID = "buzzer_channel";
    static final int NOTIF_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notif = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Oak Tree Buzzer")
            .setContentText("Listening for pings…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();

        try {
            startForeground(NOTIF_ID, notif);
        } catch (Exception ignored) {
            // If Android refuses foreground-service startup on a specific tablet,
            // do not crash the buzzer app. The in-app multicast listener still runs.
            stopSelf();
            return START_NOT_STICKY;
        }

        // START_STICKY: if killed, Android restarts it automatically
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Buzzer Service",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the buzzer alive in the background");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
