/*
 * greenDAO Build Tools
 * Copyright (C) 2016-2024 greenrobot (https://greenrobot.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.greenrobot.greendao.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.util.PatternFilterable
import org.greenrobot.greendao.codemodifier.Greendao3Generator
import org.greenrobot.greendao.codemodifier.SchemaOptions
import java.io.File
import java.io.IOException
import java.util.Properties

class Greendao3GradlePlugin : Plugin<Project> {

    val name: String = "greendao"
    val packageName: String = "org/greenrobot/greendao"

    override fun apply(project: Project) {
        project.logger.debug("$name plugin starting...")
        project.extensions.create(name, GreendaoOptions::class.java, project)

        // Use afterEvaluate so order of applying the plugins in consumer projects does not matter
        project.afterEvaluate {
            val version = getVersion()
            project.logger.debug("$name plugin $version preparing tasks...")
            val candidatesFile = project.file("build/cache/$name-candidates.list")
            val sourceProvider = getSourceProvider(project)
            val encoding = sourceProvider.encoding ?: "UTF-8"

            val prepareTask = project.tasks.register("${name}Prepare", DetectEntityCandidatesTask::class.java) { task ->
                task.sourceFiles = project.fileTree("src").matching { pf: PatternFilterable ->
                    pf.include("**/*.java")
                }
                task.candidatesListFile = candidatesFile
                task.version = version
                task.charset = encoding
                task.group = name
                task.description = "Finds entity source files for $name"
            }

            val options = project.extensions.getByType(GreendaoOptions::class.java)
            val writeToBuildFolder = options.targetGenDir == null
            val targetGenDir = if (writeToBuildFolder)
                File(project.buildDir, "generated/source/$name") else options.targetGenDir!!

            val greendaoTask = createGreendaoTask(project, candidatesFile, options, targetGenDir, encoding, version)
            greendaoTask.configure { it.dependsOn(prepareTask) }

            sourceProvider.addGeneratorTask(greendaoTask, targetGenDir, writeToBuildFolder)
        }
    }

    private fun createGreendaoTask(project: Project, candidatesFile: File, options: GreendaoOptions,
                                   targetGenDir: File, encoding: String, version: String): org.gradle.api.tasks.TaskProvider<GreendaoGenerateTask> {
        return project.tasks.register(name, GreendaoGenerateTask::class.java) { task ->
            task.outputDir.set(targetGenDir)
            task.candidatesFile.set(candidatesFile)
            task.pluginVersion.set(version)
            task.sourceEncoding.set(encoding)
            task.daoPackage.set(options.daoPackage ?: "")
            task.schemaVersion.set(options.schemaVersion)
            task.skipTestGeneration.set(options.skipTestGeneration)
            task.generateTests.set(options.generateTests)
            task.targetGenDirTests.set(options.targetGenDirTests)
            task.formattingData.set(options.formatting.data)

            task.group = name
            task.description = "Generates source files for $name"
        }
    }

    private fun getVersion(): String {
        val properties = Properties()
        val stream = javaClass.getResourceAsStream("/$packageName/gradle/version.properties")
        stream?.use {
            try {
                properties.load(it)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return properties.getProperty("version") ?: "Unknown (bad build)"
    }

    val ANDROID_PLUGINS = listOf(
        "android", "android-library", /* Legacy Android Plugin */
        "com.android.application", "com.android.library", /* Android Plugin */
        "com.android.feature" /* Instant App Plugin */
    )

    /** @throws RuntimeException if no supported plugins applied */
    private fun getSourceProvider(project: Project): SourceProvider {
        when {
            project.plugins.hasPlugin("java") -> return JavaPluginSourceProvider(project)

            ANDROID_PLUGINS.any { project.plugins.hasPlugin(it) } -> return AndroidPluginSourceProvider(project)

            else -> throw RuntimeException("greenDAO supports only Java and Android projects. " +
                "None of the corresponding plugins have been applied to the project.")
        }
    }

}

/**
 * Task that generates greenDAO source files.
 * Exposes outputDir as a DirectoryProperty for use with AGP 9's addGeneratedSourceDirectory API.
 */
abstract class GreendaoGenerateTask : org.gradle.api.DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val candidatesFile: org.gradle.api.file.RegularFileProperty

    @get:Input
    abstract val pluginVersion: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val sourceEncoding: org.gradle.api.provider.Property<String>

    @get:Input
    @get:Optional
    abstract val daoPackage: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val schemaVersion: org.gradle.api.provider.Property<Int>

    @get:Input
    abstract val skipTestGeneration: org.gradle.api.provider.ListProperty<String>

    @get:Input
    abstract val generateTests: org.gradle.api.provider.Property<Boolean>

    @get:Internal
    abstract val targetGenDirTests: org.gradle.api.file.DirectoryProperty

    @get:Internal
    abstract val formattingData: org.gradle.api.provider.Property<org.greenrobot.greendao.codemodifier.FormattingOptions>

    @TaskAction
    fun generate() {
        logging.captureStandardOutput(LogLevel.INFO)

        val candidatesFile = candidatesFile.get().asFile
        val outputDir = outputDir.get().asFile
        val encoding = sourceEncoding.get()

        require(candidatesFile.exists()) {
            "Candidates file does not exist. Can't continue"
        }

        // read candidates file skipping first line for timestamp
        val candidatesFiles = candidatesFile.readLines().asSequence().drop(1).map { File(it) }.asIterable()

        val schemaOptions = mutableMapOf("default" to SchemaOptions(
            name = "default",
            version = schemaVersion.get(),
            daoPackage = daoPackage.get().ifEmpty { null },
            outputDir = outputDir,
            testsOutputDir = if (generateTests.get()) targetGenDirTests.get().asFile else null
        ))

        Greendao3Generator(
            formattingData.get(),
            skipTestGeneration.get(),
            encoding
        ).run(candidatesFiles, schemaOptions)
    }
}
