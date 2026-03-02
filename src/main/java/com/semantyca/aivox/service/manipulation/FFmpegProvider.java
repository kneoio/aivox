package com.semantyca.aivox.service.manipulation;

import com.semantyca.aivox.config.AivoxConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;

import java.io.IOException;

@ApplicationScoped
public class FFmpegProvider {
    @Inject
    AivoxConfig config;
    
    private FFmpeg ffmpeg;
    private FFprobe ffprobe;

    @PostConstruct
    void init() throws IOException {
        this.ffmpeg = new FFmpeg(config.ffmpeg().path());
        this.ffprobe = new FFprobe(config.ffprobe().path());
    }

    public FFmpeg getFFmpeg() {
        return ffmpeg;
    }

    public FFprobe getFFprobe() {
        return ffprobe;
    }
}
