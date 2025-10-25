package org.doubao.domain.dto;

import lombok.Data;

@Data
public class ImageGenerationRequest {

    // 生图描述（必填）
    private String prompt;

    // 生图模型（默认 Seedream 4.0）
    private String model = "Seedream 4.0";

    // 模板类型（默认 placeholder）
    private String templateType = "placeholder";

    // 用户标识（用于会话绑定，非必填）
    private String user;

    // 本地会话ID（非必填，自动生成）
    private String localConversationId;

    private Boolean stream;

}
