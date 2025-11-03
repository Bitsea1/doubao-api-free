package org.doubao.utils;

import java.util.HashMap;
import java.util.Map;

public class SseUtils {

    /**
     * 创建SSE分片数据
     */
    public static Map<String, Object> createChunk(String requestId, String model,
                                                  String content, String finishReason) {
        Map<String, Object> chunk = new HashMap<>();
        chunk.put("id", requestId);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);

        Map<String, Object> delta = new HashMap<>();
        delta.put("content", content);
        choice.put("delta", delta);

        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }

        chunk.put("choices", new Object[]{choice});
        return chunk;
    }

    /**
     * 创建SSE结束标记
     */
    public static String createDoneChunk() {
        return "[DONE]";
    }
}
