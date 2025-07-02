package com.intellij.ai.chat.ui

import com.intellij.ai.chat.ChatSession
import com.intellij.ai.chat.ChatSessionManager
import com.intellij.ai.core.model.CompletionRequest
import com.intellij.ai.core.model.Message
import com.intellij.ai.core.model.Role
import com.intellij.ai.core.service.AIServiceManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.DefaultCaret

/**
 * Factory for creating the AI chat tool window
 */
class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = toolWindow.contentManager.factory
        val panel = ChatPanel(project)
        val content = contentFactory.createContent(panel, "Chat", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Main chat panel UI
 */
class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), DataProvider {
    companion object {
        private val LOG = logger<ChatPanel>()
    }
    
    private val chatDisplay = ChatDisplayPanel()
    private val inputField = createInputField()
    private val sessionManager = ChatSessionManager.getInstance(project)
    private var currentSession: ChatSession = sessionManager.createSession()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    init {
        setupUI()
        setupActions()
    }
    
    private fun setupUI() {
        // Create main panel
        val mainPanel = JPanel(BorderLayout())
        
        // Add chat display
        val scrollPane = JBScrollPane(chatDisplay).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        
        // Add input panel
        val inputPanel = createInputPanel()
        mainPanel.add(inputPanel, BorderLayout.SOUTH)
        
        setContent(mainPanel)
    }
    
    private fun createInputField(): JBTextArea {
        return JBTextArea().apply {
            rows = 3
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
            font = UIUtil.getEditorPaneFont()
            
            // Handle Ctrl+Enter for sending
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                        sendMessage()
                        e.consume()
                    }
                }
            })
        }
    }
    
    private fun createInputPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(8)
        )
        
        // Input field with scroll
        val inputScroll = JBScrollPane(inputField).apply {
            border = JBUI.Borders.customLine(JBColor.border())
            preferredSize = JBUI.size(0, 80)
        }
        panel.add(inputScroll, BorderLayout.CENTER)
        
        // Send button
        val sendButton = JButton("Send").apply {
            addActionListener { sendMessage() }
        }
        
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(sendButton)
            add(Box.createVerticalGlue())
        }
        panel.add(buttonPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun setupActions() {
        val actionGroup = DefaultActionGroup().apply {
            add(NewChatAction())
            add(ClearChatAction())
            addSeparator()
            add(ExportChatAction())
        }
        
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("AIChatToolbar", actionGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
    }
    
    private fun sendMessage() {
        val message = inputField.text.trim()
        if (message.isEmpty()) return
        
        // Clear input
        inputField.text = ""
        
        // Add user message to display
        chatDisplay.addMessage(ChatMessage(Role.USER, message))
        
        // Add to session
        currentSession.addMessage(Message(Role.USER, message))
        
        // Send to AI
        scope.launch {
            try {
                chatDisplay.showTypingIndicator()
                
                val aiService = AIServiceManager.getInstance()
                val provider = aiService.getActiveProvider()
                
                if (provider == null) {
                    chatDisplay.addMessage(
                        ChatMessage(Role.ASSISTANT, "No AI provider configured. Please configure an AI provider in settings.")
                    )
                    return@launch
                }
                
                val request = CompletionRequest(
                    messages = currentSession.getMessages(),
                    temperature = 0.7f,
                    systemPrompt = """
                        You are an AI coding assistant integrated into IntelliJ IDEA.
                        Provide helpful, accurate, and concise responses about programming.
                        When providing code examples, use proper syntax highlighting.
                        Be friendly and professional.
                    """.trimIndent()
                )
                
                if (provider.supportsStreaming) {
                    val assistantMessage = ChatMessage(Role.ASSISTANT, "")
                    chatDisplay.addMessage(assistantMessage)
                    
                    aiService.submitRequest(AIServiceManager.RequestPriority.HIGH) { aiProvider ->
                        aiProvider.completeStreaming(request).collectLatest { chunk ->
                            withContext(Dispatchers.Main) {
                                assistantMessage.appendContent(chunk.delta)
                                chatDisplay.updateMessage(assistantMessage)
                            }
                        }
                    }
                    
                    currentSession.addMessage(Message(Role.ASSISTANT, assistantMessage.content))
                } else {
                    val response = aiService.submitRequest(AIServiceManager.RequestPriority.HIGH) { aiProvider ->
                        aiProvider.complete(request)
                    }
                    
                    chatDisplay.addMessage(ChatMessage(Role.ASSISTANT, response.content))
                    currentSession.addMessage(Message(Role.ASSISTANT, response.content))
                }
            } catch (e: Exception) {
                LOG.error("Failed to send message", e)
                chatDisplay.addMessage(
                    ChatMessage(Role.ASSISTANT, "Error: ${e.message}")
                )
            } finally {
                chatDisplay.hideTypingIndicator()
            }
        }
    }
    
    override fun getData(dataId: String): Any? {
        return when (dataId) {
            CommonDataKeys.PROJECT.name -> project
            else -> null
        }
    }
    
    // Action classes
    private inner class NewChatAction : DumbAwareAction(
        "New Chat",
        "Start a new chat session",
        AllIcons.General.Add
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            currentSession = sessionManager.createSession()
            chatDisplay.clear()
        }
    }
    
    private inner class ClearChatAction : DumbAwareAction(
        "Clear Chat",
        "Clear current chat",
        AllIcons.Actions.GC
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            chatDisplay.clear()
            currentSession.clearMessages()
        }
    }
    
    private inner class ExportChatAction : DumbAwareAction(
        "Export Chat",
        "Export chat as markdown",
        AllIcons.ToolbarDecorator.Export
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            // TODO: Implement export functionality
        }
    }
}

