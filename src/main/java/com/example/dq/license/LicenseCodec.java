package com.example.dq.license;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 授权码编解码与离线验签(纯函数,不依赖 Spring)。
 *
 * 授权码格式:DQ1.&lt;base64url(payload)&gt;.&lt;base64url(signature)&gt;
 *  - payload   = "客户名|yyyy-MM-dd"(永久授权为 "客户名|PERMANENT")的 UTF-8 字节
 *  - signature = Ed25519(payload),由签发方私钥生成,程序内嵌公钥离线验证
 * DQ1 为版本前缀,便于将来格式演进。
 */
public final class LicenseCodec {

    public static final String VERSION = "DQ1";

    /** payload 中有效期的永久标记:客户名|PERMANENT */
    public static final String PERMANENT = "PERMANENT";

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private LicenseCodec() {
    }

    /** 解析出的授权内容;expiresAt 为 null 表示永久有效 */
    public record LicensePayload(String customer, LocalDate expiresAt) {
    }

    /** 签发授权码(签发工具与测试使用);expiresAt 传 null 表示永久有效 */
    public static String encode(String customer, LocalDate expiresAt, PrivateKey privateKey) {
        if (customer == null || customer.isBlank()) {
            throw new IllegalArgumentException("客户标识不能为空");
        }
        if (customer.contains("|")) {
            throw new IllegalArgumentException("客户标识不能包含 | 字符");
        }
        String expiry = expiresAt == null ? PERMANENT : expiresAt.toString();
        byte[] payload = (customer + "|" + expiry).getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(payload, privateKey);
        return VERSION + "." + B64.encodeToString(payload) + "." + B64.encodeToString(signature);
    }

    /**
     * 解析并验签授权码,失败一律抛 IllegalArgumentException("授权码无效")。
     * 容忍粘贴时带入的空白与换行。
     */
    public static LicensePayload decodeAndVerify(String code, PublicKey publicKey) {
        if (code == null) {
            throw invalid();
        }
        String compact = code.replaceAll("\\s+", "");
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw invalid();
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = B64_DEC.decode(parts[1]);
            signature = B64_DEC.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!verify(payload, signature, publicKey)) {
            throw invalid();
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        int sep = text.lastIndexOf('|');
        if (sep <= 0 || sep == text.length() - 1) {
            throw invalid();
        }
        String customer = text.substring(0, sep);
        String expiry = text.substring(sep + 1);
        LocalDate expiresAt;
        if (PERMANENT.equals(expiry)) {
            expiresAt = null;
        } else {
            try {
                expiresAt = LocalDate.parse(expiry);
            } catch (DateTimeParseException e) {
                throw invalid();
            }
        }
        return new LicensePayload(customer, expiresAt);
    }

    /** 到期日当天仍有效,today 晚于 expiresAt 才算过期;expiresAt 为 null(永久)时永不过期 */
    public static boolean isExpired(LocalDate expiresAt, LocalDate today) {
        return expiresAt != null && today.isAfter(expiresAt);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("授权码无效,请核对后重新输入");
    }

    private static byte[] sign(byte[] payload, PrivateKey privateKey) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(payload);
            return sig.sign();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException("授权码签名失败: " + e.getMessage(), e);
        }
    }

    private static boolean verify(byte[] payload, byte[] signature, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(payload);
            return sig.verify(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            return false;
        }
    }
}
