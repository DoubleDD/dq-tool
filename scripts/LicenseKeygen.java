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
 *   # 3) 签发授权码(交互式,依次提示输入私钥/客户/有效期/版本/扩展字段/菜单列表/免鉴权,回车使用默认值)
 *   java scripts/LicenseKeygen.java
 *
 * 授权码格式须与 com.example.dq.license.LicenseCodec 保持一致:
 * DQ1.<base64url(客户名|yyyy-MM-dd 或 PERMANENT|软件版本|server_url|username|sid|timestamp|features|menus|bypassAuth)>.<base64url(Ed25519 签名)>
 * (timestamp 为签发时间 epoch 毫秒,自动生成;features 为旧功能段(新码留空,仅兼容旧格式);
 * menus 为逗号分隔菜单列表(回车=全部菜单),勾选即客户实例侧边栏可见;bypassAuth 为免接口鉴权标记(true/false,演示用);
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

        // 5) 菜单列表(默认全部菜单,逗号分隔;勾选即客户实例侧边栏可见,不再区分基础/受控功能)
        System.out.println("菜单列表(逗号分隔,回车=全部菜单):");
        System.out.println("  全部菜单: datasource(数据源), dashboard(扫描记录), tags(标记统计), manual-collects(人工采集),");
        System.out.println("            report-exports(报告列表), sample-exports(抽样导出), compare(数据比对), relations(ER 关系),");
        System.out.println("            object-manage(对象管理), sql-console(SQL 控制台), lan-share(局域网共享), ai-usage(模型用量统计),");
        System.out.println("            settings(系统设置), diagnostics(系统诊断), logs(运行日志), license-admin(授权管理)");
        String menus = normalizeKeys(promptOptional(sc, "菜单列表"), KNOWN_MENUS, true);
        // 6) 免接口鉴权(演示用):true 时实例所有请求跳过 dq.access-token 校验
        String bypassAuth = prompt(sc, "免接口鉴权(true/false,演示用)", "false");
        if (!bypassAuth.equalsIgnoreCase("true") && !bypassAuth.equalsIgnoreCase("false")) {
            System.out.println("免接口鉴权只接受 true/false,已按 false 处理");
            bypassAuth = "false";
        }

        sign(keyFile, customer, expiresStr, appVersion, serverUrl, username, sid, menus, bypassAuth.equalsIgnoreCase("true"));
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

    /** 合法菜单 key,须与 common 模块 LicenseMenu 枚举保持一致 */
    private static final java.util.List<String> KNOWN_MENUS = java.util.List.of(
            "datasource", "dashboard", "tags", "manual-collects", "report-exports", "sample-exports",
            "compare", "relations", "object-manage", "sql-console", "lan-share", "ai-usage",
            "settings", "diagnostics", "logs", "license-admin");

    /**
     * 规范化逗号分隔 key 列表:剥离每项括号内的中文说明(允许直接粘贴上方提示的整行),
     * 未知 key 提示后忽略并去重;blankAsAll=true 且输入为空时返回全部 key。
     */
    private static String normalizeKeys(String input, java.util.List<String> known, boolean blankAsAll) {
        if (input.isBlank()) {
            return blankAsAll ? String.join(",", known) : "";
        }
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String item : input.split("[,、]")) {
            String key = item.replaceAll("[(（].*$", "").trim();
            if (key.isEmpty()) {
                continue;
            }
            if (!known.contains(key)) {
                System.out.println("未知 key 已忽略: " + item.trim());
                continue;
            }
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        return String.join(",", keys);
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
                             String menus, boolean bypassAuth) throws Exception {
        for (var field : new String[][]{{"客户名", customer}, {"软件版本", appVersion},
                {"server_url", serverUrl}, {"username", username}, {"sid", sid}, {"菜单列表", menus}}) {
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
        // payload 扩展字段:软件版本|server_url|username|sid|timestamp(签发时间,epoch 毫秒)|features(旧功能段,新码留空)|
        // menus(逗号分隔菜单列表)|bypassAuth(免接口鉴权标记,演示用);server_url 不回传用户实例前端
        byte[] payload = (customer + "|" + expiry + "|" + appVersion + "|" + serverUrl + "|" + username + "|" + sid
                + "|" + timestamp + "||" + menus + "|" + (bypassAuth ? "true" : "false")).getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payload);
        String code = "DQ1." + B64.encodeToString(payload) + "." + B64.encodeToString(sig.sign());

        System.out.println("授权码(客户: " + customer + "," + (permanent ? "永久有效" : "有效期至: " + expires)
                + ",版本: " + (appVersion.isEmpty() ? "(未绑定)" : appVersion)
                + ",username: " + username + ",sid: " + sid
                + ",菜单: " + menus + ",免鉴权: " + bypassAuth + ",签发时间: " + timestamp + "):");
        System.out.println(code);
    }
}
