// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.formatter;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.ide.highlighter.HighlighterFactory;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.PresentableEnumUtil;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.PythonLanguage;
import com.jetbrains.python.formatter.PyCodeStyleSettings.DictAlignment;
import com.jetbrains.python.highlighting.PyHighlighter;
import com.jetbrains.python.psi.LanguageLevel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;


public class PyOtherCodeStylePanel extends CodeStyleAbstractPanel {

  private JPanel myPanel;
  private JBCheckBox myAddTrailingBlankLineCheckbox;
  private JBCheckBox myUseContinuationIndentForParameters;
  private JBCheckBox myUseContinuationIndentForArguments;
  private JBCheckBox myUseContinuationIndentForCollectionsAndComprehensions;
  private ComboBox<DictAlignment> myDictAlignmentCombo;
  private JPanel myPreviewPanel;
  private JBCheckBox myFormatInjectedFragments;
  private JBCheckBox myAddIndentInsideInjections;

  protected PyOtherCodeStylePanel(CodeStyleSettings settings) {
    super(PythonLanguage.getInstance(), null, settings);
    addPanelToWatch(myPanel);
    installPreviewPanel(myPreviewPanel);

    PresentableEnumUtil.fill(myDictAlignmentCombo, DictAlignment.class);

    myAddTrailingBlankLineCheckbox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        somethingChanged();
      }
    });
    
    myUseContinuationIndentForParameters.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        somethingChanged();
      }
    });

    myUseContinuationIndentForArguments.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        somethingChanged();
      }
    });

    myFormatInjectedFragments.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        somethingChanged();
      }
    });

    myAddIndentInsideInjections.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        somethingChanged();
      }
    });
  }

  @Override
  protected EditorHighlighter createHighlighter(@NotNull EditorColorsScheme scheme) {
    return HighlighterFactory.createHighlighter(new PyHighlighter(LanguageLevel.PYTHON26), scheme);
  }

  @Override
  protected int getRightMargin() {
    return 80;
  }

  @Override
  protected @NotNull FileType getFileType() {
    return PythonFileType.INSTANCE;
  }

  @Override
  protected String getPreviewText() {
    return PREVIEW;
  }

  @Override
  protected void resetImpl(@NotNull CodeStyleSettings settings) {
    final PyCodeStyleSettings pySettings = getCustomSettings(settings);
    for (DictAlignment alignment : DictAlignment.values()) {
      if (pySettings.DICT_ALIGNMENT == alignment.asInt()) {
        myDictAlignmentCombo.setSelectedItem(alignment);
        break;
      }
    }
    myAddTrailingBlankLineCheckbox.setSelected(pySettings.BLANK_LINE_AT_FILE_END);
    myUseContinuationIndentForParameters.setSelected(pySettings.USE_CONTINUATION_INDENT_FOR_PARAMETERS);
    myUseContinuationIndentForArguments.setSelected(pySettings.USE_CONTINUATION_INDENT_FOR_ARGUMENTS);
    myUseContinuationIndentForCollectionsAndComprehensions.setSelected(pySettings.USE_CONTINUATION_INDENT_FOR_COLLECTION_AND_COMPREHENSIONS);
    myFormatInjectedFragments.setSelected(pySettings.FORMAT_INJECTED_FRAGMENTS);
    myAddIndentInsideInjections.setSelected(pySettings.ADD_INDENT_INSIDE_INJECTIONS);
  }

  @Override
  public void apply(@NotNull CodeStyleSettings settings) {
    final PyCodeStyleSettings customSettings = getCustomSettings(settings);
    customSettings.DICT_ALIGNMENT = getDictAlignmentAsInt();
    customSettings.BLANK_LINE_AT_FILE_END = ensureTrailingBlankLine();
    customSettings.USE_CONTINUATION_INDENT_FOR_PARAMETERS = useContinuationIndentForParameters();
    customSettings.USE_CONTINUATION_INDENT_FOR_ARGUMENTS = useContinuationIndentForArguments();
    customSettings.USE_CONTINUATION_INDENT_FOR_COLLECTION_AND_COMPREHENSIONS = useContinuationIndentForCollectionLiterals();
    customSettings.FORMAT_INJECTED_FRAGMENTS = formatInjectedFragments();
    customSettings.ADD_INDENT_INSIDE_INJECTIONS = addIndentInsideInjections();
  }

  @Override
  public boolean isModified(CodeStyleSettings settings) {
    final PyCodeStyleSettings customSettings = getCustomSettings(settings);
    return customSettings.DICT_ALIGNMENT != getDictAlignmentAsInt() ||
           customSettings.BLANK_LINE_AT_FILE_END != ensureTrailingBlankLine() || 
           customSettings.USE_CONTINUATION_INDENT_FOR_PARAMETERS != useContinuationIndentForParameters() ||
           customSettings.USE_CONTINUATION_INDENT_FOR_ARGUMENTS != useContinuationIndentForArguments() ||
           customSettings.USE_CONTINUATION_INDENT_FOR_COLLECTION_AND_COMPREHENSIONS != useContinuationIndentForCollectionLiterals() ||
           customSettings.FORMAT_INJECTED_FRAGMENTS != formatInjectedFragments() ||
           customSettings.ADD_INDENT_INSIDE_INJECTIONS != addIndentInsideInjections();
  }

  @Override
  public JComponent getPanel() {
    return myPanel;
  }

  private static @NotNull PyCodeStyleSettings getCustomSettings(@NotNull CodeStyleSettings settings) {
    return settings.getCustomSettings(PyCodeStyleSettings.class);
  }

  private int getDictAlignmentAsInt() {
    return ((DictAlignment)myDictAlignmentCombo.getSelectedItem()).asInt();
  }

  private boolean ensureTrailingBlankLine() {
    return myAddTrailingBlankLineCheckbox.isSelected();
  }

  private boolean useContinuationIndentForParameters() {
    return myUseContinuationIndentForParameters.isSelected();
  }

  private boolean useContinuationIndentForArguments() {
    return myUseContinuationIndentForArguments.isSelected();
  }

  private boolean formatInjectedFragments() {
    return myFormatInjectedFragments.isSelected();
  }

  private boolean addIndentInsideInjections() {
    return myAddIndentInsideInjections.isSelected();
  }

  protected boolean useContinuationIndentForCollectionLiterals() {
    return myUseContinuationIndentForCollectionsAndComprehensions.isSelected();
  }

  public static final String PREVIEW = """
    x = max(
        1,
        2,
        3)

    def foo_decl(
        a,
        b,
        c):
        pass

    {
        "green": 42,
        "eggs and ham": -0.0e0
    }

    odds = [
        num for num in range(42)
        if num % 2 != 0\s
    ]""";
}
