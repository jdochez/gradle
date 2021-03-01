/*
 * Copyright 2020 the original author or authors.
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

import gradlebuild.lifecycle.GradlePropertiesCheck

plugins {
    id("lifecycle-base") // Make sure the basic lifecycle tasks are added through 'lifecycle-base'. Other plugins might sneakily apply that to the root project.
}

val ciGroup = "CI Lifecycle"

val baseLifecycleTasks = listOf(
    LifecycleBasePlugin.CLEAN_TASK_NAME,
    LifecycleBasePlugin.ASSEMBLE_TASK_NAME,
    LifecycleBasePlugin.BUILD_TASK_NAME
)

val lifecycleTasks = mapOf(
    "compileAllBuild" to "Initialize CI Pipeline by priming the cache before fanning out",
    "sanityCheck" to "Run all basic checks (without tests) - to be run locally and on CI for early feedback",
    "unitTest" to "Run only unitTests (usually the default 'test' task of each project)",
    "quickTest" to "Run all unit, integration and cross-version (against latest release) tests in embedded execution mode",
    "platformTest" to "Run all unit, integration and cross-version (against latest release) tests in forking execution mode",
    "allVersionsIntegMultiVersionTest" to "Run all multi-version integration tests with all version to cover",
    "parallelTest" to "Run all integration tests in parallel execution mode: each Gradle execution started in a test run with --parallel",
    "noDaemonTest" to "Run all integration tests in no-daemon execution mode: each Gradle execution started in a test forks a new daemon",
    "configCacheTest" to "Run all integration tests with instant execution",
    "watchFsTest" to "Run all integration tests with file system watching enabled",
    "forceRealizeDependencyManagementTest" to "Runs all integration tests with the dependency management engine in 'force component realization' mode",
    "quickFeedbackCrossVersionTest" to "Run cross-version tests against a limited set of versions",
    "allVersionsCrossVersionTest" to "Run cross-version tests against all released versions (latest patch release of each)"
)

if (project == rootProject && subprojects.isEmpty() && gradle.parent == null) { // the umbrella build root (only if we run the umbrella build)
    baseLifecycleTasks.forEach { lifecycleTask ->
        tasks.named(lifecycleTask) {
            group = ciGroup
            dependsOn(gradle.includedBuilds.filter { !it.name.contains("build-logic") }.map { it.task(":$lifecycleTask") })
        }
    }
    lifecycleTasks.forEach { (lifecycleTask, taskDescription) ->
        tasks.register(lifecycleTask) {
            group = ciGroup
            description = taskDescription
            dependsOn(gradle.includedBuilds.filter { !it.name.contains("build-logic") }.map { it.task(":$lifecycleTask") })
        }
    }
    tasks.registerDistributionsPromotionTasks()
    tasks.expandSanityCheck()
} else if (subprojects.isNotEmpty()) { // a root build
    setupGlobalState()
    baseLifecycleTasks.forEach { lifecycleTask ->
        tasks.named(lifecycleTask) {
            group = ciGroup
            dependsOn(subprojects.map { "${it.name}:$lifecycleTask" })
        }
    }
    lifecycleTasks.forEach { (lifecycleTask, taskDescription) ->
        tasks.register(lifecycleTask) {
            group = ciGroup
            description = taskDescription
            dependsOn(subprojects.map { "${it.name}:$lifecycleTask" })
        }
    }
} else {
    lifecycleTasks.forEach { (lifecycleTask, taskDescription) ->
        tasks.register(lifecycleTask) {
            group = ciGroup
            description = taskDescription
        }
    }
}

/**
 * Task that are called by the (currently separate) promotion build running on CI.
 */
fun TaskContainer.registerDistributionsPromotionTasks() {
    register("packageBuild") {
        description = "Build production distros and smoke test them"
        group = "build"
        dependsOn(
            gradle.includedBuild("full").task(":distributions-full:verifyIsProductionBuildEnvironment"),
            gradle.includedBuild("full").task(":distributions-full:buildDists"),
            gradle.includedBuild("end-to-end-tests").task(":distributions-integ-tests:forkingIntegTest"),
            gradle.includedBuild("documentation").task(":docs:releaseNotes"),
            gradle.includedBuild("documentation").task(":docs:incubationReport"),
            gradle.includedBuild("documentation").task(":docs:checkDeadInternalLinks")
        )
    }
}

fun TaskContainer.expandSanityCheck() {
    val sanityCheck = named("sanityCheck") {
        dependsOn(
            gradle.includedBuild("build-logic-commons").task(":check"),
            gradle.includedBuild("build-logic").task(":check"),
            gradle.includedBuild("documentation").task(":docs:checkstyleApi"),
            gradle.includedBuild("documentation").task(":docs:javadocAll"),
            gradle.includedBuild("reports").task(":internal-build-reports:allIncubationReportsZip"),
            gradle.includedBuild("reports").task(":architecture-test:checkBinaryCompatibility"),
            gradle.includedBuild("reports").task(":architecture-test:test"),
            gradle.includedBuild("distribution-core").task(":tooling-api:toolingApiShadedJar"),
            gradle.includedBuild("end-to-end-tests").task(":performance:verifyPerformanceScenarioDefinitions"),
            ":checkSubprojectsInfo"
        )
    }
    val checkGradleProperties = gradle.includedBuilds.map { build ->
        tasks.register<GradlePropertiesCheck>("check${build.name.capitalize()}GradleProperties") {
            rootPropertiesFile.set(layout.projectDirectory.file("gradle.properties"))
            buildPropertiesFile.set(File(build.projectDir, "gradle.properties"))
        }
    }
    sanityCheck {
        dependsOn(checkGradleProperties)
    }
}

fun setupGlobalState() {
    if (needsToUseTestVersionsPartial()) {
        globalProperty("testVersions" to "partial")
    }
    if (needsToUseTestVersionsAll()) {
        globalProperty("testVersions" to "all")
    }
}

fun needsToUseTestVersionsPartial() = isRequestedTask("platformTest")

fun needsToUseTestVersionsAll() = isRequestedTask("allVersionsCrossVersionTest")
    || isRequestedTask("allVersionsIntegMultiVersionTest")
    || isRequestedTask("soakTest")

fun isRequestedTask(taskName: String) = gradle.startParameter.taskNames.contains(taskName)
    || gradle.startParameter.taskNames.any { it.contains(":$taskName") }

fun globalProperty(pair: Pair<String, Any>) {
    val propertyName = pair.first
    val value = pair.second
    if (hasProperty(propertyName)) {
        val otherValue = property(propertyName)
        if (value.toString() != otherValue.toString()) {
            throw RuntimeException("Attempting to set global property $propertyName to two different values ($value vs $otherValue)")
        }
    }
    extra.set(propertyName, value)
}
