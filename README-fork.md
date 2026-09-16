# Fork notes

This branch diverges from upstream Guice in the following ways.

## JDK 25 baseline

- The minimum Java version is 25. Compatibility code for older JDKs was removed.
- Guava was bumped to 33.7.1, which no longer touches `sun.misc.Unsafe` on modern JDKs.
  Because that Guava no longer pulls in jsr305 transitively, core declares it explicitly (optional).

## Build system: Gradle only

- Bazel and Maven are gone. The build is Gradle 9 with Groovy scripts; a wrapper is checked in.
- `build.gradle` at the root holds all conventions (Ant-style `src`/`test` layout, compiler flags,
  test variants, OSGi manifests via bnd, LICENSE/NOTICE, publishing). Module scripts only declare
  dependencies and their OSGi symbolic name. Project names equal the Maven artifact ids.
- Versions live in `gradle/libs.versions.toml`; the project version in `gradle.properties`.
- Commands:
  - `./gradlew build` compiles, builds all jars and runs every test variant (the surefire executions
    became tasks such as `testStackTracesOff`, `testChildClassLoading`, ...).
  - `./gradlew publishToMavenLocal`, then `./gradlew -p examples/guice-demo test -Pguice.version=<v>`
    checks the published artifacts from a consumer project.
  - `./gradlew aggregateJavadoc` produces the combined API docs; `./gradlew publish` deploys.
- Artifacts (jars, sources, test jars, manifests, poms) match what Maven produced, except:
  `META-INF/DEPENDENCIES` is not generated, test-scoped dependencies are not listed in poms,
  javadoc jars carry no timestamps, and the jdiff API reports are no longer published.
- The GitHub workflow runs the Gradle build, the consumer check and the snapshot/javadoc publish.

## No more embedded ASM

- Bytecode generation for enhancers/fast-classes and line-number lookup use `java.lang.classfile`.
- Consequently there is no ASM dependency, no jarjar relocation into
  `com.google.inject.internal.asm`, and no `guice-<version>-classes.jar` artifact.
