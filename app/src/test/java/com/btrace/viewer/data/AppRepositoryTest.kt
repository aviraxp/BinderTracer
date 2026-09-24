package com.btrace.viewer.data

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AppRepositoryTest {
    private val packageManager: PackageManager = mock()
    private val context: Context = mock<Context>().also {
        whenever(it.packageManager).thenReturn(packageManager)
    }
    private val repository = AppRepository(context)

    @Test
    fun `system receiver is not named after a package sharing uid 1000`() {
        whenever(packageManager.getPackagesForUid(1000))
            .thenReturn(arrayOf("com.android.settings", "android", "com.vendor.systemapp"))

        assertEquals("system", repository.getPackageOrSystemNameForUid(1000))
    }

    @Test
    fun `known system uid takes priority even if only one package is returned`() {
        whenever(packageManager.getPackagesForUid(1001)).thenReturn(arrayOf("com.android.phone"))

        assertEquals("radio", repository.getPackageOrSystemNameForUid(1001))
        assertEquals("audioserver", repository.getPackageOrSystemNameForUid(1041))
    }

    @Test
    fun `ordinary app receiver uses its unique package`() {
        whenever(packageManager.getPackagesForUid(10123)).thenReturn(arrayOf("com.example.receiver"))

        assertEquals("com.example.receiver", repository.getPackageOrSystemNameForUid(10123))
    }

    @Test
    fun `shared app uid does not pick an arbitrary receiver package`() {
        whenever(packageManager.getPackagesForUid(10123))
            .thenReturn(arrayOf("com.example.first", "com.example.second"))

        assertNull(repository.getPackageOrSystemNameForUid(10123))
    }

    @Test
    fun `unknown or missing receiver uid has no package label`() {
        whenever(packageManager.getPackagesForUid(10124)).thenReturn(emptyArray())

        assertNull(repository.getPackageOrSystemNameForUid(10123))
        assertNull(repository.getPackageOrSystemNameForUid(10124))
        assertNull(repository.getPackageOrSystemNameForUid(0))
        assertNull(repository.getPackageOrSystemNameForUid(-1))
    }
}
