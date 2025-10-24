package org.doubao.domain.dto;

import lombok.Data;

@Data
public class SignatureRequest {

    private String url;

    private String cookie;

    private String params;

}
