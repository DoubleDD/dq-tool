package com.example.dq.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** TLSv1/TLSv1.1 摘除逻辑:只移除这两个条目,其余(含名字前缀相近的条目)原样保留 */
class LegacyTlsSupportTest {

    @Test
    void 从JDK默认禁用列表中移除TLSv1与TLSv1_1() {
        // JDK 25 默认值(节选顺序一致)
        String original = "SSLv3, TLSv1, TLSv1.1, DTLSv1.0, RC4, DES, MD5withRSA,"
                + " DH keySize < 1024, EC keySize < 224, 3DES_EDE_CBC, anon, NULL, ECDH, TLS_RSA_*";
        String expected = "SSLv3, DTLSv1.0, RC4, DES, MD5withRSA,"
                + " DH keySize < 1024, EC keySize < 224, 3DES_EDE_CBC, anon, NULL, ECDH, TLS_RSA_*";
        assertEquals(expected, LegacyTlsSupport.stripLegacyTls(original));
    }

    @Test
    void 只精确匹配条目不误伤相近名字() {
        // DTLSv1.0、TLS_RSA_* 等含 "TLSv1" 子串的条目必须保留
        String original = "DTLSv1.0, TLSv1, TLS_RSA_*, TLSv1.1";
        assertEquals("DTLSv1.0, TLS_RSA_*", LegacyTlsSupport.stripLegacyTls(original));
    }

    @Test
    void 未禁用时原样返回() {
        String original = "SSLv3, RC4, DES";
        assertEquals(original, LegacyTlsSupport.stripLegacyTls(original));
        assertEquals("", LegacyTlsSupport.stripLegacyTls(""));
        assertNull(LegacyTlsSupport.stripLegacyTls(null));
    }
}
