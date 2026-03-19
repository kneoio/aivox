package com.semantyca.aivox.service;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.aivox.service.manipulation.FFmpegProvider;
import com.semantyca.aivox.service.manipulation.mixing.AudioConcatenator;
import com.semantyca.aivox.service.manipulation.mixing.handler.AudioMixingHandler;
import com.semantyca.aivox.service.manipulation.mixing.handler.IntroSongHandler;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.aivox.streaming.RadioStationPool;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.model.cnst.ConcatenationType;
import com.semantyca.mixpla.model.cnst.MergingType;
import com.semantyca.mixpla.model.stream.IStream;
import com.semantyca.mixpla.service.exceptions.AudioMergeException;
import com.semantyca.mixpla.service.exceptions.RadioStationException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

@ApplicationScoped
public class QueueService {
    @Inject
    SoundFragmentRepository soundFragmentRepository;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    private AivoxConfig aivoxConfig;

    @Inject
    private SoundFragmentService soundFragmentService;

    @Inject
    private AiAgentService aiAgentService;

    @Inject
    FFmpegProvider fFmpegProvider;

    @Inject
    AudioConcatenator audioConcatenator;

    private static final Logger LOGGER = LoggerFactory.getLogger(QueueService.class);

    public Uni<Boolean> addToQueue(SongQueueMessageDTO message) {
        String messageId = String.valueOf(message.getMessageId());
        String brandName = message.getBrandSlug();

        if (message.getMergingMethod() == MergingType.INTRO_SONG || message.getMergingMethod() == MergingType.LISTENER_INTRO_SONG) {  //keeping JIC
            return getRadioStation(message.getBrandSlug())
                    .chain(radioStation -> {
                        //LOGGER.info("[QueueService] Radio station found: {} , creating IntroSongHandler",message.getBrandSlug());
                        try {
                            IntroSongHandler handler = new IntroSongHandler(
                                    aivoxConfig,
                                    soundFragmentRepository,
                                    soundFragmentService,
                                    aiAgentService,
                                    fFmpegProvider
                            );
                            LOGGER.debug("[QueueService] IntroSongHandler created, calling handle method");
                            return handler.handle(radioStation, message);
                        } catch (IOException | AudioMergeException e) {
                            LOGGER.error("[QueueService] Failed to create or execute IntroSongHandler", e);
                            throw new RuntimeException(e);
                        }
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] INTRO_SONG operation completed successfully - messageId: {}, result: {}", messageId, result);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] INTRO_SONG operation failed - messageId: {}", messageId, err);
                    });
        } else if (message.getMergingMethod() == MergingType.NOT_MIXED) {
            LOGGER.info("Processing NOT_MIXED merging method for messageId: {}", messageId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        LOGGER.info("Stream found: {}, creating AudioMixingHandler", brandName);
                        AudioMixingHandler handler = createAudioMixingHandler();
                        return handler.handleConcatenationAndFeed(radioStation, message, ConcatenationType.DIRECT_CONCAT);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] NOT_MIXED operation completed successfully - messageId: {}", messageId);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] NOT_MIXED operation failed - messageId: {}", messageId, err);
                    });
        } else if (message.getMergingMethod() == MergingType.SONG_INTRO_SONG) {
            LOGGER.info("[QueueService] Processing SONG_INTRO_SONG merging method for messageId: {}", messageId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        //LOGGER.info("[QueueService] Radio station found: {}, handling SONG_INTRO_SONG", brandName);
                        return createAudioMixingHandler().handleSongIntroSong(radioStation, message);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] SONG_INTRO_SONG operation completed successfully - messageId: {}", messageId);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] SONG_INTRO_SONG operation failed - messageId: {}", messageId, err);
                    });
        } else if (message.getMergingMethod() == MergingType.INTRO_SONG_INTRO_SONG) {
            LOGGER.info("[QueueService] Processing INTRO_SONG_INTRO_SONG merging method for messageId: {}", messageId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        //LOGGER.info("[QueueService] Radio station found: {}, handling INTRO_SONG_INTRO_SONG", brandName);
                        return createAudioMixingHandler().handleIntroSongIntroSong(radioStation, message);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] INTRO_SONG_INTRO_SONG operation completed successfully - messageId: {}", messageId);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] INTRO_SONG_INTRO_SONG operation failed - messageId: {}", messageId, err);
                    });
        } else if (message.getMergingMethod() == MergingType.SONG_CROSSFADE_SONG) {
            LOGGER.info("[QueueService] Processing SONG_CROSSFADE_SONG merging method for messageId: {}", messageId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        //LOGGER.info("[QueueService] Radio station found: {}, creating AudioMixingHandler", brandName);
                        AudioMixingHandler handler = createAudioMixingHandler();
                        ConcatenationType concatType = Arrays.stream(ConcatenationType.values())
                                .skip(new Random().nextInt(ConcatenationType.values().length))
                                .findFirst()
                                .orElse(ConcatenationType.CROSSFADE);
                        LOGGER.debug("[QueueService] Selected concatenation type: {}", concatType);
                        return handler.handleConcatenationAndFeed(radioStation, message, concatType);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] SONG_CROSSFADE_SONG operation completed successfully - messageId: {}", messageId);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] SONG_CROSSFADE_SONG operation failed - messageId: {}", messageId, err);
                    });
        } else if (message.getMergingMethod() == MergingType.SONG_ONLY) {
            LOGGER.info("[QueueService] Processing SONG_ONLY merging method for messageId: {}", messageId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        //LOGGER.info("[QueueService] Radio station found: {}, handling SONG_ONLY", brandName);
                        return createAudioMixingHandler().handleSongOnly(radioStation, message);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] SONG_ONLY operation completed successfully - messageId: {}", messageId);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] SONG_ONLY operation failed - messageId: {}", messageId, err);
                    });
        } else if (message.getMergingMethod() == MergingType.FILLER_JINGLE) {
            LOGGER.info("[QueueService] Processing FILLER_JINGLE merging method for messageId: {}", messageId);
            return getRadioStation(brandName)
                    .chain(radioStation -> {
                        return createAudioMixingHandler().handleFillerJingle(radioStation, message);
                    })
                    .onItem().invoke(result -> {
                        LOGGER.info("[QueueService] FILLER_JINGLE operation completed successfully - messageId: {}", messageId);
                    })
                    .onFailure().invoke(err -> {
                        LOGGER.error("[QueueService] FILLER_JINGLE operation failed - messageId: {}", messageId, err);
                    });
        } else {
            LOGGER.warn("[QueueService] Unknown or unsupported merging method: {} for messageId: {}", 
                    message.getMergingMethod(), messageId);
            return Uni.createFrom().item(Boolean.FALSE);
        }
    }

    private AudioMixingHandler createAudioMixingHandler() {
        try {
            return new AudioMixingHandler(
                    aivoxConfig,
                    soundFragmentRepository,
                    soundFragmentService,
                    audioConcatenator,
                    aiAgentService,
                    fFmpegProvider
            );
        } catch (IOException | AudioMergeException e) {
            throw new RuntimeException(e);
        }
    }

    private Uni<IStream> getRadioStation(String brand) {
        LOGGER.debug("[QueueService] Looking up radio station for brand: {}", brand);
        return radioStationPool.get(brand)
                .onItem().transform(v -> {
                    if (v == null) {
                        LOGGER.error("[QueueService] Radio station not found for brand: {}", brand);
                        throw new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE,
                                String.format("Station not found for brand: %s", brand));
                    }
                    LOGGER.debug("[QueueService] Radio station retrieved successfully: {}", brand);
                    return v;
                });
    }

}
