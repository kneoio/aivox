package com.semantyca.aivox.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "aivox")
public interface AivoxConfig {
    
    @WithName("host")
    @WithDefault("http://localhost:8080")
    String getHost();
    
    @WithName("path.uploads")
    @WithDefault("uploads")
    String getPathUploads();
    
    @WithName("path.temp")
    @WithDefault("temp")
    String getPathTemp();

    @WithName("station.whitelist")
    Optional<List<String>> getStationWhitelist();
    
    @WithName("ffmpeg.path")
    @WithDefault("/usr/bin/ffmpeg")
    String getFfmpegPath();
    
    @WithName("ffprobe.path")
    @WithDefault("/usr/bin/ffprobe")
    String getFfprobePath();
    
    @WithName("segmentation.output.dir")
    @WithDefault("temp/segments")
    String getSegmentationOutputDir();
}
