package org.doubao.domain.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class ImageGenerationRequest extends ChatCompletionRequest {

    // 生图专用参数
    private String size = "1024x1024"; // 图片尺寸
    private Integer quality = 1; // 图片质量 1-标准 2-高清
    private String style = "realistic"; // 图片风格 realistic/cartoon/artistic等
    private Integer num = 1; // 生成图片数量

    // 添加提示词字段
    private String prompt;

    // 重写模型设置，固定使用生图模型
    @Override
    public String getModel() {
        return "doubao-image-generation";
    }

    /**
     * 获取提示词 - 优先使用prompt字段，如果没有则从messages中提取
     */
    public String getEffectivePrompt() {
        if (this.prompt != null && !this.prompt.trim().isEmpty()) {
            return this.prompt.trim();
        }
        return extractPromptFromMessages();
    }

    /**
     * 从messages中提取提示词
     */
    private String extractPromptFromMessages() {
        if (getMessages() != null && !getMessages().isEmpty()) {
            Message lastMessage = getMessages().get(getMessages().size() - 1);
            if (lastMessage.getContent() instanceof String) {
                return (String) lastMessage.getContent();
            } else if (lastMessage.getContent() instanceof List) {
                // 从混合内容中提取文本
                List<?> contentList = (List<?>) lastMessage.getContent();
                for (Object item : contentList) {
                    if (item instanceof Map) {
                        Map<?, ?> contentItem = (Map<?, ?>) item;
                        if ("text".equals(contentItem.get("type"))) {
                            return (String) contentItem.get("text");
                        }
                    }
                }
            }
        }
        return null;
    }
}
