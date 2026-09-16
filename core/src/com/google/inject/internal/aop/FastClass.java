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

import static com.google.inject.internal.BytecodeGen.FASTCLASS_BY_GUICE_MARKER;
import static com.google.inject.internal.aop.BytecodeTasks.box;
import static com.google.inject.internal.aop.BytecodeTasks.classDesc;
import static com.google.inject.internal.aop.BytecodeTasks.methodType;
import static com.google.inject.internal.aop.BytecodeTasks.unpackArguments;
import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.lang.classfile.ClassFile.ACC_PRIVATE;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.CLASS_INIT_NAME;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.function.BiFunction;

/**
 * Generates fast-classes.
 *
 * <p>Each fast-class has a single constructor that takes an index. It also has an instance method
 * that takes a context object and an array of argument objects which it combines with the index to
 * call the shared static trampoline. Each fast-class instance therefore acts like a bound invoker
 * to the appropriate constructor or method of the host class.
 *
 * <p>A handle to the fast-class constructor is used as the invoker table, mapping index to invoker.
 *
 * <p>Fast-classes have the following pseudo-Java structure:
 *
 * <pre>
 * public final class HostClass$$FastClassByGuice
 *   implements BiFunction // each fast-class instance represents a bound invoker
 * {
 *   private final int index; // the bound trampoline index
 *
 *   public HostClass$$FastClassByGuice(int index) {
 *     this.index = index;
 *   }
 *
 *   public Object apply(Object context, Object args) {
 *     return GUICE$TRAMPOLINE(index, context, (Object[]) args);
 *   }
 *
 *   public static Object GUICE$TRAMPOLINE(int index, Object context, Object[] args) {
 *     switch (index) {
 *       case 0: {
 *         return new HostClass(...);
 *       }
 *       case 1: {
 *         return ((HostClass) context).instanceMethod(...);
 *       }
 *       case 2: {
 *         return HostClass.staticMethod(...);
 *       }
 *     }
 *     return null;
 *   }
 * }
 * </pre>
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
final class FastClass extends AbstractGlueGenerator {

  private static final ClassDesc FAST_CLASS_API = classDesc(BiFunction.class);

  private static final String INVOKERS_NAME = "GUICE$INVOKERS";

  private static final MethodTypeDesc INDEX_TO_INVOKER_METHOD_TYPE =
      MethodTypeDesc.of(FAST_CLASS_API, CD_int);

  private static final MethodTypeDesc RAW_INVOKER_TYPE =
      MethodTypeDesc.of(CD_Object, CD_Object, CD_Object);

  private final boolean hostIsInterface;

  FastClass(Class<?> hostClass) {
    super(hostClass, FASTCLASS_BY_GUICE_MARKER);
    hostIsInterface = hostClass.isInterface();
  }

  @Override
  protected byte[] generateGlue(Collection<Executable> members) {
    return ClassFile.of()
        .build(
            proxyType,
            cb -> {
              // target Java8 because that's all we need for the generated trampoline code
              cb.withVersion(ClassFile.JAVA_8_VERSION, 0);
              cb.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER);
              cb.withSuperclass(CD_Object);
              cb.withInterfaceSymbols(FAST_CLASS_API);
              cb.with(SourceFileAttribute.of(GENERATED_SOURCE));

              // this shared field contains the constructor handle adapted to look like an invoker
              // table
              cb.withField(INVOKERS_NAME, CD_MethodHandle, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);

              setupInvokerTable(cb);

              cb.withField("index", CD_int, ACC_PRIVATE | ACC_FINAL);

              // fast-class constructor that takes an index and binds it
              cb.withMethodBody(
                  INIT_NAME,
                  MethodTypeDesc.of(CD_void, CD_int),
                  ACC_PUBLIC,
                  code -> {
                    code.aload(0);
                    code.dup();
                    code.invokespecial(CD_Object, INIT_NAME, MTD_void);
                    code.iload(1);
                    code.putfield(proxyType, "index", CD_int);
                    code.return_();
                  });

              // fast-class invoker function that takes a context object and argument array
              cb.withMethodBody(
                  "apply",
                  RAW_INVOKER_TYPE,
                  ACC_PUBLIC,
                  code -> {
                    // combine bound index with context object and argument array
                    code.aload(0);
                    code.getfield(proxyType, "index", CD_int);
                    code.aload(1);
                    code.aload(2);
                    code.checkcast(CD_Object.arrayType());
                    // call into the shared trampoline
                    code.invokestatic(proxyType, TRAMPOLINE_NAME, TRAMPOLINE_TYPE);
                    code.areturn();
                  });

              generateTrampoline(cb, members);
            });
  }

  /** Generate static initializer to setup invoker table based on the fast-class constructor. */
  private void setupInvokerTable(ClassBuilder cb) {
    cb.withMethodBody(
        CLASS_INIT_NAME,
        MTD_void,
        ACC_PRIVATE | ACC_STATIC,
        code -> {
          code.loadConstant(MethodHandleDesc.ofConstructor(proxyType, CD_int));

          // adapt constructor handle to make it look like an invoker table (int -> BiFunction)
          code.loadConstant(INDEX_TO_INVOKER_METHOD_TYPE);
          code.invokevirtual(
              CD_MethodHandle, "asType", MethodTypeDesc.of(CD_MethodHandle, CD_MethodType));

          code.putstatic(proxyType, INVOKERS_NAME, CD_MethodHandle);

          code.return_();
        });
  }

  @Override
  protected void generateConstructorInvoker(CodeBuilder code, Constructor<?> constructor) {
    code.new_(hostType);
    code.dup();

    // fast-class constructor invokers don't use the context object

    unpackArguments(code, constructor.getParameterTypes());

    code.invokespecial(hostType, INIT_NAME, methodType(constructor));
  }

  @Override
  protected void generateMethodInvoker(CodeBuilder code, Method method) {
    boolean isStatic = Modifier.isStatic(method.getModifiers());

    if (!isStatic) {
      // context object is the instance whose method we want to call
      code.aload(1);
      code.checkcast(hostType);
    }
    // (fast-class static method invokers don't use the context object)

    unpackArguments(code, method.getParameterTypes());

    if (isStatic) {
      code.invokestatic(hostType, method.getName(), methodType(method), hostIsInterface);
    } else if (hostIsInterface) {
      code.invokeinterface(hostType, method.getName(), methodType(method));
    } else {
      code.invokevirtual(hostType, method.getName(), methodType(method));
    }

    Class<?> returnType = method.getReturnType();
    if (returnType == void.class) {
      code.aconst_null();
    } else if (returnType.isPrimitive()) {
      box(code, returnType);
    }
  }

  @Override
  protected MethodHandle lookupInvokerTable(Class<?> glueClass) throws Throwable {
    return (MethodHandle) glueClass.getField(INVOKERS_NAME).get(null);
  }
}
