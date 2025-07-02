package com.intellij.ai.core.service

import com.intellij.ai.core.model.AIModelProvider
import com.intellij.ai.core.providers.OpenAIProvider
import com.intellij.ai.core.providers.AnthropicProvider
import com.intellij.ai.core.providers.OllamaProvider
import com.intellij.ai.settings.AISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Main service for managing AI providers and requests
 */
@Service(Service.Level.APP)
class AIServiceManager {
    companion object {
        private val LOG = logger<AIServiceManager>()
        
        @JvmStatic
        fun getInstance(): AIServiceManager = service()
    }
    
    private val providers = mutableMapOf<String, AIModelProvider>()
    private val requestQueue = ConcurrentLinkedQueue<QueuedRequest>()
    private val activeRequests = AtomicInteger(0)
    private val maxConcurrentRequests = 5
    
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("AIServiceManager")
    )
    
    init {
        initializeProviders()
        startRequestProcessor()
    }
    
    private fun initializeProviders() {
        val settings = AISettings.getInstance()
        
        // Initialize OpenAI provider
        if (settings.openAIKey.isNotEmpty()) {
            providers["openai"] = OpenAIProvider(
                apiKey = settings.openAIKey,
                model = settings.openAIModel
            )
        }
        
        // Initialize Anthropic provider
        if (settings.anthropicKey.isNotEmpty()) {
            providers["anthropic"] = AnthropicProvider(
                apiKey = settings.anthropicKey,
                model = settings.anthropicModel
            )
        }
        
        // Initialize Ollama provider (local models)
        if (settings.useLocalModels) {
            providers["ollama"] = OllamaProvider(
                baseUrl = settings.ollamaUrl,
                model = settings.ollamaModel
            )
        }
    }
    
    fun getActiveProvider(): AIModelProvider? {
        val settings = AISettings.getInstance()
        return providers[settings.activeProvider]
    }
    
    fun getProvider(name: String): AIModelProvider? {
        return providers[name]
    }
    
    fun getAllProviders(): Map<String, AIModelProvider> {
        return providers.toMap()
    }
    
    suspend fun <T> submitRequest(
        priority: RequestPriority = RequestPriority.NORMAL,
        request: suspend (AIModelProvider) -> T
    ): T {
        val provider = getActiveProvider() 
            ?: throw IllegalStateException("No AI provider configured")
        
        if (!provider.isAvailable()) {
            throw IllegalStateException("AI provider ${provider.name} is not available")
        }
        
        return if (priority == RequestPriority.IMMEDIATE) {
            executeRequest(provider, request)
        } else {
            queueRequest(priority, provider, request)
        }
    }
    
    private suspend fun <T> executeRequest(
        provider: AIModelProvider,
        request: suspend (AIModelProvider) -> T
    ): T {
        activeRequests.incrementAndGet()
        return try {
            request(provider)
        } finally {
            activeRequests.decrementAndGet()
        }
    }
    
    private suspend fun <T> queueRequest(
        priority: RequestPriority,
        provider: AIModelProvider,
        request: suspend (AIModelProvider) -> T
    ): T {
        val deferred = CompletableDeferred<T>()
        val queuedRequest = QueuedRequest(
            priority = priority,
            provider = provider,
            execute = {
                try {
                    val result = request(provider)
                    deferred.complete(result)
                } catch (e: Exception) {
                    deferred.completeExceptionally(e)
                }
            }
        )
        
        requestQueue.offer(queuedRequest)
        return deferred.await()
    }
    
    private fun startRequestProcessor() {
        scope.launch {
            while (isActive) {
                if (activeRequests.get() < maxConcurrentRequests && requestQueue.isNotEmpty()) {
                    val request = requestQueue.poll()
                    if (request != null) {
                        launch {
                            executeRequest(request.provider) {
                                request.execute()
                                request.provider
                            }
                        }
                    }
                }
                delay(50) // Check queue every 50ms
            }
        }
    }
    
    fun updateProviderSettings() {
        providers.clear()
        initializeProviders()
    }
    
    fun dispose() {
        scope.cancel()
    }
    
    private data class QueuedRequest(
        val priority: RequestPriority,
        val provider: AIModelProvider,
        val execute: suspend () -> Unit
    )
    
    enum class RequestPriority {
        LOW,
        NORMAL,
        HIGH,
        IMMEDIATE
    }
}