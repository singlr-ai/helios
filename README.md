# Helios

Helios is a lightweight Java client for interacting with OpenAI chat completion-compatible REST APIs. The client was generated from the Open AI's [OpenAPI spec](https://github.com/openai/openai-openapi) on 2024-11-19.

## Overview
- **Zero dependencies**: Helios avoids external dependencies – with one exception: Jackson. After all, trying to handle JSON without Jackson is like attempting stand-up comedy without a punchline.
- **Straightforward**: Built with minimal abstractions to keep the client simple and easy to integrate into your projects.

## Acknowledgements
We recognize other Java clients, such as LangChain4j, which offer extensive functionality. Helios was originally developed to meet our internal needs, and we are sharing it with the community in hopes that others might use it and help refine it further.

### Features
- Chat API
- Supports audio uploads
- Supports function calling

Please see https://platform.openai.com/docs/api-reference for more details.

## Using the Client

Set the `OPENAI_API_KEY` environment variable to your OpenAI API key. You can also pass the API key as a parameter to the client builder.

```java
var client = OpenAiClient.newBuilder().build();

var completionRequest = CompletionRequest.newBuilder()
  .withModel(ModelName.GPT_4O_MINI)
  .withMessages(List.of(
      ChatCompletionMessage.sys("You are a helpful assistant."),
      ChatCompletionMessage.user("How are you doing today?")
  ))
  .build();

var result = client.chat.createCompletion(completionRequest);
```

## Building the Project
Install JDK 21+ and Maven. Then run:

```bash
mvn package
```

## Running the Tests
To run the tests, set the `OPENAI_API_KEY` environment variable to your OpenAI API key. Then run:

```bash
mvn test
```

## License
This project is released under the [MIT License](LICENSE).
```