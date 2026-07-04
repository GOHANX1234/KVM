package com.kvm.domain.usecase

import android.content.Context
import android.net.Uri
import com.kvm.core.installer.AppInstaller
import com.kvm.core.virtual.VirtualApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class InstallApkUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: AppInstaller,
) {
    sealed class Result {
        data class Success(val app: VirtualApp) : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
    }

    suspend operator fun invoke(uri: Uri, userId: Int = 0): Result {
        return when (val r = installer.installApk(context, uri, userId)) {
            is AppInstaller.InstallResult.Success -> Result.Success(r.app)
            is AppInstaller.InstallResult.Error   -> Result.Error(r.message, r.cause)
        }
    }
}
