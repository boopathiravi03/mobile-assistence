package com.boopathi.bg.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "owner_profile")
data class OwnerProfileEntity(
    @PrimaryKey
    val id: Long = 1L,
    val name: String = "Boopathi",
    val preferredLanguage: String = "en",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

