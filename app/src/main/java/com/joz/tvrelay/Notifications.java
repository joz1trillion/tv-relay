package com.joz.tvrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

final class Notifications {
    static final String ACTION_STOP = "com.joz.tvrelay.STOP";
    private static final String CHANNEL = "relay";

    static Notification build(Service service, String text) {
        NotificationManager nm = service.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Audio relay", NotificationManager.IMPORTANCE_LOW));

        Class<?> screen = service instanceof SenderService ? TvActivity.class : MainActivity.class;
        PendingIntent open = PendingIntent.getActivity(service, 0,
                new Intent(service, screen), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(service, 1,
                new Intent(service, service.getClass()).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(service, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(service.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private Notifications() {}
}
