package dev.shalaga44.you_are_okay


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class Restarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Restarter", "restartservice received — starting DataService")
        val service = Intent(context, DataService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service) else context.startService(service)
    }
}