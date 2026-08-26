package com.example.dq.config;

/**
 * AI 大模型接口默认配置(application.yml 的 ai.*);用户未在页面配置时作为兜底。
 * 计费价格默认值 = DeepSeek 官方价(2026-08 起,旗舰 V4-Pro);两档计费:工作时间段按高峰价,
 * 其余时间与周末按谷价(非工作时间),工作时间段可多段。
 */
public class AiProperties {

    /** 接口地址(OpenAI 兼容,如 http://host:port/v1) */
    private String baseUrl;
    /** API Key(明文,仅本地部署使用) */
    private String apiKey;
    /** 模型名 */
    private String model;

    /** 是否启用峰谷计价(关闭时只用单一输入/输出价) */
    private boolean peakValleyEnabled = true;
    /** 工作时间(高峰)输入价 / 单一输入价(元/百万 token) */
    private double peakInputPrice = 9.0;
    /** 工作时间(高峰)输出价 / 单一输出价(元/百万 token) */
    private double peakOutputPrice = 27.0;
    /** 非工作时间(谷价)输入价(元/百万 token) */
    private double valleyInputPrice = 4.5;
    /** 非工作时间(谷价)输出价(元/百万 token) */
    private double valleyOutputPrice = 13.5;
    /** 工作时间段(高峰),可多段;"HH:mm-HH:mm,..." */
    private String workPeriods = "09:00-12:00,14:00-18:00";
    /** 周末全天按谷价 */
    private boolean weekendValley = true;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isPeakValleyEnabled() { return peakValleyEnabled; }
    public void setPeakValleyEnabled(boolean peakValleyEnabled) { this.peakValleyEnabled = peakValleyEnabled; }
    public double getPeakInputPrice() { return peakInputPrice; }
    public void setPeakInputPrice(double peakInputPrice) { this.peakInputPrice = peakInputPrice; }
    public double getPeakOutputPrice() { return peakOutputPrice; }
    public void setPeakOutputPrice(double peakOutputPrice) { this.peakOutputPrice = peakOutputPrice; }
    public double getValleyInputPrice() { return valleyInputPrice; }
    public void setValleyInputPrice(double valleyInputPrice) { this.valleyInputPrice = valleyInputPrice; }
    public double getValleyOutputPrice() { return valleyOutputPrice; }
    public void setValleyOutputPrice(double valleyOutputPrice) { this.valleyOutputPrice = valleyOutputPrice; }
    public String getWorkPeriods() { return workPeriods; }
    public void setWorkPeriods(String workPeriods) { this.workPeriods = workPeriods; }
    public boolean isWeekendValley() { return weekendValley; }
    public void setWeekendValley(boolean weekendValley) { this.weekendValley = weekendValley; }
}
