package org.doubao.service;


import org.doubao.domain.dto.SignatureRequest;
import org.doubao.domain.vo.SignatureResponse;

public interface ISignatureService {

    /**
     * 生成签名
     */
    SignatureResponse generateSignature(SignatureRequest request);

    /**
     * 获取 msToken
     */
    String getMsToken(String cookie);

    /**
     * 更新 msToken
     */
    void updateMsToken(String token);

    /**
     * 获取当前 msToken
     */
    String getCurrentMsToken();
}
