package com.semantyca.aivox.model.aiagent;

import com.semantyca.aivox.model.cnst.TTSEngineType;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Voice {
    private String id;
    private String name;
    private TTSEngineType engineType;

    public Voice() {}

    public Voice(String id, String name) {
        this.id = id;
        this.name = name;
    }
}