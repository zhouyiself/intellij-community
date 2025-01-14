/*
 * Copyright 2006-2019 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ig.bugs;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.PsiReplacementUtil;
import com.siyeh.ig.callMatcher.CallMatcher;
import com.siyeh.ig.psiutils.ExpressionUtils;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ReplaceAllDotInspection extends BaseInspection {

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("replace.all.dot.display.name");
  }

  @Override
  @NotNull
  public String buildErrorString(Object... infos) {
    final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)infos[0];
    final String methodName = methodCallExpression.getMethodExpression().getReferenceName();
    return InspectionGadgetsBundle.message("replace.all.dot.problem.descriptor", methodName);
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Pattern(VALID_ID_PATTERN)
  @NotNull
  @Override
  public String getID() {
    return "SuspiciousRegexArgument";
  }

  @Nullable
  @Override
  public String getAlternativeID() {
    return "ReplaceAllDot";
  }

  @Nullable
  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    final PsiExpression expression = (PsiExpression)infos[1];
    if (!(expression instanceof PsiLiteralExpression)) {
      return null;
    }
    return new EscapeCharacterFix();
  }

  private static class EscapeCharacterFix extends InspectionGadgetsFix {

    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    @Override
    public String getFamilyName() {
      return InspectionGadgetsBundle.message("replace.all.dot.quickfix");
    }

    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      if (!(element instanceof PsiExpression)) {
        return;
      }
      final PsiExpression expression = (PsiExpression)element;
      final String text = expression.getText();

      PsiReplacementUtil.replaceExpression(expression, text.substring(0, 1) + "\\\\" + text.substring(1));
    }
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new ReplaceAllDotVisitor();
  }

  private static class ReplaceAllDotVisitor extends BaseInspectionVisitor {

    private static final CallMatcher.Simple MATCHER = CallMatcher.instanceCall(CommonClassNames.JAVA_LANG_STRING, "replaceAll", "split");

    @Override
    public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
      super.visitMethodCallExpression(expression);
      if (!MATCHER.test(expression)) {
        return;
      }
      final PsiExpression argument = expression.getArgumentList().getExpressions()[0];
      if (!PsiUtil.isConstantExpression(argument) || !ExpressionUtils.hasStringType(argument)) {
        return;
      }
      final String value = (String)ExpressionUtils.computeConstantExpression(argument);
      if (!isRegexMetaChar(value)) {
        return;
      }
      registerError(argument, expression, argument);
    }

    private static boolean isRegexMetaChar(String s) {
      return s != null && s.length() == 1 && ".$|()[{^?*+\\".contains(s);
    }
  }
}