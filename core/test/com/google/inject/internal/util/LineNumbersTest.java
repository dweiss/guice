/*
 * Copyright (C) 2008 Google Inc.
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

package com.google.inject.internal.util;

import static com.google.inject.Asserts.assertContains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.internal.InternalFlags;
import com.google.inject.matcher.Matchers;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import jakarta.inject.Inject;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** @author jessewilson@google.com (Jesse Wilson) */
@RunWith(JUnit4.class)
public class LineNumbersTest {

  @Test
  public void testLineNumbers() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              bind(A.class);
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "No implementation for LineNumbersTest$B was bound.",
          "for 1st parameter b",
          "at LineNumbersTest$1.configure");
    }
  }

  static class A {
    @Inject
    A(B b) {}
  }

  public interface B {}

  @Test
  public void testCanHandleLineNumbersForGuiceGeneratedClasses() {
    assumeTrue(InternalFlags.isBytecodeGenEnabled());

    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              bindInterceptor(
                  Matchers.only(A.class),
                  Matchers.any(),
                  new MethodInterceptor() {
                    @Override
                    public Object invoke(MethodInvocation methodInvocation) {
                      return null;
                    }
                  });

              bind(A.class);
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "No implementation for LineNumbersTest$B was bound.",
          "for 1st parameter b",
          "at LineNumbersTest$2.configure");
    }
  }

  static class GeneratingClassLoader extends ClassLoader {
    static String name = "__generated";

    GeneratingClassLoader() {
      super(B.class.getClassLoader());
    }

    Class<?> generate() {
      byte[] buf =
          ClassFile.of()
              .build(
                  ClassDesc.ofInternalName(name),
                  cb -> {
                    cb.withVersion(ClassFile.JAVA_5_VERSION, 0);
                    cb.withFlags(ClassFile.ACC_PUBLIC);
                    cb.withSuperclass(ConstantDescs.CD_Object);
                    cb.withMethod(
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(
                            ConstantDescs.CD_void, ClassDesc.ofDescriptor(B.class.descriptorString())),
                        ClassFile.ACC_PUBLIC,
                        mb -> {
                          mb.with(
                              RuntimeVisibleAnnotationsAttribute.of(
                                  Annotation.of(
                                      ClassDesc.ofDescriptor(Inject.class.descriptorString()))));
                          mb.withCode(
                              code -> {
                                code.aload(0);
                                code.invokespecial(
                                    ConstantDescs.CD_Object,
                                    ConstantDescs.INIT_NAME,
                                    ConstantDescs.MTD_void);
                                code.return_();
                              });
                        });
                  });

      return defineClass(name.replace('/', '.'), buf, 0, buf.length);
    }
  }

  @Test
  public void testUnavailableByteCodeShowsUnknownSource() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              bind(new GeneratingClassLoader().generate());
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "No implementation for LineNumbersTest$B was bound.",
          "for 1st parameter",
          "at LineNumbersTest$3.configure");
    }
  }

  @Test
  public void testGeneratedClassesCanSucceed() {
    final Class<?> generated = new GeneratingClassLoader().generate();
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(generated);
                bind(B.class).toInstance(new B() {});
              }
            });
    Object instance = injector.getInstance(generated);
    assertEquals(instance.getClass(), generated);
  }
}
