package com.vanniktech.android.junit.jacoco

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport

class GenerationPlugin implements Plugin<Project> {
    @Override
    void apply(final Project rootProject) {
        rootProject.extensions.create('junitJacoco', JunitJacocoExtension)

        final def hasSubProjects = rootProject.subprojects.size() > 0

        if (hasSubProjects) {
            final def TaskProvider<JacocoReport> mergedReportTask = addJacocoMergeToRootProject(rootProject, rootProject.junitJacoco)

            rootProject.subprojects { subProject ->
                afterEvaluate {
                    final def extension = rootProject.junitJacoco
                    addJacoco(subProject, extension, mergedReportTask)
                }
            }
        } else {
            rootProject.afterEvaluate {
                final def extension = rootProject.junitJacoco

                addJacoco(rootProject, extension)
            }
        }
    }

    protected static boolean addJacoco(final Project subProject, final JunitJacocoExtension extension) {
        return addJacoco(subProject, extension, (TaskProvider<JacocoReport>) null)
    }

    protected static boolean addJacoco(final Project subProject, final JunitJacocoExtension extension, JacocoReport mergedReportTask) {
        // Convert JacocoReport to TaskProvider for backward compatibility with tests
        if (mergedReportTask != null) {
            // Get the project and find the TaskProvider for this task
            def taskProvider = mergedReportTask.project.tasks.named(mergedReportTask.name)
            return addJacoco(subProject, extension, taskProvider)
        }
        return addJacoco(subProject, extension, (TaskProvider<JacocoReport>) null)
    }

    protected static boolean addJacoco(final Project subProject, final JunitJacocoExtension extension, TaskProvider<JacocoReport> mergedReportTask) {
        if (!shouldIgnore(subProject, extension)) {
            if (isAndroidProject(subProject)) {
                return addJacocoAndroid(subProject, extension, mergedReportTask)
            } else if (isJavaProject(subProject) || isKotlinMultiplatform(subProject)) {
                return addJacocoJava(subProject, extension, mergedReportTask)
            }
        }

        return false
    }

    private static boolean addJacocoJava(final Project subProject, final JunitJacocoExtension extension, TaskProvider<JacocoReport> mergedReportTask) {
        subProject.plugins.apply('jacoco')

        subProject.jacoco.toolVersion = extension.jacocoVersion

        subProject.jacocoTestReport {
            dependsOn 'test'

            group = 'Reporting'
            description = 'Generate Jacoco coverage reports.'

            reports {
                xml.required = extension.xml.enabled
                csv.required = extension.csv.enabled
                html.required = extension.html.enabled
            }

            getClassDirectories().from(subProject.fileTree(
                    dir: getBuildDir(subProject),
                    includes: ['**/classes/**/main/**'],
                    excludes: getExcludes(extension)
            ))

            final def coverageSourceDirs = [
                'src/main/clojure',
                'src/main/groovy',
                'src/main/java',
                'src/main/kotlin',
                'src/main/scala'
            ]

            getAdditionalSourceDirs().from(subProject.files(coverageSourceDirs))
            getSourceDirectories().from(subProject.files(coverageSourceDirs))
            if (isKotlinMultiplatform(subProject)) {
                getExecutionData().from(subProject.files("${getBuildDir(subProject)}/jacoco/jvmTest.exec"))
            } else {
                getExecutionData().from(subProject.files("${getBuildDir(subProject)}/jacoco/test.exec"))
            }

            if (mergedReportTask != null) {
                mergedReportTask.configure { JacocoReport task ->
                    task.executionData.setFrom(executionData.files + task.executionData.files)
                    task.classDirectories.setFrom(classDirectories.getFrom() + task.classDirectories.getFrom())
                    task.additionalSourceDirs.setFrom(additionalSourceDirs.getFrom() + task.additionalSourceDirs.getFrom())
                    task.sourceDirectories.setFrom(sourceDirectories.getFrom() + task.sourceDirectories.getFrom())
                }
            }
        }

        subProject.check.dependsOn 'jacocoTestReport'
        return true
    }

