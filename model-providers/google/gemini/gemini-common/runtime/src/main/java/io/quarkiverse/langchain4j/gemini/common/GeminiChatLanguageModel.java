package io.quarkiverse.langchain4j.gemini.common;

import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequest;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponse;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

public abstract class GeminiChatLanguageModel implements ChatLanguageModel {
    private static final Logger log = Logger.getLogger(GeminiChatLanguageModel.class);

    private final String modelId;
    private final Double temperature;
    private final Integer maxOutputTokens;
    private final Integer topK;
    private final Double topP;
    private final ResponseFormat responseFormat;
    private final List<ChatModelListener> listeners;

    public GeminiChatLanguageModel(String modelId, Double temperature, Integer maxOutputTokens, Integer topK,
            Double topP, ResponseFormat responseFormat, List<ChatModelListener> listeners) {
        this.modelId = modelId;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.topK = topK;
        this.topP = topP;
        this.responseFormat = responseFormat;
        this.listeners = listeners;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        Set<Capability> capabilities = new HashSet<>();
        // when response format is not null, it's JSON, either application/json or text/x.enum
        if (this.responseFormat != null && ResponseFormatType.JSON.equals(this.responseFormat.type())) {
            capabilities.add(RESPONSE_FORMAT_JSON_SCHEMA);
        } else if (responseFormat == null) { // when the responseFormat is not set, we default to supporting JSON as Google's models support this
            capabilities.add(RESPONSE_FORMAT_JSON_SCHEMA);
        }
        return capabilities;
    }

    @Override
    public dev.langchain4j.model.chat.response.ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest chatRequest) {
        ChatRequestParameters requestParameters = chatRequest.parameters();
        ResponseFormat effectiveResponseFormat = getOrDefault(requestParameters.responseFormat(), responseFormat);
        GenerationConfig generationConfig = GenerationConfig.builder()
                .maxOutputTokens(getOrDefault(requestParameters.maxOutputTokens(), this.maxOutputTokens))
                .responseMimeType(computeMimeType(effectiveResponseFormat))
                .responseSchema(effectiveResponseFormat != null
                        ? SchemaMapper.fromJsonSchemaToSchema(effectiveResponseFormat.jsonSchema())
                        : null)
                .stopSequences(requestParameters.stopSequences())
                .temperature(getOrDefault(requestParameters.temperature(), this.temperature))
                .topK(getOrDefault(requestParameters.topK(), this.topK))
                .topP(getOrDefault(requestParameters.topP(), this.topP))
                .build();
        GenerateContentRequest request = ContentMapper.map(chatRequest.messages(), chatRequest.toolSpecifications(),
                generationConfig);

        ChatModelRequest modelListenerRequest = createModelListenerRequest(request, chatRequest.messages(),
                chatRequest.toolSpecifications());
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        ChatModelRequestContext requestContext = new ChatModelRequestContext(modelListenerRequest, attributes);
        listeners.forEach(listener -> {
            try {
                listener.onRequest(requestContext);
            } catch (Exception e) {
                log.warn("Exception while calling model listener", e);
            }
        });

        try {
            GenerateContentResponse response = generateContext(request);

            String text = GenerateContentResponseHandler.getText(response);
            List<ToolExecutionRequest> toolExecutionRequests = GenerateContentResponseHandler
                    .getToolExecutionRequests(response);
            AiMessage aiMessage = toolExecutionRequests.isEmpty()
                    ? aiMessage(text)
                    : aiMessage(text, toolExecutionRequests);

            final TokenUsage tokenUsage = GenerateContentResponseHandler.getTokenUsage(response.usageMetadata());
            final FinishReason finishReason = FinishReasonMapper.map(GenerateContentResponseHandler.getFinishReason(response));
            final Response<AiMessage> aiMessageResponse = Response.from(aiMessage, tokenUsage);

            ChatModelResponse modelListenerResponse = createModelListenerResponse(
                    null,
                    modelId,
                    aiMessageResponse);
            ChatModelResponseContext responseContext = new ChatModelResponseContext(
                    modelListenerResponse,
                    modelListenerRequest,
                    attributes);
            listeners.forEach(listener -> {
                try {
                    listener.onResponse(responseContext);
                } catch (Exception e) {
                    log.warn("Exception while calling model listener", e);
                }
            });

            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(GenerateContentResponseHandler.getTokenUsage(response.usageMetadata()))
                    .finishReason(FinishReasonMapper.map(GenerateContentResponseHandler.getFinishReason(response)))
                    .build();
        } catch (RuntimeException e) {
            ChatModelErrorContext errorContext = new ChatModelErrorContext(
                    e,
                    modelListenerRequest,
                    null,
                    attributes);

            listeners.forEach(listener -> {
                try {
                    listener.onError(errorContext);
                } catch (Exception e2) {
                    log.warn("Exception while calling model listener", e2);
                }
            });

            throw e;
        }
    }

    protected abstract GenerateContentResponse generateContext(GenerateContentRequest request);

    private ChatModelRequest createModelListenerRequest(GenerateContentRequest request,
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications) {
        var builder = ChatModelRequest.builder()
                .model(modelId)
                .messages(messages)
                .toolSpecifications(toolSpecifications)
                .temperature(temperature)
                .topP(topP)
                .maxTokens(maxOutputTokens);
        return builder.build();
    }

    private ChatModelResponse createModelListenerResponse(String responseId,
            String responseModel,
            Response<AiMessage> response) {
        if (response == null) {
            return null;
        }

        return ChatModelResponse.builder()
                .id(responseId)
                .model(responseModel)
                .tokenUsage(response.tokenUsage())
                .finishReason(response.finishReason())
                .aiMessage(response.content())
                .build();
    }

    private String computeMimeType(ResponseFormat responseFormat) {
        if (responseFormat == null || ResponseFormatType.TEXT.equals(responseFormat.type())) {
            return "text/plain";
        }

        if (ResponseFormatType.JSON.equals(responseFormat.type()) &&
                responseFormat.jsonSchema() != null &&
                responseFormat.jsonSchema().rootElement() != null &&
                responseFormat.jsonSchema().rootElement() instanceof JsonEnumSchema) {
            return "text/x.enum";
        }

        return "application/json";
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        var chatResponse = chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecifications)
                .build());

        return Response.from(
                chatResponse.aiMessage(),
                chatResponse.tokenUsage(),
                chatResponse.finishReason());

    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, Collections.emptyList());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return generate(messages,
                toolSpecification != null ? Collections.singletonList(toolSpecification) : Collections.emptyList());
    }
}
