package com.boopathi.bg.data.model

data class OwnerProfile(
    val id: Long = 1L,
    val name: String = "Boopathi",
    val preferredLanguage: String = "en",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

