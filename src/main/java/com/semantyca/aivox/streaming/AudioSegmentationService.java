package com.semantyca.aivox.streaming;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class AudioSegmentationService {
    
    private static final Logger LOGGER = Logger.getLogger(AudioSegmentationService.class);
    
    @ConfigProperty(name = "ffmpeg.path", defaultValue = "ffmpeg")
    String ffmpegPath;
    
    @ConfigProperty(name = "ffprobe.path", defaultValue = "ffprobe")
    String ffprobePath;
    
    @ConfigProperty(name = "segmentation.output.dir", defaultValue = "/tmp/aivox/segments")
    String outputDir;
    
    @ConfigProperty(name = "hls.segment.duration", defaultValue = "2")
    int segmentDuration;
    
    @ConfigProperty(name = "hls.bitrates", defaultValue = "128000,256000")
    List<Long> bitrates;
    
    private FFmpeg ffmpeg;
    private FFprobe ffprobe;
    
    public void init() {
        try {
            this.ffmpeg = new FFmpeg(ffmpegPath);
            this.ffprobe = new FFprobe(ffprobePath);
            
            // Create output directory if it doesn't exist
            Files.createDirectories(Paths.get(outputDir));
        } catch (IOException e) {
            LOGGER.error("Failed to initialize FFmpeg", e);
            throw new RuntimeException("Failed to initialize FFmpeg", e);
        }
    }
    
    public Uni<Map<Long, ConcurrentLinkedQueue<HlsSegment>>> slice(
            Path audioFile,
            SongMetadata metadata,
            List<Long> targetBitrates) {
        
        return Uni.createFrom().item(() -> {
            try {
                Map<Long, ConcurrentLinkedQueue<HlsSegment>> result = new ConcurrentHashMap<>();
                
                for (long bitrate : targetBitrates) {
                    List<Path> segmentFiles = generateSegments(audioFile, bitrate, metadata);
                    ConcurrentLinkedQueue<HlsSegment> segments = new ConcurrentLinkedQueue<>();
                    
                    for (int i = 0; i < segmentFiles.size(); i++) {
                        Path segmentFile = segmentFiles.get(i);
                        byte[] segmentData = Files.readAllBytes(segmentFile);
                        
                        HlsSegment segment = new HlsSegment(
                            i,
                            segmentData,
                            segmentDuration,
                            bitrate,
                            metadata,
                            i == 0  // First segment of fragment
                        );
                        
                        segments.offer(segment);
                    }
                    
                    result.put(bitrate, segments);
                }
                
                return result;
            } catch (Exception e) {
                LOGGER.error("Failed to slice audio file", e);
                throw new RuntimeException("Failed to slice audio file", e);
            }
        });
    }
    
    private List<Path> generateSegments(Path audioFile, long bitrate, SongMetadata metadata) 
            throws IOException {
        
        String brandPrefix = metadata.getSongId().toString();
        Path brandOutputDir = Paths.get(outputDir, brandPrefix);
        Files.createDirectories(brandOutputDir);
        
        String outputPattern = brandOutputDir.resolve("%03d.ts").toString();
        
        FFmpegBuilder builder = new FFmpegBuilder()
            .setInput(audioFile.toString())
            .addOutput(outputPattern)
            .setFormat("segment")
            .addExtraArgs("-segment_time", String.valueOf(segmentDuration))
            .addExtraArgs("-segment_format", "mpegts")
            .setAudioCodec("aac")
            .setAudioBitRate(bitrate)
            .setAudioChannels(2)
            .setAudioSampleRate(44100)
            .done();
        
        FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);
        executor.createJob(builder).run();
        
        // List generated segments
        List<Path> segments = new ArrayList<>();
        File dir = brandOutputDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".ts"));
        
        if (files != null) {
            for (File file : files) {
                segments.add(file.toPath());
            }
        }
        
        segments.sort((a, b) -> {
            String aNum = a.getFileName().toString().replaceAll("\\D", "");
            String bNum = b.getFileName().toString().replaceAll("\\D", "");
            return Integer.compare(Integer.parseInt(aNum), Integer.parseInt(bNum));
        });
        
        return segments;
    }
    
    @jakarta.annotation.PostConstruct
    public void postConstruct() {
        init();
    }
}
