// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.engine.dfaassist;

import com.intellij.codeInspection.dataFlow.*;
import com.intellij.codeInspection.dataFlow.value.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.sun.jdi.*;
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

class DebuggerDfaRunner extends DataFlowRunner {
  private static final Value NullConst = new Value() {
    @Override
    public VirtualMachine virtualMachine() { return null; }

    @Override
    public Type type() { return null; }

    @Override
    public String toString() { return "null"; }
  };
  private static final Set<String> COLLECTIONS_WITH_SIZE_FIELD =
    ContainerUtil.immutableSet(CommonClassNames.JAVA_UTIL_ARRAY_LIST,
                               CommonClassNames.JAVA_UTIL_LINKED_LIST,
                               CommonClassNames.JAVA_UTIL_HASH_MAP,
                               "java.util.TreeMap");
  private final @NotNull PsiCodeBlock myBody;
  private final @NotNull PsiStatement myStatement;
  private final @NotNull Project myProject;
  private final @Nullable ControlFlow myFlow;
  private final @Nullable DfaInstructionState myStartingState;
  private final long myModificationStamp;

  DebuggerDfaRunner(@NotNull PsiCodeBlock body, @NotNull PsiStatement statement, @NotNull StackFrame frame) {
    super(body.getParent() instanceof PsiClassInitializer ? ((PsiClassInitializer)body.getParent()).getContainingClass() : body);
    myBody = body;
    myStatement = statement;
    myProject = body.getProject();
    myFlow = buildFlow(myBody);
    myStartingState = getStartingState(frame);
    myModificationStamp = PsiModificationTracker.SERVICE.getInstance(myProject).getModificationCount();
  }
  
  boolean isValid() {
    return myStartingState != null;
  }

  RunnerResult interpret(InstructionVisitor visitor) {
    if (myFlow == null || myStartingState == null || 
        PsiModificationTracker.SERVICE.getInstance(myProject).getModificationCount() != myModificationStamp) {
      return RunnerResult.ABORTED;
    }
    return interpret(myBody, visitor, myFlow, Collections.singletonList(myStartingState));
  }

  @Nullable
  private DfaInstructionState getStartingState(StackFrame frame) {
    if (myFlow == null) return null;
    int offset = myFlow.getStartOffset(myStatement).getInstructionOffset();
    if (offset >= 0) {
      boolean changed = false;
      DfaMemoryState state = super.createMemoryState();
      PsiElementFactory psiFactory = JavaPsiFacade.getElementFactory(myProject);
      DfaValueFactory factory = getFactory();
      Map<Value, DfaVariableValue> canonicalMap = new HashMap<>();
      for (DfaValue dfaValue : factory.getValues().toArray(new DfaValue[0])) {
        if (dfaValue instanceof DfaVariableValue) {
          DfaVariableValue var = (DfaVariableValue)dfaValue;
          Value jdiValue = findJdiValue(frame, var);
          if (jdiValue != null) {
            DfaVariableValue canonicalVar = jdiValue instanceof ObjectReference ? canonicalMap.putIfAbsent(jdiValue, var) : null;
            if (canonicalVar != null) {
              state.applyCondition(var.eq(canonicalVar));
            } else {
              addToState(psiFactory, factory, state, var, jdiValue);
            }
            changed = true;
          }
        }
      }
      if (changed) {
        DfaVariableValue[] distinctValues =
          StreamEx.ofValues(canonicalMap).filter(v -> v.getType() != null && !DfaUtil.isComparedByEquals(v.getType()))
            .toArray(new DfaVariableValue[0]);
        EntryStream.ofPairs(distinctValues)
          .filterKeyValue((left, right) -> Objects.requireNonNull(left.getType()).isConvertibleFrom(Objects.requireNonNull(right.getType())))
          .limit(20) // avoid too complex state
          .forKeyValue((left, right) -> state.applyCondition(left.cond(RelationType.NE, right)));
        return new DfaInstructionState(myFlow.getInstruction(offset), state);
      }
    }
    return null;
  }

  @NotNull
  @Override
  protected DataFlowRunner.TimeStats createStatistics() {
    // Do not track time for DFA assist
    return new TimeStats(false);
  }

