package com.semantyca.aivox.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class AudioFile {
    private byte[] data;
    private String format;
    private UUID songId;
}
