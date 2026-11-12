# [bld](https://rife2.com/bld) Extension to Perform Static Code Analysis with [Error Prone](https://errorprone.info)

[![License](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/java-17%2B-blue)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![bld](https://img.shields.io/badge/3.0.1-FA9052?label=bld&labelColor=2392FF)](https://rife2.com/bld)
[![Release](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.rife2.com%2Freleases%2Fcom%2Fuwyn%2Frife2%2Fbld-errorprone%2Fmaven-metadata.xml&color=blue)](https://repo.rife2.com/#/releases/com/uwyn/rife2/bld-errorprone)
[![Snapshot](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.rife2.com%2Fsnapshots%2Fcom%2Fuwyn%2Frife2%2Fbld-errorprone%2Fmaven-metadata.xml&label=snapshot)](https://repo.rife2.com/#/snapshots/com/uwyn/rife2/bld-errorprone)
[![GitHub CI](https://github.com/rife2/bld-errorprone/actions/workflows/bld.yml/badge.svg)](https://github.com/rife2/bld-errorprone/actions/workflows/bld.yml)

To install the latest version, add the following to the
`lib/bld/bld-wrapper.properties` file:

```properties
bld.extension-errorprone=com.uwyn.rife2:bld-errorprone
```

For more information, please refer to the
[extensions](https://github.com/rife2/bld/wiki/Extensions) documentation.

## Compile Source with Error Prone

This extension compiles source with the Error Prone compiler plugin enabled.

### JDK Requirements

The extension is built with JDK 17 and requires JDK 17 or newer to run.

The operation picks a compatible Error Prone version for the running JDK,
currently:

* [ERROR_PRONE_VERSION](https://rife2.github.io/bld-errorprone/rife/bld/extension/ErrorProneOperation.html#ERROR_PRONE_VERSION) on JDK 21+
* [LAST_JDK17_VERSION](https://rife2.github.io/bld-errorprone/rife/bld/extension/ErrorProneOperation.html#LAST_JDK17_VERSION) on JDK 17-20

An explicit version can be selected with `version(String)`. A version
incompatible with the running JDK is rejected before downloading.

### JVM Options

Error Prone uses `javac` internals encapsulated since JDK 16.
As compilation runs in the JVM that runs bld, the following options
in `lib/bld/bld-wrapper.properties` must be added via `bld.javaOptions`:

```properties
bld.javaOptions=--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED\
  \ --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED\
  \ --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED\
  \ --add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED\
  \ --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED\
  \ --add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED\
  \ --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED\
  \ --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED\
  \ --add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED\
  \ --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
```

### Compile Project

To compile the project with Error Prone, add the following to your build file:

```java
@BuildCommand(summary = "Compiles the project with Error Prone")
public void errorprone() throws Exception {
    new ErrorProneOperation()
            .fromProject(this)
            .execute();
}
```

```console
./bld clean errorprone
```

* [View Example Project](https://github.com/rife2/bld-errorprone/tree/main/example)

### Common Options

Each check's default severity is listed in the Error Prone
[check index](https://errorprone.info/bugpatterns).

Many of the examples
below use `JdkObsolete`, which is off by default, so both
`enableCheck` and `errorCheck` are unambiguous promotions from OFF.

#### Failing the build

`-Werror` is a javac flag, not an Error Prone `-Xep` flag, so it is passed
through the compile options rather than through `flag(...)`. It promotes every
warning, javac's and Error Prone's, to an error:

```java
new ErrorProneOperation()
        .fromProject(this)
        .compileOptions(o -> o.add("-Werror"))
        .execute();
```

That includes checks set to `WARN` with `warnCheck(...)`; the per-check setting
changes how the finding is reported, not whether it fails the build. To fail
on specific checks only, omit `-Werror` and promote them with `errorCheck(...)`.

Most Error Prone checks already default to ERROR severity, so the build fails
on them without `-Werror`. To promote the checks that don't:

```java
new ErrorProneOperation()
        .fromProject(this)
        .errorCheck("JdkObsolete", "UnusedVariable")
        .execute();
```

To demote all errors to warnings, useful when first adopting Error Prone
on an existing codebase:

```java
new ErrorProneOperation()
        .fromProject(this)
        .allErrorsAsWarnings(true)
        .execute();
```

#### Selecting checks

Disable one or more checks. `disableCheck` works on both WARN- and ERROR-level
checks, and accepts either a varargs list or a `Collection<String>`:

```java
new ErrorProneOperation()
        .fromProject(this)
        .disableCheck("UnusedVariable", "MissingOverride")
        .execute();
```

```java
import java.util.List;

new ErrorProneOperation()
        .fromProject(this)
        .disableCheck(List.of("UnusedVariable", "MissingOverride"))
        .execute();
```

Enable a check that is off by default:

```java
new ErrorProneOperation()
        .fromProject(this)
        .enableCheck("JdkObsolete")
        .execute();
```

Change one or more checks' severity. `warnCheck` and `errorCheck` both enable
the check and set its severity:

```java
new ErrorProneOperation()
        .fromProject(this)
        .warnCheck("JdkObsolete", "StringSplitter")
        .errorCheck("UnusedVariable", "MissingOverride")
        .execute();
```

Report all disabled-by-default checks as warnings:

```java
new ErrorProneOperation()
        .fromProject(this)
        .allDisabledChecksAsWarnings(true)
        .execute();
```

Exclude paths from analysis. The regex must not contain whitespace; use `\s`
to match it. Multiple calls (or multiple arguments to one call) are joined as a
flat alternation:

```java
new ErrorProneOperation()
        .fromProject(this)
        .excludedPath(".*/build/generated/.*", ".*/generated-sources/.*")
        .execute();
```

A `Pattern`-typed overload is available for callers who already have a
compiled pattern. It rejects any pattern compiled with explicit flags
(non-zero `Pattern.flags()`). Inline flags such as `(?i)` are not reported
by `flags()` and therefore pass through unrejected; use the `String`
overloads if you need inline-flag semantics:

```java
import java.util.regex.Pattern;

var regex = Pattern.compile(".*/(build/generated|generated-sources)/.*");

new ErrorProneOperation()
        .fromProject(this)
        .excludedPath(regex)
        .execute();
```

Disable all checks, then enable a few, for a focused run. `enableCheck` keeps
each check's default severity; use `errorCheck` or `warnCheck` to force one:

```java
new ErrorProneOperation()
        .fromProject(this)
        .disableAllChecks(true)
        .errorCheck("DeadException")
        .enableCheck("UnusedVariable")
        .execute();
```

#### Advanced

Pass a check-specific option. Error Prone options are namespaced by check.
The single-argument form is shorthand for `=true`:

```java
new ErrorProneOperation()
        .fromProject(this)
        .option("JUnit4TestNotRun:ExpandedHeuristic")          // boolean shorthand
        .option("NullAway:AnnotatedPackages", "com.example")   // with a value; requires the NullAway artifact on the processor path
        .execute();
```

Write suggested fixes as a patch. `patchLocation` takes a directory that
must already exist; Error Prone writes one `.patch` file per modified
source into it. Use `IN_PLACE` to rewrite the sources directly:

```java
new ErrorProneOperation()
        .fromProject(this)
        .patchChecks("DeadException", "UnusedVariable")
        .patchLocation("build")
        .execute();
```

`patchChecks` also has a `Collection<String>` overload for callers that already
have a list:

```java
import java.util.List;

new ErrorProneOperation()
        .fromProject(this)
        .patchChecks(List.of("DeadException", "UnusedVariable"))
        .patchLocation("build")
        .execute();
```

Forward a raw javac option for anything the typed API does not wrap,
e.g. `--release` or `-Xlint`:

```java
new ErrorProneOperation()
        .fromProject(this)
        .compileOptions(o -> o.release(21))
        .compileOptions(o -> o.add("-Xlint:none"))
        .execute();
```

Customizers run before the Error Prone options, so an option the operation
sets itself (`-XDcompilePolicy`, `--should-stop`, the `-Xplugin` flag) wins
over a customizer's value. A `--processor-path` supplied by a customizer is
merged with the Error Prone processor path rather than replaced.

#### Alternate Repository

Download Error Prone through Google's Maven Central mirror, keeping Maven Central
as a fallback:

```java
new ErrorProneOperation()
        .fromProject(this)
        .repositories(Repository.GOOGLE_MAVEN_CENTRAL, Repository.MAVEN_CENTRAL)
        .execute();
```

## API Reference

Please check the [ErrorProneOperation documentation](https://rife2.github.io/bld-errorprone/rife/bld/extension/ErrorProneOperation.html#method-summary) for all available
configuration options.
