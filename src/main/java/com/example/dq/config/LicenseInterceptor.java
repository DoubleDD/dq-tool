package com.example.dq.config;

import com.example.dq.service.LicenseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 授权拦截:/api/** 未激活或授权过期时抛 LicenseRequiredException(转 401) */
@Component
public class LicenseInterceptor implements HandlerInterceptor {

    private final LicenseService licenseService;

    public LicenseInterceptor(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        licenseService.checkActive();
        return true;
    }
}
