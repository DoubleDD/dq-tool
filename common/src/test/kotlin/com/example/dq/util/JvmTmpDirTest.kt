package com.example.dq.util

import org.apache.poi.util.DefaultTempFileCreationStrategy
import org.apache.poi.util.TempFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** JVM 全局临时目录重定向:java.io.tmpdir 指向数据目录 tmp,POI 临时文件随之落入,二次调用清扫残留 */
class JvmTmpDirTest {

    @TempDir
    lateinit var dataDir: Path

    private lateinit var originalTmp: String

    @BeforeEach
    fun saveTmpDir() {
        originalTmp = System.getProperty("java.io.tmpdir")
    }

    /** java.io.tmpdir 与 POI 临时文件策略都是 JVM 全局状态,测试完恢复原值,避免影响同 JVM 其他用例 */
    @AfterEach
    fun restore() {
        System.setProperty("java.io.tmpdir", originalTmp)
        TempFile.setTempFileCreationStrategy(DefaultTempFileCreationStrategy())
    }

    @Test
    fun `重定向后 java-io-tmpdir 指向数据目录 tmp 且目录已建`() {
        val tmp = JvmTmpDir.redirect(dataDir)
        assertTrue(Files.isDirectory(tmp))
        assertEquals(tmp.toAbsolutePath().toString(), System.getProperty("java.io.tmpdir"))
    }

    @Test
    fun `重定向后 POI 临时文件落到数据目录 tmp 的 poifiles`() {
        val tmp = JvmTmpDir.redirect(dataDir)
        val file = TempFile.createTempFile("poi-sxssf-sheet", ".xml")
        try {
            assertTrue(file.toPath().startsWith(tmp.resolve("poifiles")),
                "POI 临时文件应落在 ${tmp.resolve("poifiles")},实际: ${file.toPath()}")
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }

    @Test
    fun `二次重定向清扫上次进程残留的 poi 临时文件`() {
        val tmp = JvmTmpDir.redirect(dataDir)
        val poifiles = Files.createDirectories(tmp.resolve("poifiles"))
        val leftover = Files.write(poifiles.resolve("poi-sxssf-sheet123.xml"), byteArrayOf(1))
        val other = Files.write(poifiles.resolve("unrelated.txt"), byteArrayOf(1))

        JvmTmpDir.redirect(dataDir)

        assertFalse(Files.exists(leftover), "残留 SXSSF 临时文件应被清扫")
        assertTrue(Files.exists(other), "非 poi 前缀文件不应被清扫")
    }
}