    private static boolean addJacocoAndroid(final Project subProject, final JunitJacocoExtension extension, TaskProvider<JacocoReport> mergedReportTask) {
        subProject.plugins.apply('jacoco')

        subProject.jacoco.toolVersion = extension.jacocoVersion

        subProject.tasks.withType(Test).configureEach {
            it.jacoco.includeNoLocationClasses = extension.includeNoLocationClasses
        }

        subProject.android.jacoco.version = extension.jacocoVersion

        Collection variants = []
        if (isAndroidApplication(subProject) || isAndroidDynamicFeature(subProject)) {
            variants = subProject.android.applicationVariants
        } else if (isAndroidLibrary(subProject)) {
            // FeatureExtension extends LibraryExtension
            variants = subProject.android.libraryVariants
        } else {
            // test plugin or something else
            return false
        }

        variants.all { variant ->
            def productFlavorName = variant.getFlavorName()
            def buildType = variant.getBuildType()
            def buildTypeName = buildType.name

            def sourceName, sourcePath
            if (!productFlavorName) {
                sourceName = sourcePath = "${buildTypeName}"
            } else {
                sourceName = "${productFlavorName}${buildTypeName.capitalize()}"
                sourcePath = "${productFlavorName}/${buildTypeName}"
            }

            final def jvmTaskName = "jacocoTestReport${sourceName.capitalize()}"
            final def combinedTaskName = "combinedTestReport${sourceName.capitalize()}"

            final def jvmTestTaskName = "test${sourceName.capitalize()}UnitTest"
            final def instrumentationTestTaskName = "create${sourceName.capitalize()}CoverageReport"

            addJacocoTask(false, subProject, extension, mergedReportTask, jvmTaskName,
                jvmTestTaskName, instrumentationTestTaskName, sourceName, sourcePath, productFlavorName, buildTypeName)

            if (buildType.testCoverageEnabled) {
                addJacocoTask(true, subProject, extension, mergedReportTask, combinedTaskName,
                    jvmTestTaskName, instrumentationTestTaskName, sourceName, sourcePath, productFlavorName, buildTypeName)
            }
        }

        return true
    }

    private static void addJacocoTask(final boolean combined, final Project subProject, final JunitJacocoExtension extension,
                                      TaskProvider<JacocoReport> mergedReportTask, final String taskName,
                                      final String jvmTestTaskName, final String instrumentationTestTaskName, final String sourceName,
                                      final String sourcePath, final String productFlavorName, final String buildTypeName) {
        def destinationDir
        if (combined) {
            destinationDir = "${getBuildDir(subProject)}/reports/jacocoCombined"
        } else {
            destinationDir = "${getBuildDir(subProject)}/reports/jacoco"
        }

        subProject.tasks.register(taskName, JacocoReport) {
            group = 'Reporting'
            description = "Generate Jacoco coverage reports after running ${sourceName} tests."

            if (combined) {
                dependsOn jvmTestTaskName, instrumentationTestTaskName
            } else {
                dependsOn jvmTestTaskName
            }

            reports {
                xml {
                    required = extension.xml.enabled
                    outputLocation = subProject.file("$destinationDir/${sourceName}/jacoco.xml")
                }
                csv {
                    required = extension.csv.enabled
                    outputLocation = subProject.file("$destinationDir/${sourceName}/jacoco.csv")
                }
                html {
                    required = extension.html.enabled
                    outputLocation = subProject.file("$destinationDir/${sourceName}")
                }
            }

            def classPaths = [
                "**/intermediates/classes/${sourcePath}/**",
                "**/intermediates/javac/${sourceName}/*/classes/**", // Android Gradle Plugin 3.2.x support.
                "**/intermediates/javac/${sourceName}/classes/**" // Android Gradle Plugin 3.4 and 3.5 support.
            ]

            if (isKotlinAndroid(subProject) || isKotlinMultiplatform(subProject)) {
                classPaths << "**/tmp/kotlin-classes/${sourcePath}/**"
                if (productFlavorName) {
                    classPaths << "**/tmp/kotlin-classes/${productFlavorName}${buildTypeName.capitalize()}/**"
                }
            }

            getClassDirectories().from(subProject.fileTree(
                dir: getBuildDir(subProject),
                includes: classPaths,
                excludes: getExcludes(extension)
            ))

            final def coverageSourceDirs = [
                "src/main/clojure",
                "src/main/groovy",
                "src/main/java",
                "src/main/kotlin",
                "src/main/scala",
                "src/$buildTypeName/clojure",
                "src/$buildTypeName/groovy",
                "src/$buildTypeName/java",
                "src/$buildTypeName/kotlin",
                "src/$buildTypeName/scala"
            ]

            if (productFlavorName) {
                coverageSourceDirs.add("src/$productFlavorName/clojure")
                coverageSourceDirs.add("src/$productFlavorName/groovy")
                coverageSourceDirs.add("src/$productFlavorName/java")
                coverageSourceDirs.add("src/$productFlavorName/kotlin")
                coverageSourceDirs.add("src/$productFlavorName/scala")
            }

            getAdditionalSourceDirs().from(subProject.files(coverageSourceDirs))
            getSourceDirectories().from(subProject.files(coverageSourceDirs))
            getExecutionData().from(subProject.files("${getBuildDir(subProject)}/jacoco/${jvmTestTaskName}.exec"))

            if (combined) {
                // add instrumentation coverage execution data
                def codeCoverageDirs = subProject.fileTree("${getBuildDir(subProject)}/outputs/code_coverage").matching {
                    include "**/*.ec"
                }
                executionData.setFrom(codeCoverageDirs.files + executionData.files)
            }

            // add if true in extension or for the unit test Jacoco task
            def addToMergeTask = !combined || extension.includeInstrumentationCoverageInMergedReport

            if (mergedReportTask != null && addToMergeTask) {
                mergedReportTask.configure { JacocoReport task ->
                    task.executionData.setFrom(executionData.files + task.executionData.files)
                    task.classDirectories.setFrom(classDirectories.getFrom() + task.classDirectories.getFrom())
                    task.additionalSourceDirs.setFrom(additionalSourceDirs.getFrom() + task.additionalSourceDirs.getFrom())
                    task.sourceDirectories.setFrom(sourceDirectories.getFrom() + task.sourceDirectories.getFrom())
                }
            }
        }

        subProject.check.dependsOn "${taskName}"
    }

