package com.example.dq.license;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 授权码编解码与验签(纯 JDK,无需容器) */
class LicenseCodecTest {

    private static KeyPair genKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void 签发的码能验过且字段正确() throws Exception {
        KeyPair kp = genKeyPair();
        String code = LicenseCodec.encode("某某公司", LocalDate.of(2026, 12, 31), kp.getPrivate());

        LicenseCodec.LicensePayload payload = LicenseCodec.decodeAndVerify(code, kp.getPublic());

        assertEquals("某某公司", payload.customer());
        assertEquals(LocalDate.of(2026, 12, 31), payload.expiresAt());
    }

    @Test
    void 容忍粘贴带入的空白和换行() throws Exception {
        KeyPair kp = genKeyPair();
        String code = LicenseCodec.encode("客户A", LocalDate.of(2027, 6, 30), kp.getPrivate());
        String messy = "  " + code.substring(0, 10) + "\n" + code.substring(10) + "  \n";

        LicenseCodec.LicensePayload payload = LicenseCodec.decodeAndVerify(messy, kp.getPublic());

        assertEquals("客户A", payload.customer());
    }

    @Test
    void 篡改payload验签失败() throws Exception {
        KeyPair kp = genKeyPair();
        String code = LicenseCodec.encode("客户A", LocalDate.of(2027, 6, 30), kp.getPrivate());
        String[] parts = code.split("\\.");
        // 换一个 payload(改为客户B),签名不变
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("客户B|2027-06-30".getBytes());
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.decodeAndVerify(tampered, kp.getPublic()));
    }

    @Test
    void 错误公钥验签失败() throws Exception {
        KeyPair signer = genKeyPair();
        KeyPair other = genKeyPair();
        String code = LicenseCodec.encode("客户A", LocalDate.of(2027, 6, 30), signer.getPrivate());

        assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.decodeAndVerify(code, other.getPublic()));
    }

    @Test
    void 乱码和错误格式拒绝() throws Exception {
        KeyPair kp = genKeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.decodeAndVerify("not-a-license", kp.getPublic()));
        assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.decodeAndVerify("DQ2.abc.def", kp.getPublic()));
        assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.decodeAndVerify("", kp.getPublic()));
        assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.decodeAndVerify(null, kp.getPublic()));
    }

    @Test
    void 签发侧校验客户标识() throws Exception {
        KeyPair kp = genKeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.encode("含|竖线", LocalDate.now(), kp.getPrivate()));
        assertThrows(IllegalArgumentException.class,
                () -> LicenseCodec.encode("  ", LocalDate.now(), kp.getPrivate()));
    }

    @Test
    void 永久授权码验签通过且永不过期() throws Exception {
        KeyPair kp = genKeyPair();
        String code = LicenseCodec.encode("永久客户", null, kp.getPrivate());

        LicenseCodec.LicensePayload payload = LicenseCodec.decodeAndVerify(code, kp.getPublic());

        assertEquals("永久客户", payload.customer());
        assertNull(payload.expiresAt());
        assertFalse(LicenseCodec.isExpired(payload.expiresAt(), LocalDate.now().plusYears(100)));
    }

    @Test
    void 到期日当天有效次日过期() {
        LocalDate expires = LocalDate.of(2026, 12, 31);
        assertFalse(LicenseCodec.isExpired(expires, LocalDate.of(2026, 12, 31)));
        assertTrue(LicenseCodec.isExpired(expires, LocalDate.of(2027, 1, 1)));
    }
}
