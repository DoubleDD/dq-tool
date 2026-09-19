package com.example.dq.util

import org.apache.poi.util.DefaultTempFileCreationStrategy
import org.apache.poi.util.TempFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * JVM 全局临时目录重定向:启动早期把 `java.io.tmpdir` 指向 `<数据目录>/tmp`。
 *
 * 系统临时目录(%TEMP%/tmp)会被存储感知/安全软件清扫,POI SXSSF 等正在写的临时文件
 * 被删后回读抛 NoSuchFileException(客户现场:HTTP 409 + ...\Temp\poifiles\poi-sxssf-sheet*.xml);
 * 数据目录由单实例锁独占,临时文件收进来不受系统清扫影响,poifiles 等子目录随用随建。
 *
 * 必须在 main 早期、任何临时文件使用方初始化前调用:JDK 首次使用 java.io.tmpdir 时才固化
 * 该值(之后改属性对 Files.createTempFile 无效);POI 的默认临时文件策略在类加载时固化
 * 该值,这里显式重建一次,确保读到重定向后的值。
 */
object JvmTmpDir {

    /** 数据目录下的全局临时目录 */
    fun dir(dataDir: Path): Path = dataDir.resolve("tmp")

    /** 重定向 java.io.tmpdir 到 `<数据目录>/tmp` 并清扫上次进程残留的 SXSSF 临时文件;幂等 */
    fun redirect(dataDir: Path): Path {
        val tmp = dir(dataDir)
        Files.createDirectories(tmp)
        System.setProperty("java.io.tmpdir", tmp.toAbsolutePath().toString())
        // POI 默认策略在 TempFile 类加载时固化 java.io.tmpdir,显式重建确保读到重定向后的值
        TempFile.setTempFileCreationStrategy(DefaultTempFileCreationStrategy())
        sweepPoiLeftovers(tmp)
        return tmp
    }

    /** 清扫上次进程崩溃残留的 SXSSF 临时文件(正常关闭的导出 POI 已自行删除) */
    private fun sweepPoiLeftovers(tmp: Path) {
        val poifiles = tmp.resolve("poifiles")
        if (!Files.isDirectory(poifiles)) {
            return
        }
        Files.list(poifiles).use { stream ->
            stream.filter { it.fileName.toString().startsWith("poi-") }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
