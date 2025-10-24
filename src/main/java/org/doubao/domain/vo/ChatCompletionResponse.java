package org.doubao.domain.vo;

import lombok.Data;
import java.util.List;

@Data
public class ChatCompletionResponse {
    private String id;
    private String object;
    private Long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    @Data
    public static class Choice {
        private Integer index;
        private Delta delta;
        private Message message;
        private String finishReason;

        @Data
        public static class Delta {
            private String role;
            private String content;
        }

        @Data
        public static class Message {
            private String role;
            private String content;
        }
    }

    @Data
    public static class Usage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
}
