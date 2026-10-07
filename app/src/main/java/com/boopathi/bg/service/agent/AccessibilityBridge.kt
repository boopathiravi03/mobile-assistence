package com.boopathi.bg.service.agent

/**
 * Interface decoupling the App Agent from the raw Android AccessibilityService.
 * Allows deterministic unit testing and Robolectric test execution with mock bridges.
 */
interface AccessibilityBridge {
    fun isServiceEnabled(): Boolean
    fun isConnected(): Boolean
    fun getCurrentPackage(): String
    suspend fun getVisibleElements(): List<UiElementInfo>
    suspend fun findByText(query: String, normalized: Boolean = false): List<UiElementInfo>
    suspend fun findByContentDescription(query: String, normalized: Boolean = false): List<UiElementInfo>
    suspend fun findByViewId(viewId: String): List<UiElementInfo>
    suspend fun click(element: UiElementInfo): Boolean
    suspend fun typeText(element: UiElementInfo, text: String): Boolean
    suspend fun scroll(direction: ScrollDirection): Boolean
    suspend fun pressBack(): Boolean
    suspend fun pressHome(): Boolean
}

