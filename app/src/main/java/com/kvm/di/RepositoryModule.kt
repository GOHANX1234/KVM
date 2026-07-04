package com.kvm.di

import com.kvm.data.repository.VirtualAppRepository
import com.kvm.data.repository.VirtualAppRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindVirtualAppRepository(
        impl: VirtualAppRepositoryImpl
    ): VirtualAppRepository
}
