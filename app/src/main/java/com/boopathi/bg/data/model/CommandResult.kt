package com.boopathi.bg.data.model

data class CommandResult(
    val textResponse: String,
    val spokenResponse: String = textResponse,
    val providerName: String,
    val isSuccess: Boolean = true
)

