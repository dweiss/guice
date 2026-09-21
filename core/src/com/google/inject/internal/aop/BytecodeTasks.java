/*
 * Copyright (C) 2020 Google Inc.
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

package com.google.inject.internal.aop;

import static java.lang.constant.ConstantDescs.CD_Object;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodType;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Utility methods to generate common bytecode tasks.
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
final class BytecodeTasks {
  private BytecodeTasks() {}

  /** Returns the symbolic descriptor of the given class. */
  public static ClassDesc classDesc(Class<?> type) {
    return ClassDesc.ofDescriptor(type.descriptorString());
  }

  /** Returns the symbolic descriptor of the given constructor/method. */
  public static MethodTypeDesc methodType(Executable member) {
    Class<?> returnType = member instanceof Method method ? method.getReturnType() : void.class;
    return MethodTypeDesc.ofDescriptor(
        MethodType.methodType(returnType, member.getParameterTypes()).descriptorString());
  }

  /** Returns the symbolic descriptors of the exceptions declared by the given constructor/method. */
  public static List<ClassDesc> exceptionTypes(Executable member) {
    return Arrays.stream(member.getExceptionTypes()).map(BytecodeTasks::classDesc).toList();
  }

  /** Packs local arguments into an argument array on the Java stack. */
  public static void packArguments(CodeBuilder code, Class<?>[] parameterTypes) {
    code.loadConstant(parameterTypes.length);
    code.anewarray(CD_Object);
    int parameterIndex = 0;
    int slot = 1;
    for (Class<?> parameterType : parameterTypes) {
      code.dup();
      code.loadConstant(parameterIndex++);
      slot += loadArgument(code, parameterType, slot);
      if (parameterType.isPrimitive()) {
        box(code, parameterType);
      }
      code.aastore();
    }
  }

  /** Unpacks an array of arguments and pushes them onto the Java stack. */
  public static void unpackArguments(CodeBuilder code, Class<?>[] parameterTypes) {
    int parameterIndex = 0;
    for (Class<?> parameterType : parameterTypes) {
      // invoker pattern means we can safely assume array is the second local argument
      code.aload(2);
      code.loadConstant(parameterIndex++);
      code.aaload();
      if (parameterType.isPrimitive()) {
        unbox(code, parameterType);
      } else {
        code.checkcast(classDesc(parameterType));
      }
    }
  }

  /** Loads a local argument onto the Java stack and returns the size of the argument. */
  public static int loadArgument(CodeBuilder code, Class<?> parameterType, int slot) {
    TypeKind kind = TypeKind.from(parameterType);
    code.loadLocal(kind, slot);
    return kind.slotSize();
  }

  /** Boxes a primitive value on the Java stack. */
  public static void box(CodeBuilder code, Class<?> primitiveType) {
    ClassDesc wrapper = wrapper(primitiveType);
    code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, classDesc(primitiveType)));
  }

  /** Unboxes a boxed value on the Java stack. */
  public static void unbox(CodeBuilder code, Class<?> primitiveType) {
    ClassDesc wrapper = wrapper(primitiveType);
    code.checkcast(wrapper);
    code.invokevirtual(
        wrapper, primitiveType.getName() + "Value", MethodTypeDesc.of(classDesc(primitiveType)));
  }

  /** Returns the symbolic descriptor of the wrapper class for the given primitive type. */
  private static ClassDesc wrapper(Class<?> primitiveType) {
    return classDesc(MethodType.methodType(primitiveType).wrap().returnType());
  }
}
