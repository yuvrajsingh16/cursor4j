package com.intellij.ai.core.model

import kotlinx.coroutines.flow.Flow

/**
 * Interface for AI model providers (OpenAI, Anthropic, local models, etc.)
 */
interface AIModelProvider {
    val name: String
    val modelId: String
    val maxTokens: Int
    val supportsStreaming: Boolean
    
    /**
     * Generate a completion for the given prompt
     */
    suspend fun complete(request: CompletionRequest): CompletionResponse
    
    /**
     * Generate a streaming completion
     */
    fun completeStreaming(request: CompletionRequest): Flow<CompletionChunk>
    
    /**
     * Count tokens in the given text
     */
    fun countTokens(text: String): Int
    
    /**
     * Check if the provider is available and properly configured
     */
    fun isAvailable(): Boolean
    
    /**
     * Get remaining context window size for the given used tokens
     */
    fun getRemainingContext(usedTokens: Int): Int = maxTokens - usedTokens
}

data class CompletionRequest(
    val messages: List<Message>,
    val temperature: Float = 0.7f,
    val maxTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val systemPrompt: String? = null
)

data class Message(
    val role: Role,
    val content: String
)

enum class Role {
    SYSTEM,
    USER,
    ASSISTANT
}

data class CompletionResponse(
    val content: String,
    val tokensUsed: Int,
    val finishReason: FinishReason
)

data class CompletionChunk(
    val delta: String,
    val isComplete: Boolean
)

enum class FinishReason {
    STOP,
    LENGTH,
    ERROR
}