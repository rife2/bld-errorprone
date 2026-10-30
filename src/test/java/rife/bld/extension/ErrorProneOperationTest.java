/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rife.bld.extension;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import rife.bld.blueprints.BaseProjectBlueprint;
import rife.bld.dependencies.Repository;
import rife.bld.dependencies.Version;
import rife.bld.dependencies.VersionNumber;
import rife.bld.operations.CompileOperation;
import rife.bld.operations.JavacOptions;
import rife.bld.operations.exceptions.ExitStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ExcessiveImports", "PMD.SignatureDeclareThrowsException", "PMD.CloseResource"})
class ErrorProneOperationTest {

    private static final String BAD_SOURCE = String.join("\n",
            "package com.example;",
            "",
            "public class Example {",
            "    public void run() {",
            "        new IllegalStateException(\"never thrown\");",
            "    }",
            "}",
            "");
    private static final String CHECK_1 = "Check1";
    private static final String CHECK_2 = "Check2";
    private static final String CHECK_3 = "Check3";
    private static final String CLEAN_SOURCE = String.join("\n",
            "package com.example;",
            "",
            "public class Example {",
            "    public String greet() {",
            "        return \"hello\";",
            "    }",
            "}",
            "");
    private static final List<String> EXPORT_PACKAGES = List.of(
            "api", "file", "main", "model", "parser", "processing", "tree", "util");
    private static final String JAVAC_PREFIX = "jdk.compiler/com.sun.tools.javac.";
    private static final List<String> OPEN_PACKAGES = List.of("code", "comp");
    private static final String OPTION_1 = "Option1";
    private static final String OPTION_2 = "Namespace:Option2";
    private static final String PATH_1 = ".*/build/generated/.*";
    private static final String PATH_2 = ".*/generated-sources/.*";
    private static final String PLUGIN = "-Xplugin:ErrorProne";
    private static final String PROCESSOR_PATH = "error_prone_core.jar";
    private static final String UNCHECKED_SOURCE = String.join("\n",
            "package com.example;",
            "",
            "import java.util.*;",
            "public class Example {",
            "    public void run() {",
            "        List l = new ArrayList();",
            "        l.add(\"a\");",
            "    }",
            "}",
            "");

    /**
     * Creates a working directory for a test project whose Error Prone sandbox
     * ({@code lib/.sandbox/bld-errorprone/}) will hold the downloaded jars.
     * <p>
     * Deliberately <em>not</em> a JUnit {@link TempDir}: javac keeps the Error
     * Prone plugin classloader (and its {@code -processor-path} jars under
     * {@code lib/.sandbox}) open after compilation, so JUnit's default
     * {@code @TempDir} cleanup fails on Windows with {@code DeletionException}
     * ({@code The process cannot access the file because it is being used by
     * another process}). The Detekt suite never hits this because Detekt is
     * forked, so its classpath jars are only locked by the child JVM. Mirror
     * that by keeping the sandbox out of {@code @TempDir}.
     * <p>
     * The directory is intentionally left for the OS to clean up; on
     * POSIX/Windows the OS reaps {@code java.io.tmpdir} entries.
     */
    private static Path newWorkDir(String testId) throws IOException {
        return Files.createTempDirectory("bld-errorprone-" + testId + "-");
    }

    private static ErrorProneOperation operationFor(Path workDir, String source, String version)
            throws IOException {
        writeSource(workDir, source);
        return new ErrorProneOperation()
                .fromProject(projectIn(workDir))
                .version(version);
    }

    private static BaseProjectBlueprint projectIn(Path dir) {
        return new BaseProjectBlueprint(dir.toFile(), "com.example", "examples",
                "Examples");
    }

    private static Arguments toggle(String flag,
                                    BiFunction<ErrorProneOperation, Boolean, ErrorProneOperation> setter) {
        return Arguments.of(flag, setter);
    }

    static Stream<Arguments> toggles() {
        return Stream.of(
                toggle("-XepAllDisabledChecksAsWarnings", ErrorProneOperation::allDisabledChecksAsWarnings),
                toggle("-XepAllErrorsAsWarnings", ErrorProneOperation::allErrorsAsWarnings),
                toggle("-XepAllSuggestionsAsWarnings", ErrorProneOperation::allSuggestionsAsWarnings),
                toggle("-XepDisableAllChecks", ErrorProneOperation::disableAllChecks),
                toggle("-XepDisableAllWarnings", ErrorProneOperation::disableAllWarnings),
                toggle("-XepDisableWarningsInGeneratedCode", ErrorProneOperation::disableWarningsInGeneratedCode),
                toggle("-XepIgnoreUnknownCheckNames", ErrorProneOperation::ignoreUnknownCheckNames));
    }

    private static void writeSource(Path dir, String source) throws IOException {
        var sourceDir = dir.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Example.java"), source);
    }

    @Nested
    @DisplayName("Compile Operation Tests")
    class CompileOperationTests {

        @Test
        void configureDoesNotPolluteProjectOptions(@TempDir Path dir) {
            var project = projectIn(dir);
            project.compileOperation().compileOptions().add("-parameters");
            var op = new ErrorProneOperation().fromProject(project).disableCheck(CHECK_1);

            var options = new JavacOptions();
            op.configure(options, PROCESSOR_PATH);

            assertThat(project.compileOperation().compileOptions().toString())
                    .doesNotContain("-XDcompilePolicy=simple")
                    .doesNotContain("ErrorProne");
            assertThat(String.join(" ", options)).contains(PLUGIN);
        }

        @Test
        void configurePreservesCustomizersOrder(@TempDir Path dir) {
            var project = projectIn(dir);
            var op = new ErrorProneOperation().fromProject(project)
                    .compileOptions(o -> o.add("-Xlint:none"))
                    .compileOptions(o -> o.add("-parameters"));

            var options = new JavacOptions();
            op.configure(options, PROCESSOR_PATH);

            assertThat(options.indexOf("-Xlint:none")).isNotNegative();
            assertThat(options.indexOf("-Xlint:none")).isLessThan(options.indexOf("-parameters"));
            assertThat(options.indexOf("-parameters")).isLessThan(options.indexOf("-XDcompilePolicy=simple"));
        }

        @Test
        void operationOptionsOverrideCustomizer() {
            var op = new ErrorProneOperation()
                    .compileOptions(o -> o.add("-XDcompilePolicy=complex"));
            var options = new JavacOptions();

            op.configure(options, PROCESSOR_PATH, 21);

            assertThat(options.indexOf("-XDcompilePolicy=simple"))
                    .as("the operation's -XDcompilePolicy must come after the customizer's")
                    .isGreaterThan(options.indexOf("-XDcompilePolicy=complex"));
        }

