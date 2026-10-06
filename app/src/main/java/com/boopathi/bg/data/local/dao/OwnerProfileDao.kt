package com.boopathi.bg.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boopathi.bg.data.local.entity.OwnerProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OwnerProfileDao {

    @Query("SELECT * FROM owner_profile WHERE id = 1 LIMIT 1")
    suspend fun getOwnerProfile(): OwnerProfileEntity?

    @Query("SELECT * FROM owner_profile WHERE id = 1 LIMIT 1")
    fun getOwnerProfileFlow(): Flow<OwnerProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(profile: OwnerProfileEntity): Long
}

