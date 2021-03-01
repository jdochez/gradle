/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.gradle.enterprise.gradleplugin.testdistribution.internal.TestDistributionExtensionInternal
import gradlebuild.basics.BuildEnvironment
import gradlebuild.basics.accessors.groovy
import gradlebuild.basics.tasks.ClasspathManifest
import gradlebuild.basics.testDistributionEnabled
import gradlebuild.filterEnvironmentVariables
import gradlebuild.jvm.argumentproviders.CiEnvironmentProvider
import gradlebuild.jvm.extension.UnitTestAndCompileExtension
import org.gradle.internal.os.OperatingSystem
import java.util.jar.Attributes

plugins {
    id("gradlebuild.lifecycle")
    groovy
    id("gradlebuild.module-identity")
    id("gradlebuild.dependency-modules")
    id("org.gradle.test-retry")
}

extensions.create<UnitTestAndCompileExtension>("gradlebuildJava", tasks)

removeTeamcityTempProperty()
addDependencies()
configureClasspathManifestGeneration()
configureCompile()
configureSourcesVariant()
configureJarTasks()
configureTests()
wireLifecycleTasks()

fun configureCompile() {
    java.toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
        vendor.set(JvmVendorSpec.ADOPTOPENJDK)
    }

    tasks.withType<JavaCompile>().configureEach {
        configureCompileTask(options)
    }
    tasks.withType<GroovyCompile>().configureEach {
        groovyOptions.encoding = "utf-8"
        sourceCompatibility = "8"
        targetCompatibility = "8"
        configureCompileTask(options)
    }
    addCompileAllTask()
}

fun configureSourcesVariant() {
    java {
        withSourcesJar()
    }

    @Suppress("unused_variable")
    val transitiveSourcesElements by configurations.creating {
        isCanBeResolved = false
        isCanBeConsumed = true
        extendsFrom(configurations.implementation.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
            attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("gradle-source-folders"))
        }
        val main = sourceSets.main.get()
        main.java.srcDirs.forEach {
            outgoing.artifact(it)
        }
        main.groovy.srcDirs.forEach {
            outgoing.artifact(it)
        }
    }
}

fun configureCompileTask(options: CompileOptions) {
    options.release.set(8)
    options.encoding = "utf-8"
    options.isIncremental = true
    options.forkOptions.jvmArgs?.add("-XX:+HeapDumpOnOutOfMemoryError")
    options.forkOptions.memoryMaximumSize = "1g"
    options.compilerArgs.addAll(mutableListOf("-Xlint:-options", "-Xlint:-path"))
}

fun configureClasspathManifestGeneration() {
    val runtimeClasspath by configurations
    val classpathManifest = tasks.register("classpathManifest", ClasspathManifest::class) {
        this.runtimeClasspath.from(runtimeClasspath)
        this.externalDependencies.from(runtimeClasspath.fileCollection { it is ExternalDependency })
        this.manifestFile.set(moduleIdentity.baseName.map { layout.buildDirectory.file("generated-resources/$it-classpath/$it-classpath.properties").get() })
    }
    sourceSets.main.get().output.dir(
        classpathManifest.map { it.manifestFile.get().asFile.parentFile }
    )
}

fun addDependencies() {
    dependencies {
        testCompileOnly(libs.junit)
        testRuntimeOnly(libs.junit5Vintage)
        testImplementation(libs.groovy)
        testImplementation(libs.groovyAnt)
        testImplementation(libs.groovyJson)
        testImplementation(libs.groovyTest)
        testImplementation(libs.groovyXml)
        testImplementation(libs.spock)
        testImplementation(libs.junit5Vintage)
        testImplementation(libs.spockJUnit4)
        testRuntimeOnly(libs.bytebuddy)
        testRuntimeOnly(libs.objenesis)

        // use a separate configuration for the platform dependency that does not get published as part of 'apiElements' or 'runtimeElements'
        val platformImplementation by configurations.creating
        configurations["compileClasspath"].extendsFrom(platformImplementation)
        configurations["runtimeClasspath"].extendsFrom(platformImplementation)
        configurations["testCompileClasspath"].extendsFrom(platformImplementation)
        configurations["testRuntimeClasspath"].extendsFrom(platformImplementation)
        platformImplementation(platform("org.gradle:distributions-dependencies"))
    }
}

fun addCompileAllTask() {
    tasks.register("compileAll") {
        val compileTasks = project.tasks.matching {
            it is JavaCompile || it is GroovyCompile
        }
        dependsOn(compileTasks)
    }
}

fun configureJarTasks() {
    tasks.withType<Jar>().configureEach {
        archiveBaseName.set(moduleIdentity.baseName)
        archiveVersion.set(moduleIdentity.version.map { it.baseVersion.version })
        manifest.attributes(
            mapOf(
                Attributes.Name.IMPLEMENTATION_TITLE.toString() to "Gradle",
                Attributes.Name.IMPLEMENTATION_VERSION.toString() to moduleIdentity.version.map { it.baseVersion.version }
            )
        )
    }
}

fun getPropertyFromAnySource(propertyName: String): Provider<String> {
    return providers.gradleProperty(propertyName).forUseAtConfigurationTime()
        .orElse(providers.systemProperty(propertyName).forUseAtConfigurationTime())
        .orElse(providers.environmentVariable(propertyName).forUseAtConfigurationTime())
}

