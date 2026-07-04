package com.kvm.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * AppModule — intentionally minimal.
 *
 * VirtualFileSystem, VirtualPackageManager, etc. are all @Singleton @Inject
 * constructor classes; Hilt auto-provides them.  Do NOT add @Provides for
 * anything that already has an @Inject constructor — it creates a duplicate
 * binding compile error.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
// No manual providers needed here — all singletons use @Inject constructors.

