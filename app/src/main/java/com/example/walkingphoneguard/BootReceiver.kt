package com.example.walkingphoneguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // 今は空でOK
        // 将来、端末再起動後に自動で監視開始したいならここに書く
    }
}