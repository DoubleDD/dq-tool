package com.example.dq.scan;

import com.example.dq.service.ScanService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 应用启动时把上次异常中断的任务标记为 INTERRUPTED,等待手动续扫 */
@Component
public class InterruptRecovery implements ApplicationRunner {

    private final ScanService scanService;

    public InterruptRecovery(ScanService scanService) {
        this.scanService = scanService;
    }

    @Override
    public void run(ApplicationArguments args) {
        scanService.recoverAfterRestart();
    }
}
