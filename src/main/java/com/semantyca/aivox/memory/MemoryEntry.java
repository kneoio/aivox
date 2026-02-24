package com.semantyca.aivox.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.Instant;

@Data
@AllArgsConstructor
public class MemoryEntry {
    private String text;
    private Instant timestamp;
}
