package org.doubao.service;

import org.doubao.domain.dto.ChatCompletionRequest;
import org.doubao.domain.vo.ChatCompletionResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IDoubaoService {

    SseEmitter chatCompletionsStream(ChatCompletionRequest request, String apiKey);

    ChatCompletionResponse chatCompletions(ChatCompletionRequest request, String apiKey);

    Object getModels(String apiKey);

    String healthCheck();

}
