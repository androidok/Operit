package com.ai.assistance.operit.services.core

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.core.chat.logMessageTiming
import com.ai.assistance.operit.core.chat.messageTimingNow
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.agent.PhoneAgentJobRegistry
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.model.InputProcessingState as EnhancedInputProcessingState
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.share
import com.ai.assistance.operit.util.WaifuMessageProcessor
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.WaifuPreferences
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.floating.ui.fullscreen.XmlTextProcessor
import com.ai.assistance.operit.ui.features.chat.webview.workspace.WorkspaceBackupManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ai.assistance.operit.core.tools.ToolProgressBus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 委托类，负责处理消息处理相关功能 */
class MessageProcessingDelegate(
        private val context: Context,
        private val coroutineScope: CoroutineScope,
        private val getEnhancedAiService: () -> EnhancedAIService?,
        private val getChatHistory: suspend (String) -> List<ChatMessage>,
        private val addMessageToChat: suspend (String, ChatMessage) -> Unit,
        private val saveCurrentChat: () -> Unit,
        private val showErrorMessage: (String) -> Unit,
        private val updateChatTitle: (chatId: String, title: String) -> Unit,
        private val onTurnComplete: (chatId: String?, service: EnhancedAIService) -> Unit,
        private val onTokenLimitExceeded: suspend (chatId: String?) -> Unit, // 新增：Token超限回调
        // 添加自动朗读相关的回调
        private val getIsAutoReadEnabled: () -> Boolean,
        private var speakMessageHandler: (String, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "MessageProcessingDelegate"
        private const val STREAM_SCROLL_THROTTLE_MS = 200L
    }

    // 角色卡管理器
    private val characterCardManager = CharacterCardManager.getInstance(context)
    
    // 模型配置管理器
    private val modelConfigManager = ModelConfigManager(context)
    
    // 功能配置管理器，用于获取正确的模型配置ID
    private val functionalConfigManager = FunctionalConfigManager(context)

    private val _userMessage = MutableStateFlow(TextFieldValue(""))
    val userMessage: StateFlow<TextFieldValue> = _userMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _activeStreamingChatIds = MutableStateFlow<Set<String>>(emptySet())
    val activeStreamingChatIds: StateFlow<Set<String>> = _activeStreamingChatIds.asStateFlow()

    private val _inputProcessingStateByChatId =
        MutableStateFlow<Map<String, EnhancedInputProcessingState>>(emptyMap())
    val inputProcessingStateByChatId: StateFlow<Map<String, EnhancedInputProcessingState>> =
        _inputProcessingStateByChatId.asStateFlow()

    private val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottomEvent = _scrollToBottomEvent.asSharedFlow()

    private val _nonFatalErrorEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val nonFatalErrorEvent = _nonFatalErrorEvent.asSharedFlow()

    private val _turnCompleteCounterByChatId = MutableStateFlow<Map<String, Long>>(emptyMap())
    val turnCompleteCounterByChatId: StateFlow<Map<String, Long>> =
        _turnCompleteCounterByChatId.asStateFlow()
    private val _currentTurnToolInvocationCountByChatId =
        MutableStateFlow<Map<String, Int>>(emptyMap())
    val currentTurnToolInvocationCountByChatId: StateFlow<Map<String, Int>> =
        _currentTurnToolInvocationCountByChatId.asStateFlow()

    // 当前活跃的AI响应流
    private data class ChatRuntime(
        var responseStream: SharedStream<String>? = null,
        var streamCollectionJob: Job? = null,
        var stateCollectionJob: Job? = null,
        val isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    )

    private val chatRuntimes = ConcurrentHashMap<String, ChatRuntime>()
    private val lastScrollEmitMsByChatKey = ConcurrentHashMap<String, AtomicLong>()
    private val suppressIdleCompletedStateByChatId = ConcurrentHashMap<String, Boolean>()
    private val pendingAsyncSummaryUiByChatId = ConcurrentHashMap<String, Boolean>()

    private fun chatKey(chatId: String?): String = chatId ?: "__DEFAULT_CHAT__"

    private fun tryEmitScrollToBottomThrottled(chatId: String?) {
        val key = chatKey(chatId)
        val now = System.currentTimeMillis()
        val last = lastScrollEmitMsByChatKey.getOrPut(key) { AtomicLong(0L) }
        val prev = last.get()
        if (now - prev >= STREAM_SCROLL_THROTTLE_MS && last.compareAndSet(prev, now)) {
            _scrollToBottomEvent.tryEmit(Unit)
        }
    }

    private fun forceEmitScrollToBottom(chatId: String?) {
        val key = chatKey(chatId)
        lastScrollEmitMsByChatKey.getOrPut(key) { AtomicLong(0L) }.set(System.currentTimeMillis())
        _scrollToBottomEvent.tryEmit(Unit)
    }

    private fun runtimeFor(chatId: String?): ChatRuntime {
        val key = chatKey(chatId)
        return chatRuntimes[key] ?: ChatRuntime().also { chatRuntimes[key] = it }
    }

    private fun updateGlobalLoadingState() {
        val anyLoading = chatRuntimes.values.any { it.isLoading.value }
        val activeChatIds = chatRuntimes
            .filter { (_, runtime) -> runtime.isLoading.value }
            .keys
            .filter { it != "__DEFAULT_CHAT__" }
            .toSet()

        _activeStreamingChatIds.value = activeChatIds
        _isLoading.value = anyLoading
    }

    private fun setChatInputProcessingState(chatId: String?, state: EnhancedInputProcessingState) {
        if (chatId != null && suppressIdleCompletedStateByChatId.containsKey(chatId)) {
            if (state is EnhancedInputProcessingState.Idle || state is EnhancedInputProcessingState.Completed) {
                return
            }
        }
        if (state !is EnhancedInputProcessingState.ExecutingTool &&
            state !is EnhancedInputProcessingState.Summarizing
        ) {
            ToolProgressBus.clear()
        }
        val key = chatKey(chatId)
        val map = _inputProcessingStateByChatId.value.toMutableMap()
        map[key] = state
        _inputProcessingStateByChatId.value = map
    }

    fun setSuppressIdleCompletedStateForChat(chatId: String, suppress: Boolean) {
        if (suppress) {
            suppressIdleCompletedStateByChatId[chatId] = true
        } else {
            suppressIdleCompletedStateByChatId.remove(chatId)
        }
    }

    fun setPendingAsyncSummaryUiForChat(chatId: String, pending: Boolean) {
        if (pending) {
            pendingAsyncSummaryUiByChatId[chatId] = true
        } else {
            pendingAsyncSummaryUiByChatId.remove(chatId)
        }
    }

    fun setInputProcessingStateForChat(chatId: String, state: EnhancedInputProcessingState) {
        setChatInputProcessingState(chatId, state)
    }

    suspend fun buildUserMessageContentForGroupOrchestration(
        messageText: String,
        attachments: List<AttachmentInfo>,
        enableMemoryQuery: Boolean,
        enableWorkspaceAttachment: Boolean,
        workspacePath: String?,
        workspaceEnv: String?,
        replyToMessage: ChatMessage?
    ): String {
        val totalStartTime = messageTimingNow()
        val configId = functionalConfigManager.getConfigIdForFunction(FunctionType.CHAT)
        val currentModelConfig = modelConfigManager.getModelConfigFlow(configId).first()
        val enableDirectImageProcessing = currentModelConfig.enableDirectImageProcessing
        val enableDirectAudioProcessing = currentModelConfig.enableDirectAudioProcessing
        val enableDirectVideoProcessing = currentModelConfig.enableDirectVideoProcessing

        val finalMessageContent = AIMessageManager.buildUserMessageContent(
            messageText = messageText,
            attachments = attachments,
            enableMemoryQuery = enableMemoryQuery,
            enableWorkspaceAttachment = enableWorkspaceAttachment,
            workspacePath = workspacePath,
            workspaceEnv = workspaceEnv,
            replyToMessage = replyToMessage,
            enableDirectImageProcessing = enableDirectImageProcessing,
            enableDirectAudioProcessing = enableDirectAudioProcessing,
            enableDirectVideoProcessing = enableDirectVideoProcessing
        )
        logMessageTiming(
            stage = "delegate.groupOrchestration.buildUserMessageContent",
            startTimeMs = totalStartTime,
            details = "attachments=${attachments.size}, configId=$configId, finalLength=${finalMessageContent.length}"
        )
        return finalMessageContent
    }

    fun getResponseStream(chatId: String): SharedStream<String>? {
        return chatRuntimes[chatKey(chatId)]?.responseStream
    }

    fun cancelMessage(chatId: String) {
        coroutineScope.launch {
            setChatInputProcessingState(chatId, EnhancedInputProcessingState.Idle)

            val chatRuntime = runtimeFor(chatId)
            chatRuntime.streamCollectionJob?.cancel()
            chatRuntime.streamCollectionJob = null
            chatRuntime.stateCollectionJob?.cancel()
            chatRuntime.stateCollectionJob = null
            chatRuntime.isLoading.value = false
            chatRuntime.responseStream = null
            updateGlobalLoadingState()
            clearCurrentTurnToolInvocationCount(chatId)

            withContext(Dispatchers.IO) {
                AIMessageManager.cancelOperation(chatId)
                saveCurrentChat()
            }
        }
    }

    init {
        AppLogger.d(TAG, "MessageProcessingDelegate初始化: 创建滚动事件流")
    }

    fun updateUserMessage(message: String) {
        _userMessage.value = TextFieldValue(message)
    }

    fun updateUserMessage(value: TextFieldValue) {
        _userMessage.value = value
    }

    fun scrollToBottom() {
        _scrollToBottomEvent.tryEmit(Unit)
    }

    fun getTurnCompleteCounter(chatId: String): Long {
        return _turnCompleteCounterByChatId.value[chatId] ?: 0L
    }

    fun setSpeakMessageHandler(handler: (String, Boolean) -> Unit) {
        speakMessageHandler = handler
    }

    private fun resetCurrentTurnToolInvocationCount(chatId: String) {
        val updated = _currentTurnToolInvocationCountByChatId.value.toMutableMap()
        updated[chatId] = 0
        _currentTurnToolInvocationCountByChatId.value = updated
    }

    private fun incrementCurrentTurnToolInvocationCount(chatId: String) {
        val updated = _currentTurnToolInvocationCountByChatId.value.toMutableMap()
        updated[chatId] = (updated[chatId] ?: 0) + 1
        _currentTurnToolInvocationCountByChatId.value = updated
    }

    private fun clearCurrentTurnToolInvocationCount(chatId: String) {
        val updated = _currentTurnToolInvocationCountByChatId.value.toMutableMap()
        updated.remove(chatId)
        _currentTurnToolInvocationCountByChatId.value = updated
    }

    fun sendUserMessage(
            attachments: List<AttachmentInfo> = emptyList(),
            chatId: String,
            messageTextOverride: String? = null,
            proxySenderNameOverride: String? = null,
            workspacePath: String? = null,
            workspaceEnv: String? = null,
            promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
            roleCardId: String,
            enableThinking: Boolean = false,
            thinkingGuidance: Boolean = false,
            enableMemoryQuery: Boolean = true, // 新增参数
            enableWorkspaceAttachment: Boolean = false, // 新增工作区附着参数
            maxTokens: Int,
            tokenUsageThreshold: Double,
            replyToMessage: ChatMessage? = null, // 新增回复消息参数
            isAutoContinuation: Boolean = false, // 标识是否为自动续写
            enableSummary: Boolean = true,
            chatModelConfigIdOverride: String? = null,
            chatModelIndexOverride: Int? = null,
            suppressUserMessageInHistory: Boolean = false,
            isGroupOrchestrationTurn: Boolean = false,
            groupParticipantNamesText: String? = null
    ) {
        val rawMessageText = messageTextOverride ?: _userMessage.value.text
        // 群组编排模式下，允许空消息（后续成员不需要用户消息）
        if (rawMessageText.isBlank() && attachments.isEmpty() && !isAutoContinuation && !isGroupOrchestrationTurn) {
            AppLogger.d(
                TAG,
                "sendUserMessage忽略: 空消息且无附件, chatId=$chatId, autoContinuation=$isAutoContinuation"
            )
            return
        }
        val chatRuntime = runtimeFor(chatId)
        if (chatRuntime.isLoading.value) {
            AppLogger.w(
                TAG,
                "sendUserMessage忽略: chat正在处理中, chatId=$chatId, roleCardId=$roleCardId, override=${!messageTextOverride.isNullOrBlank()}, suppressUserMessageInHistory=$suppressUserMessageInHistory"
            )
            return
        }

        val originalMessageText = rawMessageText.trim()
        var messageText = originalMessageText
        
        if (messageTextOverride == null) {
            _userMessage.value = TextFieldValue("")
        }
        resetCurrentTurnToolInvocationCount(chatId)
        chatRuntime.isLoading.value = true
        updateGlobalLoadingState()
        setChatInputProcessingState(chatId, EnhancedInputProcessingState.Processing(context.getString(R.string.message_processing)))

        coroutineScope.launch(Dispatchers.IO) {
            val sendUserMessageStartTime = messageTimingNow()
            // 检查这是否是聊天中的第一条用户消息（忽略AI的开场白）
            val isFirstMessage = getChatHistory(chatId).none { it.sender == "user" }
            if (isFirstMessage && chatId != null) {
                val newTitle =
                    when {
                        originalMessageText.isNotBlank() -> originalMessageText
                        attachments.isNotEmpty() -> attachments.first().fileName
                        else -> context.getString(R.string.new_conversation)
                    }
                updateChatTitle(chatId, newTitle)
            }

            AppLogger.d(TAG, "开始处理用户消息：附件数量=${attachments.size}")

            // 获取当前模型配置以检查是否启用直接图片处理
            val configId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
                ?: functionalConfigManager.getConfigIdForFunction(FunctionType.CHAT)
            val loadModelConfigStartTime = messageTimingNow()
            val currentModelConfig = modelConfigManager.getModelConfigFlow(configId).first()
            val enableDirectImageProcessing = currentModelConfig.enableDirectImageProcessing
            val enableDirectAudioProcessing = currentModelConfig.enableDirectAudioProcessing
            val enableDirectVideoProcessing = currentModelConfig.enableDirectVideoProcessing
            AppLogger.d(TAG, "直接图片处理状态: $enableDirectImageProcessing (配置ID: $configId)")
            logMessageTiming(
                stage = "delegate.loadModelConfig",
                startTimeMs = loadModelConfigStartTime,
                details = "chatId=$chatId, configId=$configId"
            )

            // 1. 使用 AIMessageManager 构建最终消息
            val buildUserMessageStartTime = messageTimingNow()
            val finalMessageContent = AIMessageManager.buildUserMessageContent(
                messageText,
                proxySenderNameOverride,
                attachments,
                enableMemoryQuery,
                enableWorkspaceAttachment,
                workspacePath,
                workspaceEnv,
                replyToMessage,
                enableDirectImageProcessing,
                enableDirectAudioProcessing,
                enableDirectVideoProcessing
            )
            logMessageTiming(
                stage = "delegate.buildUserMessageContent",
                startTimeMs = buildUserMessageStartTime,
                details = "chatId=$chatId, attachments=${attachments.size}, finalLength=${finalMessageContent.length}"
            )

            // 自动继续且原本消息为空时，不添加到聊天历史（虽然会发送"继续"给AI）
            // 群组编排模式下，空消息也不添加到聊天历史
            val shouldAddUserMessageToChat =
                !suppressUserMessageInHistory &&
                !(isAutoContinuation &&
                        originalMessageText.isBlank() &&
                        attachments.isEmpty()) &&
                !(isGroupOrchestrationTurn &&
                        originalMessageText.isBlank() &&
                        attachments.isEmpty())
            var userMessageAdded = false
            val userMessage = ChatMessage(
                sender = "user",
                content = finalMessageContent,
                roleName = context.getString(R.string.message_role_user) // 用户消息的角色名固定为"用户"
            )

            val toolHandler = AIToolHandler.getInstance(context)
            var workspaceToolHookSession: WorkspaceBackupManager.WorkspaceToolHookSession? = null

            // 在消息发送期间临时挂载 workspace hook，结束后卸载
            if (!workspacePath.isNullOrBlank()) {
                val attachWorkspaceHookStartTime = messageTimingNow()
                try {
                    val session =
                        WorkspaceBackupManager.getInstance(context)
                            .createWorkspaceToolHookSession(
                                workspacePath = workspacePath,
                                workspaceEnv = workspaceEnv,
                                messageTimestamp = userMessage.timestamp,
                                chatId = chatId
                            )
                    workspaceToolHookSession = session
                    toolHandler.addToolHook(session)
                    AppLogger.d(
                        TAG,
                        "Workspace hook attached for timestamp=${userMessage.timestamp}, path=$workspacePath"
                    )
                    logMessageTiming(
                        stage = "delegate.attachWorkspaceHook",
                        startTimeMs = attachWorkspaceHookStartTime,
                        details = "chatId=$chatId, workspacePath=$workspacePath"
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to attach workspace hook", e)
                    _nonFatalErrorEvent.emit(context.getString(R.string.message_workspace_sync_failed, e.message))
                }
            }

            if (shouldAddUserMessageToChat && chatId != null) {
                // 等待消息添加到聊天历史完成，确保getChatHistory()包含新消息
                val addUserMessageStartTime = messageTimingNow()
                addMessageToChat(chatId, userMessage)
                userMessageAdded = true
                logMessageTiming(
                    stage = "delegate.addUserMessageToChat",
                    startTimeMs = addUserMessageStartTime,
                    details = "chatId=$chatId, contentLength=${userMessage.content.length}"
                )
            }

            lateinit var aiMessage: ChatMessage
            val activeChatId = chatId
            var serviceForTurnComplete: EnhancedAIService? = null
            var shouldNotifyTurnComplete = false
            var isWaifuModeEnabled = false
            var didStreamAutoRead = false
            val effectiveRoleCardId = roleCardId
            try {
                // if (!NetworkUtils.isNetworkAvailable(context)) {
                //     withContext(Dispatchers.Main) { showErrorMessage("网络连接不可用") }
                //     _isLoading.value = false
                //     setChatInputProcessingState(activeChatId, EnhancedInputProcessingState.Idle)
                //     return@launch
                // }

                val acquireServiceStartTime = messageTimingNow()
                val chatScopedService = EnhancedAIService.getChatInstance(context, activeChatId)
                val service =
                    (chatScopedService
                        ?: getEnhancedAiService())
                        ?: run {
                            withContext(Dispatchers.Main) { showErrorMessage(context.getString(R.string.message_ai_service_not_initialized)) }
                            chatRuntime.isLoading.value = false
                            updateGlobalLoadingState()
                            setChatInputProcessingState(activeChatId, EnhancedInputProcessingState.Idle)
                            return@launch
                        }
                logMessageTiming(
                    stage = "delegate.acquireService",
                    startTimeMs = acquireServiceStartTime,
                    details = "chatId=$activeChatId, reusedChatInstance=${chatScopedService != null}"
                )
                serviceForTurnComplete = service

                // 清除上一次可能残留的 Error 状态，避免 StateFlow 重放导致新一轮发送立即再次触发弹窗
                service.setInputProcessingState(EnhancedInputProcessingState.Processing(context.getString(R.string.message_processing)))

                // 监听此 chat 对应的 EnhancedAIService 状态，映射到 per-chat state
                chatRuntime.stateCollectionJob?.cancel()
                chatRuntime.stateCollectionJob =
                    coroutineScope.launch {
                        var lastErrorMessage: String? = null
                        service.inputProcessingState.collect { state ->
                            setChatInputProcessingState(activeChatId, state)

                            if (state is EnhancedInputProcessingState.Error) {
                                val msg = state.message
                                if (msg != lastErrorMessage) {
                                    lastErrorMessage = msg
                                    withContext(Dispatchers.Main) {
                                        showErrorMessage(msg)
                                    }
                                }
                            } else {
                                lastErrorMessage = null
                            }
                        }
                    }

                val responseStartTime = messageTimingNow()
                val deferred = CompletableDeferred<Unit>()

                val userPreferencesManager = UserPreferencesManager.getInstance(context)

                // 获取角色信息用于通知
                val loadRoleInfoStartTime = messageTimingNow()
                val (characterName, avatarUri) = try {
                    val roleCard = characterCardManager.getCharacterCardFlow(effectiveRoleCardId).first()
                    val avatar =
                        userPreferencesManager.getAiAvatarForCharacterCardFlow(roleCard.id).first()
                    Pair(roleCard.name, avatar)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "获取角色信息失败: ${e.message}", e)
                    Pair(null, null)
                }
                val currentRoleName = characterName ?: "Operit"
                logMessageTiming(
                    stage = "delegate.loadRoleInfo",
                    startTimeMs = loadRoleInfoStartTime,
                    details = "chatId=$activeChatId, roleCardId=$effectiveRoleCardId, roleName=$currentRoleName"
                )

                val loadChatHistoryStartTime = messageTimingNow()
                val chatHistory = getChatHistory(activeChatId)
                logMessageTiming(
                    stage = "delegate.loadChatHistory",
                    startTimeMs = loadChatHistoryStartTime,
                    details = "chatId=$activeChatId, size=${chatHistory.size}"
                )

                // 根据enableSummary控制Token阈值检查和Token超限回调
                val effectiveMaxTokens = if (enableSummary) maxTokens else 0
                val effectiveTokenUsageThreshold = if (enableSummary) tokenUsageThreshold else Double.POSITIVE_INFINITY
                val effectiveOnTokenLimitExceeded = if (enableSummary) {
                    suspend { onTokenLimitExceeded(activeChatId) }
                } else {
                    null
                }

                // 2. 使用 AIMessageManager 发送消息
                // 群组编排模式下，只有当消息内容不为空时才添加 [From user] 前缀
                val requestMessageContent =
                    if (isGroupOrchestrationTurn &&
                        finalMessageContent.trimStart().isNotEmpty() &&
                        !finalMessageContent.trimStart().startsWith("[From user]")
                    ) {
                        "[From user]\n$finalMessageContent"
                    } else {
                        finalMessageContent
                    }

                val prepareResponseStreamStartTime = messageTimingNow()
                val responseStream = AIMessageManager.sendMessage(
                    enhancedAiService = service,
                    chatId = activeChatId,
                    messageContent = requestMessageContent,
                    // 仅在群组编排中去掉当前用户消息，避免重复拼接。
                    chatHistory = if (isGroupOrchestrationTurn && userMessageAdded && chatHistory.isNotEmpty()) {
                        chatHistory.subList(0, chatHistory.size - 1)
                    } else {
                        chatHistory
                    },
                    workspacePath = workspacePath,
                    promptFunctionType = promptFunctionType,
                    enableThinking = enableThinking,
                    thinkingGuidance = thinkingGuidance,
                    enableMemoryQuery = enableMemoryQuery, // Pass it here
                    maxTokens = effectiveMaxTokens,
                    tokenUsageThreshold = effectiveTokenUsageThreshold,
                    onNonFatalError = { error ->
                        _nonFatalErrorEvent.emit(error)
                    },
                    onTokenLimitExceeded = effectiveOnTokenLimitExceeded,
                    characterName = characterName,
                    avatarUri = avatarUri,
                    roleCardId = effectiveRoleCardId,
                    currentRoleName = currentRoleName,
                    splitHistoryByRole = true,
                    groupOrchestrationMode = isGroupOrchestrationTurn,
                    groupParticipantNamesText = groupParticipantNamesText,
                    proxySenderName = proxySenderNameOverride,
                    onToolInvocation = {
                        incrementCurrentTurnToolInvocationCount(chatId)
                    },
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride
                )
                logMessageTiming(
                    stage = "delegate.prepareResponseStream",
                    startTimeMs = prepareResponseStreamStartTime,
                    details = "chatId=$activeChatId, requestLength=${requestMessageContent.length}, history=${chatHistory.size}"
                )

                // 将字符串流共享，以便多个收集器可以使用
                // 关键修改：设置 replay = Int.MAX_VALUE，确保 UI 重组（重新订阅）时能收到所有历史字符
                // 文本数据占用内存极小，全量缓冲不会造成内存压力
                val shareResponseStreamStartTime = messageTimingNow()
                val sharedCharStream =
                    responseStream.share(
                        scope = coroutineScope,
                        replay = Int.MAX_VALUE, 
                        onComplete = {
                            deferred.complete(Unit)
                            logMessageTiming(
                                stage = "delegate.sharedStreamComplete",
                                startTimeMs = responseStartTime,
                                details = "chatId=$activeChatId"
                            )
                            chatRuntime.responseStream = null
                        }
                    )
                logMessageTiming(
                    stage = "delegate.shareResponseStream",
                    startTimeMs = shareResponseStreamStartTime,
                    details = "chatId=$activeChatId"
                )

                // 更新当前响应流，使其可以被其他组件（如悬浮窗）访问
                chatRuntime.responseStream = sharedCharStream

                // 获取当前使用的provider和model信息
                val loadProviderModelStartTime = messageTimingNow()
                val (provider, modelName) = try {
                    service.getProviderAndModelForFunction(
                        functionType = com.ai.assistance.operit.data.model.FunctionType.CHAT,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "获取provider和model信息失败: ${e.message}", e)
                    Pair("", "")
                }
                logMessageTiming(
                    stage = "delegate.loadProviderModel",
                    startTimeMs = loadProviderModelStartTime,
                    details = "chatId=$activeChatId, provider=$provider, model=$modelName"
                )

                aiMessage = ChatMessage(
                    sender = "ai", 
                    contentStream = sharedCharStream,
                    timestamp = System.currentTimeMillis()+50,
                    roleName = currentRoleName,
                    provider = provider,
                    modelName = modelName
                )
                AppLogger.d(
                    TAG,
                    "创建带流的AI消息, stream is null: ${aiMessage.contentStream == null}, timestamp: ${aiMessage.timestamp}"
                )

                // 检查是否启用waifu模式来决定是否显示流式过程
                val waifuPreferences = WaifuPreferences.getInstance(context)
                isWaifuModeEnabled = waifuPreferences.enableWaifuModeFlow.first()
                
                // 只有在非waifu模式下才添加初始的AI消息
                if (!isWaifuModeEnabled) {
                    withContext(Dispatchers.Main) {
                        if (chatId != null) {
                            addMessageToChat(chatId, aiMessage)
                        }
                    }
                }
                
                // 启动一个独立的协程来收集流内容并持续更新数据库
                val streamCollectionResult = CompletableDeferred<Throwable?>()
                chatRuntime.streamCollectionJob =
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            var hasLoggedFirstChunk = false
                            val contentBuilder = StringBuilder()
                            val autoReadBuffer = StringBuilder()
                            var isFirstAutoReadSegment = true
                            val endChars = ".,!?;:，。！？；：\n"
                            val autoReadStream = XmlTextProcessor.processStreamToText(sharedCharStream)

                            fun flushAutoReadSegment(segment: String, interrupt: Boolean) {
                                val trimmed = segment.trim()
                                if (trimmed.isNotEmpty()) {
                                    didStreamAutoRead = true
                                    speakMessageHandler(trimmed, interrupt)
                                }
                            }

                            fun findFirstEndCharIndex(text: CharSequence): Int {
                                for (i in 0 until text.length) {
                                    val c = text[i]
                                    if (endChars.indexOf(c) >= 0) return i
                                }
                                return -1
                            }

                            fun tryFlushAutoRead() {
                                if (!getIsAutoReadEnabled()) return
                                if (isWaifuModeEnabled) return
                                while (true) {
                                    val endIdx = findFirstEndCharIndex(autoReadBuffer)
                                    val shouldFlushByLen = endIdx < 0 && autoReadBuffer.length >= 50
                                    if (endIdx < 0 && !shouldFlushByLen) return

                                    val cutIdx = if (endIdx >= 0) endIdx + 1 else autoReadBuffer.length
                                    val seg = autoReadBuffer.substring(0, cutIdx)
                                    autoReadBuffer.delete(0, cutIdx)

                                    flushAutoReadSegment(seg, interrupt = isFirstAutoReadSegment)
                                    isFirstAutoReadSegment = false
                                }
                            }

                            val autoReadJob = launch {
                                autoReadStream.collect { char ->
                                    autoReadBuffer.append(char)
                                    tryFlushAutoRead()
                                }
                            }

                            sharedCharStream.collect { chunk ->
                                if (!hasLoggedFirstChunk) {
                                    hasLoggedFirstChunk = true
                                    logMessageTiming(
                                        stage = "delegate.firstResponseChunk",
                                        startTimeMs = responseStartTime,
                                        details = "chatId=$activeChatId, firstChunkLength=${chunk.length}"
                                    )
                                }
                                contentBuilder.append(chunk)
                                val content = contentBuilder.toString()
                                val updatedMessage = aiMessage.copy(content = content)
                                // 防止后续读取不到
                                aiMessage.content = content
                                
                                // 只有在非waifu模式下才显示流式更新
                                if (!isWaifuModeEnabled) {
                                    if (chatId != null) {
                                        addMessageToChat(chatId, updatedMessage)
                                    }
                                    tryEmitScrollToBottomThrottled(chatId)
                                }
                            }

                            autoReadJob.join()

                            if (getIsAutoReadEnabled() && !isWaifuModeEnabled) {
                                val remaining = autoReadBuffer.toString()
                                autoReadBuffer.clear()
                                flushAutoReadSegment(remaining, interrupt = isFirstAutoReadSegment)
                            }
                        } catch (t: Throwable) {
                            if (!streamCollectionResult.isCompleted) {
                                streamCollectionResult.complete(t)
                            }
                            throw t
                        } finally {
                            if (!streamCollectionResult.isCompleted) {
                                streamCollectionResult.complete(null)
                            }
                        }
                    }

                // 等待流完成，以便finally块可以正确执行来更新UI状态
                deferred.await()
                val streamCollectionError = streamCollectionResult.await()
                if (streamCollectionError != null) {
                    throw streamCollectionError
                }

                val stateAfterStream =
                    _inputProcessingStateByChatId.value[chatKey(chatId)]
                if (stateAfterStream !is EnhancedInputProcessingState.Error) {
                    setChatInputProcessingState(chatId, EnhancedInputProcessingState.Completed)
                    shouldNotifyTurnComplete = true
                }

                if (pendingAsyncSummaryUiByChatId.containsKey(chatId)) {
                    setSuppressIdleCompletedStateForChat(chatId, true)
                    setChatInputProcessingState(
                        chatId,
                        EnhancedInputProcessingState.Summarizing(context.getString(R.string.message_summarizing))
                    )
                }

                logMessageTiming(
                    stage = "delegate.responseProcessingComplete",
                    startTimeMs = responseStartTime,
                    details = "chatId=$activeChatId, waifu=$isWaifuModeEnabled, autoRead=$didStreamAutoRead"
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    AppLogger.d(TAG, "消息发送被取消")
                    setChatInputProcessingState(chatId, EnhancedInputProcessingState.Idle)
                    shouldNotifyTurnComplete = false
                    throw e
                }
                AppLogger.e(TAG, "发送消息时出错", e)
                setChatInputProcessingState(
                    chatId,
                    EnhancedInputProcessingState.Error(context.getString(R.string.message_send_failed, e.message))
                )
                withContext(Dispatchers.Main) { showErrorMessage(context.getString(R.string.message_send_failed, e.message)) }
            } finally {
                val finalizeMessageStartTime = messageTimingNow()
                finalizeMessageAndNotify(
                    chatId = chatId,
                    activeChatId = activeChatId,
                    aiMessageProvider = { aiMessage },
                    shouldNotifyTurnComplete = shouldNotifyTurnComplete,
                    serviceForTurnComplete = serviceForTurnComplete,
                    skipFinalAutoRead = didStreamAutoRead && !isWaifuModeEnabled,
                    roleCardId = effectiveRoleCardId,
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride
                )
                logMessageTiming(
                    stage = "delegate.finalizeMessage",
                    startTimeMs = finalizeMessageStartTime,
                    details = "chatId=$activeChatId, notifyTurnComplete=$shouldNotifyTurnComplete"
                )

                workspaceToolHookSession?.let { session ->
                    val cleanupWorkspaceHookStartTime = messageTimingNow()
                    runCatching { toolHandler.removeToolHook(session) }
                        .onFailure { AppLogger.w(TAG, "Failed to remove workspace hook", it) }
                    runCatching { session.close() }
                        .onFailure { AppLogger.w(TAG, "Failed to close workspace hook session", it) }
                    logMessageTiming(
                        stage = "delegate.cleanupWorkspaceHook",
                        startTimeMs = cleanupWorkspaceHookStartTime,
                        details = "chatId=$activeChatId"
                    )
                }

                val cleanupRuntimeStartTime = messageTimingNow()
                cleanupRuntimeAfterSend(chatId, chatRuntime)
                logMessageTiming(
                    stage = "delegate.cleanupRuntime",
                    startTimeMs = cleanupRuntimeStartTime,
                    details = "chatId=$activeChatId"
                )
                logMessageTiming(
                    stage = "delegate.sendUserMessage.total",
                    startTimeMs = sendUserMessageStartTime,
                    details = "chatId=$activeChatId, addedUserMessage=$userMessageAdded, enableSummary=$enableSummary"
                )
            }
        }
    }

    private fun notifyTurnComplete(
        chatId: String?,
        activeChatId: String?,
        service: EnhancedAIService
    ) {
        if (!chatId.isNullOrBlank()) {
            val updated = _turnCompleteCounterByChatId.value.toMutableMap()
            updated[chatId] = (updated[chatId] ?: 0L) + 1L
            _turnCompleteCounterByChatId.value = updated
        }
        onTurnComplete(activeChatId, service)
    }

    private suspend fun finalizeMessageAndNotify(
        chatId: String?,
        activeChatId: String?,
        aiMessageProvider: () -> ChatMessage,
        shouldNotifyTurnComplete: Boolean,
        serviceForTurnComplete: EnhancedAIService?,
        skipFinalAutoRead: Boolean,
        roleCardId: String,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null
    ) {
        // 修改为使用 try-catch 来检查变量是否已初始化，而不是使用 ::var.isInitialized
        try {
            val aiMessage = aiMessageProvider()
            val sharedStream = aiMessage.contentStream as? SharedStream<String>
            val replayChunks = sharedStream?.replayCache
            // 优先使用共享流的全量重放缓存重建最终文本，避免完成信号早于收集协程处理尾部字符时丢字。
            val finalContent =
                if (!replayChunks.isNullOrEmpty()) {
                    replayChunks.joinToString(separator = "")
                } else {
                    aiMessage.content
                }
            aiMessage.content = finalContent

            var deferTurnCompleteToAsyncJob = false
            withContext(Dispatchers.IO) {
                val waifuPreferences = WaifuPreferences.getInstance(context)
                val isWaifuModeEnabled = waifuPreferences.enableWaifuModeFlow.first()

                if (isWaifuModeEnabled && WaifuMessageProcessor.shouldSplitMessage(finalContent)) {
                    deferTurnCompleteToAsyncJob = true
                    AppLogger.d(TAG, "Waifu模式已启用，开始创建独立消息，内容长度: ${finalContent.length}")

                    // 获取配置的字符延迟时间和标点符号设置
                    val charDelay = waifuPreferences.waifuCharDelayFlow.first().toLong()
                    val removePunctuation = waifuPreferences.waifuRemovePunctuationFlow.first()

                    // 获取当前角色名
                    val currentRoleName = try {
                        characterCardManager.getCharacterCardFlow(roleCardId).first().name
                    } catch (e: Exception) {
                        "Operit" // 默认角色名
                    }

                    // 获取当前使用的provider和model信息（在finally块内重新获取）
                    val (provider, modelName) = try {
                        getEnhancedAiService()?.getProviderAndModelForFunction(
                            functionType = com.ai.assistance.operit.data.model.FunctionType.CHAT,
                            chatModelConfigIdOverride = chatModelConfigIdOverride,
                            chatModelIndexOverride = chatModelIndexOverride
                        )
                            ?: Pair("", "")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "获取provider和model信息失败: ${e.message}", e)
                        Pair("", "")
                    }

                    // 删除原始的空消息（因为在waifu模式下我们没有显示流式过程）
                    // 不需要显示空的AI消息
                    
                    // 启动一个协程来创建独立的句子消息
                    coroutineScope.launch(Dispatchers.IO) {
                        AppLogger.d(
                            TAG,
                            "开始Waifu独立消息创建，字符延迟: ${charDelay}ms/字符，移除标点: $removePunctuation"
                        )

                        // 分割句子
                        val sentences =
                            WaifuMessageProcessor.splitMessageBySentences(finalContent, removePunctuation)
                        AppLogger.d(TAG, "分割出${sentences.size}个句子")

                        // 为每个句子创建独立的消息
                        for ((index, sentence) in sentences.withIndex()) {
                            // 根据当前句子字符数计算延迟（模拟说话时间）
                            val characterCount = sentence.length
                            val calculatedDelay =
                                WaifuMessageProcessor.calculateSentenceDelay(characterCount, charDelay)

                            if (index > 0) {
                                // 如果不是第一句，先延迟再发送
                                AppLogger.d(TAG, "当前句字符数: $characterCount, 计算延迟: ${calculatedDelay}ms")
                                delay(calculatedDelay)
                            }

                            AppLogger.d(TAG, "创建第${index + 1}个独立消息: $sentence")

                            // 创建独立的AI消息（使用外层已获取的provider和modelName）
                            val sentenceMessage = ChatMessage(
                                sender = "ai",
                                content = sentence,
                                contentStream = null,
                                timestamp = System.currentTimeMillis() + index * 10,
                                roleName = currentRoleName,
                                provider = provider,
                                modelName = modelName
                            )

                            withContext(Dispatchers.Main) {
                                if (chatId != null) {
                                    addMessageToChat(chatId, sentenceMessage)
                                }
                                // 如果启用了自动朗读，则朗读当前句子
                                if (getIsAutoReadEnabled()) {
                                    speakMessageHandler(sentence, true)
                                }
                                if (index == sentences.lastIndex) {
                                    forceEmitScrollToBottom(chatId)
                                } else {
                                    tryEmitScrollToBottomThrottled(chatId)
                                }
                            }
                        }

                        AppLogger.d(TAG, "Waifu独立消息创建完成")

                        if (shouldNotifyTurnComplete) {
                            val service = serviceForTurnComplete
                            if (service != null) {
                                notifyTurnComplete(chatId, activeChatId, service)
                            }
                        }
                    }
                } else {
                    // 普通模式，直接清理流
                    val finalMessage = aiMessage.copy(content = finalContent, contentStream = null)
                    withContext(Dispatchers.Main) {
                        if (chatId != null) {
                            addMessageToChat(chatId, finalMessage)
                        }
                        // 如果启用了自动朗读，则朗读完整消息
                        if (getIsAutoReadEnabled() && !skipFinalAutoRead) {
                            speakMessageHandler(finalContent, true)
                        }
                        forceEmitScrollToBottom(chatId)
                    }
                }
            }

            if (shouldNotifyTurnComplete && !deferTurnCompleteToAsyncJob) {
                val service = serviceForTurnComplete
                if (service != null) {
                    notifyTurnComplete(chatId, activeChatId, service)
                }
            }
        } catch (e: UninitializedPropertyAccessException) {
            AppLogger.d(TAG, "AI消息未初始化，跳过流清理步骤")
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "消息收尾阶段被取消，跳过waifu收尾处理")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理waifu模式时出错", e)
            try {
                val aiMessage = aiMessageProvider()
                val finalContent = aiMessage.content
                val finalMessage = aiMessage.copy(content = finalContent)
                withContext(Dispatchers.Main) {
                    if (chatId != null) {
                        addMessageToChat(chatId, finalMessage)
                    }
                }

                if (shouldNotifyTurnComplete) {
                    val service = serviceForTurnComplete
                    if (service != null) {
                        notifyTurnComplete(chatId, activeChatId, service)
                    }
                }
            } catch (ex: Exception) {
                AppLogger.e(TAG, "回退到普通模式也失败", ex)
            }
        }
    }

    private fun cleanupRuntimeAfterSend(chatId: String, chatRuntime: ChatRuntime) {
        chatRuntime.streamCollectionJob = null
        chatRuntime.stateCollectionJob?.cancel()
        chatRuntime.stateCollectionJob = null
        chatRuntime.isLoading.value = false

        updateGlobalLoadingState()
        clearCurrentTurnToolInvocationCount(chatId)
    }

    /**
     * 强制重置加载状态，允许新的发送流程开始。
     * 主要用于在执行内部流程（如历史总结）后确保状态不会阻塞后续操作。
     */
    fun resetLoadingState() {
        updateGlobalLoadingState()
    }
}
