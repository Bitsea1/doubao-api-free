package org.doubao.service;

import org.doubao.domain.dto.ImageGenerationRequest;
import org.doubao.domain.vo.ImageGenerationResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IDoubaoImageService {

    /**
     * 非流式生图（同步返回结果）
     */
    ImageGenerationResponse generateImage(ImageGenerationRequest request, String apiKey);

    /**
     * 流式生图（实时返回生成状态/结果）
     */
    SseEmitter generateImageStream(ImageGenerationRequest request, String apiKey);
}
