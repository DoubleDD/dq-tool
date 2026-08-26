package com.example.dq.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BrowserOpener.pickBrowser:用户配置的浏览器优先,未配置/配置项未安装回落「自动」(探测清单第一个),
 * 一个都没探测到返回 null(上层回落系统默认浏览器)。
 */
class BrowserPickTest {

    private static final List<BrowserOpener.BrowserInfo> DETECTED = List.of(
            new BrowserOpener.BrowserInfo("edge", "Microsoft Edge", "/edge"),
            new BrowserOpener.BrowserInfo("chrome", "Google Chrome", "/chrome"));

    @Test
    void 配置的浏览器命中探测清单时使用其路径() {
        assertEquals("/chrome", BrowserOpener.pickBrowser("chrome", DETECTED));
    }

    @Test
    void 未配置时按自动优先级取清单第一个() {
        assertEquals("/edge", BrowserOpener.pickBrowser(null, DETECTED));
    }

    @Test
    void 配置的浏览器未安装时回落自动选择() {
        assertEquals("/edge", BrowserOpener.pickBrowser("brave", DETECTED));
    }

    @Test
    void 未探测到任何浏览器时返回空() {
        assertNull(BrowserOpener.pickBrowser(null, List.of()));
        assertNull(BrowserOpener.pickBrowser("chrome", List.of()));
    }
}
