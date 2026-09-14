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

import com.google.inject.internal.InternalFlags;
import com.google.inject.internal.InternalFlags.CustomClassLoadingOption;

/**
 * Entry-point for defining dynamically generated classes.
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
public final class ClassDefining {
  private ClassDefining() {}

  // initialization-on-demand...
  private static class ClassDefinerHolder {
    static final CustomClassLoadingOption OPTION = InternalFlags.getCustomClassLoadingOption();
    static final ClassDefiner LOOKUP_DEFINER =
        new LookupClassDefiner(OPTION == CustomClassLoadingOption.ANONYMOUS);
    static final ClassDefiner CHILD_DEFINER = new ChildClassDefiner();
  }

  /** Defines a new class relative to the host. */
  public static Class<?> define(Class<?> hostClass, byte[] bytecode) throws Exception {
    return findClassDefiner(hostClass).define(hostClass, bytecode);
  }

  /** Returns true if classes defined for the given host can access its package-private members. */
  public static boolean hasPackageAccess(Class<?> hostClass) {
    return ClassDefinerHolder.OPTION != CustomClassLoadingOption.CHILD
        && LookupClassDefiner.isAccessible(hostClass);
  }

  /** Returns true if it's possible to load by name proxies defined from the given host. */
  public static boolean canLoadProxyByName(Class<?> hostClass) {
    // hidden classes can't be loaded by name
    return ClassDefinerHolder.OPTION != CustomClassLoadingOption.ANONYMOUS
        || !LookupClassDefiner.canDefineHidden(hostClass);
  }

  /** Finds the appropriate class definer for the given host. */
  private static ClassDefiner findClassDefiner(Class<?> hostClass) {
    switch (ClassDefinerHolder.OPTION) {
      case CHILD:
        return ClassDefinerHolder.CHILD_DEFINER;
      case BRIDGE:
        return LookupClassDefiner.isAccessible(hostClass)
            ? ClassDefinerHolder.LOOKUP_DEFINER
            : ClassDefinerHolder.CHILD_DEFINER;
      default:
        // OFF and ANONYMOUS never create class loaders; defining fails if the package isn't open
        return ClassDefinerHolder.LOOKUP_DEFINER;
    }
  }
}
