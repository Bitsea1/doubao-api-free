package org.doubao.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "doubao")
public class DoubaoProperties {

    private Boolean enabled = true;
    private String baseUrl = "https://www.doubao.com";
    private Integer timeout = 180;
    private Integer sessionTtl = 3600;
    private String apiKey = "sk-doubao-default-key";

    private List<AccountConfig> accounts;

    // 设备指纹配置
    private String deviceId;
    private String fp;
    private String teaUuid;
    private String webId;

    // 模型映射
    private String defaultModel = "doubao-pro-chat";

    @Data
    public static class AccountConfig {
        private String cookie;
        private String deviceId;
        private String fp;
        private String teaUuid;
        private String webId;
        private String msToken;
    }
}