/**
 * Panel for displaying chat messages
 */
class ChatDisplayPanel : JPanel() {
    private val messages = mutableListOf<ChatMessage>()
    private var typingIndicator: JLabel? = null
    
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getEditorPaneBackground()
        border = JBUI.Borders.empty(8)
    }
    
    fun addMessage(message: ChatMessage) {
        messages.add(message)
        
        val messagePanel = MessagePanel(message)
        add(messagePanel)
        add(Box.createVerticalStrut(8))
        
        revalidate()
        repaint()
        
        // Scroll to bottom
        SwingUtilities.invokeLater {
            scrollRectToVisible(bounds)
        }
    }
    
    fun updateMessage(message: ChatMessage) {
        // Find and update the message panel
        components.filterIsInstance<MessagePanel>()
            .find { it.message == message }
            ?.updateContent()
        
        revalidate()
        repaint()
    }
    
    fun showTypingIndicator() {
        if (typingIndicator == null) {
            typingIndicator = JLabel("AI is typing...").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.style or java.awt.Font.ITALIC)
            }
            add(typingIndicator)
            revalidate()
            repaint()
        }
    }
    
    fun hideTypingIndicator() {
        typingIndicator?.let {
            remove(it)
            typingIndicator = null
            revalidate()
            repaint()
        }
    }
    
    fun clear() {
        messages.clear()
        removeAll()
        revalidate()
        repaint()
    }
}

/**
 * Panel for displaying a single message
 */
class MessagePanel(val message: ChatMessage) : JPanel() {
    private val contentLabel = JTextPane()
    
    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(4)
        isOpaque = true
        
        background = when (message.role) {
            Role.USER -> UIUtil.getPanelBackground()
            Role.ASSISTANT -> JBColor(
                UIUtil.getPanelBackground().brighter(),
                UIUtil.getPanelBackground().darker()
            )
            else -> UIUtil.getPanelBackground()
        }
        
        // Role label
        val roleLabel = JLabel(
            when (message.role) {
                Role.USER -> "You"
                Role.ASSISTANT -> "AI"
                else -> "System"
            }
        ).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        add(roleLabel, BorderLayout.NORTH)
        
        // Content
        contentLabel.apply {
            isEditable = false
            contentType = "text/plain"
            text = message.content
            background = this@MessagePanel.background
            border = null
        }
        add(contentLabel, BorderLayout.CENTER)
    }
    
    fun updateContent() {
        contentLabel.text = message.content
    }
}

/**
 * Data class for chat messages
 */
data class ChatMessage(
    val role: Role,
    var content: String
) {
    fun appendContent(text: String) {
        content += text
    }
}