        @Test
        void operationsDoNotShareState(@TempDir Path dir) {
            var project = projectIn(dir);
            var op1 = new ErrorProneOperation().fromProject(project);
            var op2 = new ErrorProneOperation().fromProject(project);

            op1.disableCheck(CHECK_1);

            assertThat(op1.buildPluginOption()).contains(CHECK_1);
            assertThat(op2.buildPluginOption()).doesNotContain(CHECK_1);
        }
    }

    @Nested
    @DisplayName("Configure Tests")
    class ConfigureTests {

        @Test
        void configureAddsEssentialOptions() {
            var op = new ErrorProneOperation().disableCheck(CHECK_1);
            var options = new JavacOptions();

            op.configure(options, PROCESSOR_PATH);

            var joined = String.join(" ", options);
            assertThat(options).contains("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW");
            assertThat(joined).contains(PLUGIN).contains("-Xep:Check1:OFF");
            assertThat(joined).contains(PROCESSOR_PATH);
        }

        @Test
        void configureAddsTypeAnnotationsFlagOnJdk21Plus() {
            var options = new JavacOptions();
            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);
            assertThat(options).contains("-XDaddTypeAnnotationsToSymbol=true");
        }

        @Test
        void configureAppliesCustomizersInOrderBeforeErrorProneOptions() {
            var op = new ErrorProneOperation()
                    .compileOptions(o -> o.add("-Xlint:none"))
                    .compileOptions(o -> o.add("-parameters"));
            var options = new JavacOptions();

            op.configure(options, PROCESSOR_PATH);

            assertThat(options.indexOf("-Xlint:none")).isNotNegative();
            assertThat(options.indexOf("-Xlint:none")).isLessThan(options.indexOf("-parameters"));
            assertThat(options.indexOf("-parameters")).isLessThan(options.indexOf("-XDcompilePolicy=simple"));
        }

        @Test
        void configureNeverAddsModuleOptions() {
            var options = new JavacOptions();
            new ErrorProneOperation().configure(options, PROCESSOR_PATH);
            assertThat(String.join(" ", options))
                    .doesNotContain("--add-exports")
                    .doesNotContain("--add-opens");
        }

