package com.kvm.di

import android.content.Context
import com.kvm.data.db.KVMDatabase
import com.kvm.data.db.VirtualAppDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): KVMDatabase =
        KVMDatabase.getInstance(ctx)

    @Provides
    @Singleton
    fun provideVirtualAppDao(db: KVMDatabase): VirtualAppDao = db.virtualAppDao()
}