  @Nullable
  private Value findJdiValue(StackFrame frame, @NotNull DfaVariableValue var) {
    if (var.getQualifier() != null) {
      VariableDescriptor descriptor = var.getDescriptor();
      if (descriptor instanceof SpecialField) {
        // Special fields facts are applied from qualifiers
        return null;
      }
      Value qualifierValue = findJdiValue(frame, var.getQualifier());
      if (qualifierValue == null) return null;
      PsiModifierListOwner element = descriptor.getPsiElement();
      if (element instanceof PsiField && qualifierValue instanceof ObjectReference) {
        ReferenceType type = ((ObjectReference)qualifierValue).referenceType();
        PsiClass psiClass = ((PsiField)element).getContainingClass();
        if (psiClass != null && sameType(type.name(), psiClass)) {
          Field field = type.fieldByName(((PsiField)element).getName());
          if (field != null) {
            return wrap(((ObjectReference)qualifierValue).getValue(field));
          }
        }
      }
      if (descriptor instanceof DfaExpressionFactory.ArrayElementDescriptor && qualifierValue instanceof ArrayReference) {
        int index = ((DfaExpressionFactory.ArrayElementDescriptor)descriptor).getIndex();
        int length = ((ArrayReference)qualifierValue).length();
        if (index >= 0 && index < length) {
          return wrap(((ArrayReference)qualifierValue).getValue(index));
        }
      }
      return null;
    }
    if (var.getDescriptor() instanceof DfaExpressionFactory.AssertionDisabledDescriptor) {
      ReferenceType type = frame.location().method().declaringType();
      if (type instanceof ClassType) {
        Field field = type.fieldByName("$assertionsDisabled");
        if (field != null && field.isStatic() && field.isSynthetic()) {
          Value value = type.getValue(field);
          if (value instanceof BooleanValue) {
            return value;
          }
        }
      }
    }
    PsiModifierListOwner psi = var.getPsiVariable();
    if (psi instanceof PsiClass) {
      // this
      ObjectReference thisRef = frame.thisObject();
      if (thisRef != null) {
        if (PsiTreeUtil.getParentOfType(myBody, PsiClass.class) == psi) {
          return thisRef;
        }
        ReferenceType type = thisRef.referenceType();
        if (type instanceof ClassType) {
          for (Field field : type.allFields()) {
            if (field.isSynthetic() && field.isFinal() && !field.isStatic() &&
                field.name().matches("this\\$\\d+") && sameType(field.typeName(), (PsiClass)psi)) {
              return thisRef.getValue(field);
            }
          }
        }
      }
    }
    if (psi instanceof PsiLocalVariable || psi instanceof PsiParameter) {
      String varName = ((PsiVariable)psi).getName();
      try {
        LocalVariable variable = frame.visibleVariableByName(varName);
        if (variable != null) {
          return wrap(frame.getValue(variable));
        }
      }
      catch (AbsentInformationException ignore) {
      }
      ObjectReference thisRef = frame.thisObject();
      if (thisRef != null) {
        ReferenceType type = thisRef.referenceType();
        if (type instanceof ClassType) {
          for (Field field : type.allFields()) {
            if (field.isSynthetic() && field.isFinal() && !field.isStatic() &&
                field.name().startsWith("val$") && field.name().substring("val$".length()).equals(varName)) {
              return wrap(thisRef.getValue(field));
            }
          }
        }
      }
    }
    if (psi instanceof PsiField && psi.hasModifierProperty(PsiModifier.STATIC)) {
      PsiClass psiClass = ((PsiField)psi).getContainingClass();
      if (psiClass != null) {
        String name = psiClass.getQualifiedName();
        if (name != null) {
          ReferenceType type = ContainerUtil.getOnlyItem(frame.virtualMachine().classesByName(name));
          if (type != null) {
            Field field = type.fieldByName(((PsiField)psi).getName());
            if (field != null) {
              return wrap(type.getValue(field));
            }
          }
        }
      }
    }
    return null;
  }

  private static boolean sameType(String jdiName, PsiClass psiClass) {
    return jdiName.replace('$', '.').equals(psiClass.getQualifiedName());
  }

  private void addToState(PsiElementFactory psiFactory,
                          DfaValueFactory factory,
                          DfaMemoryState state, DfaVariableValue var,
                          Value jdiValue) {
    DfaConstValue val = getConstantValue(psiFactory, factory, jdiValue);
    if (val != null) {
      state.applyCondition(var.eq(val));
    }
    if (jdiValue instanceof ObjectReference) {
      ObjectReference ref = (ObjectReference)jdiValue;
      ReferenceType type = ref.referenceType();
      PsiType psiType = getPsiReferenceType(psiFactory, type);
      if (psiType == null) return;
      TypeConstraint exactType = TypeConstraint.exact(factory.createDfaType(psiType));
      String name = type.name();
      state.applyFact(var, DfaFactType.NULLABILITY, DfaNullability.NOT_NULL);
      state.applyFact(var, DfaFactType.TYPE_CONSTRAINT, exactType);
      if (jdiValue instanceof ArrayReference) {
        DfaValue dfaLength = SpecialField.ARRAY_LENGTH.createValue(factory, var);
        int jdiLength = ((ArrayReference)jdiValue).length();
        state.applyCondition(dfaLength.eq(factory.getInt(jdiLength)));
      }
      else if (TypeConversionUtil.isPrimitiveWrapper(name)) {
        setSpecialField(psiFactory, factory, state, var, ref, type, "value", SpecialField.UNBOX);
      }
      else if (COLLECTIONS_WITH_SIZE_FIELD.contains(name)) {
        setSpecialField(psiFactory, factory, state, var, ref, type, "size", SpecialField.COLLECTION_SIZE);
      }
      else if (name.startsWith("java.util.Collections$Empty")) {
        state.applyCondition(SpecialField.COLLECTION_SIZE.createValue(factory, var).eq(factory.getInt(0)));
      }
      else if (name.startsWith("java.util.Collections$Singleton")) {
        state.applyCondition(SpecialField.COLLECTION_SIZE.createValue(factory, var).eq(factory.getInt(1)));
      }
      else if (CommonClassNames.JAVA_UTIL_OPTIONAL.equals(name) && !(var.getDescriptor() instanceof SpecialField)) {
        setSpecialField(psiFactory, factory, state, var, ref, type, "value", SpecialField.OPTIONAL_VALUE);
      }
    }
  }

