package io.github.swaidi.jadwal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when a dose alarm goes off. */
public class DoseAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DoseLogic.ACTION_FIRE.equals(intent.getAction())) return;
        String key = intent.getStringExtra(DoseLogic.EXTRA_KEY);
        if (key == null) return;
        DoseLogic.fire(context, key, intent.getIntExtra(DoseLogic.EXTRA_ATTEMPT, 0));
    }
}
