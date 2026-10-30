package com.example;

import rife.bld.Project;

import java.util.List;

import rife.bld.BuildCommand;
import rife.bld.dependencies.Repository;
import rife.bld.operations.CompileOperation;
import rife.bld.extension.ErrorProneOperation;
import rife.bld.operations.CompileOperation;

import static rife.bld.dependencies.Repository.*;
import static rife.bld.dependencies.Scope.*;

public class ExampleBuild extends Project {

    public ExampleBuild() {
        pkg = "com.example";
        name = "example";
        mainClass = "com.example.Example";
        version = version(0, 1, 0);

        downloadSources = true;
        repositories = List.of(GOOGLE_MAVEN_CENTRAL, RIFE2_RELEASES);
        scope(test)
                .include(dependency("org.junit.jupiter", "junit-jupiter", version(6, 1, 3)))
                .include(dependency("org.junit.platform", "junit-platform-console-standalone", version(6, 1, 3)));
    }

    public static void main(String[] args) {
        new ExampleBuild().start(args);
    }

    @BuildCommand(summary = "Compiles the project with Error Prone")
    public void errorprone() throws Exception {
        new ErrorProneOperation()
                .fromProject(this)
                .repositories(Repository.GOOGLE_MAVEN_CENTRAL)
                .verbose(true)
                .compileOptions(o -> o.add("-Werror")) // fails on warnings
                .disableCheck("ArrayToString")
                .execute();
    }
}