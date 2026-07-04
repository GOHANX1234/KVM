package com.kvm.data.repository

import com.kvm.core.virtual.VirtualApp
import com.kvm.data.db.VirtualAppDao
import com.kvm.data.db.entities.VirtualAppEntity
import com.kvm.data.db.entities.toEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VirtualAppRepositoryImpl @Inject constructor(
    private val dao: VirtualAppDao,
) : VirtualAppRepository {

    override fun observeAll(): Flow<List<VirtualAppEntity>> = dao.observeAll()

    override suspend fun getAll(): List<VirtualAppEntity> = dao.getAll()

    override suspend fun getApp(packageName: String, userId: Int): VirtualAppEntity? =
        dao.getApp(packageName, userId)

    override suspend fun insert(app: VirtualApp): Long = dao.insert(app.toEntity())

    override suspend fun delete(packageName: String, userId: Int) = dao.delete(packageName, userId)

    override suspend fun setAutoStart(packageName: String, userId: Int, autoStart: Boolean) =
        dao.setAutoStart(packageName, userId, autoStart)
}