    protected static TaskProvider<JacocoReport> addJacocoMergeToRootProject(final Project project, final JunitJacocoExtension extension) {
        project.plugins.apply('jacoco')

        project.afterEvaluate {
            // Apply the Jacoco version after evaluating the project so that the extension could be configured
            project.jacoco.toolVersion = extension.jacocoVersion
        }

        def mergedReportTask = project.tasks.register("jacocoTestReportMerged", JacocoReport) {
            executionData project.files().asFileTree // Start with an empty collection.

            reports {
                xml {
                    required = extension.xml.enabled
                    outputLocation = project.file("${getBuildDir(project)}/reports/jacoco/jacoco.xml")
                }
                csv {
                    required = extension.csv.enabled
                    outputLocation = project.file("${getBuildDir(project)}/reports/jacoco/jacoco.csv")
                }
                html {
                    required = extension.html.enabled
                    outputLocation = project.file("${getBuildDir(project)}/reports/jacoco")
                }
            }

            // Start with empty collections.
            getClassDirectories().from(project.files())
            getAdditionalSourceDirs().from(project.files())
            getSourceDirectories().from(project.files())
        }

        return mergedReportTask
    }

    private static File getBuildDir(final Project project) {
        return project.layout.buildDirectory.get().asFile
    }

    static List<String> getExcludes(final JunitJacocoExtension extension) {
        return extension.excludes != null ? extension.excludes : []
    }

    private static boolean isAndroidProject(final Project project) {
        final boolean isAndroidLibrary = project.plugins.hasPlugin('com.android.library')
        final boolean isAndroidApp = project.plugins.hasPlugin('com.android.application')
        final boolean isAndroidTest = project.plugins.hasPlugin('com.android.test')
        final boolean isAndroidDynamicFeature = project.plugins.hasPlugin('com.android.dynamic-feature')
        final boolean isAndroidInstantApp = project.plugins.hasPlugin('com.android.instantapp')
        return isAndroidLibrary || isAndroidApp || isAndroidTest || isAndroidDynamicFeature || isAndroidInstantApp
    }

    private static boolean isJavaProject(final Project project) {
        final boolean isJava = project.plugins.hasPlugin('java')
        final boolean isJavaLibrary = project.plugins.hasPlugin('java-library')
        final boolean isJavaGradlePlugin = project.plugins.hasPlugin('java-gradle-plugin')
        return isJava || isJavaLibrary || isJavaGradlePlugin
    }

    protected static boolean isKotlinAndroid(final Project project) {
        return project.plugins.hasPlugin('org.jetbrains.kotlin.android')
    }

    protected static boolean isKotlinMultiplatform(final Project project) {
        return project.plugins.hasPlugin('org.jetbrains.kotlin.multiplatform')
    }

    protected static boolean isAndroidApplication(final Project project) {
        return project.plugins.hasPlugin('com.android.application')
    }

    protected static boolean isAndroidLibrary(final Project project) {
        return project.plugins.hasPlugin('com.android.library')
    }

    protected static boolean isAndroidDynamicFeature(final Project project) {
        return project.plugins.hasPlugin('com.android.dynamic-feature')
    }

    private static boolean shouldIgnore(final Project project, final JunitJacocoExtension extension) {
        if (extension.ignoreProjects?.contains(project.name) || extension.ignoreProjects?.contains(project.path)) {
            // Regex could be slower.
            return true
        }

        if (extension.ignoreProjects != null) {
            for (String ignoredProject : extension.ignoreProjects) {
                if (project.name.find(ignoredProject) || project.path.find(ignoredProject)) {
                    return true
                }
            }
        }

        return false
    }
}
