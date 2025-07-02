package com.intellij.ai.editor

import com.intellij.ai.context.service.ContextCollectorService
import com.intellij.ai.core.model.CompletionRequest
import com.intellij.ai.core.model.Message
import com.intellij.ai.core.model.Role
import com.intellij.ai.core.service.AIServiceManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.SwingUtilities

/**
 * Manages inline AI editing with ghost text display
 */
class InlineAIEditor(
    private val editor: Editor,
    private val project: Project
) {
    companion object {
        private val LOG = logger<InlineAIEditor>()
        private val INLINE_AI_KEY = Key.create<InlineAIEditor>("InlineAIEditor")
        private val GHOST_TEXT_COLOR = JBColor(
            JBColor.GRAY.brighter(),
            JBColor.GRAY.darker()
        )
        
        fun getInstance(editor: Editor): InlineAIEditor? {
            return editor.getUserData(INLINE_AI_KEY)
        }
        
        fun getOrCreate(editor: Editor, project: Project): InlineAIEditor {
            var instance = editor.getUserData(INLINE_AI_KEY)
            if (instance == null) {
                instance = InlineAIEditor(editor, project)
                editor.putUserData(INLINE_AI_KEY, instance)
            }
            return instance
        }
    }
    
    private var ghostTextRenderer: GhostTextRenderer? = null
    private var currentSuggestion: String? = null
    private var suggestionOffset: Int = -1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null
    
    init {
        setupDocumentListener()
    }
    
    fun requestInlineSuggestion(prompt: String? = null) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                clearGhostText()
                
                val caretOffset = editor.caretModel.offset
                val document = editor.document
                val file = FileDocumentManager.getInstance().getFile(document) ?: return@launch
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(file) ?: return@launch
                
                // Collect context
                val contextCollector = ContextCollectorService.getInstance(project)
                val context = contextCollector.collectContext(psiFile, caretOffset, true)
                
                // Build AI request
                val prefix = document.getText(TextRange(0, caretOffset))
                val suffix = document.getText(TextRange(caretOffset, document.textLength))
                
                val systemPrompt = """
                    You are an AI coding assistant providing inline code suggestions.
                    Complete the code naturally from the cursor position.
                    Provide only the code to insert, no explanations.
                    Match the coding style and indentation of the file.
                """.trimIndent()
                
                val userPrompt = if (prompt != null) {
                    """
                    User request: $prompt
                    
                    Current code with cursor position marked as <cursor>:
                    ```${context.language}
                    ${prefix}<cursor>${suffix}
                    ```
                    """.trimIndent()
                } else {
                    """
                    Continue writing code from the cursor position:
                    ```${context.language}
                    ${prefix}<cursor>${suffix}
                    ```
                    """.trimIndent()
                }
                
                val request = CompletionRequest(
                    messages = listOf(
                        Message(Role.SYSTEM, systemPrompt),
                        Message(Role.USER, userPrompt)
                    ),
                    temperature = 0.3f,
                    maxTokens = 200
                )
                
                // Get AI suggestion with streaming
                val aiService = AIServiceManager.getInstance()
                val provider = aiService.getActiveProvider() ?: return@launch
                
                if (provider.supportsStreaming) {
                    // Stream the response
                    aiService.submitRequest(AIServiceManager.RequestPriority.IMMEDIATE) { aiProvider ->
                        aiProvider.completeStreaming(request).collectLatest { chunk ->
                            withContext(Dispatchers.Main) {
                                appendGhostText(chunk.delta, caretOffset)
                            }
                        }
                    }
                } else {
                    // Get complete response
                    val response = aiService.submitRequest(AIServiceManager.RequestPriority.HIGH) { aiProvider ->
                        aiProvider.complete(request)
                    }
                    withContext(Dispatchers.Main) {
                        showGhostText(response.content, caretOffset)
                    }
                }
            } catch (e: CancellationException) {
                LOG.debug("Inline suggestion cancelled")
            } catch (e: Exception) {
                LOG.error("Failed to get inline suggestion", e)
            }
        }
    }
    
    fun acceptSuggestion() {
        val suggestion = currentSuggestion ?: return
        val offset = suggestionOffset
        
        if (offset < 0 || suggestion.isEmpty()) return
        
        WriteCommandAction.runWriteCommandAction(project, "Accept AI Suggestion", null, {
            editor.document.insertString(offset, suggestion)
            editor.caretModel.moveToOffset(offset + suggestion.length)
        })
        
        clearGhostText()
    }
    
    fun acceptPartialSuggestion(lines: Int = 1) {
        val suggestion = currentSuggestion ?: return
        val offset = suggestionOffset
        
        if (offset < 0 || suggestion.isEmpty()) return
        
        val linesToAccept = suggestion.lines().take(lines).joinToString("\n")
        
        WriteCommandAction.runWriteCommandAction(project, "Accept AI Suggestion", null, {
            editor.document.insertString(offset, linesToAccept)
            editor.caretModel.moveToOffset(offset + linesToAccept.length)
        })
        
        // Update ghost text with remaining suggestion
        val remainingLines = suggestion.lines().drop(lines)
        if (remainingLines.isNotEmpty()) {
            val newOffset = offset + linesToAccept.length
            showGhostText(remainingLines.joinToString("\n"), newOffset)
        } else {
            clearGhostText()
        }
    }
    
    fun rejectSuggestion() {
        clearGhostText()
    }
    
    private fun showGhostText(text: String, offset: Int) {
        clearGhostText()
        
        currentSuggestion = text
        suggestionOffset = offset
        
        ApplicationManager.getApplication().invokeLater {
            if (!editor.isDisposed) {
                ghostTextRenderer = GhostTextRenderer(text, offset)
                (editor as? EditorEx)?.let { editorEx ->
                    val highlighter = editorEx.markupModel.addRangeHighlighter(
                        offset,
                        offset,
                        HighlighterLayer.LAST + 1,
                        null,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    highlighter.customRenderer = ghostTextRenderer
                    ghostTextRenderer?.highlighter = highlighter
                }
            }
        }
    }
    
    private fun appendGhostText(text: String, baseOffset: Int) {
        val existingSuggestion = currentSuggestion ?: ""
        val newSuggestion = existingSuggestion + text
        showGhostText(newSuggestion, baseOffset)
    }
    
    private fun clearGhostText() {
        ApplicationManager.getApplication().invokeLater {
            ghostTextRenderer?.highlighter?.let { highlighter ->
                if (!editor.isDisposed) {
                    editor.markupModel.removeHighlighter(highlighter)
                }
            }
            ghostTextRenderer = null
            currentSuggestion = null
            suggestionOffset = -1
        }
    }
    
    private fun setupDocumentListener() {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // Clear ghost text on any document change
                if (currentSuggestion != null) {
                    clearGhostText()
                }
            }
        }, editor.disposable)
    }
    
    fun dispose() {
        clearGhostText()
        scope.cancel()
        currentJob?.cancel()
    }
    
    /**
     * Custom renderer for ghost text display
     */
    private inner class GhostTextRenderer(
        private val text: String,
        private val offset: Int
    ) : CustomHighlighterRenderer {
        var highlighter: RangeHighlighter? = null
        
        override fun paint(
            editor: Editor,
            highlighter: RangeHighlighter,
            g: Graphics
        ) {
            if (editor !is EditorImpl) return
            
            val point = editor.offsetToXY(offset)
            val lineHeight = editor.lineHeight
            val ascent = editor.ascent
            
            g.color = GHOST_TEXT_COLOR
            g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                .deriveFont(Font.ITALIC)
            
            var x = point.x
            var y = point.y + ascent
            
            text.lines().forEach { line ->
                g.drawString(line, x, y)
                y += lineHeight
                x = editor.indentGuidePositions[0] // Reset x for new lines
            }
        }
        
        override fun getTooltip(e: Editor, highlighter: RangeHighlighter, originalTooltip: String?): String? {
            return "Press Tab to accept, Esc to dismiss"
        }
    }
}

/**
 * Handler for accepting AI suggestions with Tab
 */
class AcceptAISuggestionHandler : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
        val inlineEditor = InlineAIEditor.getInstance(editor)
        if (inlineEditor?.currentSuggestion != null) {
            inlineEditor.acceptSuggestion()
        } else {
            // Fall back to default Tab behavior
            EditorActionHandler.getDefaultHandler("EditorTab")?.execute(editor, caret, dataContext)
        }
    }
    
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext): Boolean {
        return InlineAIEditor.getInstance(editor)?.currentSuggestion != null ||
                super.isEnabledForCaret(editor, caret, dataContext)
    }
}