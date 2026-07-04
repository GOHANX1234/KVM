package com.kvm.domain.usecase

import android.content.Context
import com.kvm.core.installer.AppCloner
import com.kvm.core.virtual.VirtualApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class CloneAppUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloner: AppCloner,
) {
    sealed class Result {
        data class Success(val app: VirtualApp) : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
    }

    suspend operator fun invoke(packageName: String, userId: Int = 0): Result {
        return when (val r = cloner.cloneApp(context, packageName, userId)) {
            is AppCloner.ClonerResult.Success -> Result.Success(r.app)
            is AppCloner.ClonerResult.Error   -> Result.Error(r.message, r.cause)
        }
    }
}
