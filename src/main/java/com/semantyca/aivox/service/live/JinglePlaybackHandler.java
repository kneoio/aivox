package com.semantyca.aivox.service.live;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.dto.queue.AddToQueueDTO;
import com.semantyca.aivox.model.IStream;
import com.semantyca.aivox.model.stream.LiveScene;
import com.semantyca.aivox.model.stream.PendingSongEntry;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.aivox.service.AiAgentService;
import com.semantyca.aivox.service.manipulation.FFmpegProvider;
import com.semantyca.aivox.service.manipulation.mixing.AudioConcatenator;
import com.semantyca.aivox.service.manipulation.mixing.ConcatenationType;
import com.semantyca.aivox.service.manipulation.mixing.MergingType;
import com.semantyca.aivox.service.manipulation.mixing.handler.AudioMixingHandler;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.core.util.BrandLogger;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.service.exceptions.AudioMergeException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
public class JinglePlaybackHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(JinglePlaybackHandler.class);

    private final SoundFragmentService soundFragmentService;
    private final AivoxConfig broadcasterConfig;
    private final SoundFragmentRepository soundFragmentRepository;
    private final FFmpegProvider fFmpegProvider;
    private final AudioConcatenator audioConcatenator;
    private final AiAgentService aiAgentService;

    @Inject
    public JinglePlaybackHandler(
            SoundFragmentService soundFragmentService,
            AivoxConfig broadcasterConfig,
            SoundFragmentRepository soundFragmentRepository,
            FFmpegProvider fFmpegProvider,
            AudioConcatenator audioConcatenator,
            AiAgentService aiAgentService
    ) {
        this.soundFragmentService = soundFragmentService;
        this.broadcasterConfig = broadcasterConfig;
        this.soundFragmentRepository = soundFragmentRepository;
        this.fFmpegProvider = fFmpegProvider;
        this.audioConcatenator = audioConcatenator;
        this.aiAgentService = aiAgentService;
    }

    public void handleJinglePlayback(IStream stream, Scene scene, LiveScene liveScene, java.util.Set<UUID> fetchedSongsInScene) {
        LOGGER.info("Station '{}': Playing jingle instead of DJ intro (talkativity: {})",
                stream.getSlugName(), scene.getTalkativity());

        boolean useJingle = Math.random() < 0.7;

        if (useJingle) {
            handleJingleAndSong(stream, liveScene, fetchedSongsInScene);
        } else {
            handleTwoSongs(stream, liveScene, fetchedSongsInScene);
        }
    }

    private void handleJingleAndSong(IStream stream, LiveScene liveScene, java.util.Set<UUID> fetchedSongsInScene) {
        soundFragmentService.getByTypeAndBrand(PlaylistItemType.JINGLE, stream.getId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribe().with(
                        jingles -> {
                            if (jingles.isEmpty()) {
                                LOGGER.warn("Station '{}': No jingles available for playback", stream.getSlugName());
                                return;
                            }

                            List<PendingSongEntry> availableSongs = liveScene.getSongs().stream()
                                    .filter(entry -> !fetchedSongsInScene.contains(entry.getSoundFragment().getId()))
                                    .toList();

                            if (availableSongs.isEmpty()) {
                                LOGGER.warn("Station '{}': No unfetched songs available in scene for jingle playback", stream.getSlugName());
                                return;
                            }

                            SoundFragment selectedJingle = jingles.get(new Random().nextInt(jingles.size()));
                            SoundFragment selectedSong = availableSongs.get(new Random().nextInt(availableSongs.size())).getSoundFragment();
                            fetchedSongsInScene.add(selectedSong.getId());

                            LOGGER.info("Station '{}': Concatenating jingle '{}' with song '{}'",
                                    stream.getSlugName(), selectedJingle.getTitle(), selectedSong.getTitle());

                            AddToQueueDTO queueDTO = new AddToQueueDTO();
                            queueDTO.setMergingMethod(MergingType.FILLER_JINGLE);
                            queueDTO.setPriority(11); // less than dj agent(10)

                            Map<String, UUID> soundFragments = new HashMap<>();
                            soundFragments.put("song1", selectedJingle.getId());
                            soundFragments.put("song2", selectedSong.getId());
                            queueDTO.setSoundFragments(soundFragments);

                            concatenate(stream, queueDTO, "jingle + song");
                        },
                        failure -> LOGGER.error("Station '{}': Failed to fetch jingles: {}",
                                stream.getSlugName(), failure.getMessage(), failure)
                );
    }

    private void handleTwoSongs(IStream stream, LiveScene liveScene, java.util.Set<UUID> fetchedSongsInScene) {
        List<PendingSongEntry> availableSongs = liveScene.getSongs().stream()
                .filter(entry -> !fetchedSongsInScene.contains(entry.getSoundFragment().getId()))
                .toList();

        if (availableSongs.size() < 2) {
            LOGGER.warn("Station '{}': Not enough unfetched songs in scene for two-song playback", stream.getSlugName());
            return;
        }

        Uni.createFrom().item(availableSongs)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribe().with(
                        songs -> {
                            Random random = new Random();
                            int firstIndex = random.nextInt(songs.size());
                            int secondIndex;
                            do {
                                secondIndex = random.nextInt(songs.size());
                            } while (secondIndex == firstIndex && songs.size() > 1);

                            SoundFragment firstSong = songs.get(firstIndex).getSoundFragment();
                            SoundFragment secondSong = songs.get(secondIndex).getSoundFragment();
                            fetchedSongsInScene.add(firstSong.getId());
                            fetchedSongsInScene.add(secondSong.getId());

                            BrandLogger.logActivity(stream.getSlugName(), "handle_two_songs", " %s + %s", firstSong.getTitle(), secondSong.getTitle());

                            AddToQueueDTO queueDTO = new AddToQueueDTO();
                            queueDTO.setMergingMethod(MergingType.SONG_CROSSFADE_SONG);
                            queueDTO.setPriority(12);

                            Map<String, UUID> soundFragments = new HashMap<>();
                            soundFragments.put("song1", firstSong.getId());
                            soundFragments.put("song2", secondSong.getId());
                            queueDTO.setSoundFragments(soundFragments);

                            concatenate(stream, queueDTO, "song + crossfade + song");
                        },
                        failure -> LOGGER.error("Station '{}': Failed to process songs: {}",
                                stream.getSlugName(), failure.getMessage(), failure)
                );
    }

    private void concatenate(IStream stream, AddToQueueDTO queueDTO, String type) {
        try {
            AudioMixingHandler handler = new AudioMixingHandler(
                    broadcasterConfig,
                    soundFragmentRepository,
                    soundFragmentService,
                    audioConcatenator,
                    aiAgentService,
                    fFmpegProvider
            );

            handler.handleConcatenationAndFeed(stream, queueDTO, ConcatenationType.CROSSFADE)
                    .subscribe().with(
                            success -> LOGGER.info("Station '{}': Successfully queued {} concatenation",
                                    stream.getSlugName(), type),
                            failure -> LOGGER.error("Station '{}': Failed to concatenate {}: {}",
                                    stream.getSlugName(), type, failure.getMessage(), failure)
                    );
        } catch (IOException | AudioMergeException e) {
            LOGGER.error("Station '{}': Failed to create AudioMixingHandler: {}",
                    stream.getSlugName(), e.getMessage(), e);
        }
    }
}
