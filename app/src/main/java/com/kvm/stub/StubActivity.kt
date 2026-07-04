package com.kvm.stub

import android.app.Activity
import android.os.Bundle
import timber.log.Timber

/**
 * StubActivity — placeholder activities pre-declared in AndroidManifest.xml.
 *
 * These classes exist purely so AMS can validate them during startActivity().
 * The InstrumentationHook replaces the actual instantiated class with the real
 * guest Activity at the point Instrumentation.newActivity() is called.
 *
 * All 20 stub slots (P00–P19) are inner classes of this sealed hierarchy so they
 * can all be in one file while still having distinct class names in the manifest.
 *
 * Implementation note:
 *   Each stub is a vanilla Activity with no UI of its own — it is transparent
 *   (Theme.Transparent in the manifest).  If the hook fails for any reason, the
 *   user sees a blank transparent screen rather than a crash.
 */
open class StubActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("StubActivity [%s] onCreate — hook should have replaced this", javaClass.simpleName)
    }

    // ── 20 stub slots across 4 stub processes ──────────────────────────────

    class P00 : StubActivity()
    class P01 : StubActivity()
    class P02 : StubActivity()
    class P03 : StubActivity()
    class P04 : StubActivity()
    class P05 : StubActivity()
    class P06 : StubActivity()
    class P07 : StubActivity()
    class P08 : StubActivity()
    class P09 : StubActivity()
    class P10 : StubActivity()
    class P11 : StubActivity()
    class P12 : StubActivity()
    class P13 : StubActivity()
    class P14 : StubActivity()
    class P15 : StubActivity()
    class P16 : StubActivity()
    class P17 : StubActivity()
    class P18 : StubActivity()
    class P19 : StubActivity()
}
