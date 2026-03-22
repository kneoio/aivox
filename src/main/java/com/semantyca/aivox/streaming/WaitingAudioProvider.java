package com.semantyca.aivox.streaming;

import com.semantyca.aivox.service.manipulation.AudioSegmentationService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

@ApplicationScoped
public class WaitingAudioProvider {

    private static final Logger LOGGER = Logger.getLogger(WaitingAudioProvider.class);
    private static final String WAITING_AUDIO_FOLDER = "audio/waiting";
    private static final Random RANDOM = new Random();

    @Inject
    AudioSegmentationService segmentationService;

    private final List<WaitingAudioEntry> waitingAudioEntries = new ArrayList<>();
    private volatile boolean initialized = false;

    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            List<String> audioFiles = loadWaitingAudioFiles();
            if (audioFiles.isEmpty()) {
                LOGGER.warn("No waiting audio files found in: " + WAITING_AUDIO_FOLDER);
                return;
            }

            LOGGER.info("Found " + audioFiles.size() + " waiting audio file(s)");
            int processedCount = 0;

            for (String audioFile : audioFiles) {
                try {
                    String resourcePath = WAITING_AUDIO_FOLDER + "/" + audioFile;
                    InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
                    if (resourceStream == null) {
                        LOGGER.warn("Could not load: " + resourcePath);
                        continue;
                    }

                    Path tempWaitingFile = Files.createTempFile("waiting_", ".wav");
                    Files.copy(resourceStream, tempWaitingFile, StandardCopyOption.REPLACE_EXISTING);
                    resourceStream.close();

                    UUID songId = UUID.randomUUID();
                    SongMetadata waitingMetadata = new SongMetadata(songId, "Waiting...", "Station");

                    Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments = segmentationService
                            .slice(waitingMetadata, tempWaitingFile, List.of(128000L, 64000L))
                            .await().indefinitely();

                    if (segments.isEmpty()) {
                        LOGGER.warn("Failed to slice: " + audioFile);
                        Files.deleteIfExists(tempWaitingFile);
                        continue;
                    }

                    Map<Long, List<HlsSegment>> segmentMap = new ConcurrentHashMap<>();
                    for (Map.Entry<Long, ConcurrentLinkedQueue<HlsSegment>> entry : segments.entrySet()) {
                        segmentMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                    }

                    WaitingAudioEntry entry = new WaitingAudioEntry(songId, audioFile, segmentMap);
                    waitingAudioEntries.add(entry);
                    processedCount++;

                    LOGGER.info("Loaded waiting audio: " + audioFile + " (" + segments.get(128000L).size() + " segments)");

                    Files.deleteIfExists(tempWaitingFile);
                } catch (Exception e) {
                    LOGGER.error("Error processing waiting audio file: " + audioFile, e);
                }
            }

            if (processedCount > 0) {
                initialized = true;
                LOGGER.info("Waiting audio initialized with " + processedCount + " file(s)");
            } else {
                LOGGER.warn("No waiting audio files were successfully processed");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize waiting audio: " + e.getMessage(), e);
        }
    }

    private List<String> loadWaitingAudioFiles() {
        List<String> audioFiles = new ArrayList<>();
        try {
            URI uri = getClass().getClassLoader().getResource(WAITING_AUDIO_FOLDER).toURI();
            Path folderPath;

            if (uri.getScheme().equals("jar")) {
                FileSystem fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                folderPath = fileSystem.getPath(WAITING_AUDIO_FOLDER);
            } else {
                folderPath = Paths.get(uri);
            }

            try (Stream<Path> paths = Files.walk(folderPath, 1)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".wav"))
                        .forEach(p -> audioFiles.add(p.getFileName().toString()));
            }
        } catch (IOException | URISyntaxException e) {
            LOGGER.error("Error loading waiting audio files from folder: " + WAITING_AUDIO_FOLDER, e);
        } catch (NullPointerException e) {
            LOGGER.warn("Waiting audio folder not found: " + WAITING_AUDIO_FOLDER);
        }
        return audioFiles;
    }

    public Uni<LiveSoundFragment> createWaitingFragment() {
        if (!initialized || waitingAudioEntries.isEmpty()) {
            LOGGER.warn("Waiting audio not initialized, returning null");
            return Uni.createFrom().nullItem();
        }

        return Uni.createFrom().item(() -> {
            WaitingAudioEntry selectedEntry = waitingAudioEntries.get(RANDOM.nextInt(waitingAudioEntries.size()));
            
            LiveSoundFragment fragment = new LiveSoundFragment();
            fragment.setSoundFragmentId(selectedEntry.songId);
            fragment.setMetadata(new SongMetadata(selectedEntry.songId, "Waiting...", "Station"));
            fragment.setPriority(999);

            Map<Long, ConcurrentLinkedQueue<HlsSegment>> clonedSegments = new ConcurrentHashMap<>();

            for (Map.Entry<Long, List<HlsSegment>> entry : selectedEntry.segments.entrySet()) {
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
            LOGGER.debug("Selected waiting audio: " + selectedEntry.fileName);
            return fragment;
        });
    }

    public boolean isWaitingAudioAvailable() {
        return initialized && !waitingAudioEntries.isEmpty();
    }

    private record WaitingAudioEntry(UUID songId, String fileName, Map<Long, List<HlsSegment>> segments) {}
}