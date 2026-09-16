/*
 * Copyright (C) 2006 Google Inc.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Looks up line numbers for classes and their members.
 *
 * @author Chris Nokleberg
 */
final class LineNumbers {

  private static final Logger logger = Logger.getLogger(LineNumbers.class.getName());
  private static volatile boolean alreadyLoggedReadingFailure;

  private final Class<?> type;
  private final Map<String, Integer> lines = Maps.newHashMap();
  private String source;
  private int firstLine = Integer.MAX_VALUE;

  /**
   * Reads line number information from the given class, if available.
   *
   * @param type the class to read line number information from
   */
  public LineNumbers(Class<?> type) throws IOException {
    this.type = type;

    if (!type.isArray()) {
      InputStream in = null;
      try {
        in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class");
      } catch (IllegalStateException ignored) {
        // Some classloaders throw IllegalStateException when they can't load a resource.
      }
      if (in != null) {
        try {
          readLineNumbers(ClassFile.of().parse(in.readAllBytes()));
        } catch (Exception ignored) {
          // We may be trying to inspect classes that were compiled with a more recent version
          // of javac than this JVM supports.  If that happens, just ignore the class and don't
          // capture line numbers. But log the failure so folks know something's off.
          // (Only log it once, though, to avoid spam. It's OK if concurrent access makes this
          //  happen more than once.)
          if (!alreadyLoggedReadingFailure) {
            alreadyLoggedReadingFailure = true;
            logger.log(
                Level.WARNING,
                "Failed loading line numbers. Further failures won't be logged.",
                ignored);
          }
        } finally {
          try {
            in.close();
          } catch (IOException ignored) {
          }
        }
      }
    }
  }

  /**
   * Get the source file name as read from the bytecode.
   *
   * @return the source file name if available, or null
   */
  public String getSource() {
    return source;
  }

  /**
   * Get the line number associated with the given member.
   *
   * @param member a field, constructor, or method belonging to the class used during construction
   * @return the wrapped line number, or null if not available
   * @throws IllegalArgumentException if the member does not belong to the class used during
   *     construction
   */
  public Integer getLineNumber(Member member) {
    Preconditions.checkArgument(
        type == member.getDeclaringClass(),
        "Member %s belongs to %s, not %s",
        member,
        member.getDeclaringClass(),
        type);
    return lines.get(memberKey(member));
  }

  /** Gets the first line number. */
  public int getFirstLine() {
    return firstLine == Integer.MAX_VALUE ? 1 : firstLine;
  }

  private String memberKey(Member member) {
    checkNotNull(member, "member");
    if (member instanceof Field) {
      return member.getName();
    } else if (member instanceof Method) {
      Method method = (Method) member;
      return method.getName()
          + MethodType.methodType(method.getReturnType(), method.getParameterTypes())
              .descriptorString();
    } else if (member instanceof Constructor) {
      return "<init>"
          + MethodType.methodType(void.class, ((Constructor<?>) member).getParameterTypes())
              .descriptorString();
    } else {
      throw new IllegalArgumentException(
          "Unsupported implementation class for Member, " + member.getClass());
    }
  }

  /** Records the source file, the first line of each member, and the earliest line seen. */
  private void readLineNumbers(ClassModel classModel) {
    classModel
        .findAttribute(Attributes.sourceFile())
        .ifPresent(attribute -> source = attribute.sourceFile().stringValue());

    String className = classModel.thisClass().name().stringValue();
    for (MethodModel method : classModel.methods()) {
      if (method.flags().has(AccessFlag.PRIVATE)) {
        continue;
      }
      String pendingMethod = method.methodName().stringValue() + method.methodType().stringValue();
      int line = -1;
      List<CodeElement> code = method.code().map(CodeModel::elementList).orElse(List.of());
      for (CodeElement element : code) {
        if (element instanceof LineNumber lineNumber) {
          line = lineNumber.line();
          if (line < firstLine) {
            firstLine = line;
          }
          if (pendingMethod != null) {
            lines.put(pendingMethod, line);
            pendingMethod = null;
          }
        } else if (element instanceof FieldInstruction field
            && field.opcode() == Opcode.PUTFIELD
            && className.equals(field.owner().name().stringValue())
            && line != -1) {
          // a field is assigned on the first line where a non-private method stores it
          lines.putIfAbsent(field.name().stringValue(), line);
        }
      }
    }
  }
}
