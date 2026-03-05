package com.semantyca.aivox.service.manipulation.mixing.handler;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.dto.queue.AddToQueueDTO;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.aivox.service.AiAgentService;
import com.semantyca.aivox.service.manipulation.FFmpegProvider;
import com.semantyca.aivox.service.manipulation.mixing.AudioConcatenator;
import com.semantyca.aivox.service.manipulation.mixing.ConcatenationType;
import com.semantyca.aivox.service.playlist.PlaylistManager;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.stream.IPlaylistManager;
import com.semantyca.mixpla.model.stream.IStream;
import com.semantyca.mixpla.service.exceptions.AudioMergeException;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Deprecated
public class IntroSongHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(IntroSongHandler.class);
    private final SoundFragmentRepository repository;
    private final SoundFragmentService soundFragmentService;
    private final AiAgentService aiAgentService;
    private final AivoxConfig config;
    private final AudioConcatenator audioConcatenator;
    private final String tempBaseDir;

    public IntroSongHandler(AivoxConfig config,
                            SoundFragmentRepository repository,
                            SoundFragmentService soundFragmentService,
                            AiAgentService aiAgentService,
                            FFmpegProvider fFmpegProvider) throws IOException, AudioMergeException {
        this.config = config;
        this.repository = repository;
        this.soundFragmentService = soundFragmentService;
        this.aiAgentService = aiAgentService;
        this.audioConcatenator = new AudioConcatenator(config, fFmpegProvider);
        this.tempBaseDir = config.getPathUploads() + "/audio-processing";
    }

    public Uni<Boolean> handle(IStream stream, AddToQueueDTO toQueueDTO) {
        PlaylistManager playlistManager = (PlaylistManager) stream.getStreamer().getPlaylistManager();
        
        if (toQueueDTO.getSoundFragments() == null || toQueueDTO.getSoundFragments().isEmpty()) {
            LOGGER.error("No sound fragments provided in AddToQueueDTO");
            return Uni.createFrom().failure(new IllegalArgumentException("No sound fragments provided"));
        }
        
        UUID soundFragmentId = toQueueDTO.getSoundFragments().get("song1");
        String ttsFilePath = toQueueDTO.getFilePaths().get("audio1");

        return soundFragmentService.getById(soundFragmentId)
                .chain(soundFragment -> {
                    return repository.getFirstFile(soundFragment.getId())
                            .chain(songMetadata -> {
                                if (ttsFilePath != null) {
                                    soundFragment.setType(PlaylistItemType.MIX_INTRO_SONG);
                                    return handleWithTtsFile(stream, toQueueDTO, soundFragment, songMetadata, ttsFilePath, playlistManager);
                                } else {
                                    soundFragment.setType(PlaylistItemType.SONG);
                                    return handleWithoutTtsFile(stream, toQueueDTO, soundFragment, playlistManager);
                                }
                            });
                });
    }

    private Uni<Boolean> handleWithTtsFile(IStream brand, AddToQueueDTO toQueueDTO,
                                           SoundFragment soundFragment, FileMetadata songMetadata, String ttsFilePath,
                                           PlaylistManager playlistManager) {
        return aiAgentService.getById(brand.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(aiAgent -> {
                    double gainValue = 1.0;

                    return songMetadata.materializeFileStream(tempBaseDir)
                            .chain(songTempFile -> {
                                String outputPath = config.getPathForMerged() + "/merged_intro_" +
                                        soundFragment.getSlugName() + "_" + System.currentTimeMillis() + ".wav";

                                return audioConcatenator.concatenate(
                                        ttsFilePath,
                                        songTempFile.toString(),
                                        outputPath,
                                        ConcatenationType.DIRECT_CONCAT,
                                        gainValue
                                );
                            })
                            .onItem().transform(mergedPath -> {
                                FileMetadata mergedMetadata = new FileMetadata();
                                mergedMetadata.setTemporaryFilePath(Path.of(mergedPath));
                                soundFragment.setFileMetadataList(List.of(mergedMetadata));
                                return mergedMetadata;
                            })
                            .chain(updatedMetadata -> {
                                updateRadioStationStatus(brand);
                                return playlistManager.addFragmentToQueue(soundFragment, toQueueDTO.getPriority())
                                        .onItem().invoke(result -> {
                                            if (result) {
                                                LOGGER.info("Added merged song to queue: {}", soundFragment.getTitle());
                                            }
                                        });
                            });
                });
    }

    private Uni<Boolean> handleWithoutTtsFile(IStream stream, AddToQueueDTO toQueueDTO,
                                              SoundFragment soundFragment, PlaylistManager playlistManager) {
        updateRadioStationStatus(stream);
        return playlistManager.addFragmentToQueue(soundFragment, toQueueDTO.getPriority())
                .onItem().invoke(result -> {
                    if (result) {
                        LOGGER.info("Added song to queue: {}", soundFragment.getTitle());
                    }
                });
    }

    private void updateRadioStationStatus(IStream stream) {
        stream.setAiAgentStatus(AiAgentStatus.CONTROLLING);
        stream.setLastAgentContactAt(System.currentTimeMillis());
    }
}