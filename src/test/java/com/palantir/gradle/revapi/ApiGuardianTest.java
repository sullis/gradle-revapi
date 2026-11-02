/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.revapi;

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates how <a href="https://github.com/apiguardian-team/apiguardian">apiguardian</a> {@code @API}
 * annotations can be used alongside revapi: removing a method annotated {@code @API(status = INTERNAL)} or
 * {@code @API(status = EXPERIMENTAL)} is not a break, whereas removing or changing a {@code STABLE} method is.
 *
 * <p>revapi does not understand apiguardian, and the plugin has no option to filter elements by annotation, so the
 * consuming project's build script does it: it inspects the previously released jar and adds an accepted break to the
 * {@code revapiAnalyze} task for every {@code INTERNAL} or {@code EXPERIMENTAL} method. Only removals are covered, and
 * the accepted breaks have to match revapi's description of each method exactly.
 */
@GradlePluginTests
@DisabledConfigurationCache("revapi plugin is incompatible with configuration cache")
class ApiGuardianTest {
    private static final String FOO_JAVA = "foo/Foo.java";

    @BeforeEach
    void setUp(RootProject rootProject, MavenRepo repo) {
        rootProject.settingsGradle().rootProjectName("root");

        rootProject
                .buildGradle()
                .plugins()
                .add(TestConstants.PLUGIN_NAME)
                .add("java-library")
                .add("maven-publish");
        rootProject.buildGradle().append("""
            group = 'revapi.test'
            version = '1.0.0'

            repositories {
                mavenCentral()
                maven { url uri('%s') }
            }

            dependencies {
                api 'org.apiguardian:apiguardian-api:1.1.2'
            }

            revapi {
                oldVersion = project.version
            }

            publishing {
                publications {
                    publication(MavenPublication) {
                        from components.java
                    }
                }
                repositories {
                    maven { url uri('%s') }
                }
            }
            """, repo.path(), repo.path());

        rootProject.buildGradle().append("""
            // Accept the removal of any method in the previous release annotated
            // @API(status = INTERNAL) or @API(status = EXPERIMENTAL)
            tasks.named('revapiAnalyze') {
                def oldJars = oldApiJars
                def oldDependencyJars = oldApiDependencyJars
                acceptedBreaks.addAll(provider {
                    def jars = oldJars.get().files
                    def urls = (jars + oldDependencyJars.get().files).collect { it.toURI().toURL() } as URL[]
                    def loader = new URLClassLoader(urls, (ClassLoader) null)
                    try {
                        def apiAnnotation = loader.loadClass('org.apiguardian.api.API')
                        def breaks = []
                        jars.each { jar ->
                            new java.util.zip.ZipFile(jar).withCloseable { zip ->
                                zip.entries().findAll { it.name.endsWith('.class') && it.name != 'module-info.class' }.each { entry ->
                                    def cls = loader.loadClass(entry.name.replace('/', '.') - '.class')
                                    cls.declaredMethods.each { method ->
                                        def status = method.getAnnotation(apiAnnotation)?.status()?.name()
                                        if (status in ['INTERNAL', 'EXPERIMENTAL']) {
                                            def returnType = method.genericReturnType.typeName.replace('$', '.')
                                            def params = method.genericParameterTypes.collect { it.typeName.replace('$', '.') }.join(', ')
                                            def className = cls.name.replace('$', '.')
                                            breaks << com.palantir.gradle.revapi.config.AcceptedBreak.builder()
                                                    .code('java.method.removed')
                                                    .oldElement("method ${returnType} ${className}::${method.name}(${params})".toString())
                                                    .justification("annotated @API(status = ${status})".toString())
                                                    .build()
                                        }
                                    }
                                }
                            }
                        }
                        return breaks
                    } finally {
                        loader.close()
                    }
                })
            }
            """);

        rootProject.mainSourceSet().java().fileByPath(FOO_JAVA).overwrite("""
            package foo;

            import static org.apiguardian.api.API.Status.EXPERIMENTAL;
            import static org.apiguardian.api.API.Status.INTERNAL;
            import static org.apiguardian.api.API.Status.STABLE;

            import org.apiguardian.api.API;

            public class Foo {
                @API(status = INTERNAL)
                public void internalMethod() {}

                @API(status = EXPERIMENTAL)
                public void experimentalMethod() {}

                @API(status = STABLE)
                public void stableMethod() {}
            }
            """);
    }

    @Test
    void removing_an_internal_method_is_not_a_break(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("publish").buildsSuccessfully();

        removeMethod(rootProject, "internalMethod");

        gradle.withArgs("revapi").buildsSuccessfully();
        rootProject.yamlFile(".palantir/revapi.yml").assertThat().doesNotExist();
    }

    @Test
    void removing_an_experimental_method_is_not_a_break(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("publish").buildsSuccessfully();

        removeMethod(rootProject, "experimentalMethod");

        gradle.withArgs("revapi").buildsSuccessfully();
        rootProject.yamlFile(".palantir/revapi.yml").assertThat().doesNotExist();
    }

    @Test
    void removing_a_stable_method_is_a_break(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("publish").buildsSuccessfully();

        removeMethod(rootProject, "stableMethod");

        InvocationResult result = gradle.withArgs("revapi").buildsWithFailure();
        assertThat(result).output().contains("java.method.removed").contains("method void foo.Foo::stableMethod()");
    }

    @Test
    void changing_the_parameters_of_a_stable_method_is_a_break(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("publish").buildsSuccessfully();

        rootProject
                .mainSourceSet()
                .java()
                .fileByPath(FOO_JAVA)
                .edit(text -> text.replace("public void stableMethod() {}", "public void stableMethod(String arg) {}"));

        InvocationResult result = gradle.withArgs("revapi").buildsWithFailure();
        assertThat(result)
                .output()
                .contains("java.method.numberOfParametersChanged")
                .contains("method void foo.Foo::stableMethod()")
                .contains("method void foo.Foo::stableMethod(java.lang.String)");
    }

    @Test
    void removing_all_three_methods_only_reports_the_stable_method(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("publish").buildsSuccessfully();

        removeMethod(rootProject, "internalMethod");
        removeMethod(rootProject, "experimentalMethod");
        removeMethod(rootProject, "stableMethod");

        InvocationResult result = gradle.withArgs("revapi").buildsWithFailure();
        assertThat(result)
                .output()
                .contains("java.method.removed")
                .contains("method void foo.Foo::stableMethod()")
                .doesNotContain("internalMethod")
                .doesNotContain("experimentalMethod");
    }

    private static void removeMethod(RootProject rootProject, String methodName) {
        rootProject
                .mainSourceSet()
                .java()
                .fileByPath(FOO_JAVA)
                .edit(text ->
                        text.replaceAll("@API\\(status = \\w+\\)\\s+public void " + methodName + "\\(\\) \\{}", ""));
    }
}
