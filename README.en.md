# DouBao API Free

This project provides a free interface service for the Doubao (DouBao) API, supporting features such as chat, image generation, and signature services. Suitable for developers and enterprises needing to utilize Doubao AI capabilities.

## Features

- **Chat Interface**: Enables interaction with the Doubao model for intelligent conversations.
- **Image Generation**: Generates images using the Doubao model.
- **Signature Service**: Provides signature generation to ensure request security.
- **Health Check**: Offers a health check endpoint to verify service availability.

## Installation Guide

1. Clone the repository:
   ```bash
   git clone https://gitee.com/Bitsea19/doubao-api-free.git
   ```

2. Build the project:
   ```bash
   mvn clean package
   ```

3. Configure the `application.yml` file with the required Doubao API parameters.

4. Start the application:
   ```bash
   java -jar target/doubao-api-free.jar
   ```

## Usage Instructions

### Chat Interface

Send a POST request to `/api/doubao/v1/chat/completions` with the following request body format:

```json
{
  "model": "doubao-pro",
  "messages": [
    {
      "role": "user",
      "content": "Hello, Doubao!"
    }
  ],
  "stream": false
}
```

### Image Generation

Send a POST request to `/api/doubao/v1/chat/completions` with the following request body format:

```json
{
  "model": "doubao-pro",
  "messages": [
    {
      "role": "user",
      "content": "Generate an image of a cute cat."
    }
  ],
  "stream": false
}
```

### Signature Service

Send a POST request to `/api/doubao/v1/signature` with the following request body format:

```json
{
  "url": "https://api.doubao.com/chat/completions",
  "cookie": "your_cookie",
  "params": "your_params"
}
```

### Health Check

Send a GET request to `/api/doubao/v1/health` to check the service's health status.

## Contribution Guide

Code contributions and suggestions are welcome. Please submit a Pull Request or open an Issue.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Contact

For any questions, please contact [Bitsea19](https://gitee.com/Bitsea19).