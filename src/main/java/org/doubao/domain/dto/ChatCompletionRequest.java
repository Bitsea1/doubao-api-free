package org.doubao.domain.dto;

import lombok.Data;
import java.util.List;

@Data
public class ChatCompletionRequest {
    private String model;
    private List<Message> messages;
    private Boolean stream;
    private String user;

    @Data
    public static class Message {
        private String role;
        private Object content;
    }

    @Data
    public static class ContentItem {
        private String type;
        private String text;
        private FileUrl file_url;
        private FileUrl image_url;
    }

    @Data
    public static class FileUrl {
        private String url;
    }
}
