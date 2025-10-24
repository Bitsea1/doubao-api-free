package org.doubao.domain;

import lombok.Data;

/**
 * 会话数据模型
 */
@Data
public class SessionData {

    private String conversationId;

    private long lastActiveTime; // 最后活跃时间戳（毫秒）
}
