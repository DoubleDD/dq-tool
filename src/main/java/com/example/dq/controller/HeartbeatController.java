package com.example.dq.controller;

import com.example.dq.config.DesktopSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 页面心跳:前端每 5 秒调一次,桌面安装版的看门狗(DesktopSession)据此判断
 * --app 窗口是否还开着;不被授权拦截器拦截,未激活状态下也要能上报。
 */
@RestController
@RequestMapping("/api/heartbeat")
public class HeartbeatController {

    private final DesktopSession session;

    public HeartbeatController(DesktopSession session) {
        this.session = session;
    }

    @GetMapping
    public ResponseEntity<Void> beat() {
        session.beat();
        return ResponseEntity.noContent().build();
    }
}
