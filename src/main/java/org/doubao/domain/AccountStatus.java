package org.doubao.domain;

import lombok.Data;

/**
 * 账号状态模型：管理健康状态、负载、失效时间
 */
@Data
public class AccountStatus {

    private int accountIndex; // 账号在列表中的索引

    private boolean healthy; // 是否健康

    private int activeConnections; // 活跃连接数

    private long lastFailTime; // 最后失败时间戳（毫秒）

}
