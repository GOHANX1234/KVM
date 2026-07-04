package com.kvm.di

import com.kvm.core.virtual.VirtualFileSystem

/**
 * ServiceLocator — minimal static registry for classes that must be accessible
 * from contexts where Hilt injection is not available (e.g. ContextWrapper subclasses,
 * Instrumentation callbacks).
 *
 * Populated by Hilt-managed singletons during application init.
 * This is intentionally kept minimal — only things that genuinely cannot be
 * injected normally should appear here.
 */
object ServiceLocator {
    @Volatile var virtualFileSystem: VirtualFileSystem? = null
}
