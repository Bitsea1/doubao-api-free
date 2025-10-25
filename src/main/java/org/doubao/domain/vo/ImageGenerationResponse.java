package org.doubao.domain.vo;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ImageGenerationResponse {

    private String id = "imgcmpl-" + UUID.randomUUID();

    private String object = "image.generation";

    private long created = System.currentTimeMillis() / 1000;

    private String model;

    private List<ImageData> data;

    @Data
    public static class ImageData {
        // 图片URL（核心结果）
        private String url;
        // 图片格式（默认 png）
        private String format = "png";
    }
}
