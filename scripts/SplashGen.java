import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成 JVM 原生启动画面 splash.png(方案一:点击图标即显示,盖住 JVM 启动 + 首次 AWT 初始化的黑屏期)。
 * 单文件源码直接运行:JDK 11+ 支持 `java scripts/SplashGen.java`(无需编译产物)。
 * 输出: server/src/main/resources/splash.png(打进 fat jar,打包脚本再复制进 jpackage app 镜像)。
 * 样式与 TrayManager.createImage / DesktopSplash 的品牌一致(蓝底白字 DQ + 主色 #409EFF)。
 * 生成后建议用 `sips -g pixelWidth -g pixelHeight` 或直接肉眼确认。
 */
public class SplashGen {

    private static final Color BRAND = new Color(0x40, 0x9E, 0xFF);
    private static final Color TITLE = new Color(0x30, 0x31, 0x33);
    private static final Color SUB = new Color(0x90, 0x99, 0x99);
    private static final Color TRACK = new Color(0xE4, 0xE7, 0xED);

    public static void main(String[] args) throws Exception {
        int w = 520;
        int h = 320;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        // 品牌 logo:圆角蓝底白字 DQ
        int logoSize = 72;
        int logoX = (w - logoSize) / 2;
        int logoY = 56;
        g.setColor(BRAND);
        g.fill(new RoundRectangle2D.Float(logoX, logoY, logoSize, logoSize, 16, 16));
        Font dqFont = new Font(Font.SANS_SERIF, Font.BOLD, 34);
        g.setFont(dqFont);
        g.setColor(Color.WHITE);
        TextLayout dq = new TextLayout("DQ", dqFont, new FontRenderContext(null, true, true));
        float dqW = dq.getAdvance();
        float dqH = dq.getAscent() + dq.getDescent();
        dq.draw(g, logoX + (logoSize - dqW) / 2f, logoY + (logoSize + dqH) / 2f - dq.getDescent());

        // 标题(选一个能显示中文的字体;找不到时退回 Dialog)
        Font titleFont = pickChineseFont(24).deriveFont(Font.BOLD, 24f);
        g.setFont(titleFont);
        g.setColor(TITLE);
        drawCentered(g, "数据质量检测工具", w / 2f, 172);

        // 副标题
        g.setFont(pickChineseFont(13));
        g.setColor(SUB);
        drawCentered(g, "正在启动服务,请稍候…", w / 2f, 206);

        // 进度条(静态暗示,表示"进行中")
        int barW = 240;
        int barH = 6;
        int barX = (w - barW) / 2;
        int barY = 240;
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Float(barX, barY, barW, barH, barH, barH));
        g.setColor(BRAND);
        g.fill(new RoundRectangle2D.Float(barX, barY, barW / 3, barH, barH, barH));

        g.dispose();

        File out = new File("server/src/main/resources/splash.png");
        out.getParentFile().mkdirs();
        ImageIO.write(image, "png", out);
        System.out.println("已生成 " + out.getAbsolutePath() + " (" + w + "x" + h + ")");
    }

    private static void drawCentered(Graphics2D g, String text, float cx, float baselineY) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        TextLayout layout = new TextLayout(text, g.getFont(), frc);
        layout.draw(g, cx - layout.getAdvance() / 2f, baselineY);
    }

    /** 按候选顺序选一个能显示中文的字体(macOS 优先苹方,Windows 优先微软雅黑) */
    private static Font pickChineseFont(int size) {
        String[] candidates = {"PingFang SC", "Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Noto Sans CJK SC"};
        for (String name : candidates) {
            Font font = new Font(name, Font.PLAIN, size);
            if (font.canDisplay('打')) {
                return font;
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, size);
    }
}
