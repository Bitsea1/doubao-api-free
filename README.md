# DouBao API Free

该项目为豆包（Doubao）API 提供了一个免费的接口服务，支持聊天、文档解读（计划支持）、图像生成（计划支持）等功能。适合需要使用豆包 AI 能力的开发者使用。

## 功能特性

- **聊天接口**：支持与豆包模型进行交互，实现智能对话。
- **文档解读**：（计划支持）。
- **图像生成**：（计划支持）。
- **健康检查**：提供健康检查接口，确保服务的可用性。

## 安装指南

1. 克隆仓库：
   ```bash
   git clone https://gitee.com/Bitsea19/doubao-api-free.git
   ```

2. 构建项目：
   ```bash
   mvn clean package
   ```

3. 配置 `application.yml` 文件，设置豆包 API 的相关参数。

4. 启动应用：
   ```bash
   java -jar target/doubao-api-free.jar
   ```

## 使用说明

### 聊天接口

发送 POST 请求至 `/api/doubao/v1/chat/completions`，请求体格式如下：

```json
{
  "model": "doubao-pro",
  "messages": [
    {
      "role": "user",
      "content": "你好"
    }
  ],
  "stream": false // true(流式输出)
}
```

### 健康检查

发送 GET 请求至 `/api/doubao/v1/health`，检查服务的健康状态。

## 贡献指南

欢迎贡献代码和建议。请提交 Pull Request 或创建 Issue。

## 许可证

本项目使用 MIT 许可证。详情请查看 [LICENSE](LICENSE) 文件。

## 联系方式

如有任何问题，请联系 [Bitsea19](https://gitee.com/Bitsea19)。