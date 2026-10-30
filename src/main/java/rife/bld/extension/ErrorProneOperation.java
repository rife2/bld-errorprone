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

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import rife.bld.BaseProject;
import rife.bld.dependencies.Dependency;
import rife.bld.dependencies.Repository;
import rife.bld.dependencies.Version;
import rife.bld.dependencies.VersionNumber;
import rife.bld.extension.tools.*;
import rife.bld.instrument.ModuleMainClassAdapter;
import rife.bld.operations.AbstractOperation;
import rife.bld.operations.CompileOperation;
import rife.bld.operations.JavacOptions;
import rife.tools.FileUtils;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Compiles source code using Error Prone.
 * <p>
 * All javac diagnostics are reported, not just those of a failed compilation.
 * <p>
 * The Error Prone version defaults to {@link #ERROR_PRONE_VERSION} on JDK 21+ and {@code 2.42.0} on JDK 17-20.
 * <p>
 * <b>Required JVM options</b> in {@code lib/bld/bld-wrapper.properties}:
 * <pre>
 * bld.javaOptions=--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED\
 *   \ --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED\
 *   \ --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED\
 *   \ --add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED\
 *   \ --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED\
 *   \ --add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED\
 *   \ --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED\
 *   \ --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED\
 *   \ --add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED\
 *   \ --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
 * </pre>
 *
 * @author <a href="https://erik.thauvin.net/">Erik C. Thauvin</a>
 * @since 1.0
 */
@NullMarked
@SuppressWarnings("PMD.LooseCoupling")
public class ErrorProneOperation extends AbstractOperation<ErrorProneOperation> {

    /**
     * The Error Prone version used on JDK 21 and newer.
     */
    public static final Version ERROR_PRONE_VERSION = new VersionNumber(2, 51, 0);
    private static final String ALL_UNNAMED = "=ALL-UNNAMED";
    private static final String EXCLUDED_PATH = "excludedPath";
    private static final List<String> EXPORT_PACKAGES = List.of(
            "api", "file", "main", "model", "parser", "processing", "tree", "util");
    private static final String JAVAC_PACKAGE = "com.sun.tools.javac.";
    private static final Version LAST_JDK17_VERSION = new VersionNumber(2, 42, 0);
    private static final List<String> OPEN_PACKAGES = List.of("code", "comp");
    private static final String OPTION_NAME = "option name";
    private static final String OPTION_VALUE = "option value";
    private static final String PATCH_CHECKS = "patchChecks";
    private static final String PROCESSOR_PATH_FLAG = "--processor-path";
    private static final String PROCESSOR_PATH_FLAG_SHORT = "-processorpath";
    private static final List<Repository> REPOSITORIES = List.of(Repository.MAVEN_CENTRAL);
    private static final String XEP = "-Xep:";
    private static final Logger logger = Logger.getLogger(ErrorProneOperation.class.getName());
    private final List<Consumer<JavacOptions>> compileOptionsCustomizers_ = new ArrayList<>();
    private final List<String> excludedPaths_ = new ArrayList<>();
    private final List<String> options_ = new ArrayList<>();
    private final List<String> patchChecks_ = new ArrayList<>();
    private final Set<String> pluginArgs_ = new LinkedHashSet<>();
    private final List<Repository> repositories_ = new ArrayList<>(REPOSITORIES);
    private @Nullable String patchLocation_;
    private @Nullable BaseProject project_;
    private Version version_ = defaultVersion(Runtime.version().feature());

    /**
     * Compiles the project's main and test sources.
     *
     * @throws NullPointerException  if no project was set
     * @throws IllegalStateException if the JDK is unsupported, the JVM lacks required {@code --add-exports}/
     *                               {@code --add-opens}, or the Error Prone jars can't be downloaded
     * @throws Exception             if compilation fails
     */
    @Override
    public void execute() throws Exception {
        var project = ObjectTools.requireNonNull(project_, "project");
        var jdkFeature = Runtime.version().feature();

        requireSupportedJdk(jdkFeature, version_);
        requireJavacAccess();

        var sandbox = new Sandbox("bld-errorprone", project);
        var errorProneDir = sandbox.downloadDependencies(
                List.of(new Dependency("com.google.errorprone", "error_prone_core", version_)),
                repositories_);

        // Seed the javac options with the project's compile options before fromProject, so fromProject's
        // containsRelease() check sees the project's -release and skips adding a duplicate. Both reads are
        // guarded because a BaseProject subclass can return null despite the @NullMarked contract.
        var projectCompile = project.compileOperation();
        var compileOp = new ReportingCompileOperation();
        if (projectCompile != null) {
            var projectOptions = projectCompile.compileOptions();
            if (projectOptions != null) {
                compileOp.compileOptions().addAll(projectOptions);
            }
        }
        compileOp.fromProject(project);
        compileOp.silent(silent());
        compileOp.verbose(verbose());
        configure(compileOp.compileOptions(), processorPath(errorProneDir), jdkFeature);

        if (!silent() && logger.isLoggable(Level.INFO)) {
            logger.info("Compiling with Error Prone " + version_);
        }

        compileOp.execute();
    }

    static Version defaultVersion(int jdkFeature) {
        return jdkFeature >= 21 ? ERROR_PRONE_VERSION : LAST_JDK17_VERSION;
    }

    // The JVM options giving Error Prone access to the javac internals, see the Error Prone installation guide.
    static List<String> jvmOptions() {
        var options = new ArrayList<String>(EXPORT_PACKAGES.size() + OPEN_PACKAGES.size());
        EXPORT_PACKAGES.forEach(p -> options.add("--add-exports=jdk.compiler/" + JAVAC_PACKAGE + p + ALL_UNNAMED));
        OPEN_PACKAGES.forEach(p -> options.add("--add-opens=jdk.compiler/" + JAVAC_PACKAGE + p + ALL_UNNAMED));
        return options;
    }

    // The JVM options of jvmOptions() which the running JVM lacks. Exports to ALL-UNNAMED apply to every unnamed
    // module, so the one of this class stands in for the one javac loads the Error Prone plugin in.
    static List<String> missingJvmOptions() {
        var compiler = ModuleLayer.boot().findModule("jdk.compiler");
        if (compiler.isEmpty()) {
            return List.of();
        }

        var module = ErrorProneOperation.class.getModule();
        var missing = new ArrayList<String>();
        EXPORT_PACKAGES.stream()
                .filter(p -> !compiler.get().isExported(JAVAC_PACKAGE + p, module))
                .forEach(p -> missing.add("--add-exports=jdk.compiler/" + JAVAC_PACKAGE + p + ALL_UNNAMED));
        OPEN_PACKAGES.stream()
                .filter(p -> !compiler.get().isOpen(JAVAC_PACKAGE + p, module))
                .forEach(p -> missing.add("--add-opens=jdk.compiler/" + JAVAC_PACKAGE + p + ALL_UNNAMED));
        return missing;
    }

    // The message telling which JVM options to set, as javac crashes without them.
    static String missingOptionsMessage() {
        return missingOptionsMessage(jvmOptions());
    }

    static String missingOptionsMessage(List<String> options) {
        return "Error Prone needs access to the javac internals. Add the following options to bld.javaOptions in "
                + "lib/bld/bld-wrapper.properties, separated by spaces, not commas: "
                + String.join(" ", options);
    }

    // Fails early, with what to do, rather than letting javac crash with an IllegalAccessError.
    static void requireJavacAccess() {
        requireJavacAccess(missingJvmOptions());
    }

    static void requireJavacAccess(List<String> missing) {
        if (!missing.isEmpty()) {
            throw new IllegalStateException(missingOptionsMessage(missing));
        }
    }

    // Whitespace would split the plugin argument, which javac passes to Error Prone as one string.
    // Also ensures the value is not null, empty or blank.
    // Note: U+200B (zero-width space) is deliberately not rejected, as it would not split the argument.
    private static String requireNoWhitespace(String value, String name) {
        TextTools.requireNotBlank(value, name);
        if (value.codePoints().anyMatch(c -> Character.isWhitespace(c)
                || Character.isSpaceChar(c)
                || Character.isISOControl(c))) {
            throw new IllegalArgumentException(name + " must not contain whitespace or control characters: " + value);
        }
        return value;
    }

    // Fails early, with what to do, rather than letting the JVM fail with an UnsupportedClassVersionError.
    static void requireSupportedJdk(int jdk, Version version) {
        if (jdk >= 21) {
            return;
        }

        if (version.compareTo(LAST_JDK17_VERSION) > 0) {
            throw new IllegalStateException("Error Prone " + version + " requires JDK 21 or newer. On JDK " + jdk
                    + " use " + LAST_JDK17_VERSION + " or earlier, see version(String).");
        }
    }

    /**
     * Reports all disabled-by-default checks as warnings ({@code -XepAllDisabledChecksAsWarnings}).
     *
     * @param allDisabledChecksAsWarnings {@code true} to enable
     * @return this operation
     */
    public ErrorProneOperation allDisabledChecksAsWarnings(boolean allDisabledChecksAsWarnings) {
        return toggle("-XepAllDisabledChecksAsWarnings", allDisabledChecksAsWarnings);
    }

    /**
     * Reports all Error Prone errors as warnings ({@code -XepAllErrorsAsWarnings}).
     *
     * @param allErrorsAsWarnings {@code true} to downgrade
     * @return this operation
     */
    public ErrorProneOperation allErrorsAsWarnings(boolean allErrorsAsWarnings) {
        return toggle("-XepAllErrorsAsWarnings", allErrorsAsWarnings);
    }

    /**
     * Reports all suggestion-level findings as warnings ({@code -XepAllSuggestionsAsWarnings}).
     *
     * @param allSuggestionsAsWarnings {@code true} to report
     * @return this operation
     */
    public ErrorProneOperation allSuggestionsAsWarnings(boolean allSuggestionsAsWarnings) {
        return toggle("-XepAllSuggestionsAsWarnings", allSuggestionsAsWarnings);
    }

    /**
     * Configures the {@link JavacOptions} used for the compilation.
     * <p>
     * Customizers run before the Error Prone options, so the operation's own options win.
     * <p>
     * A customizer-supplied {@code --processor-path} is merged with the Error Prone processor path.
     * <p>
     * Annotation processors that javac would otherwise discover on the compile classpath are no longer found
     * because the operation always sets a processor path; add them with {@code o.processorPath(...)} if needed.
     *
     * @param customizer the customizer
     * @return this operation
     * @throws NullPointerException if {@code customizer} is {@code null}
     */
    public ErrorProneOperation compileOptions(Consumer<JavacOptions> customizer) {
        compileOptionsCustomizers_.add(ObjectTools.requireNonNull(customizer, "customizer"));
        return this;
    }

    /**
     * Disables all checks ({@code -XepDisableAllChecks}).
     * <p>
     * Enable individual checks afterward with {@link #errorCheck(String...)}.
     *
     * @param disableAllChecks {@code true} to disable
     * @return this operation
     */
    public ErrorProneOperation disableAllChecks(boolean disableAllChecks) {
        return toggle("-XepDisableAllChecks", disableAllChecks);
    }

    /**
     * Disables all warning-level checks ({@code -XepDisableAllWarnings}).
     *
     * @param disableAllWarnings {@code true} to disable
     * @return this operation
     */
    public ErrorProneOperation disableAllWarnings(boolean disableAllWarnings) {
        return toggle("-XepDisableAllWarnings", disableAllWarnings);
    }

    /**
     * Disables Error Prone checks ({@code -Xep:Check:OFF}).
     * <p>
     * Later calls for the same name win.
     *
     * @param names the check names
     * @return this operation
     * @throws NullPointerException     if {@code names} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or any name is blank or contains
     *                                  whitespace or control characters
     * @see #disableCheck(String...)
     */
    public ErrorProneOperation disableCheck(Collection<String> names) {
        ObjectTools.requireNotEmpty(names, "disableCheck");
        for (var name : names) {
            addPluginArg(XEP + requireNoWhitespace(name, "disableCheck") + ":OFF");
        }
        return this;
    }

    /**
     * Disables Error Prone checks ({@code -Xep:Check:OFF}).
     * <p>
     * Later calls for the same name win.
     *
     * @param names the check names
     * @return this operation
     * @throws NullPointerException     if {@code names} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or any name is blank or contains
     *                                  whitespace or control characters
     * @see #disableCheck(Collection)
     */
    public ErrorProneOperation disableCheck(String... names) {
        ObjectTools.requireNotEmpty(names, "disableCheck");
        return disableCheck(List.of(names));
    }

    /**
     * Disables warnings in classes annotated with {@code @Generated} ({@code -XepDisableWarningsInGeneratedCode}).
     *
     * @param disableWarningsInGeneratedCode {@code true} to disable
     * @return this operation
     */
    public ErrorProneOperation disableWarningsInGeneratedCode(boolean disableWarningsInGeneratedCode) {
        return toggle("-XepDisableWarningsInGeneratedCode", disableWarningsInGeneratedCode);
    }

    /**
     * Enables Error Prone checks at the severity from their own definition ({@code -Xep:Check}).
     * <p>
     * Later calls for the same name win.
     *
     * @param names the check names
     * @return this operation
     * @throws NullPointerException     if {@code names} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or any name is blank or contains
     *                                  whitespace or control characters
     * @see #enableCheck(String...)
     */
    public ErrorProneOperation enableCheck(Collection<String> names) {
        ObjectTools.requireNotEmpty(names, "enableCheck");
        for (var name : names) {
            addPluginArg(XEP + requireNoWhitespace(name, "enableCheck"));
        }
        return this;
    }

    /**
     * Enables Error Prone checks at the severity from their own definition ({@code -Xep:Check}).
     * <p>
     * Later calls for the same name win.
     *
     * @param names the check names
     * @return this operation
     * @throws NullPointerException     if {@code names} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or any name is blank or contains
     *                                  whitespace or control characters
     * @see #enableCheck(Collection)
     */
    public ErrorProneOperation enableCheck(String... names) {
        ObjectTools.requireNotEmpty(names, "enableCheck");
        return enableCheck(List.of(names));
    }

    /**
     * Sets Error Prone checks to report at error severity ({@code -Xep:Check:ERROR}).
     * <p>
     * Later calls for the same name win.
     *
     * @param names the check names
     * @return this operation
     * @throws NullPointerException     if {@code names} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or any name is blank or contains
     *                                  whitespace or control characters
     * @see #errorCheck(String...)
     */
    public ErrorProneOperation errorCheck(Collection<String> names) {
        ObjectTools.requireNotEmpty(names, "errorCheck");
        for (var name : names) {
            addPluginArg(XEP + requireNoWhitespace(name, "errorCheck") + ":ERROR");
        }
        return this;
    }

    /**
     * Sets Error Prone checks to report at error severity ({@code -Xep:Check:ERROR}).
     * <p>
     * Later calls for the same name win.
     *
     * @param names the check names
     * @return this operation
     * @throws NullPointerException     if {@code names} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or any name is blank or contains
     *                                  whitespace or control characters
     * @see #errorCheck(Collection)
     */
    public ErrorProneOperation errorCheck(String... names) {
        ObjectTools.requireNotEmpty(names, "errorCheck");
        return errorCheck(List.of(names));
    }

    /**
     * Excludes source paths from analysis ({@code -XepExcludedPaths:regex}).
     * <p>
     * Values are joined with {@code |}.
     * <p>
     * The regex must not contain whitespace.
     *
     * @param regexes the regexes
     * @return this operation
     * @throws NullPointerException     if {@code regexes} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code regexes} is empty, or any regex is blank or contains
     *                                  whitespace or control characters
     * @see #excludedPath(String...)
     * @see #excludedPath(Pattern...)
     */
    public ErrorProneOperation excludedPath(Collection<String> regexes) {
        ObjectTools.requireNotEmpty(regexes, EXCLUDED_PATH);
        for (var regex : regexes) {
            excludedPaths_.add(requireNoWhitespace(regex, EXCLUDED_PATH));
        }
        return this;
    }

    /**
     * Excludes source paths from analysis ({@code -XepExcludedPaths:regex}).
     * <p>
     * Values are joined with {@code |}.
     * <p>
     * The regex must not contain whitespace.
     *
     * @param regexes the regexes
     * @return this operation
     * @throws NullPointerException     if {@code regexes} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code regexes} is empty, or any regex is blank or contains
     *                                  whitespace or control characters
     * @see #excludedPath(Collection)
     * @see #excludedPath(Pattern...)
     */
    public ErrorProneOperation excludedPath(String... regexes) {
        ObjectTools.requireNotEmpty(regexes, EXCLUDED_PATH);
        return excludedPath(List.of(regexes));
    }

    /**
     * Excludes source paths from analysis ({@code -XepExcludedPaths:regex}).
     * <p>
     * Values are joined with {@code |}.
     * <p>
     * Any pattern with non-zero {@link Pattern#flags() flags} is rejected (including {@code (?i)}). Use a
     * {@code String} overload for flag-dependent matching.
     *
     * @param patterns the patterns
     * @return this operation
     * @throws NullPointerException     if {@code patterns} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code patterns} is empty, any element has non-zero {@code flags()},
     *                                  or any pattern string is blank or contains whitespace or control characters
     * @see #excludedPath(String...)
     * @see #excludedPath(Collection)
     */
    public ErrorProneOperation excludedPath(Pattern... patterns) {
        ObjectTools.requireNotEmpty(patterns, EXCLUDED_PATH);
        var regexes = new ArrayList<String>(patterns.length);
        for (var pattern : patterns) {
            if (pattern.flags() != 0) {
                throw new IllegalArgumentException("excludedPath: -XepExcludedPaths: does not support Pattern flags; "
                        + "use the String overloads for inline-flag semantics: " + pattern);
            }
            regexes.add(requireNoWhitespace(pattern.pattern(), EXCLUDED_PATH));
        }
        return excludedPath(regexes);
    }

    /**
     * Adds raw Error Prone flags.
     * <p>
     * Emitted in call order with the per-check methods.
     *
     * @param flags the flags
     * @return this operation
     * @throws NullPointerException     if {@code flags} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code flags} is empty, or any flag is blank or contains
     *                                  whitespace or control characters
     */
    public ErrorProneOperation flag(String... flags) {
        ObjectTools.requireNotEmpty(flags, "flags");
        for (var f : flags) {
            addPluginArg(requireNoWhitespace(f, "flag"));
        }
        return this;
    }

    /**
     * Configures the operation from a project.
     * <p>
     * Copies the project's {@code verbose} setting; a later {@code verbose(...)} call overrides it.
     *
     * @param project the project
     * @return this operation
     * @throws NullPointerException if {@code project} is {@code null}
     */
    public ErrorProneOperation fromProject(BaseProject project) {
        project_ = ObjectTools.requireNonNull(project, "fromProject");
        verbose(project.verbose());
        return this;
    }

    /**
     * Allows unknown check names instead of failing ({@code -XepIgnoreUnknownCheckNames}).
     *
     * @param ignoreUnknownCheckNames {@code true} to ignore
     * @return this operation
     */
    public ErrorProneOperation ignoreUnknownCheckNames(boolean ignoreUnknownCheckNames) {
        return toggle("-XepIgnoreUnknownCheckNames", ignoreUnknownCheckNames);
    }

    /**
     * Sets a check option ({@code -XepOpt:name}).
     * <p>
     * A bare name is equivalent to {@code =true}.
     *
     * @param name the option name, optionally namespaced
     * @return this operation
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank or contains whitespace or control characters
     */
    public ErrorProneOperation option(String name) {
        options_.add("-XepOpt:" + requireNoWhitespace(name, OPTION_NAME));
        return this;
    }

    /**
     * Sets a check option ({@code -XepOpt:name=value}).
     *
     * @param name  the option name, optionally namespaced
     * @param value the option value
     * @return this operation
     * @throws NullPointerException     if {@code name} or {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code name} or {@code value} is blank or contains whitespace or
     *                                  control characters
     */
    public ErrorProneOperation option(String name, String value) {
        options_.add("-XepOpt:"
                + requireNoWhitespace(name, OPTION_NAME) + '='
                + requireNoWhitespace(value, OPTION_VALUE));
        return this;
    }

    /**
     * Sets the checks whose suggested fixes are written as a patch ({@code -XepPatchChecks:}).
     * <p>
     * Requires {@link #patchLocation(String)}.
     *
     * @param checks the check names
     * @return this operation
     * @throws NullPointerException     if {@code checks} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code checks} is empty, or any name is blank or contains whitespace
     *                                  or control characters
     * @see #patchChecks(String...)
     * @see #patchLocation(String)
     */
    public ErrorProneOperation patchChecks(Collection<String> checks) {
        ObjectTools.requireNotEmpty(checks, PATCH_CHECKS);
        for (var c : checks) {
            patchChecks_.add(requireNoWhitespace(c, PATCH_CHECKS));
        }
        return this;
    }

    /**
     * Sets the checks whose suggested fixes are written as a patch ({@code -XepPatchChecks:}).
     * <p>
     * Requires {@link #patchLocation(String)}.
     *
     * @param checks the check names
     * @return this operation
     * @throws NullPointerException     if {@code checks} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code checks} is empty, or any name is blank or contains whitespace
     *                                  or control characters
     * @see #patchChecks(Collection)
     * @see #patchLocation(String)
     */
    public ErrorProneOperation patchChecks(String... checks) {
        ObjectTools.requireNotEmpty(checks, PATCH_CHECKS);
        return patchChecks(List.of(checks));
    }

    /**
     * Sets where patches are written ({@code -XepPatchLocation:}).
     *
     * @param location {@code IN_PLACE} or a directory
     * @return this operation
     * @throws NullPointerException     if {@code location} is {@code null}
     * @throws IllegalArgumentException if {@code location} is blank or contains whitespace or control characters
     * @see #patchChecks(String...)
     * @see #patchChecks(Collection)
     */
    public ErrorProneOperation patchLocation(String location) {
        patchLocation_ = requireNoWhitespace(location, "patchLocation");
        return this;
    }

    /**
     * Sets the repositories to download Error Prone from.
     * <p>
     * Default is {@link Repository#MAVEN_CENTRAL}.
     *
     * @param repositories the repositories
     * @return this operation
     * @throws NullPointerException     if {@code repositories} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code repositories} is empty
     * @see #repositories(Repository...)
     */
    public ErrorProneOperation repositories(Collection<Repository> repositories) {
        ObjectTools.requireNotEmpty(repositories, "repositories");
        repositories_.clear();
        repositories_.addAll(repositories);
        return this;
    }

    /**
     * Sets the repositories to download Error Prone from.
     * <p>
     * Default is {@link Repository#MAVEN_CENTRAL}.
     *
     * @param repositories the repositories
     * @return this operation
     * @throws NullPointerException     if {@code repositories} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code repositories} is empty
     * @see #repositories(Collection)
     */
    public ErrorProneOperation repositories(Repository... repositories) {
        ObjectTools.requireNonNull(repositories, "repositories");
        return repositories(List.of(repositories));
    }

    /**
     * Sets the Error Prone version.
     * <p>
     * Default is {@link #ERROR_PRONE_VERSION} on JDK 21+ and {@code 2.42.0} on JDK 17-20.
     *
     * @param version the version
     * @return this operation
     * @throws NullPointerException if {@code version} is {@code null}
     * @see #version(String)
     * @see #version()
     */
    public ErrorProneOperation version(Version version) {
        version_ = ObjectTools.requireNonNull(version, "version");
        return this;
    }

    /**
     * Sets the Error Prone version.
     * <p>
     * Default is {@link #ERROR_PRONE_VERSION} on JDK 21+ and {@code 2.42.0} on JDK 17-20.
     *
     * @param version the version string
     * @return this operation
     * @throws NullPointerException     if {@code version} is {@code null}
     * @throws IllegalArgumentException if {@code version} is blank or not a valid version
     * @see #version(Version)
     * @see #version()
     */
    public ErrorProneOperation version(String version) {
        var parsed = VersionNumber.parse(TextTools.requireNotBlank(version, "version"));
        if (parsed.equals(VersionNumber.UNKNOWN)) {
            throw new IllegalArgumentException("Invalid Error Prone version: " + version);
        }
        version_ = parsed;
        return this;
    }

    /**
     * Returns the Error Prone version.
     *
     * @return the version
     * @see #version(Version)
     * @see #version(String)
     */
    public Version version() {
        return version_;
    }

    /**
     * Sets Error Prone checks to report at warning severity ({@code -Xep:Check:WARN}).
     * <p>
     * Later calls for the same name win.
     *
     * @param names the check names
     * @return this operation
     * @throws NullPointerException     if {@code names} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or any name is blank or contains
     *                                  whitespace or control characters
     * @see #warnCheck(String...)
     */
    public ErrorProneOperation warnCheck(Collection<String> names) {
        ObjectTools.requireNotEmpty(names, "warnCheck");
        for (var name : names) {
            addPluginArg(XEP + requireNoWhitespace(name, "warnCheck") + ":WARN");
        }
        return this;
    }

    /**
     * Sets Error Prone checks to report at warning severity ({@code -Xep:Check:WARN}).
     * <p>
     * Later calls for the same name win.
     *
     * @param names the check names
     * @return this operation
     * @throws NullPointerException     if {@code names} is {@code null} or contains {@code null}
     * @throws IllegalArgumentException if {@code names} is empty, or any name is blank or contains
     *                                  whitespace or control characters
     * @see #warnCheck(Collection)
     */
    public ErrorProneOperation warnCheck(String... names) {
        ObjectTools.requireNotEmpty(names, "warnCheck");
        return warnCheck(List.of(names));
    }

    // Re-adding moves the entry to the end, so the last call wins for a given flag.
    private void addPluginArg(String arg) {
        pluginArgs_.remove(arg);
        pluginArgs_.add(arg);
    }

    // javac's -Xplugin option parses as "-Xplugin:name args" in a single argument,
    // so plugin name and all -Xep flags must be one javac argument separated by spaces.
    // Multiple excluded paths are joined as a flat alternation (wrap in a group for precedence).
    String buildPluginOption() {
        var plugin = new StringBuilder(128);
        plugin.append("-Xplugin:ErrorProne");
        pluginArgs_.forEach(a -> plugin.append(' ').append(a));
        options_.forEach(o -> plugin.append(' ').append(o));
        if (!excludedPaths_.isEmpty()) {
            plugin.append(" -XepExcludedPaths:").append(String.join("|", excludedPaths_));
        }
        if (!patchChecks_.isEmpty()) {
            plugin.append(" -XepPatchChecks:").append(String.join(",", patchChecks_));
        }
        if (patchLocation_ != null) {
            plugin.append(" -XepPatchLocation:").append(patchLocation_);
        }
        return plugin.toString();
    }

    // Overload for tests that don't need to pin the JDK feature.
    void configure(JavacOptions options, String processorPath) {
        configure(options, processorPath, Runtime.version().feature());
    }

    /**
     * Appends the Error Prone options to {@code options}, merging any existing processor path.
     * <p>
     * Recognizes {@code --processor-path <value>}, {@code --processor-path=<value>},
     * and {@code -processorpath <value>}.
     *
     * @param options       the options to extend
     * @param processorPath the Error Prone processor path to merge in
     * @param jdkFeature    the running JDK's feature version
     * @throws NullPointerException if {@code options} or {@code processorPath} is {@code null}
     */
    void configure(JavacOptions options, String processorPath, int jdkFeature) {
        ObjectTools.requireNonNull(options, "options");
        ObjectTools.requireNonNull(processorPath, "processorPath");

        compileOptionsCustomizers_.forEach(c -> c.accept(options));

        // Collect the existing processor paths, remove them, then re-add a single merged flag.
        var existing = new ArrayList<String>();
        var i = 0;
        while (i < options.size()) {
            var opt = options.get(i);
            var flag = PROCESSOR_PATH_FLAG.equals(opt) || PROCESSOR_PATH_FLAG_SHORT.equals(opt);
            var equals = opt.startsWith(PROCESSOR_PATH_FLAG + "=");
            if (flag || equals) {
                if (equals) {
                    // An empty value after "=" is malformed; drop the whole token rather than adding
                    // an empty entry.
                    var v = opt.substring(PROCESSOR_PATH_FLAG.length() + 1);
                    if (!v.isEmpty()) {
                        existing.add(v);
                    }
                    options.remove(i);
                } else {
                    if (i + 1 < options.size()) {
                        var next = options.get(i + 1);
                        // Another processor-path flag (bare, short, or equals form) means this one is
                        // dangling; any other token is its value.
                        var dangling = PROCESSOR_PATH_FLAG.equals(next)
                                || PROCESSOR_PATH_FLAG_SHORT.equals(next)
                                || next.startsWith(PROCESSOR_PATH_FLAG + "=");
                        if (!dangling) {
                            existing.add(options.remove(i + 1));
                        }
                    }
                    options.remove(i);
                }
                // Don't increment: the element that shifted into index i must be examined too.
            } else {
                i++;
            }
        }
        if (!existing.contains(processorPath)) {
            existing.add(processorPath);
        }
        options.processorPath(existing.toArray(String[]::new));

        // Required since Error Prone 2.48.0 on JDK 21+ (fix for JDK-8225377).
        if (jdkFeature >= 21) {
            options.add("-XDaddTypeAnnotationsToSymbol=true");
        }
        options.add("-XDcompilePolicy=simple");
        options.add("--should-stop=ifError=FLOW");
        options.add(buildPluginOption());
    }

    // Locates the downloaded jars and joins them into a single processor path string.
    // Throws IllegalStateException if no jars were found; this indicates the download failed or
    // the sandbox directory was cleaned between the download and the path lookup.
    String processorPath(Path errorProneDir) {
        ObjectTools.requireNonNull(errorProneDir, "errorProneDir");
        var jars = new ArrayList<>(IOTools.findFilesByExtensions(errorProneDir, ".jar"));
        if (jars.isEmpty()) {
            throw new IllegalStateException(
                    "No Error Prone " + version_ + " jars downloaded from " + repositories_ + " into: "
                            + errorProneDir);
        }
        return PathTools.joinClasspath(jars);
    }

    private ErrorProneOperation toggle(String flag, boolean enabled) {
        if (enabled) {
            addPluginArg(flag);
        } else {
            pluginArgs_.remove(flag);
        }
        return this;
    }

    // Custom CompileOperation that captures javac diagnostics while still running the Error Prone plugin.
    // bld's default reporting only surfaces diagnostics of a failed compilation; capturing them here reports
    // every diagnostic, including those promoted to errors by -Werror. Note: if the project's compile options
    // contain -cp or --module-path, those values are appended after the classpath/module path derived from the
    // project, and javac uses the last occurrence (the project's value).
    static final class ReportingCompileOperation extends CompileOperation {

        @Override
        protected void executeBuildSources(List<String> classpath, List<String> modulePath,
                                           List<File> sources, File destination) throws IOException {
            ObjectTools.requireNotEmpty(sources, "sources");
            ObjectTools.requireNonNull(classpath, "classpath");
            ObjectTools.requireNonNull(modulePath, "modulePath");
            ObjectTools.requireNonNull(destination, "destination");

            var compiler = ToolProvider.getSystemJavaCompiler();
            var collector = new DiagnosticCollector<JavaFileObject>();
            try (var fileManager = compiler.getStandardFileManager(collector, null, null)) {
                var options = new ArrayList<>(List.of("-d", destination.getAbsolutePath()));
                if (!classpath.isEmpty()) {
                    options.addAll(List.of("-cp", String.join(File.pathSeparator, classpath)));
                }
                if (!modulePath.isEmpty()) {
                    options.addAll(List.of("--module-path", String.join(File.pathSeparator, modulePath)));
                }
                options.addAll(compileOptions());

                if (!silent()) {
                    if (logger.isLoggable(Level.INFO) && verbose()) {
                        for (var source : sources) {
                            logger.info("Compiling source '" + source.getAbsolutePath() + "' into '"
                                    + destination.getAbsolutePath() + "'");
                        }
                    }

                    if (logger.isLoggable(Level.FINE)) {
                        var cmd = new StringBuilder("javac");
                        for (var opt : options) {
                            cmd.append(' ').append(opt);
                        }
                        for (var src : sources) {
                            cmd.append(' ').append(src.getPath());
                        }
                        logger.fine(cmd.toString());
                    }
                }

                var task = compiler.getTask(null, fileManager, collector, options, null,
                        fileManager.getJavaFileObjectsFromFiles(sources));
                var success = task.call();

                executeProcessDiagnostics(collector);
                if (success) {
                    var moduleInfo = new File(destination, "module-info.class");
                    if (moduleInfo.exists() && moduleMainClass() != null) {
                        FileUtils.writeBytes(ModuleMainClassAdapter.addModuleMainClassToBytes(
                                FileUtils.readBytes(moduleInfo), moduleMainClass()), moduleInfo);
                    }
                } else {
                    diagnostics().addAll(collector.getDiagnostics());
                }
            }
        }
    }
}