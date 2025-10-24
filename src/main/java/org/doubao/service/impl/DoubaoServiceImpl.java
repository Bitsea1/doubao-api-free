package org.doubao.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.doubao.config.DoubaoProperties;
import org.doubao.domain.AccountStatus;
import org.doubao.domain.SessionData;
import org.doubao.domain.dto.ChatCompletionRequest;
import org.doubao.domain.dto.SignatureRequest;
import org.doubao.domain.exception.ServiceException;
import org.doubao.domain.vo.ChatCompletionResponse;
import org.doubao.domain.vo.DoubaoModel;
import org.doubao.domain.vo.SignatureResponse;
import org.doubao.service.IDoubaoService;
import org.doubao.service.ISignatureService;
import org.doubao.util.SseUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DoubaoServiceImpl implements IDoubaoService {

    private final DoubaoProperties doubaoProperties;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ISignatureService signatureService;
    private final List<DoubaoProperties.AccountConfig> accounts; // 账号列表

    // 会话缓存（统一使用SessionData类型）
    private final Map<String, SessionData> sessionCache = new ConcurrentHashMap<>();
    // 会话-账号绑定缓存：sessionId -> 账号索引
    private final Map<String, Integer> sessionAccountBindCache = new ConcurrentHashMap<>();
    // 账号状态缓存：存储每个账号的健康状态、活跃连接数、最后失败时间
    private final Map<String, AccountStatus> accountStatusCache = new ConcurrentHashMap<>();
    // 连接状态管理
    private final Map<String, Boolean> connectionStatus = new ConcurrentHashMap<>();
    // 定时任务：清理过期会话
    private final ScheduledExecutorService sessionScheduler = Executors.newScheduledThreadPool(1);
    // 定时任务：检测失效账号恢复
    private final ScheduledExecutorService accountRecoveryScheduler = Executors.newScheduledThreadPool(1);

    // 模型映射
    private static final Map<String, String> MODEL_MAPPING = Map.of(
            "doubao-pro-chat", "7338286299411103781",
            "doubao-lite-chat", "7338286299411103782"
    );

    public DoubaoServiceImpl(DoubaoProperties doubaoProperties,
                             CloseableHttpClient httpClient,
                             ObjectMapper objectMapper,
                             ISignatureService signatureService) {
        this.doubaoProperties = doubaoProperties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.signatureService = signatureService;
        this.accounts = doubaoProperties.getAccounts();

        // 初始化账号状态
        initAccountStatusCache();
        // 启动会话清理任务（每小时执行一次）
        sessionScheduler.scheduleAtFixedRate(this::cleanExpiredSessions, 1, 1, TimeUnit.HOURS);
        // 启动账号恢复检测任务（每5分钟执行一次）
        accountRecoveryScheduler.scheduleAtFixedRate(this::recoverInvalidAccounts, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * 初始化账号状态缓存
     */
    private void initAccountStatusCache() {
        if (accounts == null || accounts.isEmpty()) {
            throw new ServiceException("未配置豆包账号信息");
        }
        for (int i = 0; i < accounts.size(); i++) {
            DoubaoProperties.AccountConfig account = accounts.get(i);
            String accountKey = getAccountKey(account); // 生成账号唯一标识
            AccountStatus status = new AccountStatus();
            status.setAccountIndex(i);
            status.setHealthy(true);
            status.setActiveConnections(0);
            status.setLastFailTime(0L);
            accountStatusCache.put(accountKey, status);
            log.info("初始化账号[{}]状态: 健康", accountKey);
        }
    }

    /**
     * 生成账号唯一标识（使用deviceId，若为空则用cookie的MD5）
     */
    private String getAccountKey(DoubaoProperties.AccountConfig account) {
        if (account.getDeviceId() != null && !account.getDeviceId().isEmpty()) {
            return account.getDeviceId();
        }
        return UUID.nameUUIDFromBytes(account.getCookie().getBytes()).toString();
    }

    @Override
    public SseEmitter chatCompletionsStream(ChatCompletionRequest request, String apiKey) {
        validateApiKey(apiKey);

        // 设置3分钟超时
        SseEmitter emitter = new SseEmitter(180000L);
        String connectionId = UUID.randomUUID().toString();
        connectionStatus.put(connectionId, true);

        setupSseCallbacks(emitter, connectionId);

        // 异步处理流式请求
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                processRealStreamRequest(request, emitter, connectionId);
            } catch (Exception e) {
                handleStreamError(emitter, e, connectionId);
            }
        });

        return emitter;
    }

    @Override
    public ChatCompletionResponse chatCompletions(ChatCompletionRequest request, String apiKey) {
        validateApiKey(apiKey);

        String sessionId = Optional.ofNullable(request.getUser())
                .orElse("session-" + UUID.randomUUID().toString().substring(0, 8));
        String accountKey = null;

        try {
            // 获取绑定的账号
            DoubaoProperties.AccountConfig account = getNextAccount(sessionId);
            accountKey = getAccountKey(account);

            // 初始化或获取会话数据
            SessionData sessionData = sessionCache.computeIfAbsent(sessionId, k -> {
                SessionData newSession = new SessionData();
                newSession.setConversationId("0");
                newSession.setLastActiveTime(System.currentTimeMillis());
                return newSession;
            });
            sessionData.setLastActiveTime(System.currentTimeMillis());
            String conversationId = sessionData.getConversationId();

            // 构建请求
            String signedUrl = buildSignedUrl(account);
            HttpPost httpPost = buildHttpPost(signedUrl, account.getCookie());
            String payload = buildRealPayload(request, conversationId, account);
            httpPost.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));

            log.info("发送豆包API请求[会话: {}, 账号: {}]，对话ID: {}", sessionId, accountKey, conversationId);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                if (response.getCode() != 200) {
                    throw new ServiceException("上游服务返回错误状态: " + response.getCode());
                }
                return parseNonStreamResponse(response, request.getModel(), sessionId, conversationId);
            }

        } catch (Exception e) {
            log.error("非流式聊天请求失败[会话: {}, 账号: {}]", sessionId, accountKey, e);
            if (accountKey != null) {
                markAccountInvalid(accountKey, e);
            }
            throw new ServiceException("豆包AI服务暂时不可用: " + e.getMessage());
        } finally {
            if (accountKey != null) {
                decrementActiveConnections(accountKey);
            }
        }
    }

    @Override
    public Object getModels(String apiKey) {
        validateApiKey(apiKey);

        Map<String, Object> response = new HashMap<>();
        response.put("object", "list");

        List<DoubaoModel> models = Arrays.asList(
                new DoubaoModel("doubao-pro-chat"),
                new DoubaoModel("doubao-lite-chat")
        );

        response.put("data", models);
        return response;
    }

    @Override
    public String healthCheck() {
        long healthyCount = accountStatusCache.values().stream().filter(AccountStatus::isHealthy).count();
        return String.format("豆包AI服务运行正常，健康账号数: %d/%d", healthyCount, accountStatusCache.size());
    }

    /**
     * 处理真实流式请求
     */
    private void processRealStreamRequest(ChatCompletionRequest request, SseEmitter emitter, String connectionId) {
        String sessionId = Optional.ofNullable(request.getUser())
                .orElse("session-" + UUID.randomUUID().toString().substring(0, 8));
        String accountKey = null;

        try {
            // 获取绑定的账号
            DoubaoProperties.AccountConfig account = getNextAccount(sessionId);
            accountKey = getAccountKey(account);

            // 初始化或获取会话数据
            SessionData sessionData = sessionCache.computeIfAbsent(sessionId, k -> {
                SessionData newSession = new SessionData();
                newSession.setConversationId("0");
                newSession.setLastActiveTime(System.currentTimeMillis());
                return newSession;
            });
            sessionData.setLastActiveTime(System.currentTimeMillis());
            String conversationId = sessionData.getConversationId();

            // 构建请求
            String signedUrl = buildSignedUrl(account);
            HttpPost httpPost = buildHttpPost(signedUrl, account.getCookie());
            String payload = buildRealPayload(request, conversationId, account);
            httpPost.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));

            String requestId = "chatcmpl-" + UUID.randomUUID().toString();
            log.info("发送流式请求[会话: {}, 账号: {}]，请求ID: {}", sessionId, accountKey, requestId);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                if (response.getCode() != 200) {
                    throw new ServiceException("上游服务返回错误状态: " + response.getCode());
                }
                processStreamResponse(response, emitter, connectionId, requestId, request.getModel(), sessionId, conversationId);
            }

        } catch (Exception e) {
            log.error("处理流式请求失败[会话: {}, 账号: {}]", sessionId, accountKey, e);
            if (accountKey != null) {
                markAccountInvalid(accountKey, e);
            }
            throw new ServiceException("流式请求处理失败: " + e.getMessage());
        } finally {
            if (accountKey != null) {
                decrementActiveConnections(accountKey);
            }
            connectionStatus.remove(connectionId);
        }
    }

    /**
     * 处理流式响应并发送SSE
     */
    private void processStreamResponse(CloseableHttpResponse response, SseEmitter emitter,
                                       String connectionId, String requestId, String model,
                                       String sessionId, String conversationId) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {

            String line;
            String newConversationId = null;

            while ((line = reader.readLine()) != null) {
                if (!connectionStatus.containsKey(connectionId)) {
                    log.info("客户端已断开连接，停止处理[连接ID: {}]", connectionId);
                    return;
                }

                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;

                    try {
                        JsonNode jsonData = objectMapper.readTree(data);
                        JsonNode eventTypeNode = jsonData.get("event_type");
                        if (eventTypeNode == null) {
                            log.debug("SSE数据缺少event_type字段，跳过: {}", data);
                            continue;
                        }
                        String eventType = eventTypeNode.asText();

                        // 处理会话ID更新
                        if ("2002".equals(eventType) && newConversationId == null) {
                            JsonNode eventDataNode = jsonData.get("event_data");
                            if (eventDataNode == null) {
                                log.debug("event_type=2002但缺少event_data字段: {}", data);
                                continue;
                            }
                            String eventData = eventDataNode.asText();
                            JsonNode eventDataJson = objectMapper.readTree(eventData);
                            JsonNode convIdNode = eventDataJson.get("conversation_id");
                            if (convIdNode == null) {
                                log.debug("event_data缺少conversation_id字段: {}", eventData);
                                continue;
                            }
                            newConversationId = convIdNode.asText();

                            // 更新会话ID
                            if (!"0".equals(conversationId)) {
                                SessionData sessionData = sessionCache.get(sessionId);
                                if (sessionData != null) {
                                    sessionData.setConversationId(newConversationId);
                                    sessionData.setLastActiveTime(System.currentTimeMillis());
                                    sessionCache.put(sessionId, sessionData);
                                    log.info("更新会话ID[会话: {}]: {} -> {}", sessionId, conversationId, newConversationId);
                                }
                            }
                        }
                        // 处理消息内容
                        else if ("2001".equals(eventType)) {
                            JsonNode eventDataNode = jsonData.get("event_data");
                            if (eventDataNode == null) {
                                log.debug("event_type=2001但缺少event_data字段: {}", data);
                                continue;
                            }
                            String eventData = eventDataNode.asText();
                            JsonNode eventDataJson = objectMapper.readTree(eventData);
                            JsonNode messageNode = eventDataJson.get("message");
                            if (messageNode == null) {
                                log.debug("event_data缺少message字段: {}", eventData);
                                continue;
                            }
                            JsonNode contentNode = messageNode.get("content");
                            if (contentNode == null) {
                                log.debug("message缺少content字段: {}", messageNode);
                                continue;
                            }
                            String contentJson = contentNode.asText();
                            JsonNode contentDetailNode = objectMapper.readTree(contentJson);
                            JsonNode textNode = contentDetailNode.get("text");
                            if (textNode == null) {
                                log.debug("content缺少text字段: {}", contentJson);
                                continue;
                            }
                            String deltaContent = textNode.asText();

                            if (!deltaContent.isEmpty()) {
                                Map<String, Object> chunk = SseUtils.createChunk(
                                        requestId, model, deltaContent, null);
                                sendSseData(emitter, connectionId, objectMapper.writeValueAsString(chunk));
                            }
                        }
                    } catch (JsonProcessingException e) {
                        log.warn("解析SSE数据失败: {}", data, e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("读取流式响应失败", e);
            throw e;
        } finally {
            // 确保发送结束标记
            try {
                if (connectionStatus.containsKey(connectionId)) {
                    Map<String, Object> finalChunk = SseUtils.createChunk(requestId, model, "", "stop");
                    sendSseData(emitter, connectionId, objectMapper.writeValueAsString(finalChunk));
                    sendSseData(emitter, connectionId, SseUtils.createDoneChunk());
                    log.info("SSE流正常结束[连接ID: {}]", connectionId);
                }
            } catch (IOException e) {
                log.warn("发送SSE结束标记失败", e);
            } finally {
                connectionStatus.remove(connectionId);
                emitter.complete(); // 显式通知客户端流结束
            }
        }
    }

    /**
     * 构建签名URL
     */
    private String buildSignedUrl(DoubaoProperties.AccountConfig account) {
        try {
            Map<String, String> baseParams = new LinkedHashMap<>();
            baseParams.put("aid", "4978");
            baseParams.put("device_platform", "web");
            baseParams.put("language", "zh");
            baseParams.put("pc_version", "");
            baseParams.put("pkg_type", "release");
            baseParams.put("real_aid", "4978");
            baseParams.put("region", "CN");
            baseParams.put("samantha_web", "1");
            baseParams.put("sys_region", "CN");
            baseParams.put("use-olympus-account", "1");
            baseParams.put("version_code", "180");

            // 设备指纹参数
            baseParams.put("device_id", account.getDeviceId());
            baseParams.put("fp", account.getFp());
            baseParams.put("tea_uuid", account.getTeaUuid());
            baseParams.put("web_id", account.getWebId());
            baseParams.put("web_tab_id", UUID.randomUUID().toString());

            // 获取msToken
            String msToken = account.getMsToken();
            if (msToken == null) {
                msToken = signatureService.getMsToken(account.getCookie());
            }
            baseParams.put("msToken", msToken);

            // 构建基础URL和查询参数
            String baseUrl = doubaoProperties.getBaseUrl() + "/samantha/chat/completion";
            StringBuilder paramsBuilder = new StringBuilder();
            baseParams.forEach((key, value) -> {
                if (value != null && !value.isEmpty()) {
                    paramsBuilder.append(key).append("=").append(value).append("&");
                }
            });
            String queryString = paramsBuilder.substring(0, paramsBuilder.length() - 1);

            // 生成签名
            SignatureRequest signatureRequest = new SignatureRequest();
            signatureRequest.setUrl(baseUrl);
            signatureRequest.setCookie(account.getCookie());
            signatureRequest.setParams(queryString);

            SignatureResponse signatureResponse = signatureService.generateSignature(signatureRequest);
            if (signatureResponse.isSuccess()) {
                log.info("签名生成成功[账号: {}]", getAccountKey(account));
                return signatureResponse.getSignedUrl();
            } else {
                log.warn("签名生成失败[账号: {}]: {}", getAccountKey(account), signatureResponse.getError());
                return baseUrl + "?" + queryString;
            }
        } catch (Exception e) {
            log.error("构建签名URL异常", e);
            throw new ServiceException("构建请求URL失败: " + e.getMessage());
        }
    }

    /**
     * 构建HTTP请求
     */
    private HttpPost buildHttpPost(String url, String cookie) {
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Accept", "*/*");
        httpPost.setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Cookie", cookie);
        httpPost.setHeader("Origin", "https://www.doubao.com");
        httpPost.setHeader("Referer", "https://www.doubao.com/chat/");
        httpPost.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        httpPost.setHeader("agw-js-conv", "str, str");
        httpPost.setHeader("sec-ch-ua", "\"Google Chrome\";v=\"141\", \"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"141\"");
        httpPost.setHeader("sec-ch-ua-mobile", "?0");
        httpPost.setHeader("sec-ch-ua-platform", "\"Windows\"");
        httpPost.setHeader("sec-fetch-dest", "empty");
        httpPost.setHeader("sec-fetch-mode", "cors");
        httpPost.setHeader("sec-fetch-site", "same-origin");
        return httpPost;
    }

    /**
     * 构建请求体
     */
    private String buildRealPayload(ChatCompletionRequest request, String conversationId,
                                    DoubaoProperties.AccountConfig account) throws JsonProcessingException {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new ServiceException("请求消息列表不能为空");
        }

        Map<String, Object> payload = new HashMap<>();

        // 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatCompletionRequest.Message msg : request.getMessages()) {
            Map<String, Object> messageContent = new HashMap<>();
            messageContent.put("text", msg.getContent());

            Map<String, Object> message = new HashMap<>();
            message.put("content", objectMapper.writeValueAsString(messageContent));
            message.put("content_type", 2001);
            message.put("attachments", Collections.emptyList());
            message.put("references", Collections.emptyList());
            message.put("role", msg.getRole());
            messages.add(message);
        }
        payload.put("messages", messages);

        // 构建completion_option
        Map<String, Object> completionOption = new HashMap<>();
        completionOption.put("is_regen", false);
        completionOption.put("with_suggest", true);
        completionOption.put("need_create_conversation", "0".equals(conversationId));
        completionOption.put("launch_stage", 1);
        completionOption.put("is_replace", false);
        completionOption.put("is_delete", false);
        completionOption.put("message_from", 0);
        completionOption.put("action_bar_skill_id", 0);
        completionOption.put("use_deep_think", false);
        completionOption.put("use_auto_cot", true);
        completionOption.put("resend_for_regen", false);
        completionOption.put("enable_commerce_credit", false);
        completionOption.put("event_id", "0");
        payload.put("completion_option", completionOption);

        // 其他参数
        payload.put("evaluate_option", Collections.singletonMap("web_ab_params", ""));
        payload.put("conversation_id", conversationId);
        payload.put("local_conversation_id", "local_" + UUID.randomUUID());
        payload.put("local_message_id", UUID.randomUUID().toString());

        // 设置模型ID
        String botId = MODEL_MAPPING.get(request.getModel());
        payload.put("bot_id", botId != null ? botId : "7338286299411103781");

        String payloadStr = objectMapper.writeValueAsString(payload);
        log.debug("构建请求Payload: {}", payloadStr);
        return payloadStr;
    }

    /**
     * 解析非流式响应
     */
    private ChatCompletionResponse parseNonStreamResponse(CloseableHttpResponse response,
                                                          String model, String sessionId,
                                                          String conversationId) throws IOException, ParseException {
        int statusCode = response.getCode();
        String responseBody = EntityUtils.toString(response.getEntity());

        log.info("豆包API响应状态: {}, 响应体长度: {}", statusCode, responseBody.length());

        if (statusCode != 200) {
            throw new ServiceException("豆包API服务异常，状态码: " + statusCode);
        }

        try {
            return parseDoubaoResponse(responseBody, model);
        } catch (Exception e) {
            log.warn("解析豆包响应失败，返回模拟响应", e);
            return createMockResponse(model);
        }
    }

    /**
     * 解析豆包API响应
     */
    private ChatCompletionResponse parseDoubaoResponse(String responseBody, String model) {
        try {
            String[] lines = responseBody.split("\n");
            StringBuilder contentBuilder = new StringBuilder();

            for (String line : lines) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;

                    try {
                        JsonNode jsonData = objectMapper.readTree(data);
                        JsonNode eventTypeNode = jsonData.get("event_type");
                        if (eventTypeNode == null) continue;

                        String eventType = eventTypeNode.asText();
                        if ("2001".equals(eventType)) {
                            JsonNode eventDataNode = jsonData.get("event_data");
                            if (eventDataNode == null) continue;

                            String eventData = eventDataNode.asText();
                            JsonNode eventDataJson = objectMapper.readTree(eventData);
                            JsonNode messageNode = eventDataJson.get("message");
                            if (messageNode == null) continue;

                            JsonNode contentNode = messageNode.get("content");
                            if (contentNode == null) continue;

                            String contentJson = contentNode.asText();
                            JsonNode contentDetailNode = objectMapper.readTree(contentJson);
                            JsonNode textNode = contentDetailNode.get("text");
                            if (textNode != null) {
                                contentBuilder.append(textNode.asText());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("解析SSE数据行失败: {}", line);
                    }
                }
            }

            String finalContent = contentBuilder.toString();
            if (!finalContent.isEmpty()) {
                return createSuccessResponse(model, finalContent);
            }
        } catch (Exception e) {
            log.error("解析豆包响应异常", e);
        }
        return createMockResponse(model);
    }

    /**
     * 创建成功响应
     */
    private ChatCompletionResponse createSuccessResponse(String model, String content) {
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setId("chatcmpl-" + UUID.randomUUID());
        response.setObject("chat.completion");
        response.setCreated(System.currentTimeMillis() / 1000);
        response.setModel(model);

        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
        ChatCompletionResponse.Choice.Message message = new ChatCompletionResponse.Choice.Message();
        message.setRole("assistant");
        message.setContent(content);
        choice.setMessage(message);
        choice.setFinishReason("stop");
        choice.setIndex(0);
        response.setChoices(Collections.singletonList(choice));

        ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage();
        usage.setPromptTokens(estimateTokens(content) / 2);
        usage.setCompletionTokens(estimateTokens(content));
        usage.setTotalTokens(usage.getPromptTokens() + usage.getCompletionTokens());
        response.setUsage(usage);

        return response;
    }

    /**
     * 创建模拟响应
     */
    private ChatCompletionResponse createMockResponse(String model) {
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setId("chatcmpl-" + UUID.randomUUID());
        response.setObject("chat.completion");
        response.setCreated(System.currentTimeMillis() / 1000);
        response.setModel(model);

        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
        ChatCompletionResponse.Choice.Message message = new ChatCompletionResponse.Choice.Message();
        message.setRole("assistant");
        message.setContent("您好！我是豆包AI助手，很高兴为您服务。");
        choice.setMessage(message);
        choice.setFinishReason("stop");
        choice.setIndex(0);
        response.setChoices(Collections.singletonList(choice));

        ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(20);
        usage.setTotalTokens(30);
        response.setUsage(usage);

        return response;
    }

    /**
     * 估算Token数量
     */
    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chineseCount = text.replaceAll("[^\\u4e00-\\u9fa5]", "").length();
        int englishCount = text.replaceAll("[^a-zA-Z]", "").length();
        return chineseCount + (int) Math.ceil(englishCount * 0.25);
    }

    /**
     * 设置SSE回调
     */
    private void setupSseCallbacks(SseEmitter emitter, String connectionId) {
        emitter.onCompletion(() -> {
            log.info("SSE连接完成[连接ID: {}], 剩余活跃连接数: {}",
                    connectionId, connectionStatus.size());
            connectionStatus.remove(connectionId);
        });

        emitter.onTimeout(() -> {
            log.warn("SSE连接超时[连接ID: {}]", connectionId);
            connectionStatus.remove(connectionId);
        });

        emitter.onError((ex) -> {
            log.error("SSE连接错误[连接ID: {}]", connectionId, ex);
            connectionStatus.remove(connectionId);
        });
    }

    /**
     * 处理流式错误
     */
    private void handleStreamError(SseEmitter emitter, Exception e, String connectionId) {
        log.error("流式请求处理失败[连接ID: {}]", connectionId, e);
        try {
            String errorMsg = "处理请求失败: " + e.getMessage();
            Map<String, Object> errorChunk = new HashMap<>();
            errorChunk.put("id", "error-" + connectionId);
            errorChunk.put("object", "chat.completion.chunk");
            errorChunk.put("created", System.currentTimeMillis() / 1000);
            errorChunk.put("model", "doubao-pro-chat");
            Map<String, Object> choice = new HashMap<>();
            choice.put("index", 0);
            choice.put("finish_reason", "error");
            errorChunk.put("choices", new Object[]{choice});

            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(errorChunk)));
        } catch (IOException ex) {
            log.error("发送错误事件失败", ex);
        } finally {
            emitter.completeWithError(e);
            connectionStatus.remove(connectionId);
        }
    }

    /**
     * 发送SSE数据
     */
    private void sendSseData(SseEmitter emitter, String connectionId, String data) throws IOException {
        if (!connectionStatus.containsKey(connectionId)) {
            log.debug("连接已关闭，停止发送数据[连接ID: {}]", connectionId);
            throw new IOException("连接已关闭");
        }
        emitter.send(SseEmitter.event().data(data));
    }

    /**
     * 多账号负载均衡+会话绑定
     */
    private DoubaoProperties.AccountConfig getNextAccount(String sessionId) {
        if (accounts.isEmpty()) {
            throw new ServiceException("未配置豆包账号信息");
        }

        // 1. 会话已绑定账号：直接返回绑定的账号（若健康）
        if (sessionAccountBindCache.containsKey(sessionId)) {
            Integer boundIndex = sessionAccountBindCache.get(sessionId);
            DoubaoProperties.AccountConfig boundAccount = accounts.get(boundIndex);
            String accountKey = getAccountKey(boundAccount);
            AccountStatus status = accountStatusCache.get(accountKey);

            if (status != null && status.isHealthy()) {
                incrementActiveConnections(accountKey);
                return boundAccount;
            } else {
                sessionAccountBindCache.remove(sessionId);
                log.warn("会话{}绑定的账号{}已失效，重新分配账号", sessionId, accountKey);
            }
        }

        // 2. 筛选健康账号
        List<Map.Entry<String, AccountStatus>> healthyAccounts = accountStatusCache.entrySet().stream()
                .filter(entry -> entry.getValue().isHealthy())
                .sorted(Comparator.comparingInt(entry -> entry.getValue().getActiveConnections()))
                .collect(Collectors.toList());

        if (healthyAccounts.isEmpty()) {
            throw new ServiceException("所有豆包账号均已失效，请检查Cookie配置");
        }

        // 3. 选择活跃连接数最少的账号（负载均衡）
        Map.Entry<String, AccountStatus> selectedEntry = healthyAccounts.get(0);
        String selectedAccountKey = selectedEntry.getKey();
        AccountStatus selectedStatus = selectedEntry.getValue();
        int selectedIndex = selectedStatus.getAccountIndex();
        DoubaoProperties.AccountConfig selectedAccount = accounts.get(selectedIndex);

        // 4. 绑定会话与账号
        sessionAccountBindCache.put(sessionId, selectedIndex);
        incrementActiveConnections(selectedAccountKey);
        log.info("会话{}绑定账号{}，当前活跃连接数:{}",
                sessionId, selectedAccountKey, selectedStatus.getActiveConnections());

        return selectedAccount;
    }

    /**
     * 标记账号为失效状态
     */
    private void markAccountInvalid(String accountKey, Exception e) {
        AccountStatus status = accountStatusCache.get(accountKey);
        if (status != null) {
            status.setHealthy(false);
            status.setLastFailTime(System.currentTimeMillis());
            log.error("账号{}已失效，原因:{}", accountKey, e.getMessage());

            // 清理该账号绑定的所有会话（避免会话上下文失效）
            List<String> boundSessions = sessionAccountBindCache.entrySet().stream()
                    .filter(entry -> {
                        int boundIndex = entry.getValue();
                        String boundAccountKey = getAccountKey(accounts.get(boundIndex));
                        return boundAccountKey.equals(accountKey);
                    })
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            boundSessions.forEach(sessionId -> {
                sessionAccountBindCache.remove(sessionId);
                sessionCache.remove(sessionId); // 清除会话缓存（避免使用无效会话ID）
                log.info("清除失效账号{}绑定的会话: {}", accountKey, sessionId);
            });
        }
    }

    /**
     * 恢复失效账号检测
     */
    private void recoverInvalidAccounts() {
        log.info("开始检测失效账号恢复状态，当前失效账号数:{}",
                accountStatusCache.values().stream().filter(s -> !s.isHealthy()).count());

        for (Map.Entry<String, AccountStatus> entry : accountStatusCache.entrySet()) {
            String accountKey = entry.getKey();
            AccountStatus status = entry.getValue();
            if (!status.isHealthy()) {
                // 失效超过30分钟才尝试恢复（避免频繁检测）
                long failDuration = System.currentTimeMillis() - status.getLastFailTime();
                if (failDuration < 30 * 60 * 1000) {
                    continue;
                }

                // 尝试通过获取msToken检测账号是否恢复
                int accountIndex = status.getAccountIndex();
                DoubaoProperties.AccountConfig account = accounts.get(accountIndex);
                try {
                    // 模拟检测：实际场景可发送简单请求验证
                    signatureService.getMsToken(account.getCookie());
                    // 检测成功，恢复账号健康状态
                    status.setHealthy(true);
                    status.setActiveConnections(0);
                    log.info("账号{}已恢复健康", accountKey);
                } catch (Exception e) {
                    log.warn("账号{}仍未恢复，原因:{}", accountKey, e.getMessage());
                }
            }
        }
    }

    /**
     * 增加账号活跃连接数
     */
    private void incrementActiveConnections(String accountKey) {
        AccountStatus status = accountStatusCache.get(accountKey);
        if (status != null) {
            status.setActiveConnections(status.getActiveConnections() + 1);
        }
    }

    /**
     * 减少账号活跃连接数
     */
    private void decrementActiveConnections(String accountKey) {
        AccountStatus status = accountStatusCache.get(accountKey);
        if (status != null) {
            int newCount = Math.max(0, status.getActiveConnections() - 1);
            status.setActiveConnections(newCount);
            log.debug("账号{}活跃连接数减少至{}", accountKey, newCount);
        }
    }

    /**
     * 验证API Key
     */
    private void validateApiKey(String apiKey) {
        if (!doubaoProperties.getApiKey().equals(apiKey)) {
            throw new ServiceException("无效的API密钥");
        }
    }

    /**
     * 清理过期会话（1小时未活跃）
     */
    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long expireTime = 3600 * 1000; // 1小时
        int initialSize = sessionCache.size();

        // 清理过期会话
        List<String> expiredSessions = sessionCache.entrySet().stream()
                .filter(entry -> now - entry.getValue().getLastActiveTime() > expireTime)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        expiredSessions.forEach(sessionId -> {
            sessionCache.remove(sessionId);
            sessionAccountBindCache.remove(sessionId); // 同时解除账号绑定
        });

        log.info("清理过期会话完成，清理前: {}，清理后: {}，解除绑定账号数: {}",
                initialSize, sessionCache.size(), expiredSessions.size());
    }
}