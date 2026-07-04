package com.kvm.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kvm.core.virtual.VirtualApp

/**
 * Room entity that persists a virtual app installation across process restarts.
 *
 * Primary key: auto-generated Long [id].
 * Logical uniqueness constraint: (packageName, userId) — enforced via a unique index.
 *
 * NOTE: We use a single auto-PK instead of a composite PK so that Room's
 * insert() can reliably return the generated row ID (composite PKs never
 * auto-generate on both columns simultaneously in SQLite).
 */
@Entity(
    tableName = "virtual_apps",
    indices = [
        Index(value = ["package_name", "user_id"], unique = true),
        Index(value = ["installed_at"])
    ]
)
data class VirtualAppEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")           val id:          Long   = 0,

    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "user_id")      val userId:      Int,
    @ColumnInfo(name = "apk_path")     val apkPath:     String,
    @ColumnInfo(name = "native_lib_dir") val nativeLibDir: String,
    @ColumnInfo(name = "data_dir")     val dataDir:     String,
    @ColumnInfo(name = "label")        val label:       String,
    @ColumnInfo(name = "version_name") val versionName: String,
    @ColumnInfo(name = "version_code") val versionCode: Long,
    @ColumnInfo(name = "target_sdk")   val targetSdk:   Int,
    @ColumnInfo(name = "min_sdk")      val minSdk:      Int,
    @ColumnInfo(name = "auto_start")   val autoStart:   Boolean = false,
    @ColumnInfo(name = "installed_at") val installedAt: Long = System.currentTimeMillis(),
)

// ── Mappers ───────────────────────────────────────────────────────────────────

fun VirtualApp.toEntity() = VirtualAppEntity(
    id           = id,
    packageName  = packageName,
    userId       = userId,
    apkPath      = apkPath,
    nativeLibDir = nativeLibDir,
    dataDir      = dataDir,
    label        = label,
    versionName  = versionName,
    versionCode  = versionCode,
    targetSdk    = targetSdk,
    minSdk       = minSdk,
    autoStart    = autoStart,
    installedAt  = installedAt,
)
