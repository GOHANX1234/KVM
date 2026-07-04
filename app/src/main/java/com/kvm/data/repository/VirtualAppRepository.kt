package com.kvm.data.repository

import com.kvm.core.virtual.VirtualApp
import com.kvm.data.db.entities.VirtualAppEntity
import kotlinx.coroutines.flow.Flow

interface VirtualAppRepository {
    fun observeAll(): Flow<List<VirtualAppEntity>>
    suspend fun getAll(): List<VirtualAppEntity>
    suspend fun getApp(packageName: String, userId: Int): VirtualAppEntity?
    suspend fun insert(app: VirtualApp): Long
    suspend fun delete(packageName: String, userId: Int)
    suspend fun setAutoStart(packageName: String, userId: Int, autoStart: Boolean)
}
