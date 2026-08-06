package com.example.dq.controller;

import com.example.dq.model.LicenseActivateRequest;
import com.example.dq.model.LicenseStatusView;
import com.example.dq.service.LicenseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 授权码:查询状态 + 激活(不被授权拦截器拦截) */
@RestController
@RequestMapping("/api/license")
public class LicenseController {

    private final LicenseService service;

    public LicenseController(LicenseService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public LicenseStatusView status() {
        return service.status();
    }

    @PostMapping("/activate")
    public LicenseStatusView activate(@RequestBody LicenseActivateRequest req) {
        return service.activate(req.code());
    }
}
