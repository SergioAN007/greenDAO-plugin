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

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

interface SourceProvider {
    fun sourceFiles(): Sequence<FileTree>
    fun sourceTree(): FileTree = sourceFiles().reduce { a, b -> a + b }
    val encoding: String? get
    fun addGeneratorTask(generatorTask: org.gradle.api.tasks.TaskProvider<GreendaoGenerateTask>, targetGenDir: File, writeToBuildFolder: Boolean)
}

class AndroidPluginSourceProvider(val project: Project) : SourceProvider {

    override fun sourceFiles(): Sequence<FileTree> {
        // Source files are collected by DetectEntityCandidatesTask at build time
        return emptySequence()
    }

    override val encoding: String?
        get() = project.tasks.withType(JavaCompile::class.java).firstOrNull()?.options?.encoding

    override fun addGeneratorTask(generatorTask: org.gradle.api.tasks.TaskProvider<GreendaoGenerateTask>, targetGenDir: File, writeToBuildFolder: Boolean) {
        // Try application extension first (AGP 9 compatible)
        val appExtension = project.extensions.findByType(ApplicationAndroidComponentsExtension::class.java)
        if (appExtension != null) {
            registerGeneratedSources(appExtension, generatorTask)
            return
        }

        // Try library extension
        val libExtension = project.extensions.findByType(LibraryAndroidComponentsExtension::class.java)
        if (libExtension != null) {
            registerGeneratedSources(libExtension, generatorTask)
            return
        }

        // Fallback: add to compile task directly
        project.tasks.withType(JavaCompile::class.java).configureEach { compileTask ->
            compileTask.dependsOn(generatorTask)
        }
    }

    private fun registerGeneratedSources(extension: Any, generatorTask: org.gradle.api.tasks.TaskProvider<GreendaoGenerateTask>) {
        // Use reflection to call onVariants with a dynamically created proxy
        // This avoids AGP 9 type system issues where the callback parameter type is not accessible
        try {
            val onVariantsMethod = extension.javaClass.methods.first { it.name == "onVariants" }
            val callbackType = onVariantsMethod.parameterTypes.last()

            val proxy = Proxy.newProxyInstance(
                callbackType.classLoader,
                arrayOf(callbackType)
            ) { _: Any, method: Method, args: Array<Any?>? ->
                if (method.name == "invoke" && args != null && args.isNotEmpty()) {
                    val variant = args[0]
                    onVariant(variant, generatorTask)
                }
                null
            }

            if (onVariantsMethod.parameterTypes.size == 1) {
                onVariantsMethod.invoke(extension, proxy)
            } else {
                // Two parameters: selector and callback
                val selectorType = onVariantsMethod.parameterTypes[0]
                val selector = Proxy.newProxyInstance(
                    selectorType.classLoader,
                    arrayOf(selectorType)
                ) { _: Any, _: Method, _: Array<Any?>? -> null }
                onVariantsMethod.invoke(extension, selector, proxy)
            }
        } catch (e: Exception) {
            project.logger.info("greenDAO: Could not register via onVariants, using fallback: ${e.message}")
            // Fallback: make compile tasks depend on generator
            project.tasks.withType(JavaCompile::class.java).configureEach { compileTask ->
                compileTask.dependsOn(generatorTask)
            }
        }
    }

    private fun onVariant(variant: Any?, generatorTask: org.gradle.api.tasks.TaskProvider<GreendaoGenerateTask>) {
        if (variant == null) return
        try {
            val sources = variant.javaClass.getMethod("sources").invoke(variant)
            val javaSources = sources.javaClass.getMethod("java").invoke(sources)
            if (javaSources != null) {
                val addMethod = javaSources.javaClass.methods.first { it.name == "addGeneratedSourceDirectory" }
                val wiringType = addMethod.parameterTypes.last()

                val wiring = Proxy.newProxyInstance(
                    wiringType.classLoader,
                    arrayOf(wiringType)
                ) { _: Any, _: Method, args: Array<Any?>? ->
                    if (args != null && args.isNotEmpty()) {
                        val task = args[0] ?: return@newProxyInstance null
                        task.javaClass.getMethod("getOutputDir").invoke(task)
                    } else {
                        null
                    }
                }

                if (addMethod.parameterTypes.size == 2) {
                    addMethod.invoke(javaSources, generatorTask, wiring)
                }
            }
        } catch (e: Exception) {
            project.logger.debug("greenDAO: Could not register generated sources: ${e.message}")
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

    override fun addGeneratorTask(generatorTask: org.gradle.api.tasks.TaskProvider<GreendaoGenerateTask>, targetGenDir: File, writeToBuildFolder: Boolean) {
        // for the main source set...
        val mainSourceSet = javaPlugin.sourceSets.maybeCreate("main")
        // ...make the compile task depend on the generator task
        val compileJavaTask = project.tasks.named(mainSourceSet.compileJavaTaskName) as JavaCompile
        compileJavaTask.dependsOn(generatorTask)
        if (writeToBuildFolder) {
            // ...add the generated sources folder to the source dirs
            mainSourceSet.java.srcDir(targetGenDir)
            // ...ensure the compile task has them on the classpath
            compileJavaTask.setSource(compileJavaTask.source + generatorTask.map { it.outputs.files })
        }
    }
}
