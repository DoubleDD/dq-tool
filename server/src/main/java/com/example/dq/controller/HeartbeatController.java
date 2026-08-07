package com.example.dq.controller;

import com.example.dq.config.DesktopSession;
import io.javalin.http.Context;

/**
 * 页面心跳:前端每 5 秒调一次,桌面安装版的看门狗(DesktopSession)据此判断
 * --app 窗口是否还开着;不被授权拦截,未激活状态下也要能上报。
 */
public class HeartbeatController {

    private final DesktopSession session;

    public HeartbeatController(DesktopSession session) {
        this.session = session;
    }

    public void beat(Context ctx) {
        session.beat();
        ctx.status(204);
    }
}
