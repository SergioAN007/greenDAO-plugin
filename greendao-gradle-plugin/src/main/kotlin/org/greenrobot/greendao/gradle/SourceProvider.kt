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

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

interface SourceProvider {
    fun sourceFiles(): Sequence<FileTree>
    fun sourceTree(): FileTree = sourceFiles().reduce { a, b -> a + b }
    val encoding: String?
    fun addGeneratorTask(generatorTask: Task, targetGenDir: File, writeToBuildFolder: Boolean)
}

class AndroidPluginSourceProvider(val project: Project) : SourceProvider {

    override fun sourceFiles(): Sequence<FileTree> {
        // Source files are collected by DetectEntityCandidatesTask at build time
        return emptySequence()
    }

    override val encoding: String?
        get() = project.tasks.withType(JavaCompile::class.java).firstOrNull()?.options?.encoding

    override fun addGeneratorTask(generatorTask: Task, targetGenDir: File, writeToBuildFolder: Boolean) {
        // Variant sources are registered earlier in registerVariantSources();
        // here we only make compilation wait for the generator.
        project.tasks.withType(JavaCompile::class.java).configureEach { it.dependsOn(generatorTask) }
    }

    companion object {
        /** Must be called when the Android plugin is applied, before AGP runs its variant callbacks. */
        fun registerVariantSources(project: Project, genDir: () -> File) {
            val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
            androidComponents.onVariants { variant ->
                variant.sources.java?.addStaticSourceDirectory(genDir().absolutePath)
            }
        }
    }
}

class JavaPluginSourceProvider(val project: Project) : SourceProvider {
    private val javaPlugin: JavaPluginExtension =
        project.extensions.getByType(JavaPluginExtension::class.java)

    override fun sourceFiles(): Sequence<FileTree> =
        javaPlugin.sourceSets.asSequence().map { it.allJava.asFileTree }

    override val encoding: String?
        get() = project.tasks.withType(JavaCompile::class.java).firstOrNull()?.options?.encoding

    override fun addGeneratorTask(generatorTask: Task, targetGenDir: File, writeToBuildFolder: Boolean) {
        // for the main source set...
        val mainSourceSet = javaPlugin.sourceSets.maybeCreate("main")
        // ...make the compile task depend on the generator task
        val compileJavaTask = project.tasks.named(mainSourceSet.compileJavaTaskName) as JavaCompile
        compileJavaTask.dependsOn(generatorTask)
        if (writeToBuildFolder) {
            // ...add the generated sources folder to the source dirs
            mainSourceSet.java.srcDir(targetGenDir)
            // ...ensure the compile task has them on the classpath
            compileJavaTask.setSource(compileJavaTask.source + generatorTask.outputs.files)
        }
    }
}
