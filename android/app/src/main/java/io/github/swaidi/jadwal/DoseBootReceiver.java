package io.github.swaidi.jadwal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores dose alarms after a restart or an app update. */
public class DoseBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(a)) {
            DoseLogic.scheduleAll(context);
        }
    }
}
