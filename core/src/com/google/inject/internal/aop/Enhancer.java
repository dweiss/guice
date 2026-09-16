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

import static com.google.inject.internal.BytecodeGen.ENHANCER_BY_GUICE_MARKER;
import static com.google.inject.internal.aop.BytecodeTasks.box;
import static com.google.inject.internal.aop.BytecodeTasks.classDesc;
import static com.google.inject.internal.aop.BytecodeTasks.exceptionTypes;
import static com.google.inject.internal.aop.BytecodeTasks.loadArgument;
import static com.google.inject.internal.aop.BytecodeTasks.methodType;
import static com.google.inject.internal.aop.BytecodeTasks.packArguments;
import static com.google.inject.internal.aop.BytecodeTasks.unbox;
import static com.google.inject.internal.aop.BytecodeTasks.unpackArguments;
import static java.lang.classfile.ClassFile.ACC_ABSTRACT;
import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.lang.classfile.ClassFile.ACC_NATIVE;
import static java.lang.classfile.ClassFile.ACC_PRIVATE;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.classfile.ClassFile.ACC_SYNCHRONIZED;
import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CLASS_INIT_NAME;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Generates enhanced classes.
 *
 * <p>Each enhancer has the same number of constructors as the class it enhances, but each
 * constructor takes an additional handler array before the rest of the expected arguments.
 *
 * <p>Enhanced methods are overridden to call the handler with the same index as the method. The
 * handler delegates to the interceptor stack. Once the last interceptor returns the handler will
 * call back into the trampoline with the method index, which invokes the superclass method.
 *
 * <p>The trampoline also provides access to constructor invokers that take a context object (the
 * handler array) with an argument array and invokes the appropriate enhanced constructor. These
 * invokers are used in the proxy factory to create enhanced instances.
 *
 * <p>Enhanced classes have the following pseudo-Java structure:
 *
 * <pre>
 * public class HostClass$$EnhancerByGuice
 *   extends HostClass
 * {
 *   // InterceptorStackCallbacks, one per enhanced method
 *   private final InvocationHandler[] GUICE$HANDLERS;
 *
 *   public HostClass$$EnhancerByGuice(InvocationHandler[] handlers, ...) {
 *      // JVM lets us store this before calling the superclass constructor
 *     GUICE$HANDLERS = handlers;
 *     super(...);
 *   }
 *
 *   public static Object GUICE$TRAMPOLINE(int index, Object context, Object[] args) {
 *     switch (index) {
 *       case 0: {
 *         return new HostClass$$EnhancerByGuice((InvocationHandler[]) context, ...);
 *       }
 *       case 1: {
 *         return context.super.instanceMethod(...); // call original unenhanced method
 *       }
 *     }
 *     return null;
 *   }
 *
 *   // enhanced method
 *   public final Object instanceMethod(...) {
 *     // pack arguments and trigger the associated InterceptorStackCallback
 *     return GUICE$HANDLERS[0].invoke(this, null, args);
 *   }
 *
 *   // ...
 * }
 * </pre>
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
final class Enhancer extends AbstractGlueGenerator {

  private static final String HANDLERS_NAME = "GUICE$HANDLERS";

  private static final ClassDesc HANDLER_TYPE = classDesc(InvocationHandler.class);

  private static final ClassDesc HANDLERS_TYPE = HANDLER_TYPE.arrayType();

  private static final String INVOKERS_NAME = "GUICE$INVOKERS";

  private static final MethodTypeDesc CALLBACK_TYPE =
      MethodTypeDesc.of(CD_Object, CD_Object, classDesc(Method.class), CD_Object.arrayType());

  // Describes the LambdaMetafactory.metafactory method arguments and return type
  private static final MethodTypeDesc METAFACTORY_TYPE =
      MethodTypeDesc.of(
          CD_CallSite,
          CD_MethodHandles_Lookup,
          CD_String,
          CD_MethodType,
          CD_MethodType,
          CD_MethodHandle,
          CD_MethodType);

  private static final MethodTypeDesc INDEX_TO_INVOKER_METHOD_TYPE =
      MethodTypeDesc.of(classDesc(BiFunction.class), CD_int);

  private static final MethodTypeDesc RAW_INVOKER_METHOD_TYPE =
      MethodTypeDesc.of(CD_Object, CD_Object, CD_Object);

  private static final MethodTypeDesc INVOKER_METHOD_TYPE =
      MethodTypeDesc.of(CD_Object, CD_Object, CD_Object.arrayType());

