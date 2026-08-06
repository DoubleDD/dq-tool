package com.example.dq.service;

import com.example.dq.config.DqProperties;
import com.example.dq.license.LicenseCodec;
import com.example.dq.model.LicenseRequiredException;
import com.example.dq.model.LicenseStatusView;
import com.example.dq.repository.LicenseRepository;
import com.example.dq.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * 授权码:离线 Ed25519 验签,公钥在 dq.license.public-key;
 * 激活后的授权码加密存本地 H2(license_info 单行)。
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    private final LicenseRepository repository;
    private final CryptoUtil crypto;
    private final PublicKey publicKey;

    /** 状态缓存,activate 后刷新,避免每个请求查库验签 */
    private volatile LicenseStatusView cached;

    public LicenseService(LicenseRepository repository, CryptoUtil crypto, DqProperties props) {
        this.repository = repository;
        this.crypto = crypto;
        this.publicKey = parsePublicKey(props.getLicense().getPublicKey());
    }

    private static PublicKey parsePublicKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("dq.license.public-key 配置无效: " + e.getMessage(), e);
        }
    }

    /** 当前授权状态(展示用,不回传授权码本身) */
    public LicenseStatusView status() {
        LicenseStatusView c = cached;
        if (c == null) {
            c = loadStatus();
            cached = c;
        }
        return c;
    }

    /** 提交授权码激活,成功后返回最新状态 */
    public synchronized LicenseStatusView activate(String code) {
        if (publicKey == null) {
            throw new IllegalStateException("程序未配置授权公钥,请联系分发方");
        }
        LicenseCodec.LicensePayload payload = LicenseCodec.decodeAndVerify(code, publicKey);
        if (LicenseCodec.isExpired(payload.expiresAt(), LocalDate.now())) {
            throw new IllegalArgumentException("授权码已于 " + payload.expiresAt() + " 到期,请向分发方索取新授权码");
        }
        String compact = code.replaceAll("\\s+", "");
        repository.upsert(crypto.encrypt(compact), payload.customer(), payload.expiresAt());
        cached = loadStatus();
        return cached;
    }

    /** 拦截器调用:未激活/已过期抛 LicenseRequiredException */
    public void checkActive() {
        LicenseStatusView s = status();
        if (!s.activated()) {
            throw new LicenseRequiredException("程序未激活,请先输入授权码");
        }
        if (s.expired()) {
            throw new LicenseRequiredException("授权已于 " + s.expiresAt() + " 到期,请更新授权码");
        }
    }

    /** 从库中加载并校验(库里被手改导致验签失败时按未激活处理) */
    private LicenseStatusView loadStatus() {
        return repository.get().map(row -> {
            try {
                LicenseCodec.LicensePayload payload =
                        LicenseCodec.decodeAndVerify(crypto.decrypt(row.codeEnc()), publicKey);
                LocalDate today = LocalDate.now();
                boolean expired = LicenseCodec.isExpired(payload.expiresAt(), today);
                Long daysLeft = payload.expiresAt() == null ? null
                        : Math.max(ChronoUnit.DAYS.between(today, payload.expiresAt()), 0);
                return new LicenseStatusView(true, expired, payload.customer(), payload.expiresAt(), daysLeft);
            } catch (RuntimeException e) {
                log.warn("库存授权码校验失败,按未激活处理: {}", e.getMessage());
                return LicenseStatusView.notActivated();
            }
        }).orElseGet(LicenseStatusView::notActivated);
    }
}
