package com.intellij.ai.completion

import com.intellij.ai.context.service.ContextCollectorService
import com.intellij.ai.core.model.CompletionRequest
import com.intellij.ai.core.model.Message
import com.intellij.ai.core.model.Role
import com.intellij.ai.core.service.AIServiceManager
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/**
 * Main completion contributor that provides AI-powered code suggestions
 */
class AICompletionContributor : CompletionContributor(), DumbAware {
    companion object {
        private val LOG = logger<AICompletionContributor>()
        private const val AI_COMPLETION_TIMEOUT_MS = 3000L
        private const val MIN_PREFIX_LENGTH = 2
    }
    
    init {
        // Register for all contexts
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            AICompletionProvider()
        )
    }
    
    override fun beforeCompletion(context: CompletionInitializationContext) {
        // Don't modify the default behavior
        super.beforeCompletion(context)
    }
    
    private class AICompletionProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            if (!shouldProvideCompletions(parameters)) {
                return
            }
            
            val project = parameters.position.project
            val document = parameters.editor.document
            val offset = parameters.offset
            
            // Get context asynchronously
            val contextCollector = ContextCollectorService.getInstance(project)
            val codeContext = contextCollector.collectContext(
                file = parameters.originalFile,
                offset = offset,
                includeRelatedFiles = true
            )
            
            // Build AI request
            val prefix = document.getText(TextRange(0, offset))
            val suffix = document.getText(TextRange(offset, document.textLength))
            
            val aiService = AIServiceManager.getInstance()
            val provider = aiService.getActiveProvider() ?: return
            
            // Create completion request with context
            val systemPrompt = buildSystemPrompt(parameters)
            val userPrompt = buildUserPrompt(prefix, suffix, codeContext)
            
            val request = CompletionRequest(
                messages = listOf(
                    Message(Role.SYSTEM, systemPrompt),
                    Message(Role.USER, userPrompt)
                ),
                temperature = 0.3f,
                maxTokens = 150,
                stopSequences = listOf("\n\n", "```", "</code>")
            )
            
            // Submit async request
            runBlocking {
                withTimeoutOrNull(AI_COMPLETION_TIMEOUT_MS) {
                    try {
                        val response = aiService.submitRequest(
                            priority = AIServiceManager.RequestPriority.HIGH
                        ) { aiProvider ->
                            aiProvider.complete(request)
                        }
                        
                        // Process AI response
                        val suggestions = parseAISuggestions(response.content)
                        suggestions.forEach { suggestion ->
                            result.addElement(createAILookupElement(suggestion, parameters))
                        }
                    } catch (e: Exception) {
                        LOG.warn("AI completion failed", e)
                    }
                }
            }
        }
        
        private fun shouldProvideCompletions(parameters: CompletionParameters): Boolean {
            // Check if AI completions are enabled
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                val settings = com.intellij.ai.settings.AISettings.getInstance()
                if (!settings.enableCompletion) return false
            }
            
            // Check minimum prefix length
            val prefix = parameters.position.text
            if (prefix.length < MIN_PREFIX_LENGTH) return false
            
            return true
        }
        
        private fun buildSystemPrompt(parameters: CompletionParameters): String {
            val language = parameters.originalFile.language.displayName
            return """
                You are an AI code completion assistant for $language.
                Provide concise, contextually relevant code completions.
                Return only the code to be inserted, without explanations.
                Focus on completing the current line or block being typed.
                Ensure proper syntax and follow the coding style of the file.
            """.trimIndent()
        }
        
        private fun buildUserPrompt(
            prefix: String,
            suffix: String,
            context: com.intellij.ai.context.CodeContext
        ): String {
            return """
                Complete the code at the cursor position (marked with <cursor>):
                
                ```${context.language}
                ${prefix}<cursor>${suffix}
                ```
                
                Context:
                - File: ${context.fileName}
                - Imports: ${context.imports.joinToString(", ")}
                - Recent edits: ${context.recentEdits.take(3).joinToString("\n")}
                
                Provide the completion that should be inserted at the cursor position.
            """.trimIndent()
        }
        
        private fun parseAISuggestions(response: String): List<AISuggestion> {
            // Parse the AI response to extract code suggestions
            val suggestions = mutableListOf<AISuggestion>()
            
            // Simple parsing - in real implementation, use more sophisticated parsing
            val cleanedResponse = response.trim()
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            if (cleanedResponse.isNotEmpty()) {
                suggestions.add(
                    AISuggestion(
                        text = cleanedResponse,
                        displayText = cleanedResponse.lines().firstOrNull() ?: cleanedResponse,
                        isMultiline = cleanedResponse.contains('\n'),
                        confidence = 0.8f
                    )
                )
            }
            
            return suggestions
        }
        
        private fun createAILookupElement(
            suggestion: AISuggestion,
            parameters: CompletionParameters
        ): LookupElement {
            return LookupElementBuilder
                .create(suggestion.text)
                .withPresentableText(suggestion.displayText)
                .withIcon(getAIIcon())
                .withTypeText("AI suggestion", true)
                .withTailText(if (suggestion.isMultiline) " (multiline)" else "", true)
                .withInsertHandler(AIInsertHandler(suggestion))
                .withRenderer(AILookupRenderer(suggestion))
                .withAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE)
        }
        
        private fun getAIIcon(): Icon = AllIcons.Nodes.PpWeb
    }
    
    private data class AISuggestion(
        val text: String,
        val displayText: String,
        val isMultiline: Boolean,
        val confidence: Float
    )
    
    private class AIInsertHandler(
        private val suggestion: AISuggestion
    ) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            val startOffset = context.startOffset
            val tailOffset = context.tailOffset
            
            // Delete the prefix that was already typed
            document.deleteString(startOffset, tailOffset)
            
            // Insert the AI suggestion
            document.insertString(startOffset, suggestion.text)
            
            // Move caret to the end of insertion
            context.editor.caretModel.moveToOffset(startOffset + suggestion.text.length)
            
            // Trigger formatting if multiline
            if (suggestion.isMultiline) {
                ApplicationManager.getApplication().invokeLater {
                    ProgressManager.getInstance().runProcessWithProgressSynchronously(
                        {
                            context.commitDocument()
                            // Format the inserted code
                            com.intellij.codeInsight.actions.ReformatCodeProcessor(
                                context.project,
                                context.file,
                                TextRange(startOffset, startOffset + suggestion.text.length),
                                false
                            ).run()
                        },
                        "Formatting AI suggestion",
                        false,
                        context.project
                    )
                }
            }
        }
    }
    
    private class AILookupRenderer(
        private val suggestion: AISuggestion
    ) : LookupElementRenderer<LookupElement>() {
        override fun renderElement(
            element: LookupElement,
            presentation: LookupElementPresentation
        ) {
            presentation.itemText = suggestion.displayText
            presentation.icon = getAIIcon()
            presentation.typeText = "AI"
            presentation.isTypeGrayed = true
            
            if (suggestion.isMultiline) {
                presentation.appendTailText(" ↵", true)
            }
            
            // Show confidence as percentage
            val confidencePercent = (suggestion.confidence * 100).toInt()
            presentation.appendTailTextItalic(" ${confidencePercent}%", true)
        }
        
        private fun getAIIcon(): Icon = AllIcons.Nodes.PpWeb
    }
}