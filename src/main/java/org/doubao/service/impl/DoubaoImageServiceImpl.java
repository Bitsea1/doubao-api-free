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
import org.doubao.domain.dto.ImageGenerationRequest;
import org.doubao.domain.dto.SignatureRequest;
import org.doubao.domain.exception.ServiceException;
import org.doubao.domain.vo.ImageGenerationResponse;
import org.doubao.domain.vo.SignatureResponse;
import org.doubao.service.IDoubaoImageService;
import org.doubao.service.ISignatureService;
import org.doubao.utils.SseUtils;
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
public class DoubaoImageServiceImpl implements IDoubaoImageService {

    // 生图接口核心常量
    private static final String DEFAULT_IMAGE_MODEL = "Seedream 4.0"; // 默认生图模型

    // 依赖注入
    private final DoubaoProperties doubaoProperties;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ISignatureService signatureService;
    private final List<DoubaoProperties.AccountConfig> accounts;

    // 生图专属缓存
    private final Map<String, SessionData> imageSessionCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> imageSessionAccountBindCache = new ConcurrentHashMap<>();
    private final Map<String, AccountStatus> imageAccountStatusCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> imageConnectionStatus = new ConcurrentHashMap<>();

    // 定时任务线程池
    private final ScheduledExecutorService imageSessionScheduler = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService imageAccountRecoveryScheduler = Executors.newScheduledThreadPool(1);


