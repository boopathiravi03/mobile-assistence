package com.boopathi.bg.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.boopathi.bg.service.agent.AccessibilityBridge
import com.boopathi.bg.service.agent.ScrollDirection
import com.boopathi.bg.service.agent.UiElementInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Official Android Accessibility Service for BG.
 * Observes foreground package and hierarchy changes, extracts safe UiElementInfo
 * models, and performs controlled user-directed taps, text entry, and scrolling.
 */
class BGAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BGAccessibilityService"
        var instance: BGAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        fun isServiceEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${BGAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabledServicesSetting.split(':').any {
                it.equals(expectedServiceName, ignoreCase = true) ||
                        it.contains(BGAccessibilityService::class.java.simpleName, ignoreCase = true)
            }
        }
    }

    private var currentPackageName: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "BG Accessibility Service connected successfully.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank() && !pkg.contains("com.android.systemui")) {
            currentPackageName = pkg
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "BG Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "BG Accessibility Service destroyed.")
    }

    fun getCurrentPackage(): String {
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        if (!rootPkg.isNullOrBlank()) {
            currentPackageName = rootPkg
        }
        return currentPackageName
    }

    fun getVisibleElements(): List<UiElementInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<UiElementInfo>()
        try {
            traverseNode(root, 0, elements)
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree: ${e.message}", e)
        }
        return elements
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        outList: MutableList<UiElementInfo>,
        maxDepth: Int = 25,
        maxElements: Int = 250
    ) {
        if (depth > maxDepth || outList.size >= maxElements) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isEnabled = node.isEnabled
        val packageName = node.packageName?.toString() ?: currentPackageName

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Only include nodes that have meaningful content or are actionable
        if (text.isNotBlank() || contentDesc.isNotBlank() || isClickable || isEditable || isScrollable) {
            outList.add(
                UiElementInfo(
                    text = text,
                    contentDescription = contentDesc,
                    className = className,
                    viewId = viewId,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isScrollable = isScrollable,
                    isEnabled = isEnabled,
                    bounds = bounds,
                    depth = depth,
                    packageName = packageName
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, depth + 1, outList, maxDepth, maxElements)
        }
    }

    fun click(element: UiElementInfo): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findMatchingNode(root, element) ?: return false

        // 1. Try clicking the node directly if clickable
        if (node.isClickable && node.isEnabled) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // 2. If node is not directly clickable, check clickable parent (e.g. TextView inside Button or LinearLayout)
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable && parent.isEnabled) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
            depth++
        }

        return false
    }

    fun typeText(element: UiElementInfo, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findMatchingNode(root, element) ?: return false

        if (!node.isEditable || !node.isEnabled) {
            Log.w(TAG, "Target node is not editable or disabled.")
            return false
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun scroll(direction: ScrollDirection): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(root) ?: return false

        val action = when (direction) {
            ScrollDirection.DOWN, ScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.UP, ScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return scrollableNode.performAction(action)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isEnabled) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findMatchingNode(root: AccessibilityNodeInfo, target: UiElementInfo): AccessibilityNodeInfo? {
        // Compare attributes
        val nodeText = root.text?.toString() ?: ""
        val nodeDesc = root.contentDescription?.toString() ?: ""
        val nodeViewId = root.viewIdResourceName ?: ""

        val textMatches = target.text.isNotBlank() && (nodeText.equals(target.text, ignoreCase = true) || nodeText.contains(target.text, ignoreCase = true))
        val descMatches = target.contentDescription.isNotBlank() && (nodeDesc.equals(target.contentDescription, ignoreCase = true) || nodeDesc.contains(target.contentDescription, ignoreCase = true))
        val idMatches = target.viewId.isNotBlank() && nodeViewId.equals(target.viewId, ignoreCase = true)

        if (textMatches || descMatches || idMatches) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val match = findMatchingNode(child, target)
            if (match != null) return match
        }

        return null
    }

    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }
}

/**
 * Standard Android runtime implementation of AccessibilityBridge.
 */
class AndroidAccessibilityBridge(private val context: Context) : AccessibilityBridge {

    override fun isServiceEnabled(): Boolean = BGAccessibilityService.isServiceEnabled(context)

    override fun isConnected(): Boolean = BGAccessibilityService.isConnected()

    override fun getCurrentPackage(): String {
        return BGAccessibilityService.instance?.getCurrentPackage() ?: ""
    }

    override suspend fun getVisibleElements(): List<UiElementInfo> = withContext(Dispatchers.Main) {
        BGAccessibilityService.instance?.getVisibleElements() ?: emptyList()
    }

    override suspend fun findByText(query: String, normalized: Boolean): List<UiElementInfo> {
        val all = getVisibleElements()
        return all.filter { it.matchesQuery(query, normalized) }
    }

    override suspend fun findByContentDescription(query: String, normalized: Boolean): List<UiElementInfo> {
        val all = getVisibleElements()
        return all.filter { it.contentDescription.contains(query, ignoreCase = true) }
    }

    override suspend fun findByViewId(viewId: String): List<UiElementInfo> {
        val all = getVisibleElements()
        return all.filter { it.viewId.contains(viewId, ignoreCase = true) }
    }

    override suspend fun click(element: UiElementInfo): Boolean = withContext(Dispatchers.Main) {
        BGAccessibilityService.instance?.click(element) ?: false
    }

    override suspend fun typeText(element: UiElementInfo, text: String): Boolean = withContext(Dispatchers.Main) {
        BGAccessibilityService.instance?.typeText(element, text) ?: false
    }

    override suspend fun scroll(direction: ScrollDirection): Boolean = withContext(Dispatchers.Main) {
        BGAccessibilityService.instance?.scroll(direction) ?: false
    }

    override suspend fun pressBack(): Boolean = withContext(Dispatchers.Main) {
        BGAccessibilityService.instance?.pressBack() ?: false
    }

    override suspend fun pressHome(): Boolean = withContext(Dispatchers.Main) {
        BGAccessibilityService.instance?.pressHome() ?: false
    }
}

