package org.greenrobot.greendao.gradle

import org.gradle.api.Project
import org.junit.Test
import org.mockito.Mockito.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class GreendaoOptionsTest {
    @Test
    fun testDefaultOptions() {
        val project = mock(Project::class.java)
        `when`(project.file(any(String::class.java))).thenReturn(mock(java.io.File::class.java))

        val options = GreendaoOptions(project)

        assertEquals(1, options.schemaVersion)
        assertNull(options.daoPackage)
        assertEquals(false, options.generateTests)
    }

    @Test
    fun testSetOptions() {
        val project = mock(Project::class.java)
        `when`(project.file(any(String::class.java))).thenReturn(mock(java.io.File::class.java))

        val options = GreendaoOptions(project)
        options.schemaVersion(3)
        options.daoPackage("com.example.dao")
        options.generateTests(true)

        assertEquals(3, options.schemaVersion)
        assertEquals("com.example.dao", options.daoPackage)
        assertEquals(true, options.generateTests)
    }
}