fun Test.jvmVersionForTest(): JavaLanguageVersion {
    return JavaLanguageVersion.of(getPropertyFromAnySource("testJavaVersion").getOrElse(JavaVersion.current().majorVersion))
}

fun Test.configureJvmForTest() {
    jvmArgumentProviders.add(CiEnvironmentProvider(this))
    val launcher = project.javaToolchains.launcherFor {
        languageVersion.set(jvmVersionForTest())
        getPropertyFromAnySource("testJavaVendor").map {
            when (it.toLowerCase()) {
                "oracle" -> vendor.set(JvmVendorSpec.ORACLE)
                "openjdk" -> vendor.set(JvmVendorSpec.ADOPTOPENJDK)
            }
        }.getOrNull()
    }
    javaLauncher.set(launcher)
    if (jvmVersionForTest().canCompileOrRun(9) && (isUnitTest() || usesEmbeddedExecuter())) {
        jvmArgs(org.gradle.internal.jvm.GroovyJpmsConfiguration.GROOVY_JPMS_JVM_ARGS)
    }
}

fun Test.addOsAsInputs() {
    // Add OS as inputs since tests on different OS may behave differently https://github.com/gradle/gradle-private/issues/2831
    // the version currently differs between our dev infrastructure, so we only track the name and the architecture
    inputs.property("operatingSystem", "${OperatingSystem.current().name} ${System.getProperty("os.arch")}")
}

fun Test.isUnitTest() = listOf("test", "writePerformanceScenarioDefinitions", "writeTmpPerformanceScenarioDefinitions").contains(name)

fun Test.usesEmbeddedExecuter() = name.startsWith("embedded")

fun configureTests() {
    normalization {
        runtimeClasspath {
            // Ignore the build receipt as it is not relevant for tests and changes between each execution
            ignore("org/gradle/build-receipt.properties")
        }
    }

    tasks.withType<Test>().configureEach {
        filterEnvironmentVariables()

        maxParallelForks = project.maxParallelForks

        configureJvmForTest()
        addOsAsInputs()

        val testName = name

        if (BuildEnvironment.isCiServer) {
            retry {
                maxRetries.convention(1)
                maxFailures.set(10)
            }
            doFirst {
                logger.lifecycle("maxParallelForks for '$path' is $maxParallelForks")
            }
        }

        useJUnitPlatform()
        if (project.testDistributionEnabled() && !isUnitTest()) {
            println("Test distribution has been enabled for $testName")
            distribution {
                enabled.set(true)

                // Dogfooding TD against ge-experiment until GE 2021.1 is available on e.grdev.net and ge.gradle.org (and the new TD Gradle plugin version 2.0 is accepted)
                (this as TestDistributionExtensionInternal).server.set(uri("https://ge-experiment.grdev.net"))

                if (BuildEnvironment.isCiServer) {
                    when {
                        OperatingSystem.current().isLinux -> requirements.set(listOf("os=linux", "gbt-dogfooding"))
                        OperatingSystem.current().isWindows -> requirements.set(listOf("os=windows", "gbt-dogfooding"))
                        OperatingSystem.current().isMacOsX -> requirements.set(listOf("os=macos", "gbt-dogfooding"))
                    }
                } else {
                    requirements.set(listOf("gbt-dogfooding"))
                }
            }
        }
    }
}

fun removeTeamcityTempProperty() {
    // Undo: https://github.com/JetBrains/teamcity-gradle/blob/e1dc98db0505748df7bea2e61b5ee3a3ba9933db/gradle-runner-agent/src/main/scripts/init.gradle#L818
    if (project.hasProperty("teamcity")) {
        @Suppress("UNCHECKED_CAST")
        val teamcity = project.property("teamcity") as MutableMap<String, Any>
        teamcity["teamcity.build.tempDir"] = ""
    }
}

val Project.maxParallelForks: Int
    get() = if (System.getenv("BUILD_AGENT_VARIANT") == "AX41") {
        8
    } else {
        findProperty("maxParallelForks")?.toString()?.toInt() ?: 4
    }

fun wireLifecycleTasks() {
    tasks.compileAllBuild {
        dependsOn("compileAll")
    }
    tasks.sanityCheck {
        dependsOn("compileAll", "codeQuality")
    }
    tasks.unitTest {
        dependsOn("test")
    }
    tasks.quickTest {
        dependsOn("test")
    }
    tasks.platformTest {
        dependsOn("test")
    }
    tasks.quickTest {
        dependsOn("embeddedIntegTest")
    }
    tasks.platformTest {
        dependsOn("forkingIntegTest")
    }
    tasks.allVersionsIntegMultiVersionTest {
        dependsOn("integMultiVersionTest")
    }
    tasks.parallelTest {
        dependsOn("parallelIntegTest")
    }
    tasks.noDaemonTest {
        dependsOn("noDaemonIntegTest")
    }
    tasks.configCacheTest {
        dependsOn("configCacheIntegTest")
    }
    tasks.watchFsTest {
        dependsOn("watchFsIntegTest")
    }
    tasks.forceRealizeDependencyManagementTest {
        dependsOn("integForceRealizeTest")
    }
    tasks.quickTest {
        dependsOn("embeddedCrossVersionTest")
    }
    tasks.platformTest {
        dependsOn("forkingCrossVersionTest")
    }
    tasks.quickFeedbackCrossVersionTest {
        dependsOn("quickFeedbackCrossVersionTests")
    }
    tasks.allVersionsCrossVersionTest {
        dependsOn("allVersionsCrossVersionTests")
    }
}
