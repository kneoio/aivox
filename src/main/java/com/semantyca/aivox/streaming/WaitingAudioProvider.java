package com.semantyca.aivox.streaming;

import com.semantyca.aivox.service.manipulation.AudioSegmentationService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class WaitingAudioProvider {

    private static final Logger LOGGER = Logger.getLogger(WaitingAudioProvider.class);
    private static final String WAITING_AUDIO_RESOURCE = "audio/Waiting_State.wav";

    @Inject
    AudioSegmentationService segmentationService;

    private UUID waitingSongId;
    private Map<Long, List<HlsSegment>> originalWaitingSegments = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(WAITING_AUDIO_RESOURCE);
            if (resourceStream == null) {
                LOGGER.warn("Waiting audio not found in resources: " + WAITING_AUDIO_RESOURCE);
                return;
            }

            Path tempWaitingFile = Files.createTempFile("waiting_state_", ".wav");
            Files.copy(resourceStream, tempWaitingFile, StandardCopyOption.REPLACE_EXISTING);
            resourceStream.close();

            waitingSongId = UUID.randomUUID();
            SongMetadata waitingMetadata = new SongMetadata(waitingSongId, "Waiting...", "Station");

            // Use FFmpeg to properly segment the audio
            segmentationService.slice(waitingMetadata, tempWaitingFile, List.of(128000L, 64000L))
                    .subscribe().with(
                            segments -> {
                                if (segments.isEmpty()) {
                                    LOGGER.warn("Failed to slice waiting audio");
                                    return;
                                }

                                for (Map.Entry<Long, ConcurrentLinkedQueue<HlsSegment>> entry : segments.entrySet()) {
                                    List<HlsSegment> segmentList = new ArrayList<>(entry.getValue());
                                    originalWaitingSegments.put(entry.getKey(), segmentList);
                                }

                                initialized = true;
                                LOGGER.info("Waiting audio initialized with " + segments.get(128000L).size() + " segments per bitrate");

                                // Clean up temp file
                                try {
                                    Files.deleteIfExists(tempWaitingFile);
                                } catch (Exception e) {
                                    LOGGER.warn("Failed to delete temp waiting file", e);
                                }
                            },
                            error -> LOGGER.error("Error slicing waiting audio: " + error.getMessage(), error)
                    );
        } catch (Exception e) {
            LOGGER.error("Failed to initialize waiting audio: " + e.getMessage(), e);
        }
    }

    public Uni<LiveSoundFragment> createWaitingFragment() {
        if (!initialized || originalWaitingSegments.isEmpty()) {
            LOGGER.warn("Waiting audio not initialized, returning null");
            return Uni.createFrom().nullItem();
        }

        return Uni.createFrom().item(() -> {
            LiveSoundFragment fragment = new LiveSoundFragment();
            fragment.setSoundFragmentId(waitingSongId);
            fragment.setMetadata(new SongMetadata(waitingSongId, "Waiting...", "Station"));
            fragment.setPriority(999);

            Map<Long, ConcurrentLinkedQueue<HlsSegment>> clonedSegments = new ConcurrentHashMap<>();

            for (Map.Entry<Long, List<HlsSegment>> entry : originalWaitingSegments.entrySet()) {
                ConcurrentLinkedQueue<HlsSegment> queue = new ConcurrentLinkedQueue<>();

                for (HlsSegment originalSegment : entry.getValue()) {
                    HlsSegment clonedSegment = new HlsSegment();
                    clonedSegment.setSequence(originalSegment.getSequence());
                    clonedSegment.setDuration(originalSegment.getDuration());
                    clonedSegment.setData(originalSegment.getData());
                    clonedSegment.setBitrate(originalSegment.getBitrate());
                    clonedSegment.setSongMetadata(fragment.getMetadata());
                    clonedSegment.setFirstSegmentOfFragment(originalSegment.isFirstSegmentOfFragment());

                    queue.offer(clonedSegment);
                }

                clonedSegments.put(entry.getKey(), queue);
            }

            fragment.setSegments(clonedSegments);
            return fragment;
        });
    }

    public boolean isWaitingAudioAvailable() {
        return initialized && !originalWaitingSegments.isEmpty();
    }
}