package com.semantyca.aivox.dto.queue;

import com.semantyca.core.model.cnst.SSEProgressStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SSEProgressDTO {
    private String id;
    private String name;
    private SSEProgressStatus status;
    private String errorMessage;
}