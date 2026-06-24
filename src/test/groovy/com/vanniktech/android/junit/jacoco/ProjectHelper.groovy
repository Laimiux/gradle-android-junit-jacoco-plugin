package com.vanniktech.android.junit.jacoco

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

/** Provides projects for testing */
final class ProjectHelper {
    static ProjectHelper prepare(ProjectType projectType) {
        return prepare(projectType, null)
    }

    static ProjectHelper prepare(ProjectType projectType, Project parent) {
        return new ProjectHelper(projectType, parent)
    }

    private final ProjectType projectType
    private final Project project

    private ProjectHelper(ProjectType projectType, Project parent) {
        this.projectType = projectType

        def builder = ProjectBuilder.builder().withParent(parent)

        switch (projectType) {
            case ProjectType.ROOT:
                project = builder.withName('root').build()
                project.extensions.create('junitJacoco', JunitJacocoExtension)
                GenerationPlugin.addJacocoMergeToRootProject(project, project.junitJacoco)
                break
            case ProjectType.JAVA:
                project = builder.withName('java').build()
                break
            case ProjectType.ANDROID_APPLICATION:
            case ProjectType.ANDROID_KOTLIN_APPLICATION:
            case ProjectType.ANDROID_DYNAMIC_FEATURE:
                def name = "android app ${projectType.name()}"
                project = builder.withName(name).build()
                def androidMock = createMockAppExtension()
                project.metaClass.android = androidMock
                installMockAndroidComponents(project, buildDefaultVariants(androidMock))
                break
            case ProjectType.ANDROID_LIBRARY:
            case ProjectType.ANDROID_KOTLIN_MULTIPLATFORM:
                def name = "android library ${projectType.name()}"
                project = builder.withName(name).build()
                def androidMock = createMockLibraryExtension()
                project.metaClass.android = androidMock
                installMockAndroidComponents(project, buildDefaultVariants(androidMock))
                break
            case ProjectType.ANDROID_TEST:
                project = builder.withName('android test').build()
                def androidMock = createMockTestExtension()
                project.metaClass.android = androidMock
                break
        }

        if (projectType.pluginNames != null) {
            for (String pluginName : projectType.pluginNames) {
                if (pluginName) {
                    project.plugins.apply(pluginName)
                }
            }
        }
    }

    private static def createMockAppExtension() {
        def buildTypes = ["debug", "release"].collect { bt ->
            def buildType = new Expando()
            buildType.name = bt
            buildType.testCoverageEnabled = true
            buildType.getName = { -> bt }
            return buildType
        }

        def androidMock = [
            getBuildTypes: { return buildTypes },
            buildTypes   : buildTypes,
            testOptions  : null,
            jacoco       : createMockJacocoOptions()
        ]
        return androidMock
    }

    private static def createMockLibraryExtension() {
        def buildTypes = ["debug", "release"].collect { bt ->
            def buildType = new Expando()
            buildType.name = bt
            buildType.testCoverageEnabled = true
            buildType.getName = { -> bt }
            return buildType
        }

        def androidMock = [
            getBuildTypes: { return buildTypes },
            buildTypes   : buildTypes,
            testOptions  : null,
            jacoco       : createMockJacocoOptions()
        ]
        return androidMock
    }

    private static def createMockTestExtension() {
        def androidMock = [
            testOptions: null,
            jacoco     : createMockJacocoOptions()
        ]
        return androidMock
    }

    private static def createMockJacocoOptions() {
        return [
            version: '7.9.0'
        ]
    }

    private static List buildDefaultVariants(androidMock) {
        return androidMock.buildTypes.collect { buildType ->
            new Expando(
                name: buildType.name,
                buildType: buildType.name,
                flavorName: '',
            )
        }
    }

    /**
     * Installs a fake AGP {@code androidComponents} via metaClass (same pattern used for the
     * {@code android} mock). Goes through {@code metaClass} rather than {@code extensions.add}
     * because the real AGP plugin is still applied by {@link ProjectType#pluginNames} and would
     * collide on the extension name.
     */
    private static def installMockAndroidComponents(Project project, List variants) {
        def components = new Expando()
        components.variants = variants
        components.selector = { -> new Expando(all: { -> 'ALL' }) }
        components.onVariants = { selector, Closure body ->
            components.variants.each { variant -> body.call(variant) }
        }
        project.metaClass.androidComponents = components
        return components
    }

    /** Adds flavors to project, only for Android based projects */
    ProjectHelper withRedBlueFlavors() {
        if (projectType == ProjectType.JAVA || projectType == ProjectType.ROOT) {
            throw new UnsupportedOperationException('Not supported with Java or plain projects')
        }

        def flavorNames = ['red', 'blue']
        def androidMock = project.android

        def newVariants = flavorNames.collectMany { flavorName ->
            androidMock.buildTypes.collect { buildType ->
                new Expando(
                    name: "${flavorName}${buildType.name.capitalize()}",
                    buildType: buildType.name,
                    flavorName: flavorName,
                )
            }
        }

        switch (projectType) {
            case ProjectType.ANDROID_APPLICATION:
            case ProjectType.ANDROID_LIBRARY:
            case ProjectType.ANDROID_DYNAMIC_FEATURE:
                project.androidComponents.variants = newVariants
                break
        }

        return this
    }

    Project get() {
        return project
    }

    enum ProjectType {
        ANDROID_APPLICATION('com.android.application'),
        ANDROID_KOTLIN_APPLICATION('com.android.application', 'org.jetbrains.kotlin.android'),
        ANDROID_KOTLIN_MULTIPLATFORM('com.android.library', 'org.jetbrains.kotlin.multiplatform'),
        ANDROID_LIBRARY('com.android.library'),
        ANDROID_DYNAMIC_FEATURE('com.android.dynamic-feature'),
        ANDROID_TEST('com.android.test'),
        JAVA('java'),
        ROOT(null)

        private final String[] pluginNames

        ProjectType(String... pluginNames) {
            this.pluginNames = pluginNames
        }
    }
}
