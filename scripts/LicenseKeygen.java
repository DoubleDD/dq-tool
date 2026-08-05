import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;

/**
 * 授权码签发工具(仅分发方使用,私钥自行保管、不要提交仓库)。
 * 纯 JDK 单文件,无需编译项目,直接源码模式运行:
 *
 *   # 1) 生成密钥对(只需一次):输出公钥和私钥文件 license-private.key
 *   java scripts/LicenseKeygen.java --gen-keypair
 *
 *   # 2) 把公钥填入 src/main/resources/application.yml 的 dq.license.public-key
 *
 *   # 3) 签发授权码
 *   java scripts/LicenseKeygen.java --key license-private.key --customer "某某公司" --expires 2026-12-31
 *
 * 授权码格式须与 com.example.dq.license.LicenseCodec 保持一致:
 * DQ1.<base64url(客户名|yyyy-MM-dd 或 PERMANENT)>.<base64url(Ed25519 签名)>
 */
public class LicenseKeygen {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--gen-keypair")) {
            genKeyPair();
            return;
        }
        String keyFile = null, customer = null, expires = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--key" -> keyFile = args[++i];
                case "--customer" -> customer = args[++i];
                case "--expires" -> expires = args[++i];
                default -> { }
            }
        }
        if (keyFile == null || customer == null || expires == null) {
            System.err.println("用法:");
            System.err.println("  生成密钥对: java scripts/LicenseKeygen.java --gen-keypair");
            System.err.println("  签发授权码: java scripts/LicenseKeygen.java --key <私钥文件> --customer <客户名> --expires <yyyy-MM-dd|permanent>");
            System.exit(1);
        }
        sign(keyFile, customer, expires);
    }

    private static void genKeyPair() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyPath = Path.of("license-private.key");
        Files.write(keyPath, Base64.getEncoder().encode(kp.getPrivate().getEncoded()));
        try {
            Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows 无 POSIX 权限,提醒用户自行保管即可
        }
        System.out.println("密钥对已生成:");
        System.out.println("  私钥文件: " + keyPath.toAbsolutePath() + "  (务必自行保管,不要提交仓库/外发)");
        System.out.println();
        System.out.println("把下面的公钥填入 src/main/resources/application.yml 的 dq.license.public-key:");
        System.out.println(Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
    }

    private static void sign(String keyFile, String customer, String expiresStr) throws Exception {
        if (customer.contains("|")) {
            System.err.println("客户名不能包含 | 字符");
            System.exit(1);
        }
        // expires 传 permanent 表示永久授权(payload 中存 PERMANENT 标记)
        boolean permanent = "permanent".equalsIgnoreCase(expiresStr);
        LocalDate expires = permanent ? null : LocalDate.parse(expiresStr);
        byte[] keyBytes = Base64.getDecoder().decode(Files.readString(Path.of(keyFile)).trim());
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        String expiry = permanent ? "PERMANENT" : expires.toString();
        byte[] payload = (customer + "|" + expiry).getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payload);
        String code = "DQ1." + B64.encodeToString(payload) + "." + B64.encodeToString(sig.sign());

        System.out.println("授权码(客户: " + customer + "," + (permanent ? "永久有效" : "有效期至: " + expires) + "):");
        System.out.println(code);
    }
}
