package com.kvm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kvm.data.db.entities.VirtualAppEntity

@Database(
    entities = [VirtualAppEntity::class],
    version  = 1,
    exportSchema = true
)
abstract class KVMDatabase : RoomDatabase() {

    abstract fun virtualAppDao(): VirtualAppDao

    companion object {
        private const val DB_NAME = "kvm_virtual_space.db"

        @Volatile private var INSTANCE: KVMDatabase? = null

        fun getInstance(context: Context): KVMDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KVMDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()   // replace with proper Migrations before v2
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
