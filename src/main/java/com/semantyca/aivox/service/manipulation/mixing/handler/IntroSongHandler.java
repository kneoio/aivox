package com.semantyca.aivox.service.manipulation.mixing.handler;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.aivox.service.AiAgentService;
import com.semantyca.aivox.service.manipulation.FFmpegProvider;
import com.semantyca.aivox.service.manipulation.mixing.AudioConcatenator;
import com.semantyca.aivox.service.playlist.PlaylistManager;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.mixpla.dto.queue.livestream.IntroKey;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.ConcatenationType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
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

    public Uni<Boolean> handle(IStream stream, SongQueueMessageDTO message) {
        PlaylistManager playlistManager = (PlaylistManager) stream.getStreamer().getPlaylistManager();
        
        if (message.getSongs() == null || message.getSongs().isEmpty()) {
            LOGGER.error("No sound fragments provided in AddToQueueDTO");
            return Uni.createFrom().failure(new IllegalArgumentException("No sound fragments provided"));
        }
        
        SongInfoDTO songInfo = message.getSongs().get(SongKey.SONG_1);
        String ttsFilePath = message.getFilePaths().get(IntroKey.INTRO_1).getFilePath();

        return soundFragmentService.getById(songInfo.getSongId())
                .chain(soundFragment -> {
                    return repository.getFirstFile(soundFragment.getId())
                            .chain(songMetadata -> {
                                if (ttsFilePath != null) {
                                    soundFragment.setType(PlaylistItemType.MIX_INTRO_SONG);
                                    return handleWithTtsFile(stream, message, soundFragment, songMetadata, ttsFilePath, playlistManager);
                                } else {
                                    soundFragment.setType(PlaylistItemType.SONG);
                                    return handleWithoutTtsFile(stream, message, soundFragment, playlistManager);
                                }
                            });
                });
    }

    private Uni<Boolean> handleWithTtsFile(IStream brand, SongQueueMessageDTO message,
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
                                return playlistManager.addFragmentToQueue(soundFragment, message.getPriority())
                                        .onItem().invoke(result -> {
                                            if (result) {
                                                LOGGER.info("Added merged song to queue: {}", soundFragment.getTitle());
                                            }
                                        });
                            });
                });
    }

    private Uni<Boolean> handleWithoutTtsFile(IStream stream, SongQueueMessageDTO message,
                                              SoundFragment soundFragment, PlaylistManager playlistManager) {
        updateRadioStationStatus(stream);
        return playlistManager.addFragmentToQueue(soundFragment, message.getPriority())
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