package org.doubao.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.doubao.domain.R;
import org.doubao.domain.dto.ChatCompletionRequest;
import org.doubao.service.IDoubaoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/doubao/v1")
public class DoubaoController {

    @Autowired
    private IDoubaoService doubaoService;

    @PostMapping("/chat/completions")
    public Object chatCompletions(@Validated @RequestBody ChatCompletionRequest request,
                                  HttpServletRequest httpRequest) {
        String apiKey = extractApiKey(httpRequest);
        log.info("收到聊天请求，API Key: {}, 流式: {}",
            apiKey != null ? "***" + apiKey.substring(Math.max(0, apiKey.length() - 4)) : "null",
            request.getStream());

        if (Boolean.TRUE.equals(request.getStream())) {
            return doubaoService.chatCompletionsStream(request, apiKey);
        } else {
            return R.ok(doubaoService.chatCompletions(request, apiKey));
        }
    }

    @GetMapping("/models")
    public R<Object> listModels(HttpServletRequest httpRequest) {
        String apiKey = extractApiKey(httpRequest);
        log.info("获取模型列表，API Key: {}",
            apiKey != null ? "***" + apiKey.substring(Math.max(0, apiKey.length() - 4)) : "null");
        return R.ok(doubaoService.getModels(apiKey));
    }

    @GetMapping("/health")
    public R<String> health() {
        return R.ok("豆包AI服务运行正常");
    }

    private String extractApiKey(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        String apiKeyFromQuery = request.getParameter("api_key");
        if (apiKeyFromQuery != null && !apiKeyFromQuery.trim().isEmpty()) {
            return apiKeyFromQuery.trim();
        }

        String apiKeyFromHeader = request.getHeader("X-API-Key");
        if (apiKeyFromHeader != null && !apiKeyFromHeader.trim().isEmpty()) {
            return apiKeyFromHeader.trim();
        }

        log.warn("未提供有效的API密钥，Header: {}, Query: {}",
            request.getHeader("Authorization"), request.getParameter("api_key"));
        throw new RuntimeException("未提供有效的API密钥");
    }
}
