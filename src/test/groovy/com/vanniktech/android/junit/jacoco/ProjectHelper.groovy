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
                // mock .all{ } function from android gradle lib with standard groovy .each{ }
                androidMock.applicationVariants.metaClass.all = { delegate.each(it) }
                break
            case ProjectType.ANDROID_LIBRARY:
            case ProjectType.ANDROID_KOTLIN_MULTIPLATFORM:
                def name = "android library ${projectType.name()}"
                project = builder.withName(name).build()
                def androidMock = createMockLibraryExtension()
                project.metaClass.android = androidMock
                // mock .all{ } function from android gradle lib with standard groovy .each{ }
                androidMock.libraryVariants.metaClass.all = { delegate.each(it) }
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
            [
                getName            : { -> bt },
                name               : bt,
                testCoverageEnabled: true
            ]
        }

        def variants = buildTypes.collect { bt ->
            [
                getFlavorName: { -> null },
                getBuildType : { -> bt }
            ]
        }

        def androidMock = [
            getBuildTypes         : {
                return buildTypes
            },
            getApplicationVariants: {
                return variants
            },
            applicationVariants   : variants,
            testOptions           : null,
            jacoco                : createMockJacocoOptions()
        ]
        return androidMock
    }

    private static def createMockLibraryExtension() {
        def buildTypes = ["debug", "release"].collect { bt ->
            [
                getName            : { -> bt },
                name               : bt,
                testCoverageEnabled: true
            ]
        }

        def variants = buildTypes.collect { bt ->
            [
                getFlavorName: { -> null },
                getBuildType : { -> bt }
            ]
        }

        def androidMock = [
            getBuildTypes     : {
                return buildTypes
            },
            getLibraryVariants: {
                return variants
            },
            libraryVariants   : variants,
            testOptions       : null,
            jacoco            : createMockJacocoOptions()
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

    /** Adds flavors to project, only for Android based projects */
    ProjectHelper withRedBlueFlavors() {
        if (projectType == ProjectType.JAVA || projectType == ProjectType.ROOT) {
            throw new UnsupportedOperationException('Not supported with Java or plain projects')
        }

        def customFlavors = [
            red : [applicationId: 'com.example.red'],
            blue: [applicationId: 'com.example.blue']
        ]

        def variants = customFlavors.collect { flavorName, config ->
            def android = project.android ?: project.metaClass.android
            android.buildTypes.collect { buildType ->
                [
                    getBuildType    : {
                        return [
                            getName            : { -> buildType.name },
                            name               : buildType.name,
                            testCoverageEnabled: true
                        ]
                    },
                    getFlavorName   : { -> flavorName },
                    getApplicationId: { -> config.applicationId }
                ]
            }
        }.flatten()

        switch (projectType) {
            case ProjectType.ANDROID_APPLICATION:
                project.android.applicationVariants = variants
                // mock .all{ } function from android gradle lib with standard groovy .each{ }
                project.android.applicationVariants.metaClass.all = { delegate.each(it) }
                break
            case ProjectType.ANDROID_LIBRARY:
            case ProjectType.ANDROID_DYNAMIC_FEATURE:
                project.android.libraryVariants = variants
                // mock .all{ } function from android gradle lib with standard groovy .each{ }
                project.android.libraryVariants.metaClass.all = { delegate.each(it) }
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
