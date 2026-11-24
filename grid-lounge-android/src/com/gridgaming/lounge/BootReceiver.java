package com.gridgaming.lounge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;

/** Restores alarms and the background service after a reboot or app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        JSONArray arr = Store.sessions(c);
        Store.applyAlarms(c, arr);
        SessionService.refresh(c, arr.length() > 0);
    }
}
