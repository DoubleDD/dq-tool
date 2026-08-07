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
 *   # 1) 生成密钥对(只需一次):输出私钥文件 license-private.key 和公钥文件 license-public.key
 *   java scripts/LicenseKeygen.java --gen-keypair
 *
 *   # 2) 把公钥文件内容写入 server/src/main/resources/license-public.key
 *   #    (application.yml 的 dq.license.public-key-file 只存该文件路径)
 *
 *   # 3) 签发授权码
 *   java scripts/LicenseKeygen.java --key license-private.key --customer "某某公司" --expires 2026-12-31
 *
 * 授权码格式须与 com.example.dq.license.LicenseCodec 保持一致:
 * DQ1.<base64url(客户名|yyyy-MM-dd 或 PERMANENT|软件版本|server_url|username|sid|timestamp)>.<base64url(Ed25519 签名)>
 * (timestamp 为签发时间 epoch 毫秒,自动生成;软件版本绑定当前发布版本;server_url 仅存在于授权码中,不回传用户实例前端)
 */
public class LicenseKeygen {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--gen-keypair")) {
            genKeyPair();
            return;
        }
        String keyFile = null, customer = null, expires = null;
        String appVersion = "", serverUrl = "", username = "", sid = "";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--key" -> keyFile = args[++i];
                case "--customer" -> customer = args[++i];
                case "--expires" -> expires = args[++i];
                case "--version" -> appVersion = args[++i];
                case "--server-url" -> serverUrl = args[++i];
                case "--username" -> username = args[++i];
                case "--sid" -> sid = args[++i];
                default -> { }
            }
        }
        if (keyFile == null || customer == null || expires == null) {
            System.err.println("用法:");
            System.err.println("  生成密钥对: java scripts/LicenseKeygen.java --gen-keypair");
            System.err.println("  签发授权码: java scripts/LicenseKeygen.java --key <私钥文件> --customer <客户名> --expires <yyyy-MM-dd|permanent>");
            System.err.println("              [--version <软件版本>] [--server-url <地址>] [--username <用户名>] [--sid <SID>]  (可空;timestamp 自动取签发时间)");
            System.exit(1);
        }
        sign(keyFile, customer, expires, appVersion, serverUrl, username, sid);
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
        Path pubPath = Path.of("license-public.key");
        Files.writeString(pubPath, Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n");
        System.out.println("密钥对已生成:");
        System.out.println("  私钥文件: " + keyPath.toAbsolutePath() + "  (务必自行保管,不要提交仓库/外发)");
        System.out.println("  公钥文件: " + pubPath.toAbsolutePath());
        System.out.println();
        System.out.println("把公钥文件内容写入 server/src/main/resources/license-public.key 后重新打包即可");
        System.out.println("(application.yml 的 dq.license.public-key-file 只存文件路径):");
        System.out.println(Files.readString(pubPath).trim());
    }

    private static void sign(String keyFile, String customer, String expiresStr,
                             String appVersion, String serverUrl, String username, String sid) throws Exception {
        for (var field : new String[][]{{"客户名", customer}, {"软件版本", appVersion},
                {"server_url", serverUrl}, {"username", username}, {"sid", sid}}) {
            if (field[1].contains("|")) {
                System.err.println(field[0] + "不能包含 | 字符");
                System.exit(1);
            }
        }
        // expires 传 permanent 表示永久授权(payload 中存 PERMANENT 标记)
        boolean permanent = "permanent".equalsIgnoreCase(expiresStr);
        LocalDate expires = permanent ? null : LocalDate.parse(expiresStr);
        byte[] keyBytes = Base64.getDecoder().decode(Files.readString(Path.of(keyFile)).trim());
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        String expiry = permanent ? "PERMANENT" : expires.toString();
        long timestamp = System.currentTimeMillis();
        // payload 扩展字段:软件版本|server_url|username|sid|timestamp(签发时间,epoch 毫秒);server_url 不回传用户实例前端
        byte[] payload = (customer + "|" + expiry + "|" + appVersion + "|" + serverUrl + "|" + username + "|" + sid
                + "|" + timestamp).getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payload);
        String code = "DQ1." + B64.encodeToString(payload) + "." + B64.encodeToString(sig.sign());

        System.out.println("授权码(客户: " + customer + "," + (permanent ? "永久有效" : "有效期至: " + expires)
                + ",版本: " + (appVersion.isEmpty() ? "(未绑定)" : appVersion)
                + ",username: " + username + ",sid: " + sid + ",签发时间: " + timestamp + "):");
        System.out.println(code);
    }
}
