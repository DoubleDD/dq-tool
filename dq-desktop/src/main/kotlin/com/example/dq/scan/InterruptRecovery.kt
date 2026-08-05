package com.example.dq.scan

import com.example.dq.service.ScanService

/** 应用启动时把上次异常中断的任务标记为 INTERRUPTED,等待手动续扫;由程序入口 main 显式调用 [recover] */
class InterruptRecovery(private val scanService: ScanService) {

    fun recover() {
        scanService.recoverAfterRestart()
    }
}