  private final Map<Method, Method> bridgeDelegates;

  Enhancer(Class<?> hostClass, Map<Method, Method> bridgeDelegates) {
    super(hostClass, ENHANCER_BY_GUICE_MARKER);
    this.bridgeDelegates = bridgeDelegates;
  }

  @Override
  protected byte[] generateGlue(Collection<Executable> members) {
    return ClassFile.of()
        .build(
            proxyType,
            cb -> {
              // target Java8 because that's all we need for the generated trampoline code
              cb.withVersion(ClassFile.JAVA_8_VERSION, 0);
              cb.withFlags(ACC_PUBLIC | ACC_SUPER);
              cb.withSuperclass(hostType);
              cb.with(SourceFileAttribute.of(GENERATED_SOURCE));

              // this shared field either contains the trampoline or glue to make it into an
              // invoker table
              cb.withField(INVOKERS_NAME, CD_MethodHandle, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);

              setupInvokerTable(cb);

              generateTrampoline(cb, members);

              // this field will hold the handlers configured for this particular enhanced instance
              cb.withField(HANDLERS_NAME, HANDLERS_TYPE, ACC_PRIVATE | ACC_FINAL);

              Set<Method> remainingBridgeMethods = new HashSet<>(bridgeDelegates.keySet());

              int methodIndex = 0;
              for (Executable member : members) {
                if (member instanceof Constructor<?> constructor) {
                  enhanceConstructor(cb, constructor);
                } else {
                  enhanceMethod(cb, (Method) member, methodIndex++);
                  remainingBridgeMethods.remove(member);
                }
              }

              // replace any remaining bridge methods with virtual dispatch to their non-bridge
              // targets
              for (Method method : remainingBridgeMethods) {
                Method target = bridgeDelegates.get(method);
                if (target != null) {
                  generateVirtualBridge(cb, method, target);
                }
              }
            });
  }

  /** Generate static initializer to setup invoker table based on the trampoline. */
  private void setupInvokerTable(ClassBuilder cb) {
    cb.withMethodBody(
        CLASS_INIT_NAME,
        MTD_void,
        ACC_PRIVATE | ACC_STATIC,
        code -> {
          DirectMethodHandleDesc trampolineHandle =
              MethodHandleDesc.ofMethod(
                  DirectMethodHandleDesc.Kind.STATIC, proxyType, TRAMPOLINE_NAME, TRAMPOLINE_TYPE);

          if (ClassDefining.canLoadProxyByName(hostClass)) {
            // generate lambda glue to make the raw trampoline look like an invoker table

            code.invokestatic(
                CD_MethodHandles, "lookup", MethodTypeDesc.of(CD_MethodHandles_Lookup));

            code.loadConstant("apply");
            code.loadConstant(INDEX_TO_INVOKER_METHOD_TYPE);
            code.loadConstant(RAW_INVOKER_METHOD_TYPE);
            code.loadConstant(trampolineHandle);
            code.loadConstant(INVOKER_METHOD_TYPE);

            code.invokestatic(classDesc(LambdaMetafactory.class), "metafactory", METAFACTORY_TYPE);

            code.invokevirtual(CD_CallSite, "getTarget", MethodTypeDesc.of(CD_MethodHandle));

          } else {
            // proxy class is hidden so we can't create our lambda glue, store raw trampoline instead
            code.loadConstant(trampolineHandle);
          }

          code.putstatic(proxyType, INVOKERS_NAME, CD_MethodHandle);

          code.return_();
        });
  }

