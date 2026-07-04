package com.kvm.stub

import android.app.Service
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

/**
 * StubService — placeholder Services pre-declared in AndroidManifest.xml.
 *
 * Mirrors the same pattern as StubActivity: AMS validates the stub class name,
 * and our Binder proxy swaps the implementation before the service starts.
 */
open class StubService : Service() {

    override fun onCreate() {
        super.onCreate()
        Timber.d("StubService [%s] onCreate", javaClass.simpleName)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    class P00 : StubService()
    class P01 : StubService()
    class P02 : StubService()
    class P03 : StubService()
    class P04 : StubService()
    class P05 : StubService()
    class P06 : StubService()
    class P07 : StubService()
    class P08 : StubService()
    class P09 : StubService()
}
