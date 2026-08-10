# Instructions for Java Code Refactoring: `ai-customer-enquiry-triage`

Please update the existing Java project **`ai-customer-enquiry-triage`** to implement dynamic prompt loading and modify the HTTP payload sent to the AI server according to the following specification:

## Requirements

1. **Prompt Loading on Application Startup:**
   - Locate and load the prompt template file `TriagePipelinePromptv1` located at:
     `src/main/resources/com/sib/triage/ai/prompts/TriagePipelinePromptv1` (or relative to the package path in classpath: `com/sib/triage/ai/prompts/TriagePipelinePromptv1`).
   - Load this file into memory **once** during application startup (e.g., using `@PostConstruct`, a static initializer, or a Spring Bean dependency) to avoid unnecessary file I/O operations per request.

2. **Message Content Replacement:**
   - When a Customer Enquiry request is pulled from MQ, extract the text from the request's `message` field.
   - Dynamically replace the placeholder string `{INCOMING_TEXT}` inside the loaded prompt template with the extracted `message` content.

3. **HTTP Post Payload Modification:**
   - Locate the `TriagePipeline` class and its `process` method.
   - Modify the code responsible for sending the HTTP POST request to the AI server.
   - Construct the outgoing JSON body using the following structure, dynamically injecting the updated prompt into `content`:

```json
{
  "model": "/models/qwen2.5-3b-instruct-q4_k_m.gguf",
  "messages": [     
    {
      "role": "user",
      "content": "<REPLACE PROMPT WITH>"
    }
  ],
  "temperature": 0.1,
  "max_tokens": 200,
  "top_p": 0.9,
  "stream": false
}
```

4. **Code Quality & Guidelines:**
   - Use standard JSON mapping libraries (such as Jackson `ObjectMapper` or Gson) to properly serialize the JSON body to ensure quotes and special characters in the prompt are properly escaped.
   - Ensure adequate exception handling for missing resource files or file reading errors during startup.
   - Provide the complete modified Java source files (or explicit diffs) for affected classes.
   - Update README.md file with required details