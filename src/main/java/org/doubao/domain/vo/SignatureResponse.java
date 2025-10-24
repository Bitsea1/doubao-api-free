package org.doubao.domain.vo;

import lombok.Data;

@Data
public class SignatureResponse {
    private boolean success;
    private String aBogus;
    private String signedUrl;
    private String error;
}
