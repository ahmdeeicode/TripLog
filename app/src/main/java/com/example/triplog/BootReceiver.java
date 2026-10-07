package com.example.triplog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** إذا أُعيد تشغيل الشاشة أثناء رحلة لم تُنهَ، نكمل تسجيلها. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && new SessionStore(ctx).loadActive() != null) {
            TripService.start(ctx);
        }
    }
}
