package org.doubao.domain.dto;

import lombok.Data;

@Data
public class ImageGenerationRequest {

    // 生图模型（默认 Seedream 4.0）
    private String model;

    // 生图描述（必填）
    private String prompt;

    // 比例
    private String ratio;

    // 模板类型（默认 placeholder）
    private String templateType = "placeholder";

    // 用户标识（用于会话绑定，非必填）
    private String user;

    // 本地会话ID（非必填，自动生成）
    private String localConversationId;

    // 流式输出
    private Boolean stream;

}
