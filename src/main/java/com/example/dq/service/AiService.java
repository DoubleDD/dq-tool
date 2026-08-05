package com.example.dq.service;

import com.example.dq.model.ColumnMeta;
import com.example.dq.model.TableStat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/** OpenAI 兼容大模型接口调用(/chat/completions),只发送表结构元数据,不涉及业务数据 */
@Service
public class AiService {

    /** 发给模型的字段数上限,超出截断标注,控制 token 消耗 */
    static final int MAX_PROMPT_COLUMNS = 100;

    static final String SYSTEM_PROMPT = "你是数据库专家。根据用户提供的表结构信息,用简洁中文概括该表的业务用途和存储的数据。"
            + "只输出一段 100 字以内的描述文字,不要罗列字段,不要使用列表和标题。";

    private final RestClient client = RestClient.builder()
            .requestFactory(createFactory())
            .build();

    private static SimpleClientHttpRequestFactory createFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        return factory;
    }

    /** 生成一张表的说明文字 */
    public String describeTable(AiConfigService.Config config, TableStat table, List<ColumnMeta> columns) {
        return chat(config, SYSTEM_PROMPT, buildTablePrompt(table, columns));
    }

    @SuppressWarnings("unchecked")
    private String chat(AiConfigService.Config config, String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "temperature", 0.3,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        Map<String, Object> resp;
        try {
            resp = client.post()
                    .uri(config.baseUrl() + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("大模型接口调用失败:" + abbreviate(e.getMessage()), e);
        }
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = ((String) message.get("content")).trim();
            if (content.isEmpty()) {
                throw new IllegalStateException("大模型返回了空内容");
            }
            return content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("大模型响应解析失败:" + abbreviate(String.valueOf(resp)), e);
        }
    }

    /** 拼表结构 prompt:表名/注释/引擎/约行数 + 字段清单,纯函数便于单测 */
    public static String buildTablePrompt(TableStat table, List<ColumnMeta> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("表名:").append(table.name()).append('\n');
        if (notBlank(table.comment())) {
            sb.append("表注释:").append(table.comment()).append('\n');
        }
        if (notBlank(table.storageInfo())) {
            sb.append("引擎/表空间:").append(table.storageInfo()).append('\n');
        }
        if (table.estRows() != null) {
            sb.append("约行数:").append(table.estRows()).append('\n');
        }
        sb.append("字段(共 ").append(columns.size()).append(" 个):\n");
        int limit = Math.min(columns.size(), MAX_PROMPT_COLUMNS);
        for (int i = 0; i < limit; i++) {
            ColumnMeta c = columns.get(i);
            sb.append("- ").append(c.name());
            if (notBlank(c.displayType())) {
                sb.append(' ').append(c.displayType());
            }
            if (!c.keyLabel().isEmpty()) {
                sb.append(" [").append(c.keyLabel()).append(']');
            }
            if (!c.nullable()) {
                sb.append(" 非空");
            }
            if (notBlank(c.comment())) {
                sb.append(" — ").append(c.comment());
            }
            sb.append('\n');
        }
        if (columns.size() > limit) {
            sb.append("...(其余 ").append(columns.size() - limit).append(" 个字段省略)\n");
        }
        sb.append("\n请描述这张表的业务用途。");
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
