package com.semantyca.aivox.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "aivox")
public interface AivoxConfig {

    @WithDefault("http://localhost:8080")
    String host();

    @WithName("agent.api-key")
    String agentApiKey();

    @WithName("agent.url")
    @WithDefault("http://localhost:38799")
    String getAgentUrl();

    @WithName("jesoos.url")
    String getJesoosUrl();

    Path path();

    Ffmpeg ffmpeg();

    Ffprobe ffprobe();

    Segmentation segmentation();

    @WithName("station.whitelist")
    Optional<List<String>> stationWhitelist();

    @WithName("debug.token")
    @WithDefault("debug-token-2026-secret")
    String debugToken();

    @WithName("merged")
    @WithDefault("merged_tmp")
    String getPathForMerged();

    @WithName("external.upload.files.path")
    @WithDefault("external_uploads")
    String getPathForExternalServiceUploads();

    String getPathUploads();

    String getSegmentationOutputDir();

    interface Path {
        @WithDefault("uploads")
        String uploads();

        @WithDefault("temp")
        String temp();
    }

    interface Ffmpeg {
        @WithDefault("/usr/bin/ffmpeg")
        String path();
    }

    interface Ffprobe {
        @WithDefault("/usr/bin/ffprobe")
        String path();
    }

    interface Segmentation {
        Output output();

        interface Output {
            @WithDefault("temp/segments")
            String dir();
        }
    }


}