package com.semantyca.aivox.service.chat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.semantyca.aivox.config.LLMConfig;
import com.semantyca.aivox.model.cnst.ChatType;
import com.semantyca.aivox.service.ListenerService;
import com.semantyca.aivox.service.chat.tools.AddToQueueTool;
import com.semantyca.aivox.service.chat.tools.AddToQueueToolHandler;
import com.semantyca.aivox.service.chat.tools.AudienceTool;
import com.semantyca.aivox.service.chat.tools.AudienceToolHandler;
import com.semantyca.aivox.service.chat.tools.GetStations;
import com.semantyca.aivox.service.chat.tools.GetStationsToolHandler;
import com.semantyca.aivox.service.chat.tools.ListGeneratorPromptsTool;
import com.semantyca.aivox.service.chat.tools.ListGeneratorPromptsToolHandler;
import com.semantyca.aivox.service.chat.tools.PerplexitySearchTool;
import com.semantyca.aivox.service.chat.tools.PerplexitySearchToolHandler;
import com.semantyca.aivox.service.chat.tools.RadioStationControlTool;
import com.semantyca.aivox.service.chat.tools.RadioStationControlToolHandler;
import com.semantyca.aivox.service.chat.tools.SearchBrandSoundFragments;
import com.semantyca.aivox.service.chat.tools.SearchBrandSoundFragmentsToolHandler;
import com.semantyca.aivox.service.live.AiHelperService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@ApplicationScoped
public class OwnerChatService extends ChatService {

    protected OwnerChatService() {
        super(null, null);
    }

    @Inject
    ListenerService listenerService;

    @Inject
    public OwnerChatService(LLMConfig config, AiHelperService aiHelperService) {
        super(config, aiHelperService);
    }

    @Override
    protected ChatType getChatType() {
        return ChatType.OWNER;
    }

    @Override
    protected List<Tool> getAvailableTools() {
        return List.of(
                GetStations.toTool(),
                SearchBrandSoundFragments.toTool(),
                AddToQueueTool.toTool(),
                RadioStationControlTool.toTool(),
                PerplexitySearchTool.toTool(),
                AudienceTool.toTool(),
                ListGeneratorPromptsTool.toTool()
        );
    }

    @Override
    protected MessageCreateParams buildMessageCreateParams(String renderedPrompt, List<MessageParam> history) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .maxTokens(1024L)
                .system(renderedPrompt)
                .messages(history)
                .model(Model.CLAUDE_HAIKU_4_5_20251001);
        
        for (Tool tool : getAvailableTools()) {
            builder.addTool(tool);
        }
        
        return builder.build();
    }

    @Override
    protected Uni<Void> handleToolCall(ToolUseBlock toolUse,
                                      Consumer<String> chunkHandler,
                                      Consumer<String> completionHandler,
                                      String connectionId,
                                      String brandName,
                                      long userId,
                                      List<MessageParam> conversationHistory) {

        Map<String, JsonValue> inputMap = extractInputMap(toolUse);
        Function<MessageCreateParams, Uni<Void>> streamFn =
                createStreamFunction(chunkHandler, completionHandler, connectionId, brandName, userId);

        if ("get_stations".equals(toolUse.name())) {
            return GetStationsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("search_brand_sound_fragments".equals(toolUse.name())) {
            return SearchBrandSoundFragmentsToolHandler.handle(
                    toolUse, inputMap, aiHelperService, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("add_to_queue".equals(toolUse.name())) {
            String djVoiceId = assistantNameByConnectionId.get(connectionId + "_voice");
            return AddToQueueToolHandler.handle(
                    toolUse, inputMap, queueService, aiHelperService, elevenLabsClient, config, djVoiceId, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("control_station".equals(toolUse.name())) {
            return RadioStationControlToolHandler.handle(
                    toolUse, inputMap, radioService, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("perplexity_search".equals(toolUse.name())) {
            return PerplexitySearchToolHandler.handle(
                    toolUse, inputMap, perplexitySearchHelper, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("listener".equals(toolUse.name())) {
            return AudienceToolHandler.handle(
                    toolUse, inputMap, listenerService, brandName, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else if ("list_generator_prompts".equals(toolUse.name())) {
            return ListGeneratorPromptsToolHandler.handle(
                    toolUse, inputMap, promptService, chunkHandler, connectionId, conversationHistory, followUpPrompt, streamFn
            );
        } else {
            return Uni.createFrom().failure(new IllegalArgumentException("Unknown tool: " + toolUse.name()));
        }
    }
}
