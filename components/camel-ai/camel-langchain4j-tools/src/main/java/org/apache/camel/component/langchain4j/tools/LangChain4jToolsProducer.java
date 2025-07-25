/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.langchain4j.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import org.apache.camel.Exchange;
import org.apache.camel.InvalidPayloadException;
import org.apache.camel.component.langchain4j.tools.spec.CamelToolExecutorCache;
import org.apache.camel.component.langchain4j.tools.spec.CamelToolSpecification;
import org.apache.camel.support.DefaultProducer;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LangChain4jToolsProducer extends DefaultProducer {
    private static final Logger LOG = LoggerFactory.getLogger(LangChain4jToolsProducer.class);

    private final LangChain4jToolsEndpoint endpoint;

    private ChatModel chatModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public LangChain4jToolsProducer(LangChain4jToolsEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        processMultipleMessages(exchange);
    }

    @SuppressWarnings("unchecked")
    private void processMultipleMessages(Exchange exchange) throws InvalidPayloadException {
        List<ChatMessage> messages = exchange.getIn().getMandatoryBody(List.class);

        final String response = toolsChat(messages, exchange);
        populateResponse(response, exchange);
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        this.chatModel = this.endpoint.getConfiguration().getChatModel();
        ObjectHelper.notNull(chatModel, "chatModel");
    }

    private void populateResponse(String response, Exchange exchange) {
        exchange.getMessage().setBody(response);
    }

    private boolean isMatch(String[] tags, Map.Entry<String, Set<CamelToolSpecification>> entry) {
        for (String tag : tags) {
            if (entry.getKey().equals(tag)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Send a ChatMessage
     *
     * @param  chatMessages
     * @return
     */
    private String toolsChat(List<ChatMessage> chatMessages, Exchange exchange) {
        final CamelToolExecutorCache toolCache = CamelToolExecutorCache.getInstance();

        final ToolPair toolPair = computeCandidates(toolCache, exchange);
        if (toolPair == null) {
            return null;
        }

        // First talk to the model to get the tools to be called
        int i = 0;
        do {
            LOG.debug("Starting iteration {}", i);
            final Response<AiMessage> response = chatWithLLM(chatMessages, toolPair, exchange);
            if (isDoneExecuting(response)) {
                return extractAiResponse(response);
            }

            // Only invoke the tools ... the response will be computed on the next loop
            invokeTools(chatMessages, exchange, response, toolPair);
            LOG.debug("Finished iteration {}", i);
            i++;
        } while (true);
    }

    private boolean isDoneExecuting(Response<AiMessage> response) {
        if (!response.content().hasToolExecutionRequests()) {
            LOG.info("Finished executing tools because of there are no more execution requests");
            return true;
        }

        if (response.finishReason() != null) {
            LOG.info("Finished executing tools because of {}", response.finishReason());

            if (response.finishReason() == FinishReason.STOP) {
                return true;
            }
        }

        return false;
    }

    private void invokeTools(
            List<ChatMessage> chatMessages, Exchange exchange, Response<AiMessage> response, ToolPair toolPair) {
        int i = 0;
        List<ToolExecutionRequest> toolExecutionRequests = response.content().toolExecutionRequests();
        for (ToolExecutionRequest toolExecutionRequest : toolExecutionRequests) {
            String toolName = toolExecutionRequest.name();
            LOG.info("Invoking tool {} ({}) of {}", i, toolName, toolExecutionRequests.size());

            final CamelToolSpecification camelToolSpecification = toolPair.callableTools().stream()
                    .filter(c -> c.getToolSpecification().name().equals(toolName)).findFirst().get();

            try {
                // Map Json to Header
                JsonNode jsonNode = objectMapper.readValue(toolExecutionRequest.arguments(), JsonNode.class);

                jsonNode.fieldNames()
                        .forEachRemaining(name -> exchange.getMessage().setHeader(name, jsonNode.get(name)));

                // Execute the consumer route

                camelToolSpecification.getConsumer().getProcessor().process(exchange);
                i++;
            } catch (Exception e) {
                // How to handle this exception?
                exchange.setException(e);
            }

            chatMessages.add(new ToolExecutionResultMessage(
                    toolExecutionRequest.id(),
                    toolExecutionRequest.name(),
                    exchange.getIn().getBody(String.class)));
        }
    }

    /**
     * This talks with the LLM to, passing the list of tools, and expects a response listing one ore more tools to be
     * called
     *
     * @param  chatMessages the input chat messages
     * @param  toolPair     the toolPair containing the available tools to be called
     * @return              the response provided by the model
     */
    private Response<AiMessage> chatWithLLM(List<ChatMessage> chatMessages, ToolPair toolPair, Exchange exchange) {

        ChatRequest.Builder requestBuilder = ChatRequest.builder()
                .messages(chatMessages);

        // Add tools if available
        if (toolPair != null && toolPair.toolSpecifications() != null) {
            requestBuilder.toolSpecifications(toolPair.toolSpecifications());
        }

        // build request
        ChatRequest chatRequest = requestBuilder.build();

        // generate response
        ChatResponse chatResponse = this.chatModel.chat(chatRequest);

        // Convert ChatResponse to Response<AiMessage> for compatibility
        AiMessage aiMessage = chatResponse.aiMessage();
        Response<AiMessage> response = Response.from(aiMessage);

        if (!response.content().hasToolExecutionRequests()) {
            exchange.getMessage().setHeader(LangChain4jTools.NO_TOOLS_CALLED_HEADER, Boolean.TRUE);
            return response;
        }

        chatMessages.add(response.content());
        return response;
    }

    /**
     * This method traverses all tag sets to search for the tools that match the tags for the current endpoint.
     *
     * @param  toolCache the global cache of tools
     * @return           It returns a record containing both the specification, and the {@link CamelToolSpecification}
     *                   that can be used to call the endpoints.
     */
    private ToolPair computeCandidates(CamelToolExecutorCache toolCache, Exchange exchange) {
        final List<ToolSpecification> toolSpecifications = new ArrayList<>();
        final List<CamelToolSpecification> callableTools = new ArrayList<>();

        final Map<String, Set<CamelToolSpecification>> tools = toolCache.getTools();
        String[] tags = TagsHelper.splitTags(endpoint.getTags());
        for (var entry : tools.entrySet()) {
            if (isMatch(tags, entry)) {
                final List<CamelToolSpecification> callablesForTag = entry.getValue().stream()
                        .toList();

                callableTools.addAll(callablesForTag);

                final List<ToolSpecification> toolsForTag = entry.getValue().stream()
                        .map(CamelToolSpecification::getToolSpecification)
                        .toList();

                toolSpecifications.addAll(toolsForTag);
            }
        }

        if (toolSpecifications.isEmpty()) {
            exchange.getMessage().setHeader(LangChain4jTools.NO_TOOLS_CALLED_HEADER, Boolean.TRUE);
            return null;
        }

        return new ToolPair(toolSpecifications, callableTools);
    }

    /**
     * The pair of tools specifications that the Camel tools (i.e.: routes) that can be called for that set
     *
     * @param toolSpecifications
     * @param callableTools
     */
    private record ToolPair(List<ToolSpecification> toolSpecifications, List<CamelToolSpecification> callableTools) {
    }

    private String extractAiResponse(Response<AiMessage> response) {
        AiMessage message = response.content();
        return message == null ? null : message.text();
    }

}