        @Test
        void configureOmitsTypeAnnotationsFlagBeforeJdk21() {
            var options = new JavacOptions();
            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 17);
            assertThat(options).doesNotContain("-XDaddTypeAnnotationsToSymbol=true");
        }

        @Test
        @SuppressWarnings("DataFlowIssue")
        void configureWithNullOptions() {
            assertThatThrownBy(() -> new ErrorProneOperation().configure(null, PROCESSOR_PATH, 21))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("options");
        }

        @Test
        @SuppressWarnings("DataFlowIssue")
        void configureWithNullProcessorPath() {
            assertThatThrownBy(() ->
                    new ErrorProneOperation().configure(new JavacOptions(), null, 21)
            ).isInstanceOf(NullPointerException.class).hasMessageContaining("processorPath");
        }

        @Test
        void processorPathWithJars(@TempDir Path dir) throws IOException {
            Files.createFile(dir.resolve("a.jar"));
            Files.createFile(dir.resolve("b.jar"));
            var classpath = new ErrorProneOperation().processorPath(dir);
            assertThat(classpath).contains("a.jar").contains("b.jar");
        }

        @Test
        void processorPathWithoutJars(@TempDir Path dir) {
            var op = new ErrorProneOperation();
            assertThatThrownBy(() -> op.processorPath(dir))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No Error Prone")
                    .hasMessageContaining(dir.toString());
        }
    }

    @Nested
    @DisplayName("Execute Tests")
    class ExecuteTests {

        @Test
        void executeHandlesNullCompileOperation() throws Exception {
            var dir = newWorkDir("nullCompileOp");
            writeSource(dir, CLEAN_SOURCE);
            var project = new BaseProjectBlueprint(dir.toFile(), "com.example", "examples",
                    "Examples") {
                @Override
                public CompileOperation compileOperation() {
                    return null;
                }
            };

            new ErrorProneOperation().fromProject(project)
                    .disableCheck("DeadException")
                    .execute();

            assertThat(dir.resolve("build/main/com/example/Example.class")).exists();
        }

        @Test
        void executeNoProject() {
            var op = new ErrorProneOperation();
            assertThatThrownBy(op::execute).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("project");
        }

        @SuppressWarnings("PMD.DetachedTestCase")
        abstract static class ExecuteTestBase {

            private static final String PARAM_SOURCE = String.join("\n",
                    "package com.example;",
                    "",
                    "public class Example {",
                    "    public String greet(String name) {",
                    "        return \"hello, \" + name;",
                    "    }",
                    "}",
                    "");

            private Path workDir;

            @BeforeEach
            void setUpWorkDir() throws IOException {
                workDir = newWorkDir(getClass().getSimpleName());
            }

            Path workDir() {
                return workDir;
            }

            void assertCompiled() {
                assertThat(workDir().resolve("build/main/com/example/Example.class")).exists();
            }

            String captureDiagnostics(ThrowingRunnable runnable) throws Exception {
                var combined = new ByteArrayOutputStream();
                var originalErr = System.err;
                var originalOut = System.out;
                try {
                    var teeErr = new PrintStream(new OutputStream() {
                        @Override
                        public void write(int b) {
                            combined.write(b);
                            originalErr.write(b);
                        }

                        @Override
                        public void write(byte @NonNull [] b, int off, int len) {
                            combined.write(b, off, len);
                            originalErr.write(b, off, len);
                        }
                    }, true, StandardCharsets.UTF_8);
                    var teeOut = new PrintStream(new OutputStream() {
                        @Override
                        public void write(int b) {
                            combined.write(b);
                            originalOut.write(b);
                        }

                        @Override
                        public void write(byte @NonNull [] b, int off, int len) {
                            combined.write(b, off, len);
                            originalOut.write(b, off, len);
                        }
                    }, true, StandardCharsets.UTF_8);
                    System.setErr(teeErr);
                    System.setOut(teeOut);
                    runnable.run();
                    teeErr.flush();
                    teeOut.flush();
                } finally {
                    System.setErr(originalErr);
                    System.setOut(originalOut);
                }
                return combined.toString(StandardCharsets.UTF_8);
            }

            @Test
            void execute() throws Exception {
                var output = captureDiagnostics(() -> op(CLEAN_SOURCE).execute());
                assertCompiled();
                assertThat(output).doesNotContain("[DeadException]");
            }

            @Test
            void executeAllErrorsAsWarnings() throws Exception {
                var output = captureDiagnostics(() -> op(BAD_SOURCE).allErrorsAsWarnings(true).execute());
                assertCompiled();
                assertThat(output).contains("[DeadException]");
            }

            @Test
            void executeDisableCheck() throws Exception {
                var output = captureDiagnostics(() -> op(BAD_SOURCE).disableCheck("DeadException").execute());
                assertCompiled();
                assertThat(output).doesNotContain("[DeadException]");
            }

            @Test
            void executeDoesNotMutateProjectCompileOptions() throws Exception {
                var dir = workDir();
                writeSource(dir, CLEAN_SOURCE);
                var project = projectIn(dir);
                project.compileOperation().compileOptions().add("-parameters");
                var before = project.compileOperation().compileOptions().toString();

                new ErrorProneOperation().fromProject(project)
                        .disableCheck("DeadException")
                        .execute();

                assertThat(project.compileOperation().compileOptions().toString()).isEqualTo(before);
            }

            @Test
            void executeFindingFailsBuild() {
                var op = assertDoesNotThrow(() -> op(BAD_SOURCE).errorCheck("DeadException"));
                assertThatThrownBy(op::execute).isInstanceOf(ExitStatusException.class);
            }

            @Test
            void executeHonorsProjectCompileOptions() throws Exception {
                var dir = workDir();
                writeSource(dir, PARAM_SOURCE);
                var project = projectIn(dir);
                project.compileOperation().compileOptions().add("-parameters");

                var logger = Logger.getLogger(ErrorProneOperation.class.getName());
                var previous = logger.getLevel();
                logger.setLevel(Level.FINE);
                try {
                    new ErrorProneOperation().fromProject(project)
                            .allErrorsAsWarnings(true)
                            .execute();
                } finally {
                    logger.setLevel(previous);
                }

                var classFile = dir.resolve("build/main/com/example/Example.class");
                assertThat(classFile).exists();
                var bytes = Files.readAllBytes(classFile);
                assertThat(new String(bytes, StandardCharsets.ISO_8859_1))
                        .as("javac must receive -parameters from the project's compile options")
                        .contains("MethodParameters");
            }

            @Test
            void executeWithCompileOptionsCustomFlag() throws Exception {
                op(CLEAN_SOURCE).compileOptions(o -> o.add("-Xlint:none")).execute();
                assertCompiled();
            }

            @Test
            void executeWithRepository() throws Exception {
                op(CLEAN_SOURCE).repositories(Repository.GOOGLE_MAVEN_CENTRAL_EU).execute();
                assertCompiled();
            }

            ErrorProneOperation op(String source) throws IOException {
                return operationFor(workDir(), source, version());
            }

            abstract String version();

            @FunctionalInterface
            interface ThrowingRunnable {

                void run() throws Exception;
            }
        }

        @Nested
        @DisplayName("JDK 17-20 - ErrorProne 2.40.0")
        @EnabledForJreRange(min = JRE.JAVA_17, max = JRE.JAVA_20, disabledReason = "2.40.0 for JDK 17-20")
        class Jdk17 extends ExecuteTestBase {

            @Override
            String version() {
                return "2.40.0";
            }
        }

        @Nested
        @DisplayName("JDK 21+ - ErrorProne 2.51.0")
        @EnabledForJreRange(min = JRE.JAVA_21, disabledReason = "2.51.0 requires JDK 21+")
        class Jdk21Plus extends ExecuteTestBase {

            @Override
            String version() {
                return "2.51.0";
            }

            @Test
            @EnabledForJreRange(min = JRE.JAVA_21, disabledReason = "release 21 requires JDK 21+")
            void executeHonorsProjectCompileOptionsRelease() throws Exception {
                var dir = workDir();
                writeSource(dir, CLEAN_SOURCE);
                var project = projectIn(dir);
                project.compileOperation().compileOptions().release(21);

                new ErrorProneOperation().fromProject(project)
                        .disableCheck("DeadException")
                        .execute();

                var bytes = Files.readAllBytes(dir.resolve("build/main/com/example/Example.class"));
                var major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
                assertThat(major).isEqualTo(65);
            }

            @Test
            @EnabledForJreRange(min = JRE.JAVA_21, max = JRE.JAVA_26,
                    disabledReason = "release + ErrorProne not supported on JDK 27+")
            void executeWithCompileOptionsRelease() throws Exception {
                op(CLEAN_SOURCE).compileOptions(o -> o.release(21)).execute();
                assertCompiled();
            }

            @Test
            @EnabledForJreRange(min = JRE.JAVA_21, max = JRE.JAVA_26,
                    disabledReason = "release + ErrorProne not supported on JDK 27+")
            void executeWithMultipleCompileOptionsCustomizers() throws Exception {
                op(CLEAN_SOURCE)
                        .compileOptions(o -> o.release(21))
                        .compileOptions(o -> o.add("-Xlint:none"))
                        .execute();
                assertCompiled();
            }
        }
    }

    @Nested
    @DisplayName("JDK Support Tests")
    class JdkSupportTests {

        final VersionNumber v2_50_0 = new VersionNumber(2, 50, 0);

        @Test
        void defaultVersionBeforeJdk17FallsBackToJdk17Version() {
            assertThat(ErrorProneOperation.defaultVersion(11))
                    .isEqualTo(new VersionNumber(2, 42, 0));
            assertThat(ErrorProneOperation.defaultVersion(16))
                    .isEqualTo(new VersionNumber(2, 42, 0));
        }

        @Test
        void defaultVersionForJdk17() {
            assertThat(ErrorProneOperation.defaultVersion(17))
                    .isEqualTo(new VersionNumber(2, 42, 0));
            assertThat(ErrorProneOperation.defaultVersion(20))
                    .isEqualTo(new VersionNumber(2, 42, 0));
        }

        @Test
        void defaultVersionForJdk21Plus() {
            assertThat(ErrorProneOperation.defaultVersion(21))
                    .isSameAs(ErrorProneOperation.ERROR_PRONE_VERSION);
            assertThat(ErrorProneOperation.defaultVersion(25))
                    .isSameAs(ErrorProneOperation.ERROR_PRONE_VERSION);
        }

        @Test
        void executeRequiresSupportedJdkWithExplicitVersion() {
            assertThatThrownBy(() ->
                    ErrorProneOperation.requireSupportedJdk(11, ErrorProneOperation.ERROR_PRONE_VERSION)
            ).isInstanceOf(IllegalStateException.class).hasMessageContaining("2.51.0");
        }

        @Test
        void requireSupportedJdkAllowsJdk21WithAnyVersion() {
            assertThatCode(() -> ErrorProneOperation.requireSupportedJdk(21, v2_50_0))
                    .doesNotThrowAnyException();
            assertThatCode(() ->
                    ErrorProneOperation.requireSupportedJdk(22, new VersionNumber(9, 0, 0))
            ).doesNotThrowAnyException();
        }

        @Test
        void requireSupportedJdkAllowsLastJdk17VersionOnJdk17() {
            assertThatCode(() ->
                    ErrorProneOperation.requireSupportedJdk(17, new VersionNumber(2, 42, 0))
            ).doesNotThrowAnyException();
        }

        @Test
        void requireSupportedJdkAllowsVersionBeforeJdk17() {
            assertThatCode(() ->
                    ErrorProneOperation.requireSupportedJdk(11, new VersionNumber(2, 40, 0))
            ).doesNotThrowAnyException();
        }

        @Test
        void requireSupportedJdkRejectsNewVersionOnJdk11() {
            assertThatThrownBy(() -> ErrorProneOperation.requireSupportedJdk(11, v2_50_0))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("requires JDK 21");
        }

        @Test
        void requireSupportedJdkRejectsNewVersionOnJdk17() {
            assertThatThrownBy(() -> ErrorProneOperation.requireSupportedJdk(17, v2_50_0))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("JVM Options Tests")
    class JvmOptionsTests {

        @Test
        void executeRequiresJvmOptions(@TempDir Path dir) {
            assumeFalse(ErrorProneOperation.missingJvmOptions().isEmpty(),
                    "The JVM has the options, so the check can't fail");
            var op = new ErrorProneOperation().fromProject(projectIn(dir));

            assertThatThrownBy(op::execute).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bld.javaOptions");
        }

        @Test
        void jvmOptions() {
            var expected = Stream.concat(
                    EXPORT_PACKAGES.stream().map(p -> "--add-exports=" + JAVAC_PREFIX + p + "=ALL-UNNAMED"),
                    OPEN_PACKAGES.stream().map(p -> "--add-opens=" + JAVAC_PREFIX + p + "=ALL-UNNAMED")).toList();

            assertThat(ErrorProneOperation.jvmOptions()).containsExactlyElementsOf(expected);
        }

        @Test
        void missingJvmOptionsAreAmongTheJvmOptions() {
            assertThat(ErrorProneOperation.jvmOptions()).containsAll(ErrorProneOperation.missingJvmOptions());
        }

        @Test
        void missingOptionsMessage() {
            var message = ErrorProneOperation.missingOptionsMessage();

            assertThat(message).contains("bld.javaOptions", "lib/bld/bld-wrapper.properties", "separated by spaces");
            ErrorProneOperation.jvmOptions().forEach(o -> assertThat(message).contains(o));
        }

        @Nested
        @DisplayName("Javac Access Tests")
        class JavacAccessTests {

            @Test
            void missingOptionsMessageJoinsGivenOptions() {
                var message = ErrorProneOperation.missingOptionsMessage(List.of("--add-exports=a", "--add-opens=b"));
                assertThat(message)
                        .contains("bld.javaOptions", "lib/bld/bld-wrapper.properties", "separated by spaces")
                        .contains("--add-exports=a --add-opens=b");
            }

            @Test
            void requireJavacAccessFailsWithoutOptions() {
                assumeFalse(ErrorProneOperation.missingJvmOptions().isEmpty(),
                        "The JVM has the options, so the check can't fail");

                assertThatThrownBy(ErrorProneOperation::requireJavacAccess).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("--add-exports")
                        .hasMessageContaining("bld.javaOptions");
            }

            @Test
            void requireJavacAccessNoArgMatchesMissingJvmOptions() {
                var missing = ErrorProneOperation.missingJvmOptions();
                if (missing.isEmpty()) {
                    assertThatCode(ErrorProneOperation::requireJavacAccess).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(ErrorProneOperation::requireJavacAccess)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(ErrorProneOperation.missingOptionsMessage());
                }
            }

            @Test
            void requireJavacAccessPassesWithOptions() {
                assumeTrue(ErrorProneOperation.missingJvmOptions().isEmpty(),
                        "The JVM lacks the options, so the check can't pass");

                assertThatCode(ErrorProneOperation::requireJavacAccess).doesNotThrowAnyException();
            }

            @Test
            void requireJavacAccessWithMissingOptionsThrows() {
                assertThatThrownBy(() -> ErrorProneOperation.requireJavacAccess(List.of("--add-exports=x")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("--add-exports=x")
                        .hasMessageContaining("bld.javaOptions");
            }

            @Test
            void requireJavacAccessWithNoMissingOptionsPasses() {
                assertThatCode(() -> ErrorProneOperation.requireJavacAccess(List.of())).doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("Options Tests")
    class OptionsTests {

        @Nested
        @DisplayName("Check Tests")
        class CheckTests {

            @Test
            void checkAfterFlagOverridesFlag() {
                var op = new ErrorProneOperation().flag("-Xep:Foo:OFF").errorCheck("Foo");
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -Xep:Foo:OFF -Xep:Foo:ERROR");
                assertThat(op.buildPluginOption().indexOf("-Xep:Foo:OFF"))
                        .isLessThan(op.buildPluginOption().indexOf("-Xep:Foo:ERROR"));
            }

            @Test
            void checksFromCollectionPreserveOrder() {
                var op = new ErrorProneOperation()
                        .disableCheck(List.of(CHECK_1, CHECK_2))
                        .errorCheck(CHECK_3);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Check1:OFF -Xep:Check2:OFF -Xep:Check3:ERROR");
            }

            @Test
            void checksFromVarargsPreserveOrder() {
                var op = new ErrorProneOperation().errorCheck(CHECK_1, CHECK_2);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Check1:ERROR -Xep:Check2:ERROR");
            }

            @Test
            void disableCheck() {
                var op = new ErrorProneOperation().disableCheck(CHECK_1);
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -Xep:Check1:OFF");
            }

            @Test
            void disableCheckFromCollection() {
                var op = new ErrorProneOperation().disableCheck(List.of(CHECK_1, CHECK_2));
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Check1:OFF -Xep:Check2:OFF");
            }

            @Test
            void disableCheckFromVarargs() {
                var op = new ErrorProneOperation().disableCheck(CHECK_1, CHECK_2);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Check1:OFF -Xep:Check2:OFF");
            }

            @Test
            void enableCheck() {
                var op = new ErrorProneOperation().enableCheck(CHECK_1);
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -Xep:Check1");
            }

            @Test
            void enableCheckFromCollection() {
                var op = new ErrorProneOperation().enableCheck(List.of(CHECK_1, CHECK_2));
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Check1 -Xep:Check2");
            }

            @Test
            void enableCheckFromVarargs() {
                var op = new ErrorProneOperation().enableCheck(CHECK_1, CHECK_2);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Check1 -Xep:Check2");
            }

            @Test
            void errorCheck() {
                var op = new ErrorProneOperation().errorCheck(CHECK_1);
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -Xep:Check1:ERROR");
            }

            @Test
            void errorCheckWinsOverInterveningDisable() {
                var op = new ErrorProneOperation()
                        .errorCheck(CHECK_1)
                        .disableCheck(CHECK_1)
                        .errorCheck(CHECK_1);
                var plugin = op.buildPluginOption();
                // The two distinct severity flags are both emitted, in call order; the last one wins.
                assertThat(plugin).isEqualTo(PLUGIN + " -Xep:Check1:OFF -Xep:Check1:ERROR");
                assertThat(plugin.lastIndexOf("-Xep:Check1:ERROR"))
                        .isGreaterThan(plugin.lastIndexOf("-Xep:Check1:OFF"));
            }

            @Test
            void flag() {
                var op = new ErrorProneOperation().flag("@errorprone.cfg");
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " @errorprone.cfg");
            }

            @Test
            void flagAfterCheckOverridesCheck() {
                var op = new ErrorProneOperation().errorCheck("Foo").flag("-Xep:Foo:OFF");
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -Xep:Foo:ERROR -Xep:Foo:OFF");
                assertThat(op.buildPluginOption().indexOf("-Xep:Foo:ERROR"))
                        .isLessThan(op.buildPluginOption().indexOf("-Xep:Foo:OFF"));
            }

            @Test
            void flagVarargs() {
                var op = new ErrorProneOperation().flag("@a.cfg", "@b.cfg");
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " @a.cfg @b.cfg");
            }

            @Test
            void reEnablingToggleMovesItToEnd() {
                var op = new ErrorProneOperation()
                        .disableAllChecks(true)
                        .errorCheck("Foo")
                        .disableAllChecks(true);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Foo:ERROR -XepDisableAllChecks");
            }

            @Test
            void warnCheck() {
                var op = new ErrorProneOperation().warnCheck(CHECK_1);
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -Xep:Check1:WARN");
            }

            @Test
            void warnCheckFromCollection() {
                var op = new ErrorProneOperation().warnCheck(List.of(CHECK_1, CHECK_2));
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Check1:WARN -Xep:Check2:WARN");
            }

            @Test
            void warnCheckFromVarargs() {
                var op = new ErrorProneOperation().warnCheck(CHECK_1, CHECK_2);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -Xep:Check1:WARN -Xep:Check2:WARN");
            }
        }

        @Nested
        @DisplayName("CompileOptions Tests")
        class CompileOptionsTests {

            @Test
            void compileOptionsReturnsSameInstance() {
                var op = new ErrorProneOperation();
                var same = op.compileOptions(o -> {
                });
                assertThat(same).isSameAs(op);
            }
        }

        @Nested
        @DisplayName("Excluded Path Tests")
        class ExcludedPathTests {

            @Test
            void duplicateExcludedPathIsEmittedTwice() {
                // excludedPaths_ is joined, not deduped; pin the current behavior.
                var op = new ErrorProneOperation().excludedPath(PATH_1).excludedPath(PATH_1);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -XepExcludedPaths:" + PATH_1 + '|' + PATH_1);
            }

            @Test
            void excludedPath() {
                var op = new ErrorProneOperation().excludedPath(PATH_1);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -XepExcludedPaths:" + PATH_1);
            }

            @Test
            void excludedPathFromCollection() {
                var op = new ErrorProneOperation().excludedPath(List.of(PATH_1, PATH_2));
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -XepExcludedPaths:" + PATH_1 + '|' + PATH_2);
            }

            @Test
            void excludedPathFromPattern() {
                var op = new ErrorProneOperation().excludedPath(Pattern.compile(PATH_1));
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -XepExcludedPaths:" + PATH_1);
            }

            @Test
            void excludedPathFromPatternVarargs() {
                var op = new ErrorProneOperation()
                        .excludedPath(Pattern.compile(PATH_1), Pattern.compile(PATH_2));
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -XepExcludedPaths:" + PATH_1 + '|' + PATH_2);
            }

            @Test
            void excludedPathFromPatternWithFlagsRejected() {
                var pattern = Pattern.compile(PATH_1, Pattern.CASE_INSENSITIVE);
                assertThatThrownBy(() -> new ErrorProneOperation().excludedPath(pattern))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Pattern flags");
            }

            @Test
            void excludedPathsAreJoinedIntoOneRegex() {
                var op = new ErrorProneOperation().excludedPath(PATH_1).excludedPath(PATH_2);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -XepExcludedPaths:" + PATH_1 + '|' + PATH_2);
            }
        }

        @Nested
        @DisplayName("Option Tests")
        class OptionTests {

            @Test
            void duplicateOptionIsEmittedTwice() {
                // options_ is not deduped (unlike pluginArgs_); pin the current behavior.
                var op = new ErrorProneOperation().option(OPTION_1).option(OPTION_1);
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -XepOpt:Option1 -XepOpt:Option1");
            }

            @Test
            void optionWithName() {
                var op = new ErrorProneOperation().option(OPTION_1);
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -XepOpt:Option1");
            }

            @Test
            void optionWithNameAndValue() {
                var op = new ErrorProneOperation().option(OPTION_2, "value");
                assertThat(op.buildPluginOption())
                        .isEqualTo(PLUGIN + " -XepOpt:Namespace:Option2=value");
            }

            @Test
            void optionsPreserveOrder() {
                var op = new ErrorProneOperation().option(OPTION_2, "value").option(OPTION_1);
                assertThat(op.buildPluginOption()).isEqualTo(
                        PLUGIN + " -XepOpt:Namespace:Option2=value -XepOpt:Option1");
            }
        }

        @Nested
        @DisplayName("Patch Tests")
        class PatchTests {

            @Test
            void patchChecksAndLocation() {
                var op = new ErrorProneOperation().patchChecks(List.of(CHECK_1, CHECK_2)).patchLocation("IN_PLACE");
                assertThat(op.buildPluginOption()).isEqualTo(
                        PLUGIN + " -XepPatchChecks:Check1,Check2 -XepPatchLocation:IN_PLACE");
            }

            @Test
            void patchChecksAndLocationWithArray() {
                var op = new ErrorProneOperation().patchChecks(CHECK_1, CHECK_2).patchLocation("IN_PLACE");
                assertThat(op.buildPluginOption()).isEqualTo(
                        PLUGIN + " -XepPatchChecks:Check1,Check2 -XepPatchLocation:IN_PLACE");
            }

            @Test
            void patchChecksOnly() {
                var op = new ErrorProneOperation().patchChecks(List.of(CHECK_1));
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -XepPatchChecks:Check1");
            }

            @Test
            void patchLocationOnly() {
                var op = new ErrorProneOperation().patchLocation("IN_PLACE");
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " -XepPatchLocation:IN_PLACE");
            }
        }

        @Nested
        @DisplayName("Plugin Option Tests")
        class PluginOptionTests {

            @Test
            void allOptionsInDocumentedOrder() {
                var op = new ErrorProneOperation()
                        .flag("-XepIgnoreUnknownCheckNames")
                        .errorCheck(CHECK_1)
                        .allErrorsAsWarnings(true)
                        .option(OPTION_1)
                        .excludedPath(PATH_1)
                        .patchChecks(List.of(CHECK_2))
                        .patchLocation("IN_PLACE")
                        .disableCheck(CHECK_3);

                assertThat(op.buildPluginOption()).isEqualTo(
                        PLUGIN +
                                " -XepIgnoreUnknownCheckNames -Xep:Check1:ERROR -XepAllErrorsAsWarnings" +
                                " -Xep:Check3:OFF" +
                                " -XepOpt:Option1" +
                                " -XepExcludedPaths:" + PATH_1 +
                                " -XepPatchChecks:Check2" +
                                " -XepPatchLocation:IN_PLACE");
            }

            @Test
            void defaultPluginOption() {
                assertThat(new ErrorProneOperation().buildPluginOption()).isEqualTo(PLUGIN);
            }

            @Test
            void pluginArgsPreserveCallOrder() {
                var op = new ErrorProneOperation()
                        .errorCheck(CHECK_1)
                        .disableAllChecks(true)
                        .warnCheck(CHECK_2);
                assertThat(op.buildPluginOption()).isEqualTo(
                        PLUGIN + " -Xep:Check1:ERROR -XepDisableAllChecks -Xep:Check2:WARN");
            }
        }

        @Nested
        @DisplayName("Toggle Tests")
        class ToggleTests {

            @ParameterizedTest(name = "{0}")
            @MethodSource("rife.bld.extension.ErrorProneOperationTest#toggles")
            void toggleDisabled(String flag, BiFunction<ErrorProneOperation, Boolean, ErrorProneOperation> setter) {
                var op = setter.apply(setter.apply(new ErrorProneOperation(), true), false);
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN);
            }

            @ParameterizedTest(name = "{0}")
            @MethodSource("rife.bld.extension.ErrorProneOperationTest#toggles")
            void toggleEnabled(String flag, BiFunction<ErrorProneOperation, Boolean, ErrorProneOperation> setter) {
                var op = setter.apply(new ErrorProneOperation(), true);
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " " + flag);
            }

            @ParameterizedTest(name = "{0}")
            @MethodSource("rife.bld.extension.ErrorProneOperationTest#toggles")
            void toggleEnabledTwice(String flag, BiFunction<ErrorProneOperation, Boolean, ErrorProneOperation> setter) {
                var op = setter.apply(setter.apply(new ErrorProneOperation(), true), true);
                assertThat(op.buildPluginOption()).isEqualTo(PLUGIN + " " + flag);
            }
        }

        @Nested
        @DisplayName("Version Tests")
        class VersionTests {

            @Test
            @EnabledForJreRange(min = JRE.JAVA_21, disabledReason = "Only on JDK 21+")
            void defaultVersion() {
                assertThat(new ErrorProneOperation().version()).isSameAs(ErrorProneOperation.ERROR_PRONE_VERSION);
            }

            @Test
            void defaultVersionIsCompatibleWithCurrentJdk() {
                var v = new ErrorProneOperation().version();
                assertThatCode(() -> ErrorProneOperation.requireSupportedJdk(Runtime.version().feature(), v))
                        .doesNotThrowAnyException();
            }

            @Test
            void versionAsString() {
                var op = new ErrorProneOperation();
                op.version("2.40.0");
                assertThat(op.version()).isEqualTo(new VersionNumber(2, 40, 0));
            }

            @Test
            void versionAsVersionNumber() {
                var version = new VersionNumber(2, 40, 0);
                var op = new ErrorProneOperation();
                op = op.version(version);
                assertThat(op.version()).isSameAs(version);
            }
        }
    }

    @Nested
    @DisplayName("Processor Path Tests")
    class ProcessorPathTests {

        @Test
        void consecutiveProcessorPathFlagsLeaveTheValueWithTheLastFlag() {
            var options = new JavacOptions();
            options.add("--processor-path");
            options.add("--processor-path");
            options.add("x.jar");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            var value = options.get(options.indexOf("--processor-path") + 1);
            assertThat(value).contains("x.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void danglingLongFlagFollowedByEqualsFormIsTreatedAsDangling() {
            var options = new JavacOptions();
            options.add("--processor-path");
            options.add("--processor-path=a.jar");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            var value = options.get(options.indexOf("--processor-path") + 1);
            assertThat(value).contains("a.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void doesNotDuplicateWhenSameProcessorPath() {
            var options = new JavacOptions();
            options.processorPath(PROCESSOR_PATH);

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            var flagIndex = options.indexOf("--processor-path");
            var value = options.get(flagIndex + 1);
            // The path appears once in the value, whether stored joined or not.
            assertThat(value.split(Pattern.quote(PROCESSOR_PATH), -1)).hasSize(2);
        }

        @Test
        void doesNotStrandProcessorPathValueToken() {
            var options = new JavacOptions();
            options.processorPath("project-processors.jar");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            var flagIndex = options.indexOf("--processor-path");
            var value = options.get(flagIndex + 1);
            assertThat(value).contains("project-processors.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void handlesProcessorPathWithoutValue() {
            var options = new JavacOptions();
            options.add("--processor-path"); // dangling: no value follows

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            // Exactly one --processor-path remains (the merged one).
            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);

            // The option appended after the merge must not have been swallowed.
            assertThat(options).contains("-XDaddTypeAnnotationsToSymbol=true");

            // The merged value must contain the Error Prone path and must not contain the swallowed option.
            var flagIndex = options.indexOf("--processor-path");
            var value = options.get(flagIndex + 1);
            assertThat(value).contains(PROCESSOR_PATH);
            assertThat(value).doesNotContain("-XDaddTypeAnnotationsToSymbol");
        }

        @Test
        void keepsProcessorPathValueThatStartsWithDash() {
            var options = new JavacOptions();
            options.add("--processor-path");
            options.add("-weird/dir/x.jar");
            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);
            var joined = String.join(" ", options);
            assertThat(joined).contains("-weird/dir/x.jar").contains(PROCESSOR_PATH);
            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
        }

        @Test
        void longFlagFollowedByShortFlagIsTreatedAsDangling() {
            var options = new JavacOptions();
            options.add("--processor-path");
            options.add("-processorpath");
            options.add("x.jar");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            // The first flag is dangling; the short flag keeps x.jar; the merged value has both.
            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            assertThat(options).doesNotContain("-processorpath");
            var value = options.get(options.indexOf("--processor-path") + 1);
            assertThat(value).contains("x.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void mergesEqualsFormFromCustomizer() {
            var options = new JavacOptions();
            new ErrorProneOperation()
                    .compileOptions(o -> o.add("--processor-path=custom.jar"))
                    .configure(options, PROCESSOR_PATH, 21);
            var value = options.get(options.indexOf("--processor-path") + 1);
            assertThat(value).contains("custom.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void mergesEqualsFormProcessorPath() {
            var options = new JavacOptions();
            options.add("--processor-path=project-processors.jar");
            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);
            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            assertThat(options).noneMatch(o -> o.startsWith("--processor-path="));
            var value = options.get(options.indexOf("--processor-path") + 1);
            assertThat(value).contains("project-processors.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void mergesWithExistingProcessorPath() {
            var options = new JavacOptions();
            options.processorPath("project-processors.jar");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            // Exactly one --processor-path flag remains.
            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            var joined = String.join(" ", options);
            assertThat(joined).contains("project-processors.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void mergesWithExistingShortProcessorPath() {
            var options = new JavacOptions();
            options.add("-processorpath");
            options.add("project-processors.jar");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            assertThat(options).doesNotContain("-processorpath");
            var joined = String.join(" ", options);
            assertThat(joined).contains("project-processors.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void rechecksShiftedElementAfterRemoval() {
            // --processor-path followed by another option, so removal shifts the next element into the index.
            var options = new JavacOptions();
            options.add("--processor-path");
            options.add("a.jar");
            options.add("-Xlint:none");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            assertThat(options).contains("-Xlint:none");
            assertThat(String.join(" ", options)).contains("a.jar").contains(PROCESSOR_PATH);
            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
        }

        @Test
        void removesMultipleExistingProcessorPathFlags() {
            var options = new JavacOptions();
            options.processorPath("a.jar");
            options.processorPath("b.jar");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            var joined = String.join(" ", options);
            assertThat(joined).contains("a.jar").contains("b.jar").contains(PROCESSOR_PATH);
        }

        @Test
        void shortFlagFollowedByLongFlagIsTreatedAsDangling() {
            var options = new JavacOptions();
            options.add("-processorpath");
            options.add("--processor-path");
            options.add("x.jar");

            new ErrorProneOperation().configure(options, PROCESSOR_PATH, 21);

            assertThat(options.stream().filter("--processor-path"::equals)).hasSize(1);
            assertThat(options).doesNotContain("-processorpath");
            var value = options.get(options.indexOf("--processor-path") + 1);
            assertThat(value).contains("x.jar").contains(PROCESSOR_PATH);
        }
    }

    @Nested
    @DisplayName("ReportingCompileOperation Tests")
    class ReportingCompileOperationTests {

        @Test
        void compilesWithJavacDiagnosticsEnabled(@TempDir Path dir) throws Exception {
            writeSource(dir, UNCHECKED_SOURCE);
            var project = projectIn(dir);
            var op = new ErrorProneOperation.ReportingCompileOperation().fromProject(project);
            op.compileOptions().add("-Xlint:unchecked");
            op.execute();
            assertThat(dir.resolve("build/main/com/example/Example.class")).exists();
        }

        private Path createValidJar(Path dir) throws IOException {
            var jarPath = dir.resolve("fake.jar");
            var manifest = new java.util.jar.Manifest();
            manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
            try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
                jos.putNextEntry(new java.util.jar.JarEntry("META-INF/"));
                jos.closeEntry();
            }
            return jarPath;
        }

        private ErrorProneOperation.ReportingCompileOperation newDirectOp() {
            return new ErrorProneOperation.ReportingCompileOperation();
        }

        private java.io.File writeDirectSource(Path dir, String className, String body) throws IOException {
            var src = "public class " + className + " { " + body + " }";
            var file = dir.resolve(className + ".java").toFile();
            Files.writeString(file.toPath(), src);
            return file;
        }

        @Nested
        @DisplayName("executeBuildSources Tests")
        class executeBuildSourcesTests {

            @Test
            void classpath(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "WithCp", "public String greet(){ return \"hi\"; }");
                var fakeJar = createValidJar(dir);
                op.executeBuildSources(List.of(fakeJar.toString()), List.of(), List.of(src), dest);
                assertThat(new java.io.File(dest, "WithCp.class")).exists();
            }

            @Test
            void classpathAndModulePathBothSet(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "BothPaths", "public String greet(){ return \"hi\"; }");
                var fakeJar = createValidJar(dir);
                var fakeMod = dir.resolve("mods");
                Files.createDirectories(fakeMod);
                op.executeBuildSources(List.of(fakeJar.toString()), List.of(fakeMod.toString()), List.of(src), dest);
                assertThat(new java.io.File(dest, "BothPaths.class")).exists();
            }

            @Test
            void compileOptions(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                op.compileOptions().add("-Xlint:none");
                op.compileOptions().add("-parameters");
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "WithOpts", "public String greet(){ return \"hi\"; }");
                op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                assertThat(new java.io.File(dest, "WithOpts.class")).exists();
            }

            @Test
            void emptyClasspathAndModulePath(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "EmptyCp", "public String greet(){ return \"hi\"; }");
                op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                assertThat(new java.io.File(dest, "EmptyCp.class")).exists();
                assertThat(op.diagnostics()).isEmpty();
            }

            @Test
            void emptySourcesThrows(@TempDir Path dir) {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                assertThatThrownBy(() -> op.executeBuildSources(List.of(), List.of(), List.of(), dest))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sources");
            }

            @Test
            void failedCompilationAddsDiagnostics(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var bad = writeDirectSource(dir, "BadSyntax", "this is not valid java!!!");
                op.executeBuildSources(List.of(), List.of(), List.of(bad), dest);
                assertThat(op.diagnostics()).isNotEmpty();
                assertThat(new java.io.File(dest, "BadSyntax.class")).doesNotExist();
            }

            @Test
            void fineLogging(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                op.verbose(true);
                op.silent(false);
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "FineLog", "public String greet(){ return \"hi\"; }");
                var logger = Logger.getLogger(ErrorProneOperation.class.getName());
                var old = logger.getLevel();
                logger.setLevel(Level.FINE);
                try {
                    op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                    assertThat(new java.io.File(dest, "FineLog.class")).exists();
                } finally {
                    logger.setLevel(old);
                }
            }

            @Test
            void moduleInfoExistsButNoMainClass(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var moduleInfoJava = dir.resolve("module-info.java");
                Files.writeString(moduleInfoJava, "module test {}");
                var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
                try (var fm = compiler.getStandardFileManager(null, null, null)) {
                    var task = compiler.getTask(null, fm, null,
                            List.of("-d", dest.getAbsolutePath()), null,
                            fm.getJavaFileObjects(moduleInfoJava.toFile()));
                    task.call();
                }
                var src = writeDirectSource(dir, "ModuleExists",
                        "public String greet(){ return \"hi\"; }");
                op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                assertThat(new java.io.File(dest, "ModuleExists.class")).exists();
            }

            @Test
            void moduleInfoWithMainClass(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                op.moduleMainClass("com.example.Main");
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var moduleInfoJava = dir.resolve("module-info.java");
                Files.writeString(moduleInfoJava, "module test {}");
                var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
                try (var fm = compiler.getStandardFileManager(null, null, null)) {
                    var task = compiler.getTask(null, fm, null,
                            List.of("-d", dest.getAbsolutePath()), null,
                            fm.getJavaFileObjects(moduleInfoJava.toFile()));
                    assertThat(task.call()).isTrue();
                }
                assertThat(new java.io.File(dest, "module-info.class")).exists();
                var src = writeDirectSource(dir, "ModuleTest", "public String greet(){ return \"hi\"; }");
                op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                assertThat(new java.io.File(dest, "ModuleTest.class")).exists();
            }

            @Test
            void moduleInfoWithoutMainClass(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "ModuleNoMain",
                        "public String greet(){ return \"hi\"; }");
                op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                assertThat(new java.io.File(dest, "ModuleNoMain.class")).exists();
            }

            @Test
            void modulePath(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "WithMp", "public String greet(){ return \"hi\"; }");
                var fakeMod = dir.resolve("mods");
                Files.createDirectories(fakeMod);
                op.executeBuildSources(List.of(), List.of(fakeMod.toString()), List.of(src), dest);
                assertThat(new java.io.File(dest, "WithMp.class")).exists();
            }

            @Test
            @SuppressWarnings("DataFlowIssue")
            void nullSourcesThrows(@TempDir Path dir) {
                var op = newDirectOp();
                var dest = dir.resolve("out").toFile();
                assertThatThrownBy(() -> op.executeBuildSources(List.of(), List.of(), null, dest))
                        .isInstanceOf(NullPointerException.class).hasMessageContaining("sources");
            }

            @Test
            void silentTrue(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                op.silent(true);
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "SilentTrue", "public String greet(){ return \"hi\"; }");
                op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                assertThat(new java.io.File(dest, "SilentTrue.class")).exists();
            }

            @Test
            void verboseFalse(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                op.verbose(false);
                op.silent(false);
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "VerboseFalse",
                        "public String greet(){ return \"hi\"; }");
                op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                assertThat(new java.io.File(dest, "VerboseFalse.class")).exists();
            }

            @Test
            void verboseTrueInfoLogging(@TempDir Path dir) throws Exception {
                var op = newDirectOp();
                op.verbose(true);
                op.silent(false);
                var dest = dir.resolve("out").toFile();
                Files.createDirectories(dest.toPath());
                var src = writeDirectSource(dir, "VerboseTrue",
                        "public String greet(){ return \"hi\"; }");
                var logger = Logger.getLogger(ErrorProneOperation.class.getName());
                var old = logger.getLevel();
                logger.setLevel(Level.INFO);
                try {
                    op.executeBuildSources(List.of(), List.of(), List.of(src), dest);
                    assertThat(new java.io.File(dest, "VerboseTrue.class")).exists();
                } finally {
                    logger.setLevel(old);
                }
            }
        }
    }

    @Nested
    @DisplayName("ErrorProneOperation Validation Tests")
    @SuppressWarnings("DataFlowIssue")
    class ValidationTests {

        @Test
        void compileOptionsWithNull() {
            assertThatThrownBy(() -> new ErrorProneOperation().compileOptions(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void disableCheckWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().disableCheck(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        void disableCheckWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().disableCheck(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }

        @ParameterizedTest
        @NullAndEmptySource
        void enableCheckWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().enableCheck(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        void enableCheckWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().enableCheck(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }

        @ParameterizedTest
        @NullAndEmptySource
        void errorCheckWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().errorCheck(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        void errorCheckWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().errorCheck(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }

        @Test
        void excludedPathPatternWithEmptyVarargs() {
            assertThatThrownBy(() -> new ErrorProneOperation().excludedPath(new Pattern[0]))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void excludedPathPatternWithNullArray() {
            assertThatThrownBy(() -> new ErrorProneOperation().excludedPath((Pattern[]) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void excludedPathPatternWithNullElement() {
            assertThatThrownBy(() -> new ErrorProneOperation().excludedPath((Pattern) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void excludedPathWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().excludedPath(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        void excludedPathWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().excludedPath(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }

        @Test
        void flagWithEmptyVarargs() {
            assertThatThrownBy(() -> new ErrorProneOperation().flag())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void flagWithNullArray() {
            assertThatThrownBy(() -> new ErrorProneOperation().flag((String[]) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void flagWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().flag(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        @ParameterizedTest
        void flagWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().flag(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }

        @Test
        void fromProjectWithNull() {
            assertThatThrownBy(() -> new ErrorProneOperation().fromProject(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void optionWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().option(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
            assertThatThrownBy(() -> new ErrorProneOperation().option(arg, "value"))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
            assertThatThrownBy(() -> new ErrorProneOperation().option("name", arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        void optionWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().option(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
            assertThatThrownBy(() -> new ErrorProneOperation().option(arg, "value"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
            assertThatThrownBy(() -> new ErrorProneOperation().option("name", arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }

        @Test
        void patchChecksWithNullArray() {
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks((String[]) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void patchChecksWithNullCollection() {
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks((Collection<String>) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @SuppressWarnings("NullableProblems")
        void patchChecksWithNullOrEmpty() {
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks((String) null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks("foo", null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks(Arrays.asList(CHECK_1, null)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks(List.of(CHECK_1, "")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        void patchChecksWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
            assertThatThrownBy(() -> new ErrorProneOperation().patchChecks(List.of(arg)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }

        @ParameterizedTest
        @NullAndEmptySource
        void patchLocationWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().patchLocation(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        void patchLocationWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().patchLocation(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }

        @Test
        void versionAsInvalidStringThrows() {
            assertThatThrownBy(() -> new ErrorProneOperation().version("not-a-version"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void versionAsStringWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().version(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @Test
        void versionWithNull() {
            assertThatThrownBy(() -> new ErrorProneOperation().version((Version) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void warnCheckWithNullOrEmpty(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().warnCheck(arg))
                    .isInstanceOf(arg == null ? NullPointerException.class : IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has\ttab", "has\u00a0nbsp", "has\u0007bell"})
        void warnCheckWithWhitespace(String arg) {
            assertThatThrownBy(() -> new ErrorProneOperation().warnCheck(arg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        }
    }
}