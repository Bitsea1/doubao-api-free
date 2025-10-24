package org.doubao.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class ImageGenerationResponse {
    private Long created;
    private List<ImageData> data;

    @Data
    public static class ImageData {
        private String url;
        private String revisedPrompt;
        private String size;
        private String style;
    }
}
