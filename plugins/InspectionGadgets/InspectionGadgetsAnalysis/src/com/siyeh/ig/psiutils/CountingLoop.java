// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.siyeh.ig.psiutils;

import com.intellij.codeInspection.dataFlow.value.RelationType;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.siyeh.ig.psiutils.VariableAccessUtils.CountingLoopType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.util.ObjectUtils.tryCast;

/**
 * Represents a loop of form {@code for(int/long counter = initializer; counter </<= bound; counter++/--)}
 *
 * @author Tagir Valeev
 */
public final class CountingLoop {
  final @NotNull PsiLocalVariable myCounter;
  final @NotNull PsiLoopStatement myLoop;
  final @NotNull PsiExpression myInitializer;
  final @NotNull PsiExpression myBound;
  final boolean myIncluding;
  final boolean myDescending;
  final boolean myMayOverflow;

  private CountingLoop(@NotNull PsiLoopStatement loop,
                       @NotNull PsiLocalVariable counter,
                       @NotNull PsiExpression initializer,
                       @NotNull PsiExpression bound,
                       boolean including,
                       boolean descending,
                       boolean mayOverflow) {
    myInitializer = initializer;
    myCounter = counter;
    myLoop = loop;
    myBound = bound;
    myIncluding = including;
    myDescending = descending;
    myMayOverflow = mayOverflow;
  }

  /**
   * @return loop counter variable
   */
  @NotNull
  public PsiLocalVariable getCounter() {
    return myCounter;
  }

  /**
   * @return loop statement
   */
  @NotNull
  public PsiLoopStatement getLoop() {
    return myLoop;
  }

  /**
   * @return counter variable initial value
   */
  @NotNull
  public PsiExpression getInitializer() {
    return myInitializer;
  }

  /**
   * @return loop bound
   */
  @NotNull
  public PsiExpression getBound() {
    return myBound;
  }

  /**
   * @return true if bound is including
   */
  public boolean isIncluding() {
    return myIncluding;
  }

  /**
   * @return true if the loop is descending
   */
  public boolean isDescending() {
    return myDescending;
  }

  /**
   * @return true if the loop variable may experience integer overflow before reaching the bound, 
   * like for(int i = 10; i != -10; i++) will go through MAX_VALUE and MIN_VALUE. 
   */
  public boolean mayOverflow() {
    return myMayOverflow;
  }

  @Nullable
  public static CountingLoop from(PsiForStatement forStatement) {
    // check that initialization is for(int/long i = <initial_value>;...;...)
    PsiDeclarationStatement initialization = tryCast(forStatement.getInitialization(), PsiDeclarationStatement.class);
    if (initialization == null) return null;
    PsiElement[] declaredElements = initialization.getDeclaredElements();
    if (declaredElements.length != 1) return null;
    PsiLocalVariable counter = tryCast(declaredElements[0], PsiLocalVariable.class);
    if(counter == null) return null;
    PsiType counterType = counter.getType();
    if(!counterType.equals(PsiType.INT) && !counterType.equals(PsiType.LONG)) return null;

    PsiExpression initializer = PsiUtil.skipParenthesizedExprDown(counter.getInitializer());
    if(initializer == null) return null;

    // check that increment is like for(...;...;i++)
    CountingLoopType countingLoopType = VariableAccessUtils.evaluateCountingLoopType(counter, forStatement.getUpdate());
    if (countingLoopType == null) return null;
    boolean descending = countingLoopType == CountingLoopType.DEC;

    // check that condition is like for(...;i<bound;...) or for(...;i<=bound;...)
    PsiBinaryExpression condition = tryCast(PsiUtil.skipParenthesizedExprDown(forStatement.getCondition()), PsiBinaryExpression.class);
    if(condition == null) return null;
    IElementType type = condition.getOperationTokenType();
    boolean closed = false;
    RelationType relationType = RelationType.fromElementType(type);
    if (relationType == null || !relationType.isInequality()) return null;
    if (relationType.isSubRelation(RelationType.EQ)) {
      closed = true;
    }
    if (descending) {
      relationType = relationType.getFlipped();
      assert relationType != null;
    }
    PsiExpression bound = ExpressionUtils.getOtherOperand(condition, counter);
    if (bound == null) return null;
    if (bound == condition.getLOperand()) {
      relationType = relationType.getFlipped();
      assert relationType != null;
    }
    if (!relationType.isSubRelation(RelationType.LT)) return null;
    if(!TypeConversionUtil.areTypesAssignmentCompatible(counterType, bound)) return null;
    if(VariableAccessUtils.variableIsAssigned(counter, forStatement.getBody())) return null;
    return new CountingLoop(forStatement, counter, initializer, bound, closed, descending, relationType == RelationType.NE);
  }
}
