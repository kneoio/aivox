package com.semantyca.aivox.model.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.aivox.model.cnst.ChatType;
import com.semantyca.core.model.cnst.MessageType;
import io.kneo.core.model.DataEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage extends DataEntity<UUID> {
    private Long userId;
    private String brandName;
    private ChatType chatType;
    private MessageType messageType;
    private String username;
    private String content;
    private String connectionId;
    private LocalDateTime timestamp;
    private LocalDateTime summarizedAt;
    private UUID summaryId;
}
