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
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Scanner;
import java.util.UUID;

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
 *   # 3) 签发授权码(交互式,依次提示输入私钥/客户/有效期/版本/扩展字段/功能列表,回车使用默认值)
 *   java scripts/LicenseKeygen.java
 *
 * 授权码格式须与 com.example.dq.license.LicenseCodec 保持一致:
 * DQ1.<base64url(客户名|yyyy-MM-dd 或 PERMANENT|软件版本|server_url|username|sid|timestamp|features)>.<base64url(Ed25519 签名)>
 * (timestamp 为签发时间 epoch 毫秒,自动生成;features 为逗号分隔功能列表,回车=仅基础业务功能;
 * server_url 仅存在于授权码中,不回传用户实例前端)
 */
public class LicenseKeygen {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--gen-keypair")) {
            genKeyPair();
            return;
        }
        interactiveSign();
    }

    /** 交互式签发:依次提示输入各字段,回车使用默认值,输入校验失败会提示重输 */
    private static void interactiveSign() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("===== 授权码签发(交互式)=====");
        System.out.println("(直接回车使用默认值;随时 Ctrl+C 取消)");
        System.out.println();

        // 1) 私钥文件
        String keyFile = prompt(sc, "私钥文件路径", "license-private.key");
        if (!Files.exists(Path.of(keyFile))) {
            System.err.println("私钥文件不存在: " + keyFile);
            System.exit(1);
        }

        // 2) 客户名称(默认内部测试,不含 |)
        String customer;
        while (true) {
            customer = prompt(sc, "客户名称", "内部测试");
            if (customer.contains("|")) {
                System.out.println("客户名称不能包含 | 字符,请重新输入");
                continue;
            }
            break;
        }

        // 3) 有效期(默认 30 天后;permanent 永久)
        String defaultExpiry = LocalDate.now().plusDays(30).toString();
        String expiresStr;
        while (true) {
            expiresStr = prompt(sc, "有效期(yyyy-MM-dd 或 permanent)", defaultExpiry);
            if ("permanent".equalsIgnoreCase(expiresStr)) {
                break;
            }
            try {
                LocalDate.parse(expiresStr);
                break;
            } catch (DateTimeParseException e) {
                System.out.println("有效期格式无效,应为 yyyy-MM-dd 或 permanent,请重新输入");
            }
        }

        // 4) 扩展字段(版本默认取 VERSION 文件去 0. 前缀;SID 回车自动生成)
        String appVersion = prompt(sc, "软件版本", defaultAppVersion());
        String serverUrl = promptOptional(sc, "server_url(仅留档,不下发用户实例)");
        String username = promptOptional(sc, "用户名");
        String sid = promptOptional(sc, "SID");
        if (sid.isBlank()) {
            sid = UUID.randomUUID().toString().replace("-", "");
            System.out.println("SID 已自动生成: " + sid);
        }

        // 5) 功能列表(默认仅基础业务功能;logs/license_admin 受控需显式包含)
        System.out.println("功能列表(逗号分隔,回车=仅基础业务功能):");
        System.out.println("  全部功能: scan(扫描检测), datasource(数据源管理), excel(Excel导出), report(Word报告),");
        System.out.println("            ai_doc(AI表说明), ai_tag(AI自动打标), tag(表标记), logs(运行日志), license_admin(授权码管理)");
        System.out.println("  说明: 扫描/数据源/Excel/报告/AI/标记为基础业务功能恒可用;运行日志(logs)、授权码管理(license_admin)为受控功能,需显式包含");
        String features = promptOptional(sc, "功能列表");

        sign(keyFile, customer, expiresStr, appVersion, serverUrl, username, sid, features);
    }

    /** 读取一行输入(有默认值):回车返回默认值,否则返回输入内容 */
    private static String prompt(Scanner sc, String label, String def) {
        System.out.print(label + "(回车=" + def + "): ");
        String line = sc.nextLine().trim();
        return line.isEmpty() ? def : line;
    }

    /** 读取一行输入(选填):回车返回空串 */
    private static String promptOptional(Scanner sc, String label) {
        System.out.print(label + "(选填,回车跳过): ");
        return sc.nextLine().trim();
    }

    /** 软件版本默认值:读根目录 VERSION 文件去 0. 前缀(如 0.1.8 -> 1.8,与构建注入 app-version.txt 口径一致),读不到返回空 */
    private static String defaultAppVersion() {
        try {
            String v = Files.readString(Path.of("VERSION")).trim();
            return v.replaceFirst("^0\\.", "");
        } catch (Exception e) {
            return "";
        }
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
                             String appVersion, String serverUrl, String username, String sid,
                             String features) throws Exception {
        for (var field : new String[][]{{"客户名", customer}, {"软件版本", appVersion},
                {"server_url", serverUrl}, {"username", username}, {"sid", sid}, {"功能列表", features}}) {
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
        // payload 扩展字段:软件版本|server_url|username|sid|timestamp(签发时间,epoch 毫秒)|features(逗号分隔功能列表,可空);
        // server_url 不回传用户实例前端
        byte[] payload = (customer + "|" + expiry + "|" + appVersion + "|" + serverUrl + "|" + username + "|" + sid
                + "|" + timestamp + "|" + features).getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payload);
        String code = "DQ1." + B64.encodeToString(payload) + "." + B64.encodeToString(sig.sign());

        System.out.println("授权码(客户: " + customer + "," + (permanent ? "永久有效" : "有效期至: " + expires)
                + ",版本: " + (appVersion.isEmpty() ? "(未绑定)" : appVersion)
                + ",username: " + username + ",sid: " + sid
                + ",功能: " + (features.isEmpty() ? "仅基础业务功能" : features) + ",签发时间: " + timestamp + "):");
        System.out.println(code);
    }
}
