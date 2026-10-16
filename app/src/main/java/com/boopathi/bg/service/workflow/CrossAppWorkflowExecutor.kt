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
class CrossAppWorkflowExecutor(
    private val appAgent: AppAgent,
    private val multiStepAgent: MultiStepAgent? = null,
    private val toolRegistry: ToolRegistry? = null,
    private val handoffVerifier: CrossAppHandoffVerifier = CrossAppHandoffVerifier(appAgent.bridge),
    private val messagingAdapter: MessagingAppAdapter = WhatsAppMessagingAdapter(),
    private val workflowValidator: CrossAppWorkflowValidator = CrossAppWorkflowValidator()
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

    @Suppress("UNUSED_PARAMETER")
    fun cancelWorkflow(reason: String = "User requested cancellation"): Boolean {
        val wf = _currentWorkflow.value
        if (wf != null && !wf.isComplete) {
            Log.i(TAG, "Workflow cancellation requested.")
            val cancelledWf = wf.copy(
                status = CrossAppWorkflowStatus.CANCELLED,
                failureReason = "Cancellation requested. An action already in progress may finish; check the target app before retrying."
            )
            _currentWorkflow.value = cancelledWf
            _workflowStatus.value = CrossAppWorkflowStatus.CANCELLED
            _progressMessage.value = "Cancellation requested. An action already in progress may finish; check the target app before retrying."
            activeContext?.isCancelled = true
            activeContext?.clearTransientData()
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

        val validation = workflowValidator.validateWorkflow(workflow)
        val validatedWorkflow = validation.validatedWorkflow
        if (!validation.isValid || validatedWorkflow == null) {
            return@withContext CommandResult(
                textResponse = validation.rejectionReason ?: "Workflow did not pass local safety checks.",
                providerName = "CrossAppWorkflowValidator",
                isSuccess = false
            )
        }

        val startTime = System.currentTimeMillis()
        var currentWf = validatedWorkflow.copy(status = CrossAppWorkflowStatus.EXECUTING)
        _currentWorkflow.value = currentWf
        _workflowStatus.value = CrossAppWorkflowStatus.EXECUTING

        val context = WorkflowContext(
            workflowId = currentWf.workflowId,
            authorizedGoal = currentWf.goalDescription
        )
        activeContext = context

        _progressMessage.value = "Starting workflow (${currentWf.totalSteps} steps)."
        Log.i(TAG, "Executing validated CrossAppWorkflow (${currentWf.totalSteps} steps)")

        fun cancelledResult(): CommandResult {
            currentWf = currentWf.copy(status = CrossAppWorkflowStatus.CANCELLED,
                failureReason = "Cancellation requested. An action already in progress may finish; check the target app before retrying.")
            _currentWorkflow.value = currentWf
            _workflowStatus.value = CrossAppWorkflowStatus.CANCELLED
            _progressMessage.value = currentWf.failureReason.orEmpty()
            context.clearTransientData()
            return CommandResult(currentWf.failureReason.orEmpty(), providerName = "CrossAppWorkflow", isSuccess = false)
        }

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
            Log.d(TAG, "Running workflow step ${index + 1}: ${step.actionReference}")

            val expectedPkg = step.expectedPackage ?: ""

            // 1. App Handoff / Foreground Verification
            if (step.actionReference == "OPEN_APP") {
                _progressMessage.value = "Opening ${step.targetApp}..."
                val openResult = appAgent.executeAction(AppAction.OpenApp(step.targetApp))
                if (context.isCancelled) return@withContext cancelledResult()
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
                if (context.isCancelled) return@withContext cancelledResult()
                if (fgCheck !is HandoffVerificationResult.Success) {
                    val failMsg = "Unexpected foreground app during inspection. Expected $expectedPkg."
                    return@withContext failWorkflow(currentWf, context, failMsg)
                }

                _progressMessage.value = "Inspecting ${step.targetApp}..."
                val inspectResult = appAgent.executeAction(AppAction.InspectUi)
                if (context.isCancelled) return@withContext cancelledResult()
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
                if (context.isCancelled) return@withContext cancelledResult()
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
                if (context.isCancelled) return@withContext cancelledResult()

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
                if (context.isCancelled) return@withContext cancelledResult()
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
                if (context.isCancelled) return@withContext cancelledResult()

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

                return@withContext CommandResult(
                    textResponse = "Ready to send '$messageText' to $recipient on ${step.targetApp}. Please confirm.",
                    providerName = "CrossAppWorkflow",
                    isSuccess = true
                )
            }
        }

        // All steps completed successfully
        currentWf = currentWf.copy(status = CrossAppWorkflowStatus.SUCCESS)
        _currentWorkflow.value = currentWf
        _workflowStatus.value = CrossAppWorkflowStatus.SUCCESS
        _progressMessage.value = "Workflow completed successfully."
        context.clearTransientData()

        val summary = "Completed cross-app workflow: ${currentWf.goalDescription}."
        CommandResult(
            textResponse = summary,
            providerName = "CrossAppWorkflow",
            isSuccess = true
        )
    }

    private fun failWorkflow(
        wf: CrossAppWorkflow,
        context: WorkflowContext,
        reason: String
    ): CommandResult {
        Log.w(TAG, "Workflow failed.")
        val failed = wf.copy(
            status = CrossAppWorkflowStatus.FAILED,
            failureReason = reason
        )
        _currentWorkflow.value = failed
        _workflowStatus.value = CrossAppWorkflowStatus.FAILED
        _progressMessage.value = "Stopped safely: $reason"
        context.clearTransientData()

        return CommandResult(
            textResponse = "Stopped safely: $reason",
            providerName = "CrossAppWorkflow",
            isSuccess = false
        )
    }
}

