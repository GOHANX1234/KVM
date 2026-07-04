#include "common.h"
#include <cstring>

/*
 * Proc utilities — hide host process identity from virtual apps.
 *
 * Virtual apps may read /proc/self/cmdline or call android.os.Process.myPid()
 * to inspect who they are.  We intercept the low-level calls and substitute
 * the guest package name / process name so apps see themselves running as
 * their own process.
 */

namespace kvm {

static char  g_fakeProcessName[256] = {};
static pid_t g_fakePid = 0;

void setVirtualProcessName(const char* name, pid_t pid) {
    if (name) {
        strncpy(g_fakeProcessName, name, sizeof(g_fakeProcessName) - 1);
    }
    g_fakePid = pid;
    KLOGI("ProcUtils: virtual process name='%s' pid=%d", g_fakeProcessName, g_fakePid);
}

} // namespace kvm
