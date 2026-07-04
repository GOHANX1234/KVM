package com.kvm.domain.usecase

import android.content.Context
import com.kvm.core.virtual.VirtualFileSystem
import com.kvm.core.virtual.VirtualPackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class UninstallVirtualAppUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpm: VirtualPackageManager,
    private val vfs: VirtualFileSystem,
) {
    suspend operator fun invoke(packageName: String, userId: Int, deleteAllInstances: Boolean = false) {
        if (deleteAllInstances) {
            vpm.getAllVirtualApps()
                .filter { it.packageName == packageName }
                .forEach { vpm.uninstallVirtualApp(it.packageName, it.userId) }
            vfs.deleteAppCompletely(packageName)
        } else {
            vpm.uninstallVirtualApp(packageName, userId)
            vfs.deleteInstanceData(packageName, userId)
        }
    }
}
