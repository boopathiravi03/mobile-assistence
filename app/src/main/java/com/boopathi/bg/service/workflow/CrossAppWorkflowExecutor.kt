package com.boopathi.bg.service.workflow

import android.util.Log
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.MultiStepAgent
import com.boopathi.bg.service.messaging.MessageDraft
import com.boopathi.bg.service.messaging.MessagingAppAdapter
import com.boopathi.bg.service.messaging.WhatsAppMessagingAdapter
import com.boopathi.bg.service.recovery.RecoveryPolicy
import com.boopathi.bg.service.recovery.UiSnapshotObserver
import com.boopathi.bg.service.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Executes a CrossAppWorkflow step by step, enforcing:
 * - App handoff verification (foreground app checking before interaction)
 * - Safe text transfer (no auto-send, exact text preservation, editable check)
 * - Context invalidation if unexpected app becomes foreground
 * - Integration with existing Phase 5C/5D messaging confirmation boundaries
 * - Bounded recovery via Phase 6B
 * - Transient data clearing on finish/cancel
 */
import com.boopathi.bg.data.local.entity.WorkflowHistoryEntity
import com.boopathi.bg.data.repository.BGRepository

class CrossAppWorkflowExecutor(
    private val appAgent: AppAgent,
    private val multiStepAgent: MultiStepAgent? = null,
    private val toolRegistry: ToolRegistry? = null,
    private val handoffVerifier: CrossAppHandoffVerifier = CrossAppHandoffVerifier(appAgent.bridge),
    private val messagingAdapter: MessagingAppAdapter = WhatsAppMessagingAdapter(),
    private val repository: BGRepository? = null,
    val resumePolicy: WorkflowResumePolicy = WorkflowResumePolicy()
) {

    companion object {
        private const val TAG = "CrossAppWorkflowExec"
    }

    private val _currentWorkflow = MutableStateFlow<CrossAppWorkflow?>(null)
    val currentWorkflow: StateFlow<CrossAppWorkflow?> = _currentWorkflow.asStateFlow()

    private val _workflowStatus = MutableStateFlow(CrossAppWorkflowStatus.READY)
    val workflowStatus: StateFlow<CrossAppWorkflowStatus> = _workflowStatus.asStateFlow()

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private var activeContext: WorkflowContext? = null

    suspend fun saveHistoryRecord(
        wf: CrossAppWorkflow,
        checkpoint: WorkflowCheckpoint? = null,
        isEligibleForResume: Boolean = false,
        consequentialAttempted: Boolean = false,
        consequentialUncertain: Boolean = false
    ) {
        if (repository == null) return
        try {
            val entity = WorkflowHistoryEntity(
                workflowId = wf.workflowId,
                goalDescription = wf.goalDescription,
                workflowType = wf.type.name,
                status = wf.status.name,
                currentStepIndex = wf.currentStepIndex,
                totalSteps = wf.totalSteps,
                createdAt = wf.createdAtMillis,
                updatedAt = System.currentTimeMillis(),
                lastSafeCheckpointJson = checkpoint?.toJson(),
                failureReason = wf.failureReason,
                isEligibleForResume = isEligibleForResume,
                consequentialActionAttempted = consequentialAttempted,
                consequentialOutcomeUncertain = consequentialUncertain,
                stepsJson = WorkflowStepSerializer.serializeSteps(wf.steps)
            )
            repository.saveWorkflowHistory(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist workflow history: ${e.message}")
        }
    }

    suspend fun cancelWorkflow(reason: String = "User requested cancellation"): Boolean {
        val wf = _currentWorkflow.value
        if (wf != null && !wf.isComplete) {
            Log.i(TAG, "Workflow ${wf.workflowId} cancelled: $reason")
            val cancelledWf = wf.copy(
                status = CrossAppWorkflowStatus.CANCELLED,
                failureReason = reason
            )
            _currentWorkflow.value = cancelledWf
            _workflowStatus.value = CrossAppWorkflowStatus.CANCELLED
            _progressMessage.value = "Stopped safely: $reason"
            activeContext?.isCancelled = true
            activeContext?.clearTransientData()
            saveHistoryRecord(cancelledWf, isEligibleForResume = false)
            return true
        }
        return false
    }

    suspend fun executeWorkflow(
        workflow: CrossAppWorkflow,
        owner: OwnerProfile
    ): CommandResult = withContext(Dispatchers.Default) {
        if (workflow.status == CrossAppWorkflowStatus.CANCELLED) {
            _currentWorkflow.value = workflow
            _workflowStatus.value = CrossAppWorkflowStatus.CANCELLED
            _progressMessage.value = "Workflow cancelled."
            return@withContext CommandResult(
                textResponse = "Workflow cancelled.",
                providerName = "CrossAppWorkflow",
                isSuccess = false
            )
        }

        val startTime = System.currentTimeMillis()
        var currentWf = workflow.copy(status = CrossAppWorkflowStatus.EXECUTING)
        _currentWorkflow.value = currentWf
        _workflowStatus.value = CrossAppWorkflowStatus.EXECUTING

        val context = WorkflowContext(
            workflowId = currentWf.workflowId,
            authorizedGoal = currentWf.goalDescription
        )
        activeContext = context

        _progressMessage.value = "Starting workflow: ${currentWf.goalDescription}"
        Log.i(TAG, "Executing CrossAppWorkflow '${currentWf.goalDescription}' (${currentWf.totalSteps} steps)")

        for ((index, step) in currentWf.steps.withIndex()) {
            if (context.isCancelled || currentWf.status == CrossAppWorkflowStatus.CANCELLED) {
                context.clearTransientData()
                _progressMessage.value = "Workflow cancelled."
                return@withContext CommandResult(
                    textResponse = "Workflow cancelled.",
                    providerName = "CrossAppWorkflow",
                    isSuccess = false
                )
            }

            // Check overall duration bound
            if ((System.currentTimeMillis() - startTime) > WorkflowLimits.MAX_WORKFLOW_DURATION_MS) {
                val timeoutReason = "Workflow exceeded maximum duration limit (${WorkflowLimits.MAX_WORKFLOW_DURATION_MS / 1000}s)."
                currentWf = currentWf.copy(
                    status = CrossAppWorkflowStatus.FAILED,
                    failureReason = timeoutReason
                )
                _currentWorkflow.value = currentWf
                _workflowStatus.value = CrossAppWorkflowStatus.FAILED
                _progressMessage.value = "Failed: $timeoutReason"
                context.clearTransientData()
                return@withContext CommandResult(
                    textResponse = timeoutReason,
                    providerName = "CrossAppWorkflow",
                    isSuccess = false
                )
            }

            currentWf = currentWf.copy(currentStepIndex = index)
            _currentWorkflow.value = currentWf
            context.currentStepIndex = index

            _progressMessage.value = "Step ${index + 1}/${currentWf.totalSteps}: ${step.expectedOutcome}"
            Log.d(TAG, "Running step ${index + 1}: ${step.actionReference} on ${step.targetApp}")

            val expectedPkg = step.expectedPackage ?: ""

            // 1. App Handoff / Foreground Verification
            if (step.actionReference == "OPEN_APP") {
                _progressMessage.value = "Opening ${step.targetApp}..."
                val openResult = appAgent.executeAction(AppAction.OpenApp(step.targetApp))
                if (!openResult.isSuccess) {
                    val failMsg = "Failed to open ${step.targetApp}: ${openResult.reason ?: "App unavailable"}"
                    return@withContext failWorkflow(currentWf, context, failMsg)
                }

                // Verify handoff foreground package
                _progressMessage.value = "Verifying ${step.targetApp} in foreground..."
                val verifyResult = handoffVerifier.verifyForegroundApp(expectedPkg, step.targetApp)
                if (verifyResult !is HandoffVerificationResult.Success) {
                    val failMsg = "Handoff verification failed: Expected $expectedPkg in foreground."
                    return@withContext failWorkflow(currentWf, context, failMsg)
                }

                if (step.appRole == WorkflowAppRole.SOURCE) {
                    context.verifiedSourcePackage = expectedPkg
                } else if (step.appRole == WorkflowAppRole.DESTINATION) {
                    context.verifiedDestinationPackage = expectedPkg
                }

            } else if (step.actionReference == "INSPECT_SCREEN") {
                // Ensure foreground package has not changed unexpectedly
                val fgCheck = handoffVerifier.verifyForegroundApp(expectedPkg, step.targetApp)
                if (fgCheck !is HandoffVerificationResult.Success) {
                    val failMsg = "Unexpected foreground app during inspection. Expected $expectedPkg."
                    return@withContext failWorkflow(currentWf, context, failMsg)
                }

                _progressMessage.value = "Inspecting ${step.targetApp}..."
                val inspectResult = appAgent.executeAction(AppAction.InspectUi)
                if (!inspectResult.isSuccess) {
                    val failMsg = "Failed to inspect ${step.targetApp}: ${inspectResult.reason}"
                    return@withContext failWorkflow(currentWf, context, failMsg)
                }

            } else if (step.actionReference == "TRANSFER_TEXT") {
                val textToTransfer = step.parameters["text"] ?: ""
                if (textToTransfer.isBlank()) {
                    return@withContext failWorkflow(currentWf, context, "No text provided to transfer.")
                }

                // Verify foreground package first
                val fgCheck = handoffVerifier.verifyForegroundApp(expectedPkg, step.targetApp)
                if (fgCheck !is HandoffVerificationResult.Success) {
                    return@withContext failWorkflow(currentWf, context, "Destination app $expectedPkg is not active.")
                }

                // Save non-sensitive text to context
                try {
                    context.setUserProvidedText(textToTransfer)
                } catch (e: Exception) {
                    return@withContext failWorkflow(currentWf, context, e.message ?: "Invalid text content.")
                }

                _progressMessage.value = "Preparing text transfer in ${step.targetApp}..."

                // Find editable field safely
                val snapshot = appAgent.bridge.getVisibleElements()
                val editableElement = snapshot.firstOrNull { it.isEditable && it.isEnabled }
                if (editableElement == null) {
                    return@withContext failWorkflow(
                        currentWf,
                        context,
                        "No editable input field found in ${step.targetApp}."
                    )
                }

                val typeResult = appAgent.executeAction(
                    AppAction.TypeText(
                        targetQuery = editableElement.displayLabel,
                        textToType = textToTransfer
                    )
                )

                if (!typeResult.isSuccess) {
                    return@withContext failWorkflow(
                        currentWf,
                        context,
                        "Failed to enter text into ${step.targetApp}: ${typeResult.reason}"
                    )
                }

                // Post-transfer verification: verify text exists without auto-submitting
                _progressMessage.value = "Verifying draft text..."
                val textCheck = handoffVerifier.verifyInsertedText(textToTransfer, expectedPkg)
                if (textCheck !is HandoffVerificationResult.Success) {
                    return@withContext failWorkflow(
                        currentWf,
                        context,
                        "Could not verify text insertion in ${step.targetApp}."
                    )
                }

            } else if (step.actionReference == "PREPARE_MESSAGE") {
                val recipient = step.parameters["recipient"] ?: ""
                val messageText = step.parameters["message"] ?: ""

                // Ensure foreground app is messaging app
                val fgCheck = handoffVerifier.verifyForegroundApp(expectedPkg, step.targetApp)
                if (fgCheck !is HandoffVerificationResult.Success) {
                    return@withContext failWorkflow(currentWf, context, "Messaging app $expectedPkg is not active.")
                }

                // Verify recipient in conversation
                val recipientVerified = messagingAdapter.verifyRecipient(appAgent.bridge, recipient)
                if (!recipientVerified) {
                    return@withContext failWorkflow(
                        currentWf,
                        context,
                        "Recipient '$recipient' could not be verified in foreground conversation."
                    )
                }

                // Prepare draft in conversation by typing into input field
                val snapshot = appAgent.bridge.getVisibleElements()
                val inputElement = snapshot.firstOrNull { it.isEditable && it.isEnabled }
                val draftPrepared = if (inputElement != null) {
                    appAgent.bridge.typeText(inputElement, messageText)
                } else false

                if (!draftPrepared) {
                    return@withContext failWorkflow(
                        currentWf,
                        context,
                        "Failed to prepare draft message in ${step.targetApp}."
                    )
                }

                // Pause at explicit confirmation boundary
                currentWf = currentWf.copy(
                    status = CrossAppWorkflowStatus.WAITING_FOR_CONFIRMATION
                )
                _currentWorkflow.value = currentWf
                _workflowStatus.value = CrossAppWorkflowStatus.WAITING_FOR_CONFIRMATION
                _progressMessage.value = "Ready to send message to $recipient. Waiting for confirmation."

                saveHistoryRecord(
                    currentWf,
                    checkpoint = WorkflowCheckpoint(
                        workflowId = currentWf.workflowId,
                        completedStepIndex = index,
                        expectedPackage = expectedPkg,
                        expectedOutcome = "Prepared draft for $recipient"
                    ),
                    isEligibleForResume = false,
                    consequentialAttempted = false
                )

                return@withContext CommandResult(
                    textResponse = "Ready to send '$messageText' to $recipient on ${step.targetApp}. Please confirm.",
                    providerName = "CrossAppWorkflow",
                    isSuccess = true
                )
            }

            // Save safe checkpoint after successfully verifying a non-consequential step
            val stepCheckpoint = WorkflowCheckpoint(
                workflowId = currentWf.workflowId,
                completedStepIndex = index,
                expectedPackage = expectedPkg,
                expectedOutcome = step.expectedOutcome
            )
            saveHistoryRecord(
                currentWf,
                checkpoint = stepCheckpoint,
                isEligibleForResume = true
            )
        }

        // All steps completed successfully
        currentWf = currentWf.copy(status = CrossAppWorkflowStatus.SUCCESS)
        _currentWorkflow.value = currentWf
        _workflowStatus.value = CrossAppWorkflowStatus.SUCCESS
        _progressMessage.value = "Workflow completed successfully."
        context.clearTransientData()
        saveHistoryRecord(currentWf, isEligibleForResume = false)

        val summary = "Completed cross-app workflow: ${currentWf.goalDescription}."
        CommandResult(
            textResponse = summary,
            providerName = "CrossAppWorkflow",
            isSuccess = true
        )
    }

    private suspend fun failWorkflow(
        wf: CrossAppWorkflow,
        context: WorkflowContext,
        reason: String,
        consequentialAttempted: Boolean = false,
        consequentialUncertain: Boolean = false
    ): CommandResult {
        Log.w(TAG, "Workflow failed: $reason")
        val finalStatus = if (consequentialUncertain) CrossAppWorkflowStatus.UNVERIFIED else CrossAppWorkflowStatus.FAILED
        val failed = wf.copy(
            status = finalStatus,
            failureReason = reason
        )
        _currentWorkflow.value = failed
        _workflowStatus.value = finalStatus
        _progressMessage.value = "Stopped safely: $reason"
        context.clearTransientData()

        saveHistoryRecord(
            failed,
            isEligibleForResume = false,
            consequentialAttempted = consequentialAttempted,
            consequentialUncertain = consequentialUncertain
        )

        return CommandResult(
            textResponse = "Stopped safely: $reason",
            providerName = "CrossAppWorkflow",
            isSuccess = false
        )
    }

    /**
     * Resumes an interrupted workflow after fresh validation via WorkflowResumePolicy.
     */
    suspend fun resumeWorkflow(
        workflowId: String,
        owner: OwnerProfile,
        currentForegroundPackage: String? = null
    ): CommandResult {
        if (repository == null) {
            return CommandResult(
                textResponse = "Task history database unavailable.",
                providerName = "CrossAppWorkflow",
                isSuccess = false
            )
        }

        val historyEntity = repository.getWorkflowHistoryById(workflowId)
        if (historyEntity == null) {
            return CommandResult(
                textResponse = "Workflow record not found.",
                providerName = "CrossAppWorkflow",
                isSuccess = false
            )
        }

        // Validate via resume policy
        val validation = resumePolicy.validateResumeEligibility(
            historyEntity = historyEntity,
            currentForegroundPackage = currentForegroundPackage
        )

        if (!validation.isEligible || validation.revalidatedWorkflow == null) {
            val rejection = validation.rejectionReason ?: "Workflow is not eligible for resumption."
            return CommandResult(
                textResponse = "Cannot resume: $rejection",
                providerName = "CrossAppWorkflow",
                isSuccess = false
            )
        }

        Log.i(TAG, "Resuming workflow '${historyEntity.goalDescription}' from step index ${historyEntity.currentStepIndex}")
        return executeWorkflow(validation.revalidatedWorkflow, owner)
    }
}

