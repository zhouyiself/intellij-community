/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.unsorted;

import com.intellij.codeInspection.*;
import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.PropertiesImplUtil;
import com.intellij.lang.properties.ResourceBundle;
import com.intellij.lang.properties.psi.PropertiesElementFactory;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.lang.properties.psi.PropertiesList;
import com.intellij.lang.properties.psi.codeStyle.PropertiesCodeStyleSettings;
import com.intellij.lang.properties.psi.impl.PropertiesFileImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Dmitry Batkovich
 */
public class AlphaUnsortedPropertiesFileInspection extends LocalInspectionTool {
  private static final Logger LOG = Logger.getInstance(AlphaUnsortedPropertiesFileInspection.class);
  private static final String MESSAGE_TEMPLATE_WHOLE_RESOURCE_BUNDLE = "Property keys of resource bundle '%s' aren't alphabetically sorted";

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
    return new PsiElementVisitor() {
      @Override
      public void visitFile(@NotNull PsiFile file) {
        final PropertiesFile propertiesFile = PropertiesImplUtil.getPropertiesFile(file);
        if (!(propertiesFile instanceof PropertiesFileImpl)) {
          return;
        }
        for (AlphaUnsortedPropertiesFileInspectionSuppressor filter : AlphaUnsortedPropertiesFileInspectionSuppressor.EP_NAME.getExtensions()) {
          if (filter.suppressInspectionFor(propertiesFile)) {
            return;
          }
        }
        final ResourceBundle resourceBundle = propertiesFile.getResourceBundle();
        final String resourceBundleBaseName = resourceBundle.getBaseName();
        if (!isResourceBundleAlphaSortedExceptOneFile(resourceBundle, propertiesFile)) {
          final List<PropertiesFile> allFiles = resourceBundle.getPropertiesFiles();
          holder.registerProblem(file, String.format(MESSAGE_TEMPLATE_WHOLE_RESOURCE_BUNDLE, resourceBundleBaseName),
                                 ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                                 new PropertiesSorterQuickFix(allFiles.toArray(new PropertiesFile[0])));
          return;
        }
        if (!propertiesFile.isAlphaSorted()) {
          holder.registerProblem(file, "Properties file is alphabetically unsorted", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new PropertiesSorterQuickFix(
            propertiesFile));
        }
      }
    };
  }

  private static boolean isResourceBundleAlphaSortedExceptOneFile(@NotNull final ResourceBundle resourceBundle,
                                                                  @NotNull final PropertiesFile exceptedFile) {
    for (PropertiesFile file : resourceBundle.getPropertiesFiles()) {
      if (!(file instanceof PropertiesFileImpl)) {
        return true;
      }
      if (!file.equals(exceptedFile) && !file.isAlphaSorted()) {
        return false;
      }
    }
    return true;
  }

  private static class PropertiesSorterQuickFix implements LocalQuickFix {
    private final PropertiesFile[] myFilesToSort;

    private PropertiesSorterQuickFix(PropertiesFile... toSort) {
      myFilesToSort = toSort;
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return "Sort resource bundle files";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final boolean force = myFilesToSort.length == 1;
      for (PropertiesFile file : myFilesToSort) {
        if (!force && file.isAlphaSorted()) {
          continue;
        }
        sortPropertiesFile(file);
      }
    }
  }

  private static void sortPropertiesFile(final PropertiesFile file) {
    final List<IProperty> properties = new ArrayList<>(file.getProperties());

    Collections.sort(properties, (p1, p2) -> Comparing.compare(p1.getKey(), p2.getKey(), String.CASE_INSENSITIVE_ORDER));
    final char delimiter = PropertiesCodeStyleSettings.getInstance(file.getProject()).getDelimiter();
    final StringBuilder rawText = new StringBuilder();
    for (int i = 0; i < properties.size(); i++) {
      IProperty property = properties.get(i);
      final String value = property.getValue();
      final String commentAboveProperty = property.getDocCommentText();
      if (commentAboveProperty != null) {
        rawText.append(commentAboveProperty).append("\n");
      }
      final String key = property.getKey();
      final String propertyText;
      if (key != null) {
        propertyText = PropertiesElementFactory.getPropertyText(key, value != null ? value : "", delimiter, null, false);
        rawText.append(propertyText);
        if (i != properties.size() - 1) {
          rawText.append("\n");
        }
      }
    }

    final PropertiesFile fakeFile = PropertiesElementFactory.createPropertiesFile(file.getProject(), rawText.toString());

    final PropertiesList propertiesList = PsiTreeUtil.findChildOfType(file.getContainingFile(), PropertiesList.class);
    LOG.assertTrue(propertiesList != null);
    final PropertiesList fakePropertiesList = PsiTreeUtil.findChildOfType(fakeFile.getContainingFile(), PropertiesList.class);
    LOG.assertTrue(fakePropertiesList != null);
    propertiesList.replace(fakePropertiesList);
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return "Alphabetically Unsorted Properties File or Resource Bundle";
  }

  @Override
  @NotNull
  public String getShortName() {
    return "AlphaUnsortedPropertiesFile";
  }
}