  /** Generate enhanced constructor that takes a handler array along with the expected arguments. */
  private void enhanceConstructor(ClassBuilder cb, Constructor<?> constructor) {
    MethodTypeDesc type = methodType(constructor);
    MethodTypeDesc enhancedType = type.insertParameterTypes(0, HANDLERS_TYPE);

    cb.withMethod(
        INIT_NAME,
        enhancedType,
        ACC_PUBLIC,
        mb -> {
          declareExceptions(mb, constructor);
          mb.withCode(
              code -> {
                code.aload(0);
                code.dup();
                code.aload(1);
                // store handlers before invoking the superclass constructor (JVM allows this)
                code.putfield(proxyType, HANDLERS_NAME, HANDLERS_TYPE);

                int slot = 2;
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                  slot += loadArgument(code, parameterType, slot);
                }

                code.invokespecial(hostType, INIT_NAME, type);

                code.return_();
              });
        });
  }

  /** Generate enhanced method that calls the handler with the same index. */
  private void enhanceMethod(ClassBuilder cb, Method method, int methodIndex) {
    cb.withMethod(
        method.getName(),
        methodType(method),
        ACC_FINAL | (method.getModifiers() & ~(ACC_ABSTRACT | ACC_NATIVE | ACC_SYNCHRONIZED)),
        mb -> {
          declareExceptions(mb, method);
          mb.withCode(
              code -> {
                code.aload(0);
                code.dup();
                code.getfield(proxyType, HANDLERS_NAME, HANDLERS_TYPE);
                code.loadConstant(methodIndex);
                code.aaload();
                code.swap();
                // we don't use the method argument in InterceptorStackCallback.invoke, so can use
                // null here
                code.aconst_null();
                packArguments(code, method.getParameterTypes());

                code.invokeinterface(HANDLER_TYPE, "invoke", CALLBACK_TYPE);

                Class<?> returnType = method.getReturnType();
                if (returnType.isPrimitive()) {
                  // void is a primitive too and TypeKind.VOID gives a plain return
                  if (returnType != void.class) {
                    unbox(code, returnType);
                  }
                } else {
                  code.checkcast(classDesc(returnType));
                }
                code.return_(TypeKind.from(returnType));
              });
        });
  }

  @Override
  protected void generateConstructorInvoker(CodeBuilder code, Constructor<?> constructor) {
    MethodTypeDesc enhancedType = methodType(constructor).insertParameterTypes(0, HANDLERS_TYPE);

    code.new_(proxyType);
    code.dup();

    code.aload(1);
    code.checkcast(HANDLERS_TYPE);
    unpackArguments(code, constructor.getParameterTypes());

    code.invokespecial(proxyType, INIT_NAME, enhancedType);
  }

  @Override
  protected void generateMethodInvoker(CodeBuilder code, Method method) {
    Method target = bridgeDelegates.getOrDefault(method, method);

    code.aload(1);
    code.checkcast(proxyType);
    unpackArguments(code, target.getParameterTypes());

    // if this was a bridge method and we know the target then replace superclass delegation
    // with virtual dispatch to avoid skipping other interceptors overriding the target method
    if (target != method) {
      code.invokevirtual(hostType, target.getName(), methodType(target));
    } else {
      code.invokespecial(hostType, target.getName(), methodType(target));
    }

    Class<?> returnType = target.getReturnType();
    if (returnType == void.class) {
      code.aconst_null();
    } else if (returnType.isPrimitive()) {
      box(code, returnType);
    }
  }

  /** Override the original bridge method and replace it with virtual dispatch to the target. */
  private void generateVirtualBridge(ClassBuilder cb, Method bridge, Method target) {
    cb.withMethod(
        bridge.getName(),
        methodType(bridge),
        ACC_FINAL | (bridge.getModifiers() & ~(ACC_ABSTRACT | ACC_NATIVE | ACC_SYNCHRONIZED)),
        mb -> {
          declareExceptions(mb, bridge);
          mb.withCode(
              code -> {
                code.aload(0);
                code.checkcast(proxyType);

                Class<?>[] bridgeParameterTypes = bridge.getParameterTypes();
                Class<?>[] targetParameterTypes = target.getParameterTypes();

                int slot = 1;
                for (int i = 0, len = targetParameterTypes.length; i < len; i++) {
                  Class<?> parameterType = targetParameterTypes[i];
                  slot += loadArgument(code, parameterType, slot);
                  if (parameterType != bridgeParameterTypes[i]) {
                    // cast incoming argument to the specific type expected by target
                    code.checkcast(classDesc(parameterType));
                  }
                }

                code.invokevirtual(hostType, target.getName(), methodType(target));

                Class<?> returnType = bridge.getReturnType();
                if (target.getReturnType() != returnType) {
                  // cast return value to the specific type expected by bridge
                  code.checkcast(classDesc(returnType));
                }
                code.return_(TypeKind.from(returnType));
              });
        });
  }

  @Override
  protected MethodHandle lookupInvokerTable(Class<?> glueClass) throws Throwable {
    return (MethodHandle) glueClass.getField(INVOKERS_NAME).get(null);
  }

  /** Declares the exceptions thrown by the given constructor/method on the generated method. */
  private static void declareExceptions(MethodBuilder mb, Executable member) {
    List<ClassDesc> exceptions = exceptionTypes(member);
    if (!exceptions.isEmpty()) {
      mb.with(ExceptionsAttribute.ofSymbols(exceptions));
    }
  }
}
