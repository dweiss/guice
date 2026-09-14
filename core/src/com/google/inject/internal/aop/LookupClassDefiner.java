/*
 * Copyright (C) 2026 Google Inc.
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ClassDefiner} that defines classes in the same class loader and runtime package as their
 * host, using a private {@link Lookup} obtained via {@link MethodHandles#privateLookupIn}.
 *
 * <p>This requires the host's package to be open to Guice. That is always the case for classes on
 * the class path (the unnamed module), never the case for JDK classes, and only the case for named
 * modules that explicitly {@code opens} the package to Guice.
 */
final class LookupClassDefiner implements ClassDefiner {

  private static final Logger logger = Logger.getLogger(LookupClassDefiner.class.getName());

  private static final Lookup LOOKUP = MethodHandles.lookup();

  /** Private lookups for host classes, or {@code null} when the host's package is not open. */
  private static final ClassValue<Lookup> HOST_LOOKUPS =
      new ClassValue<Lookup>() {
        @Override
        protected Lookup computeValue(Class<?> hostClass) {
          try {
            return MethodHandles.privateLookupIn(hostClass, LOOKUP);
          } catch (IllegalAccessException | RuntimeException e) {
            logger.log(Level.FINE, "Cannot obtain private lookup in " + hostClass, e);
            return null;
          }
        }
      };

  /** Returns true if it's possible to define classes alongside the given host. */
  static boolean isAccessible(Class<?> hostClass) {
    return HOST_LOOKUPS.get(hostClass) != null;
  }

  /**
   * Returns true if it's possible to define hidden classes alongside the given host. This needs a
   * full-privilege lookup, which is only available when the host is in the same module as Guice.
   */
  static boolean canDefineHidden(Class<?> hostClass) {
    Lookup lookup = HOST_LOOKUPS.get(hostClass);
    return lookup != null && lookup.hasFullPrivilegeAccess();
  }

  private final boolean hidden;

  /**
   * @param hidden whether to prefer defining classes as hidden nest-mates of their host; such
   *     classes cannot be looked up by name but are easier to unload. Hosts in other modules (for
   *     example those loaded by other class loaders) still get regular classes.
   */
  LookupClassDefiner(boolean hidden) {
    this.hidden = hidden;
  }

  @Override
  public Class<?> define(Class<?> hostClass, byte[] bytecode) throws Exception {
    Lookup lookup = HOST_LOOKUPS.get(hostClass);
    if (lookup == null) {
      throw new IllegalAccessException("Package of " + hostClass + " is not open to Guice");
    }
    if (hidden && lookup.hasFullPrivilegeAccess()) {
      return lookup.defineHiddenClass(bytecode, false, ClassOption.NESTMATE).lookupClass();
    }
    return lookup.defineClass(bytecode);
  }
}