  @Nullable
  private PsiType getPsiReferenceType(PsiElementFactory psiFactory, ReferenceType jdiType) {
    Type componentType = jdiType;
    int depth = 0;
    while (componentType instanceof ArrayType) {
      try {
        componentType = ((ArrayType)componentType).componentType();
        depth++;
      }
      catch (ClassNotLoadedException e) {
        return null;
      }
    }
    PsiType psiType = psiFactory.createTypeByFQClassName(componentType.name(), myBody.getResolveScope());
    while (depth > 0) {
      psiType = psiType.createArrayType();
      depth--;
    }
    return psiType;
  }

  private void setSpecialField(PsiElementFactory psiFactory,
                               DfaValueFactory factory,
                               DfaMemoryState state,
                               DfaVariableValue dfaQualifier,
                               ObjectReference jdiQualifier,
                               ReferenceType type,
                               String fieldName,
                               SpecialField specialField) {
    Field value = type.fieldByName(fieldName);
    if (value != null) {
      DfaVariableValue dfaUnboxed = ObjectUtils.tryCast(specialField.createValue(factory, dfaQualifier), DfaVariableValue.class);
      Value jdiUnboxed = jdiQualifier.getValue(value);
      if (jdiUnboxed != null && dfaUnboxed != null) {
        addToState(psiFactory, factory, state, dfaUnboxed, jdiUnboxed);
      }
    }
  }

  @Nullable
  private DfaConstValue getConstantValue(PsiElementFactory psiFactory, DfaValueFactory factory, Value jdiValue) {
    if (jdiValue == NullConst) {
      return factory.getConstFactory().getNull();
    }
    if (jdiValue instanceof BooleanValue) {
      return factory.getBoolean(((BooleanValue)jdiValue).value());
    }
    if (jdiValue instanceof ShortValue || jdiValue instanceof CharValue ||
        jdiValue instanceof ByteValue || jdiValue instanceof IntegerValue) {
      return factory.getConstFactory().createFromValue(((PrimitiveValue)jdiValue).intValue(), PsiType.LONG);
    }
    if (jdiValue instanceof FloatValue) {
      return factory.getConstFactory().createFromValue(((FloatValue)jdiValue).floatValue(), PsiType.FLOAT);
    }
    if (jdiValue instanceof DoubleValue) {
      return factory.getConstFactory().createFromValue(((DoubleValue)jdiValue).doubleValue(), PsiType.DOUBLE);
    }
    if (jdiValue instanceof StringReference) {
      return factory.getConstFactory().createFromValue(
        ((StringReference)jdiValue).value(), psiFactory.createTypeByFQClassName(CommonClassNames.JAVA_LANG_STRING,
                                                                                myBody.getResolveScope()));
    }
    if (jdiValue instanceof ObjectReference) {
      ReferenceType type = ((ObjectReference)jdiValue).referenceType();
      String enumConstantName = getEnumConstantName((ObjectReference)jdiValue);
      if (enumConstantName != null) {
        PsiType psiType = getPsiReferenceType(psiFactory, type);
        if (psiType instanceof PsiClassType) {
          PsiClass enumClass = ((PsiClassType)psiType).resolve();
          if (enumClass != null && enumClass.isEnum()) {
            PsiField enumConst = enumClass.findFieldByName(enumConstantName, false);
            if (enumConst instanceof PsiEnumConstant) {
              return factory.getConstFactory().createFromValue(enumConst, psiType);
            }
          }
        }
      }
    }
    return null;
  }

  private static String getEnumConstantName(ObjectReference ref) {
    ReferenceType type = ref.referenceType();
    if (!(type instanceof ClassType) || !((ClassType)type).isEnum()) return null;
    ClassType superclass = ((ClassType)type).superclass();
    if (superclass == null) return null;
    if (!superclass.name().equals(CommonClassNames.JAVA_LANG_ENUM)) {
      superclass = superclass.superclass();
    }
    if (superclass == null || !superclass.name().equals(CommonClassNames.JAVA_LANG_ENUM)) return null;
    Field nameField = superclass.fieldByName("name");
    if (nameField == null) return null;
    Value nameValue = ref.getValue(nameField);
    return nameValue instanceof StringReference ? ((StringReference)nameValue).value() : null;
  }

  private static Value wrap(Value value) {
    return value == null ? NullConst : value;
  }
}
