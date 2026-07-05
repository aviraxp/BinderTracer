package com.btrace.viewer.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UpdateChecker.isNewer] 版本比较逻辑:semver 三元组 + 预发布后缀。 */
class UpdateCheckerTest {

    @Test
    fun `higher major minor patch is newer`() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "v1.0.1"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "v1.1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "v2.0.0"))
    }

    @Test
    fun `same or lower is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "v1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "v1.1.0"))
        assertFalse(UpdateChecker.isNewer("2.0.0", "v1.9.9"))
    }

    @Test
    fun `release beats local prerelease at same triple`() {
        // 本地 dev 版:同版本号的正式 release 视为更新
        assertTrue(UpdateChecker.isNewer("1.0.0-dev", "v1.0.0"))
        // 但更低的正式版不算更新
        assertFalse(UpdateChecker.isNewer("1.0.0-dev", "v0.9.0"))
    }

    @Test
    fun `prerelease not newer than same prerelease triple`() {
        // remote 也是预发布、三元组相同 → 不算更新(不无限提示)
        assertFalse(UpdateChecker.isNewer("1.0.0-dev", "v1.0.0-dev"))
    }

    @Test
    fun `unparseable version does not falsely report update`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "latest"))
        assertFalse(UpdateChecker.isNewer("garbage", "also-garbage"))
    }
}
