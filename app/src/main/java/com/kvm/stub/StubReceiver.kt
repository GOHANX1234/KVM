package com.kvm.stub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

open class StubReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Timber.d("StubReceiver [%s] onReceive: %s", javaClass.simpleName, intent?.action)
    }

    class P00 : StubReceiver()
    class P01 : StubReceiver()
    class P02 : StubReceiver()
    class P03 : StubReceiver()
}
