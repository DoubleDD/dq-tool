package com.example.dq.controller;

import com.example.dq.model.AiConfigRequest;
import com.example.dq.model.AiConfigView;
import com.example.dq.service.AiConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 大模型接口配置(全局单条,页面可视化维护) */
@RestController
@RequestMapping("/api/ai-config")
public class AiConfigController {

    private final AiConfigService service;

    public AiConfigController(AiConfigService service) {
        this.service = service;
    }

    @GetMapping
    public AiConfigView get() {
        return service.get();
    }

    @PutMapping
    public void save(@RequestBody AiConfigRequest req) {
        service.save(req);
    }
}
