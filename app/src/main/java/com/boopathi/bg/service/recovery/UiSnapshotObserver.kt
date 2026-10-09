package com.boopathi.bg.service.recovery

import com.boopathi.bg.service.agent.AccessibilityBridge
import com.boopathi.bg.service.agent.UiElementInfo

/**
 * Builds clean, lightweight UiStateSnapshots from AccessibilityBridge.
 * Limits label length, deduplicates entries, normalizes whitespace,
 * and ensures no AccessibilityNodeInfo references are leaked.
 */
class UiSnapshotObserver(
    private val bridge: AccessibilityBridge,
    private val maxLabels: Int = 50,
    private val maxLabelLength: Int = 100
) {

    suspend fun captureSnapshot(): UiStateSnapshot {
        if (!bridge.isServiceEnabled() || !bridge.isConnected()) {
            return UiStateSnapshot.unavailable("Accessibility service is disconnected or disabled.")
        }

        val pkg = bridge.getCurrentPackage()
        val elements = try {
            bridge.getVisibleElements()
        } catch (e: Exception) {
            emptyList<UiElementInfo>()
        }

        if (elements.isEmpty()) {
            return UiStateSnapshot(
                packageName = pkg.ifBlank { null },
                visibleLabels = emptyList(),
                actionableLabels = emptyList(),
                editableLabels = emptyList(),
                scrollableLabels = emptyList(),
                isAvailable = true
            )
        }

        val visibleLabels = elements
            .map { it.displayLabel }
            .filter { it.isNotBlank() }
            .map { sanitizeLabel(it) }
            .distinct()
            .take(maxLabels)

        val actionableLabels = elements
            .filter { it.isActionable }
            .map { it.displayLabel }
            .filter { it.isNotBlank() }
            .map { sanitizeLabel(it) }
            .distinct()
            .take(maxLabels)

        val editableLabels = elements
            .filter { it.isEditable && it.isEnabled }
            .map { it.displayLabel }
            .filter { it.isNotBlank() }
            .map { sanitizeLabel(it) }
            .distinct()
            .take(maxLabels)

        val scrollableLabels = elements
            .filter { it.isScrollable && it.isEnabled }
            .map { it.displayLabel }
            .filter { it.isNotBlank() }
            .map { sanitizeLabel(it) }
            .distinct()
            .take(maxLabels)

        return UiStateSnapshot(
            packageName = pkg.ifBlank { null },
            visibleLabels = visibleLabels,
            actionableLabels = actionableLabels,
            editableLabels = editableLabels,
            scrollableLabels = scrollableLabels,
            capturedAtMillis = System.currentTimeMillis(),
            isAvailable = true
        )
    }

    fun verifyExpectedState(
        snapshot: UiStateSnapshot,
        expectedState: ExpectedState
    ): StateVerificationResult {
        if (!snapshot.isAvailable) {
            return StateVerificationResult(
                status = StateVerificationStatus.OBSERVATION_UNAVAILABLE,
                mismatch = UiMismatch(
                    type = UiMismatchType.OBSERVATION_UNAVAILABLE,
                    description = "Accessibility snapshot unavailable."
                ),
                details = "Cannot observe current UI state."
            )
        }

        return when (expectedState.type) {
            ExpectedStateType.APP_FOREGROUND -> {
                val expectedPkg = expectedState.expectedPackage?.lowercase() ?: ""
                val currentPkg = snapshot.packageName?.lowercase() ?: ""
                if (currentPkg.isNotEmpty() && currentPkg.contains(expectedPkg)) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "App foreground verified: $currentPkg")
                } else {
                    StateVerificationResult(
                        status = StateVerificationStatus.NOT_VERIFIED,
                        mismatch = UiMismatch(
                            type = UiMismatchType.WRONG_FOREGROUND_APP,
                            description = "Expected package '$expectedPkg' but found '$currentPkg'",
                            expectedPackage = expectedState.expectedPackage,
                            actualPackage = snapshot.packageName,
                            observedSnapshot = snapshot
                        ),
                        details = "Expected app '$expectedPkg' is not in foreground. Found: '$currentPkg'"
                    )
                }
            }

            ExpectedStateType.TARGET_VISIBLE -> {
                val query = expectedState.targetQuery ?: ""
                if (snapshot.hasVisibleLabel(query)) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "Target visible: $query")
                } else {
                    StateVerificationResult(
                        status = StateVerificationStatus.NOT_VERIFIED,
                        mismatch = UiMismatch(
                            type = UiMismatchType.TARGET_NOT_FOUND,
                            description = "Target '$query' not visible on screen",
                            targetQuery = query,
                            observedSnapshot = snapshot
                        ),
                        details = "Target '$query' was not found in visible UI labels."
                    )
                }
            }

            ExpectedStateType.TARGET_ACTIONABLE -> {
                val query = expectedState.targetQuery ?: ""
                if (snapshot.hasActionableLabel(query)) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "Target actionable: $query")
                } else {
                    StateVerificationResult(
                        status = StateVerificationStatus.NOT_VERIFIED,
                        mismatch = UiMismatch(
                            type = UiMismatchType.EXPECTED_STATE_NOT_REACHED,
                            description = "Target '$query' not actionable",
                            targetQuery = query,
                            observedSnapshot = snapshot
                        ),
                        details = "Target '$query' is not an interactive element on the current screen."
                    )
                }
            }

            ExpectedStateType.EDITOR_VISIBLE -> {
                val query = expectedState.targetQuery
                val isPresent = if (query != null) {
                    snapshot.hasEditableLabel(query)
                } else {
                    snapshot.editableLabels.isNotEmpty()
                }

                if (isPresent) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "Editor field verified")
                } else {
                    StateVerificationResult(
                        status = StateVerificationStatus.NOT_VERIFIED,
                        mismatch = UiMismatch(
                            type = UiMismatchType.TARGET_NOT_FOUND,
                            description = "No editable input field found",
                            targetQuery = query,
                            observedSnapshot = snapshot
                        ),
                        details = "No editable field is available on the current screen."
                    )
                }
            }

            ExpectedStateType.EDITOR_CONTAINS_EXPECTED_TEXT -> {
                val expected = expectedState.expectedText ?: ""
                val found = snapshot.visibleLabels.any { it.contains(expected, ignoreCase = true) }
                if (found) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "Expected text found in UI: $expected")
                } else {
                    StateVerificationResult(
                        status = StateVerificationStatus.NOT_VERIFIED,
                        mismatch = UiMismatch(
                            type = UiMismatchType.EXPECTED_STATE_NOT_REACHED,
                            description = "Expected text '$expected' not present in UI",
                            targetQuery = expected,
                            observedSnapshot = snapshot
                        ),
                        details = "UI does not contain expected text '$expected'."
                    )
                }
            }

            ExpectedStateType.SCREEN_CHANGED -> {
                val baseline = expectedState.baselineSnapshot
                if (baseline == null) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "No baseline snapshot to compare.")
                } else {
                    val changed = snapshot.visibleLabels != baseline.visibleLabels ||
                            snapshot.packageName != baseline.packageName
                    if (changed) {
                        StateVerificationResult(StateVerificationStatus.VERIFIED, details = "Screen content change verified.")
                    } else {
                        StateVerificationResult(
                            status = StateVerificationStatus.NOT_VERIFIED,
                            mismatch = UiMismatch(
                                type = UiMismatchType.EXPECTED_STATE_NOT_REACHED,
                                description = "Screen did not change after action",
                                observedSnapshot = snapshot
                            ),
                            details = "Screen labels remained identical after action execution."
                        )
                    }
                }
            }

            ExpectedStateType.SCROLL_STATE_CHANGED -> {
                val baseline = expectedState.baselineSnapshot
                if (baseline == null) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "No baseline snapshot provided.")
                } else {
                    val changed = snapshot.visibleLabels != baseline.visibleLabels
                    if (changed) {
                        StateVerificationResult(StateVerificationStatus.VERIFIED, details = "Scroll change verified.")
                    } else {
                        StateVerificationResult(
                            status = StateVerificationStatus.NOT_VERIFIED,
                            mismatch = UiMismatch(
                                type = UiMismatchType.EXPECTED_STATE_NOT_REACHED,
                                description = "Scroll action did not reveal new UI content",
                                observedSnapshot = snapshot
                            ),
                            details = "Visible labels remained unchanged after scroll."
                        )
                    }
                }
            }

            ExpectedStateType.HOME_VISIBLE -> {
                val isLauncher = snapshot.packageName?.let {
                    it.contains("launcher") || it.contains("home") || it.contains("nexuslauncher")
                } ?: false
                if (isLauncher) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "Home screen verified.")
                } else {
                    StateVerificationResult(
                        status = StateVerificationStatus.NOT_VERIFIED,
                        mismatch = UiMismatch(
                            type = UiMismatchType.EXPECTED_STATE_NOT_REACHED,
                            description = "Device did not return to home screen",
                            actualPackage = snapshot.packageName,
                            observedSnapshot = snapshot
                        ),
                        details = "Active package is '${snapshot.packageName}', not the home launcher."
                    )
                }
            }

            ExpectedStateType.SETTINGS_SECTION_VISIBLE -> {
                val query = expectedState.targetQuery ?: ""
                val isSettings = snapshot.packageName?.contains("settings") == true
                val hasSection = if (query.isNotBlank()) snapshot.hasVisibleLabel(query) else isSettings
                if (isSettings && hasSection) {
                    StateVerificationResult(StateVerificationStatus.VERIFIED, details = "Settings section verified.")
                } else {
                    StateVerificationResult(
                        status = StateVerificationStatus.NOT_VERIFIED,
                        mismatch = UiMismatch(
                            type = UiMismatchType.EXPECTED_STATE_NOT_REACHED,
                            description = "Settings section '$query' not visible",
                            targetQuery = query,
                            observedSnapshot = snapshot
                        ),
                        details = "Settings section '$query' is not currently displayed."
                    )
                }
            }
        }
    }

    private fun sanitizeLabel(label: String): String {
        val singleLine = label.replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length > maxLabelLength) {
            singleLine.substring(0, maxLabelLength)
        } else {
            singleLine
        }
    }
}

