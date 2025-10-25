package org.doubao.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.doubao.config.DoubaoProperties;
import org.doubao.domain.dto.SignatureRequest;
import org.doubao.domain.vo.SignatureResponse;
import org.doubao.service.ISignatureService;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SignatureServiceImpl implements ISignatureService {

    private final DoubaoProperties doubaoProperties;
    private final Map<String, String> msTokenCache = new ConcurrentHashMap<>();
    private String currentMsToken;

    public SignatureServiceImpl(DoubaoProperties doubaoProperties) {
        this.doubaoProperties = doubaoProperties;
        this.currentMsToken = generateRandomMsToken();
    }

    @Override
    public SignatureResponse generateSignature(SignatureRequest request) {
        try {
//            log.info("生成签名，URL: {}, 参数长度: {}", request.getUrl(),
//                request.getParams() != null ? request.getParams().length() : 0);

            // 正确构建签名URL
            String signedUrl = buildCorrectSignedUrl(request.getUrl(), request.getParams());

            SignatureResponse response = new SignatureResponse();
            response.setABogus("s-7d10B3a4C5e6F7g8"); // 模拟固定签名值
            response.setSignedUrl(signedUrl);
            response.setSuccess(true);

//            log.info("签名生成成功: {}", signedUrl);
            return response;

        } catch (Exception e) {
            log.error("生成签名失败", e);
            SignatureResponse response = new SignatureResponse();
            response.setSuccess(false);
            response.setError("签名生成失败: " + e.getMessage());
            return response;
        }
    }

    /**
     * 正确构建签名URL
     */
    private String buildCorrectSignedUrl(String baseUrl, String params) {
        try {
            // 解析参数字符串
            Map<String, String> paramMap = parseParams(params);

            // 构建URL
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl);

            // 添加所有参数
            paramMap.forEach(builder::queryParam);

            // 添加签名参数（模拟）
            builder.queryParam("a_bogus", "s-7d10B3a4C5e6F7g8");

            String signedUrl = builder.build().toUriString();
//            log.info("构建签名URL: {}", signedUrl);

            return signedUrl;

        } catch (Exception e) {
            log.error("构建签名URL失败", e);
            return baseUrl + "?" + params + "&a_bogus=s-7d10B3a4C5e6F7g8";
        }
    }

    /**
     * 解析参数字符串为Map
     */
    private Map<String, String> parseParams(String params) {
        Map<String, String> paramMap = new LinkedHashMap<>();
        if (params == null || params.isEmpty()) {
            return paramMap;
        }

        String[] pairs = params.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                paramMap.put(key, value);
            }
        }
        return paramMap;
    }

    @Override
    public String getMsToken(String cookie) {
        String msToken = msTokenCache.get(cookie);
        if (msToken == null) {
            msToken = generateRandomMsToken();
            msTokenCache.put(cookie, msToken);
            log.info("为新Cookie生成msToken: {}", msToken);
        }
        return msToken;
    }

    @Override
    public void updateMsToken(String token) {
        this.currentMsToken = token;
        log.info("更新msToken: {}", token);
    }

    @Override
    public String getCurrentMsToken() {
        return currentMsToken;
    }

    private String generateRandomMsToken() {
        return "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    }
}