    public DoubaoImageServiceImpl(DoubaoProperties doubaoProperties,
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
        // 启动会话清理任务
        imageSessionScheduler.scheduleAtFixedRate(this::cleanExpiredSessions, 1, 1, TimeUnit.HOURS);
        // 启动账号恢复任务
        imageAccountRecoveryScheduler.scheduleAtFixedRate(this::recoverInvalidAccounts, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * 非流式生图实现
     */
    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request, String apiKey) {
        validateApiKey(apiKey);
        validateImageRequest(request);

        String sessionId = buildImageSessionId(request);
        String accountKey = null;

        try {
            DoubaoProperties.AccountConfig account = getNextImageAccount(sessionId);
            accountKey = getAccountKey(account);

            SessionData sessionData = imageSessionCache.computeIfAbsent(sessionId, k -> {
                SessionData newSession = new SessionData();
                newSession.setConversationId("0");
                newSession.setLastActiveTime(System.currentTimeMillis());
                return newSession;
            });
            sessionData.setLastActiveTime(System.currentTimeMillis());
            String conversationId = sessionData.getConversationId();

            String signedUrl = buildImageSignedUrl(account);
            HttpPost httpPost = buildImageHttpPost(signedUrl, account.getCookie());
            String payload = buildImagePayload(request, conversationId);
            httpPost.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));

            log.info("发送非流式生图请求[会话: {}, 账号: {}]，对话ID: {}", sessionId, accountKey, conversationId);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                if (response.getCode() != 200) {
                    throw new ServiceException("生图服务返回错误状态: " + response.getCode());
                }
                return parseImageResponse(response, request.getModel());
            }

        } catch (Exception e) {
            log.error("非流式生图失败[会话: {}, 账号: {}]", sessionId, accountKey, e);
            if (accountKey != null) {
                markImageAccountInvalid(accountKey, e);
            }
            throw new ServiceException("生图服务暂时不可用: " + e.getMessage());
        } finally {
            if (accountKey != null) {
                decrementImageActiveConnections(accountKey);
            }
        }
    }

    /**
     * 流式生图实现
     */
    @Override
    public SseEmitter generateImageStream(ImageGenerationRequest request, String apiKey) {
        validateApiKey(apiKey);
        validateImageRequest(request);

        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        String connectionId = "img-stream-" + UUID.randomUUID();
        imageConnectionStatus.put(connectionId, true);

        setupImageSseCallbacks(emitter, connectionId);

        Executors.newSingleThreadExecutor().submit(() -> {
            String sessionId = buildImageSessionId(request);
            String accountKey = null;

            try {
                DoubaoProperties.AccountConfig account = getNextImageAccount(sessionId);
                accountKey = getAccountKey(account);

                SessionData sessionData = imageSessionCache.computeIfAbsent(sessionId, k -> {
                    SessionData newSession = new SessionData();
                    newSession.setConversationId("0");
                    newSession.setLastActiveTime(System.currentTimeMillis());
                    return newSession;
                });
                String conversationId = sessionData.getConversationId();

                String signedUrl = buildImageSignedUrl(account);
                HttpPost httpPost = buildImageHttpPost(signedUrl, account.getCookie());
                String payload = buildImagePayload(request, conversationId);
                httpPost.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));

                String requestId = "imgcmpl-" + UUID.randomUUID();
                log.info("发送流式生图请求[会话: {}, 账号: {}]，请求ID: {}", sessionId, accountKey, requestId);

                try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                    if (response.getCode() != 200) {
                        throw new ServiceException("生图服务返回错误状态: " + response.getCode());
                    }
                    processImageStreamResponse(response, emitter, connectionId, requestId, request.getModel());
                }

            } catch (Exception e) {
                log.error("流式生图失败[会话: {}, 账号: {}]", sessionId, accountKey, e);
                if (accountKey != null) {
                    markImageAccountInvalid(accountKey, e);
                }
                handleImageStreamError(emitter, e, connectionId);
            } finally {
                if (accountKey != null) {
                    decrementImageActiveConnections(accountKey);
                }
                imageConnectionStatus.remove(connectionId);
            }
        });

        return emitter;
    }

    /**
     * 校验API密钥
     */
    private void validateApiKey(String apiKey) {
        if (!doubaoProperties.getApiKey().equals(apiKey)) {
            throw new ServiceException("无效的API密钥");
        }
    }

    /**
     * 校验生图请求参数
     */
    private void validateImageRequest(ImageGenerationRequest request) {
        if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
            throw new ServiceException("生图描述（prompt）不能为空");
        }
    }

    /**
     * 构建生图会话ID
     */
    private String buildImageSessionId(ImageGenerationRequest request) {
        return Optional.ofNullable(request.getUser())
                .orElse("img-session-" + UUID.randomUUID().toString().substring(0, 8));
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
            String accountKey = getAccountKey(account);
            AccountStatus status = new AccountStatus();
            status.setAccountIndex(i);
            status.setHealthy(true);
            status.setActiveConnections(0);
            status.setLastFailTime(0L);
            imageAccountStatusCache.put(accountKey, status);
            log.info("初始化生图账号[{}]状态: 健康", accountKey);
        }
    }

    /**
     * 获取账号唯一标识
     */
    private String getAccountKey(DoubaoProperties.AccountConfig account) {
        return UUID.nameUUIDFromBytes(account.getCookie().getBytes()).toString();
    }

    /**
     * 获取下一个可用账号（负载均衡）
     */
    private DoubaoProperties.AccountConfig getNextImageAccount(String sessionId) {
        if (accounts.isEmpty()) {
            throw new ServiceException("未配置豆包账号信息");
        }

        // 检查会话绑定账号
        if (imageSessionAccountBindCache.containsKey(sessionId)) {
            Integer boundIndex = imageSessionAccountBindCache.get(sessionId);
            DoubaoProperties.AccountConfig boundAccount = accounts.get(boundIndex);
            String accountKey = getAccountKey(boundAccount);
            AccountStatus status = imageAccountStatusCache.get(accountKey);

            if (status != null && status.isHealthy()) {
                incrementImageActiveConnections(accountKey);
                return boundAccount;
            } else {
                imageSessionAccountBindCache.remove(sessionId);
                log.warn("生图会话{}绑定账号{}失效，重新分配", sessionId, accountKey);
            }
        }

        // 筛选健康账号
        List<Map.Entry<String, AccountStatus>> healthyAccounts = imageAccountStatusCache.entrySet().stream()
                .filter(entry -> entry.getValue().isHealthy())
                .sorted(Comparator.comparingInt(entry -> entry.getValue().getActiveConnections()))
                .collect(Collectors.toList());

        if (healthyAccounts.isEmpty()) {
            throw new ServiceException("所有生图账号均已失效，请检查Cookie配置");
        }

        // 选择活跃连接最少的账号
        Map.Entry<String, AccountStatus> selectedEntry = healthyAccounts.get(0);
        String selectedAccountKey = selectedEntry.getKey();
        AccountStatus selectedStatus = selectedEntry.getValue();
        int selectedIndex = selectedStatus.getAccountIndex();
        DoubaoProperties.AccountConfig selectedAccount = accounts.get(selectedIndex);

        imageSessionAccountBindCache.put(sessionId, selectedIndex);
        incrementImageActiveConnections(selectedAccountKey);
        log.info("生图会话{}绑定账号{}，活跃连接数:{}", sessionId, selectedAccountKey, selectedStatus.getActiveConnections());

        return selectedAccount;
    }

    /**
     * 构建生图签名URL
     */
    private String buildImageSignedUrl(DoubaoProperties.AccountConfig account) {
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

            // 设备参数
            baseParams.put("device_id", account.getDeviceId());
            baseParams.put("tea_uuid", account.getTeaUuid());
            baseParams.put("web_id", account.getWebId());
            baseParams.put("web_tab_id", UUID.randomUUID().toString());

            // 获取msToken
            String msToken = account.getMsToken();
            if (msToken == null) {
                msToken = signatureService.getMsToken(account.getCookie());
            }
            baseParams.put("msToken", msToken);

            // 构建URL参数
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
                log.info("生图账号{}签名生成成功", getAccountKey(account));
                return signatureResponse.getSignedUrl();
            } else {
                log.warn("生图账号{}签名生成失败: {}", getAccountKey(account), signatureResponse.getError());
                return baseUrl + "?" + queryString;
            }
        } catch (Exception e) {
            log.error("构建生图签名URL异常", e);
            throw new ServiceException("生图请求URL构建失败: " + e.getMessage());
        }
    }

    /**
     * 构建生图HTTP请求
     */
    private HttpPost buildImageHttpPost(String url, String cookie) {
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
     * 构建生图请求体
     */
    private String buildImagePayload(ImageGenerationRequest request, String conversationId) throws JsonProcessingException {
        Map<String, Object> payload = new HashMap<>();

        // 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> messageContent = new HashMap<>();
        messageContent.put("text", request.getPrompt());

        Map<String, Object> message = new HashMap<>();
        message.put("content", objectMapper.writeValueAsString(messageContent));
        message.put("content_type", 2009);
        message.put("attachments", Collections.emptyList());
        message.put("references", Collections.emptyList());
        message.put("role", "user");
        messages.add(message);
        payload.put("messages", messages);

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

        String payloadStr = objectMapper.writeValueAsString(payload);
        log.debug("构建请求Payload: {}", payloadStr);
        return payloadStr;
    }

    /**
     * 解析生图响应
     */
    private ImageGenerationResponse parseImageResponse(CloseableHttpResponse response, String model) throws IOException, ParseException {
        String responseBody = EntityUtils.toString(response.getEntity());
        log.info("生图响应体长度: {}", responseBody.length());
        log.debug("完整生图响应体: {}", responseBody);

        try {
            String[] lines = responseBody.split("\n");
            List<String> imageUrls = new ArrayList<>();

            for (String line : lines) {
                if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                    String dataStr = line.substring(6).trim();
                    if (dataStr.isEmpty()) {
                        continue;
                    }

                    JsonNode eventNode = objectMapper.readTree(dataStr);
                    JsonNode eventTypeNode = eventNode.get("event_type");
                    JsonNode eventDataStrNode = eventNode.get("event_data");

                    if (eventTypeNode == null || !"2001".equals(eventTypeNode.asText()) || eventDataStrNode == null) {
                        continue;
                    }

                    String eventDataStr = eventDataStrNode.asText().trim();
                    JsonNode eventDataNode = objectMapper.readTree(eventDataStr);
                    JsonNode messageNode = eventDataNode.get("message");
                    if (messageNode == null) {
                        continue;
                    }

                    JsonNode contentTypeNode = messageNode.get("content_type");
                    if (contentTypeNode == null || !"2010".equals(contentTypeNode.asText())) {
                        log.debug("跳过非图片结果事件，content_type: {}", contentTypeNode);
                        continue;
                    }

                    JsonNode contentStrNode = messageNode.get("content");
                    if (contentStrNode == null) {
                        continue;
                    }
                    String contentStr = contentStrNode.asText().trim();
                    JsonNode contentNode = objectMapper.readTree(contentStr);
                    JsonNode imageDataArrayNode = contentNode.get("data");

                    // 提取每张图片的原始URL（image_ori.url）
                    if (imageDataArrayNode != null && imageDataArrayNode.isArray()) {
                        for (JsonNode imageItemNode : imageDataArrayNode) {
                            JsonNode imageOriNode = imageItemNode.get("image_ori");
                            if (imageOriNode != null) {
                                JsonNode imageUrlNode = imageOriNode.get("url");
                                if (imageUrlNode != null && !imageUrlNode.asText().trim().isEmpty()) {
                                    String imageUrl = imageUrlNode.asText().trim();
                                    imageUrls.add(imageUrl);
                                    log.debug("提取到图片URL: {}", imageUrl);
                                }
                            }
                        }
                    }
                }
            }

            // 校验是否提取到图片URL
            if (imageUrls.isEmpty()) {
                throw new ServiceException("未从响应中提取到有效图片URL，请检查响应结构或生图服务状态");
            }

            // 构建返回结果
            ImageGenerationResponse result = new ImageGenerationResponse();
            result.setModel(Optional.ofNullable(model).orElse(DEFAULT_IMAGE_MODEL));
            List<ImageGenerationResponse.ImageData> imageDataList = imageUrls.stream()
                    .map(url -> {
                        ImageGenerationResponse.ImageData imageData = new ImageGenerationResponse.ImageData();
                        imageData.setUrl(url);
                        String format = "png";
                        if (url.contains(".")) {
                            String suffix = url.substring(url.lastIndexOf(".") + 1).toLowerCase();
                            if (Arrays.asList("png", "jpeg", "jpg", "webp", "avif").contains(suffix)) {
                                format = suffix;
                            }
                        }
                        imageData.setFormat(format);
                        return imageData;
                    })
                    .collect(Collectors.toList());
            result.setData(imageDataList);
            return result;

        } catch (JsonProcessingException e) {
            log.error("JSON解析失败（可能是双重转义处理异常）", e);
            throw new ServiceException("生图响应JSON解析失败: " + e.getMessage());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析生图响应失败", e);
            throw new ServiceException("生图结果解析失败: " + e.getMessage());
        }
    }

    /**
     * 处理流式生图响应（与非流式解析逻辑保持一致）
     */
    private void processImageStreamResponse(CloseableHttpResponse response, SseEmitter emitter,
                                            String connectionId, String requestId, String model) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {

            String line;
            List<String> imageUrls = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                // 检查客户端连接状态
                if (!imageConnectionStatus.containsKey(connectionId)) {
                    log.info("客户端已断开，停止生图流式处理[连接ID: {}]", connectionId);
                    return;
                }

                if (line.startsWith("data: ")) {
                    String dataStr = line.substring(6).trim();
                    if (dataStr.isEmpty() || "[DONE]".equals(dataStr)) {
                        continue;
                    }

                    try {
                        JsonNode eventNode = objectMapper.readTree(dataStr);
                        JsonNode eventTypeNode = eventNode.get("event_type");
                        JsonNode eventDataStrNode = eventNode.get("event_data");

                        if (eventTypeNode == null || eventDataStrNode == null) {
                            continue;
                        }

                        String eventType = eventTypeNode.asText();

                        if ("2003".equals(eventType)) {
                            String eventDataStr = eventDataStrNode.asText().trim();
                            if (!eventDataStr.isEmpty()) {
                                JsonNode progressNode = objectMapper.readTree(eventDataStr);
                                Map<String, Object> progress = new HashMap<>();
                                progress.put("id", requestId);
                                progress.put("object", "image.generation.chunk");
                                progress.put("created", System.currentTimeMillis() / 1000);
                                progress.put("status", "generating");
                                progress.put("progress", progressNode.has("progress")
                                        ? progressNode.get("progress").asInt(0)
                                        : 0);
                                sendImageSseData(emitter, connectionId, objectMapper.writeValueAsString(progress));
                            }
                        }

                        else if ("2001".equals(eventType)) {
                            String eventDataStr = eventDataStrNode.asText().trim();
                            JsonNode eventDataNode = objectMapper.readTree(eventDataStr);
                            JsonNode messageNode = eventDataNode.get("message");
                            if (messageNode == null) {
                                continue;
                            }

                            JsonNode contentTypeNode = messageNode.get("content_type");
                            if (contentTypeNode == null || !"2010".equals(contentTypeNode.asText())) {
                                log.debug("跳过非图片结果事件，content_type: {}", contentTypeNode);
                                continue;
                            }

                            JsonNode contentStrNode = messageNode.get("content");
                            if (contentStrNode == null) {
                                continue;
                            }
                            String contentStr = contentStrNode.asText().trim();
                            JsonNode contentNode = objectMapper.readTree(contentStr);
                            JsonNode imageDataArrayNode = contentNode.get("data");

                            // 提取原始图片URL
                            if (imageDataArrayNode != null && imageDataArrayNode.isArray()) {
                                for (JsonNode imageItemNode : imageDataArrayNode) {
                                    JsonNode imageOriNode = imageItemNode.get("image_ori");
                                    if (imageOriNode != null) {
                                        JsonNode imageUrlNode = imageOriNode.get("url");
                                        if (imageUrlNode != null && !imageUrlNode.asText().trim().isEmpty()) {
                                            String imageUrl = imageUrlNode.asText().trim();
                                            imageUrls.add(imageUrl);
                                            log.debug("流式提取到图片URL: {}", imageUrl);
                                        }
                                    }
                                }
                            }
                        }

                    } catch (JsonProcessingException e) {
                        log.error("流式JSON解析失败（双重转义问题），数据: {}", dataStr, e);
                        sendImageSseError(emitter, connectionId, "解析生图进度失败: " + e.getMessage());
                    } catch (Exception e) {
                        log.warn("处理生图流式数据异常: {}", dataStr, e);
                    }
                }
            }

            if (!imageUrls.isEmpty() && imageConnectionStatus.containsKey(connectionId)) {
                ImageGenerationResponse result = new ImageGenerationResponse();
                result.setId(requestId);
                result.setModel(Optional.ofNullable(model).orElse(DEFAULT_IMAGE_MODEL));
                List<ImageGenerationResponse.ImageData> imageDataList = imageUrls.stream()
                        .map(url -> {
                            ImageGenerationResponse.ImageData imageData = new ImageGenerationResponse.ImageData();
                            imageData.setUrl(url);
                            String format = "png";
                            if (url.contains(".")) {
                                String suffix = url.substring(url.lastIndexOf(".") + 1).toLowerCase();
                                if (Arrays.asList("png", "jpeg", "jpg", "webp", "avif").contains(suffix)) {
                                    format = suffix;
                                }
                            }
                            imageData.setFormat(format);
                            return imageData;
                        })
                        .collect(Collectors.toList());
                result.setData(imageDataList);
                sendImageSseData(emitter, connectionId, objectMapper.writeValueAsString(result));
                sendImageSseData(emitter, connectionId, SseUtils.createDoneChunk());
            } else {
                throw new ServiceException("未生成有效图片URL");
            }

        } catch (IOException e) {
            log.error("读取生图流式响应失败", e);
            throw e;
        } finally {
            if (imageConnectionStatus.containsKey(connectionId)) {
                emitter.complete();
            }
        }
    }

    // 发送错误事件的工具方法
    private void sendImageSseError(SseEmitter emitter, String connectionId, String errorMsg) throws IOException {
        if (!imageConnectionStatus.containsKey(connectionId)) {
            return;
        }
        Map<String, Object> error = new HashMap<>();
        error.put("id", "error-" + connectionId);
        error.put("object", "image.generation.error");
        error.put("message", errorMsg);
        emitter.send(SseEmitter.event()
                .name("error")
                .data(objectMapper.writeValueAsString(error)));
    }

    /**
     * 设置SSE回调
     */
    private void setupImageSseCallbacks(SseEmitter emitter, String connectionId) {
        emitter.onCompletion(() -> {
            log.info("生图SSE连接完成[连接ID: {}]", connectionId);
            imageConnectionStatus.remove(connectionId);
        });

        emitter.onTimeout(() -> {
            log.warn("生图SSE连接超时[连接ID: {}]", connectionId);
            imageConnectionStatus.remove(connectionId);
        });

        emitter.onError((ex) -> {
            log.error("生图SSE连接错误[连接ID: {}]", connectionId, ex);
            imageConnectionStatus.remove(connectionId);
        });
    }

    /**
     * 发送SSE数据
     */
    private void sendImageSseData(SseEmitter emitter, String connectionId, String data) throws IOException {
        if (!imageConnectionStatus.containsKey(connectionId)) {
            throw new IOException("生图SSE连接已关闭");
        }
        emitter.send(SseEmitter.event().data(data));
    }

    /**
     * 处理流式错误
     */
    private void handleImageStreamError(SseEmitter emitter, Exception e, String connectionId) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("id", "error-" + connectionId);
            error.put("object", "image.generation.error");
            error.put("message", e.getMessage());
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(error)));
        } catch (IOException ex) {
            log.warn("发送生图错误事件失败", ex);
        } finally {
            emitter.completeWithError(e);
        }
    }

    /**
     * 标记账号为失效
     */
    private void markImageAccountInvalid(String accountKey, Exception e) {
        AccountStatus status = imageAccountStatusCache.get(accountKey);
        if (status != null) {
            status.setHealthy(false);
            status.setLastFailTime(System.currentTimeMillis());
            log.error("生图账号{}已失效，原因:{}", accountKey, e.getMessage());

            // 清理绑定的会话
            List<String> boundSessions = imageSessionAccountBindCache.entrySet().stream()
                    .filter(entry -> {
                        int boundIndex = entry.getValue();
                        String boundAccountKey = getAccountKey(accounts.get(boundIndex));
                        return boundAccountKey.equals(accountKey);
                    })
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            boundSessions.forEach(sessionId -> {
                imageSessionAccountBindCache.remove(sessionId);
                imageSessionCache.remove(sessionId);
                log.info("清除失效生图账号{}绑定的会话: {}", accountKey, sessionId);
            });
        }
    }

    /**
     * 恢复失效账号
     */
    private void recoverInvalidAccounts() {
        log.info("检测生图失效账号恢复，当前失效数:{}",
                imageAccountStatusCache.values().stream().filter(s -> !s.isHealthy()).count());

        for (Map.Entry<String, AccountStatus> entry : imageAccountStatusCache.entrySet()) {
            String accountKey = entry.getKey();
            AccountStatus status = entry.getValue();
            if (!status.isHealthy()) {
                long failDuration = System.currentTimeMillis() - status.getLastFailTime();
                if (failDuration < 30 * 60 * 1000) { // 失效30分钟后才检测
                    continue;
                }

                int accountIndex = status.getAccountIndex();
                DoubaoProperties.AccountConfig account = accounts.get(accountIndex);
                try {
                    signatureService.getMsToken(account.getCookie()); // 验证账号有效性
                    status.setHealthy(true);
                    status.setActiveConnections(0);
                    log.info("生图账号{}已恢复健康", accountKey);
                } catch (Exception e) {
                    log.warn("生图账号{}仍未恢复:{}", accountKey, e.getMessage());
                }
            }
        }
    }

    /**
     * 清理过期会话
     */
    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long expireTime = 3600 * 1000; // 1小时过期
        int initialSize = imageSessionCache.size();

        List<String> expiredSessions = imageSessionCache.entrySet().stream()
                .filter(entry -> now - entry.getValue().getLastActiveTime() > expireTime)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        expiredSessions.forEach(sessionId -> {
            imageSessionCache.remove(sessionId);
            imageSessionAccountBindCache.remove(sessionId);
        });

        log.info("清理生图过期会话，清理前:{}，清理后:{}，清理数:{}",
                initialSize, imageSessionCache.size(), expiredSessions.size());
    }

    /**
     * 增加活跃连接数
     */
    private void incrementImageActiveConnections(String accountKey) {
        AccountStatus status = imageAccountStatusCache.get(accountKey);
        if (status != null) {
            status.setActiveConnections(status.getActiveConnections() + 1);
        }
    }

    /**
     * 减少活跃连接数
     */
    private void decrementImageActiveConnections(String accountKey) {
        AccountStatus status = imageAccountStatusCache.get(accountKey);
        if (status != null) {
            int newCount = Math.max(0, status.getActiveConnections() - 1);
            status.setActiveConnections(newCount);
        }
    }
}