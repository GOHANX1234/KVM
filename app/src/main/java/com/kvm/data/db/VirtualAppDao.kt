package com.kvm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kvm.data.db.entities.VirtualAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VirtualAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: VirtualAppEntity): Long

    @Update
    suspend fun update(entity: VirtualAppEntity)

    @Query("SELECT * FROM virtual_apps ORDER BY installed_at DESC")
    fun observeAll(): Flow<List<VirtualAppEntity>>

    @Query("SELECT * FROM virtual_apps ORDER BY installed_at DESC")
    suspend fun getAll(): List<VirtualAppEntity>

    @Query("SELECT * FROM virtual_apps WHERE package_name = :pkg AND user_id = :userId LIMIT 1")
    suspend fun getApp(pkg: String, userId: Int): VirtualAppEntity?

    @Query("SELECT * FROM virtual_apps WHERE package_name = :pkg")
    suspend fun getAllInstancesOf(pkg: String): List<VirtualAppEntity>

    /**
     * Delete by logical key (package_name + user_id).
     * We query the id first, but simpler to just target the unique index columns.
     */
    @Query("DELETE FROM virtual_apps WHERE package_name = :pkg AND user_id = :userId")
    suspend fun delete(pkg: String, userId: Int)

    @Query("DELETE FROM virtual_apps WHERE package_name = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Query("DELETE FROM virtual_apps")
    suspend fun deleteAll()

    @Query("UPDATE virtual_apps SET auto_start = :autoStart WHERE package_name = :pkg AND user_id = :userId")
    suspend fun setAutoStart(pkg: String, userId: Int, autoStart: Boolean)

    @Query("SELECT COUNT(*) FROM virtual_apps")
    suspend fun count(): Int
}
