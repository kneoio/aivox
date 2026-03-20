package com.semantyca.aivox.service.manipulation.mixing.handler;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.aivox.service.AiAgentService;
import com.semantyca.aivox.service.manipulation.FFmpegProvider;
import com.semantyca.aivox.service.manipulation.mixing.AudioConcatenator;
import com.semantyca.aivox.service.playlist.PlaylistManager;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.mixpla.dto.queue.livestream.IntroKey;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongKey;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.model.cnst.ConcatenationType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.cnst.SourceType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IStream;
import com.semantyca.mixpla.service.exceptions.AudioMergeException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class AudioMixingHandler extends MixingHandlerBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioMixingHandler.class);
    private static final int SAMPLE_RATE = 44100;
    //TODO we should not use repo directly
    private final SoundFragmentRepository soundFragmentRepository;
    private final SoundFragmentService soundFragmentService;
    private final AudioConcatenator audioConcatenator;
    private final AiAgentService aiAgentService;
    private final String outputDir;
    private final String tempBaseDir;

    public AudioMixingHandler(AivoxConfig config,
                              SoundFragmentRepository repository,
                              SoundFragmentService soundFragmentService,
                              AudioConcatenator audioConcatenator,
                              AiAgentService aiAgentService,
                              FFmpegProvider fFmpegProvider) throws IOException, AudioMergeException {
        super(fFmpegProvider);
        this.soundFragmentRepository = repository;
        this.soundFragmentService = soundFragmentService;
        this.audioConcatenator = audioConcatenator;
        this.aiAgentService = aiAgentService;
        this.outputDir = config.getPathForMerged();
        this.tempBaseDir = config.getPathUploads() + "/audio-processing";
    }

    public Uni<Boolean> handleSongIntroSong(IStream stream, SongQueueMessageDTO toQueueDTO) {
        PlaylistManager playlistManager = (PlaylistManager) stream.getStreamer().getPlaylistManager();
        SongInfoDTO songInfo1 = toQueueDTO.getSongs().get(SongKey.SONG_1);
        String introSongPath = toQueueDTO.getFilePaths().get(IntroKey.INTRO_1).getFilePath();
        SongInfoDTO songInfo2 = toQueueDTO.getSongs().get(SongKey.SONG_2);
        MixingProfile settings = MixingProfile.randomProfile(12345L);
        LOGGER.info("Applied Mixing sis {}", settings.description);

        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(aiAgent -> {
                    return soundFragmentService.getById(songInfo1.getSongId())
                            .chain(soundFragment1 -> {
                                return soundFragmentRepository.getFirstFile(soundFragment1.getId())
                                        .chain(songMetadata1 -> {
                                            return songMetadata1.materializeFileStream(tempBaseDir)
                                                    .chain(tempPath1 -> {
                                                        String tempMixPath = outputDir + "/temp_mix_" +
                                                                soundFragment1.getSlugName() + "_i_" +
                                                                System.currentTimeMillis() + ".wav";
                                                        return mixSongPlusIntro(tempPath1.toString(),
                                                                introSongPath,
                                                                tempMixPath,
                                                                2.0,
                                                                false,
                                                                -3,
                                                                0.2);
                                                    })
                                                    .chain(actualTempMixPath -> {
                                                        return soundFragmentService.getById(songInfo2.getSongId())
                                                                .chain(soundFragment2 -> {
                                                                    return soundFragmentRepository.getFirstFile(soundFragment2.getId())
                                                                            .chain(songMetadata2 -> {
                                                                                return songMetadata2.materializeFileStream(tempBaseDir)
                                                                                        .chain(tempPath2 -> {
                                                                                            SoundFragment fragment1 = new SoundFragment();
                                                                                            fragment1.setId(soundFragment1.getId());
                                                                                            fragment1.setTitle(soundFragment1.getTitle());
                                                                                            fragment1.setArtist(soundFragment1.getArtist());
                                                                                            fragment1.setSource(soundFragment1.getSource());
                                                                                            FileMetadata fileMetadata1 = new FileMetadata();
                                                                                            fileMetadata1.setTemporaryFilePath(Path.of(actualTempMixPath));
                                                                                            fragment1.setFileMetadataList(List.of(fileMetadata1));
                                                                                            fragment1.setType(PlaylistItemType.MIX_1_SONG);

                                                                                            SoundFragment fragment2 = new SoundFragment();
                                                                                            fragment2.setId(soundFragment2.getId());
                                                                                            fragment2.setTitle(soundFragment2.getTitle());
                                                                                            fragment2.setArtist(soundFragment2.getArtist());
                                                                                            fragment2.setSource(soundFragment2.getSource());
                                                                                            FileMetadata fileMetadata2 = new FileMetadata();
                                                                                            fileMetadata2.setTemporaryFilePath(tempPath2);
                                                                                            fragment2.setFileMetadataList(List.of(fileMetadata2));
                                                                                            fragment2.setType(PlaylistItemType.MIX_2_SONG);

                                                                                            return playlistManager.addFragmentToQueue(fragment1, toQueueDTO.getPriority(), toQueueDTO.getTraceId())
                                                                                                    .chain(() ->
                                                                                                            playlistManager.addFragmentToQueue(fragment2, toQueueDTO.getPriority(), toQueueDTO.getTraceId()));
                                                                                        });
                                                                            });
                                                                });
                                                    });
                                        });
                            });
                });
    }

    public Uni<Boolean> handleIntroSongIntroSong(IStream stream, SongQueueMessageDTO message) {
        PlaylistManager playlistManager = (PlaylistManager) stream.getStreamer().getPlaylistManager();
        String part1 = message.getFilePaths().get(IntroKey.INTRO_1).getFilePath();           // intro1
        SongInfoDTO songInfo1 = message.getSongs().get(SongKey.SONG_1);       // song
        String part2 = message.getFilePaths().get(IntroKey.INTRO_2).getFilePath();           // intro2
        SongInfoDTO songInfo2 = message.getSongs().get(SongKey.SONG_2);      // next song
        MixingProfile settings = MixingProfile.randomProfile(12345L);
        LOGGER.info("Applied Mixing isis {}", settings.description);

        return aiAgentService.getById(stream.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                .chain(aiAgent -> {
                    return soundFragmentService.getById(songInfo1.getSongId())
                            .chain(soundFragment1 -> {
                                return soundFragmentRepository.getFirstFile(soundFragment1.getId())
                                        .chain(songMetadata1 -> {
                                            return songMetadata1.materializeFileStream(tempBaseDir)
                                                    .chain(tempPath1 -> {
                                                        String tempMixPath = outputDir + "/temp_mix_" +
                                                                soundFragment1.getSlugName() + "_i_" +
                                                                System.currentTimeMillis() + ".wav";

                                                        return mixIntroSongPlusIntro(
                                                                part1,                     // intro1
                                                                tempPath1.toString(),      // song
                                                                part2,                     // intro2
                                                                tempMixPath                // output
                                                        ).chain(actualTempMixPath -> {
                                                            return soundFragmentService.getById(songInfo2.getSongId())
                                                                    .chain(soundFragment2 -> {
                                                                        return soundFragmentRepository.getFirstFile(soundFragment2.getId())
                                                                                .chain(songMetadata2 -> {
                                                                                    return songMetadata2.materializeFileStream(tempBaseDir)
                                                                                            .chain(tempPath2 -> {
                                                                                                SoundFragment fragment1 = new SoundFragment();
                                                                                                fragment1.setId(soundFragment1.getId());
                                                                                                fragment1.setTitle(soundFragment1.getTitle());
                                                                                                fragment1.setArtist(soundFragment1.getArtist());
                                                                                                fragment1.setSource(soundFragment1.getSource());
                                                                                                FileMetadata fileMetadata1 = new FileMetadata();
                                                                                                fileMetadata1.setTemporaryFilePath(Path.of(actualTempMixPath));
                                                                                                fragment1.setFileMetadataList(List.of(fileMetadata1));
                                                                                                fragment1.setType(PlaylistItemType.MIX_1_INTRO_FADED_SONG);

                                                                                                SoundFragment fragment2 = new SoundFragment();
                                                                                                fragment2.setId(soundFragment2.getId());
                                                                                                fragment2.setTitle(soundFragment2.getTitle());
                                                                                                fragment2.setArtist(soundFragment2.getArtist());
                                                                                                fragment2.setSource(soundFragment2.getSource());
                                                                                                FileMetadata fileMetadata2 = new FileMetadata();
                                                                                                fileMetadata2.setTemporaryFilePath(tempPath2);
                                                                                                fragment2.setFileMetadataList(List.of(fileMetadata2));
                                                                                                fragment2.setType(PlaylistItemType.MIX_2_SONG);


                                                                                                return playlistManager.addFragmentToQueue(fragment1, message.getPriority(), message.getTraceId())
                                                                                                        .chain(() ->
                                                                                                                playlistManager.addFragmentToQueue(fragment2, message.getPriority(), message.getTraceId()));
                                                                                            });
                                                                                });
                                                                    });
                                                        });
                                                    });
                                        });
                            });
                });
    }

    public Uni<Boolean> handleSongOnly(IStream stream, SongQueueMessageDTO toQueueDTO) {
        PlaylistManager playlistManager = (PlaylistManager) stream.getStreamer().getPlaylistManager();
        SongInfoDTO songInfo1 = toQueueDTO.getSongs().get(SongKey.SONG_1);

        LOGGER.info("Handling single song feed");

        return soundFragmentService.getById(songInfo1.getSongId())
                .chain(soundFragment -> soundFragmentRepository.getFirstFile(soundFragment.getId())
                        .chain(songMetadata -> songMetadata.materializeFileStream(tempBaseDir)
                                .chain(tempPath -> {
                                    SoundFragment fragment = new SoundFragment();
                                    fragment.setId(soundFragment.getId());
                                    fragment.setTitle(soundFragment.getTitle());
                                    fragment.setArtist(soundFragment.getArtist());
                                    fragment.setSource(soundFragment.getSource());
                                    FileMetadata fileMetadata = new FileMetadata();
                                    fileMetadata.setTemporaryFilePath(tempPath);
                                    fragment.setFileMetadataList(List.of(fileMetadata));
                                    fragment.setType(PlaylistItemType.SONG);

                                    return playlistManager.addFragmentToQueue(
                                            fragment,
                                            toQueueDTO.getPriority(),
                                            toQueueDTO.getTraceId()
                                    ).replaceWith(Boolean.TRUE);
                                })));
    }

    public Uni<Boolean> handleConcatenationAndFeed(IStream stream, SongQueueMessageDTO toQueueDTO, ConcatenationType concatType) {
        PlaylistManager playlistManager = (PlaylistManager) stream.getStreamer().getPlaylistManager();
        SongInfoDTO songInfo1 = toQueueDTO.getSongs().get(SongKey.SONG_1);
        SongInfoDTO songInfo2 = toQueueDTO.getSongs().get(SongKey.SONG_2);

        LOGGER.info("Applied Concatenation Type {}", concatType);

        return soundFragmentService.getById(songInfo1.getSongId())
                .chain(sf1 -> soundFragmentRepository.getFirstFile(sf1.getId())
                        .chain(meta1 -> meta1.materializeFileStream(tempBaseDir)
                                .chain(tempPath1 ->
                                        soundFragmentService.getById(songInfo2.getSongId())
                                                .chain(sf2 -> soundFragmentRepository.getFirstFile(sf2.getId())
                                                        .chain(meta2 -> meta2.materializeFileStream(tempBaseDir)
                                                                .chain(tempPath2 -> {
                                                                    String outputPath = outputDir + "/crossfade_" +
                                                                            System.currentTimeMillis() + ".wav";
                                                                    return audioConcatenator.concatenate(
                                                                                    tempPath1.toString(),
                                                                                    tempPath2.toString(),
                                                                                    outputPath,
                                                                                    concatType,
                                                                                    0
                                                                            )
                                                                            .chain(finalPath -> {
                                                                                SoundFragment crossfadeFragment = new SoundFragment();
                                                                                if (sf1.getType() == PlaylistItemType.JINGLE) {
                                                                                    crossfadeFragment.setId(sf2.getId());
                                                                                    crossfadeFragment.setTitle(sf2.getTitle());
                                                                                    crossfadeFragment.setArtist(sf2.getArtist());
                                                                                } else {
                                                                                    crossfadeFragment.setId(sf1.getId());  //at least one gonna be marked as played
                                                                                    crossfadeFragment.setTitle(sf1.getTitle() + " → " + sf2.getTitle());
                                                                                    crossfadeFragment.setArtist(sf1.getArtist() + " / " + sf2.getArtist());
                                                                                }
                                                                                crossfadeFragment.setSource(SourceType.TEMPORARY_MIX);
                                                                                crossfadeFragment.setType(PlaylistItemType.MIX_SONG_1_SONG_2);

                                                                                FileMetadata fileMetadata = new FileMetadata();
                                                                                fileMetadata.setTemporaryFilePath(Path.of(finalPath));
                                                                                crossfadeFragment.setFileMetadataList(List.of(fileMetadata));

                                                                                return playlistManager.addFragmentToQueue(
                                                                                        crossfadeFragment,
                                                                                        toQueueDTO.getPriority(),
                                                                                        toQueueDTO.getTraceId()
                                                                                ).replaceWith(Boolean.TRUE);
                                                                            });
                                                                }))))));
    }

    public Uni<Boolean> handleFillerJingle(IStream stream, SongQueueMessageDTO toQueueDTO) {
        PlaylistManager playlistManager = (PlaylistManager) stream.getStreamer().getPlaylistManager();
        SongInfoDTO songInfo1 = toQueueDTO.getSongs().get(SongKey.SONG_1);
        SongInfoDTO songInfo2 = toQueueDTO.getSongs().get(SongKey.SONG_2);

        LOGGER.info("[AudioMixingHandler] Processing FILLER_JINGLE with DIRECT_CONCAT");

        return soundFragmentService.getById(songInfo1.getSongId())
                .chain(sf1 -> soundFragmentRepository.getFirstFile(sf1.getId())
                        .chain(meta1 -> meta1.materializeFileStream(tempBaseDir)
                                .chain(tempPath1 ->
                                        soundFragmentService.getById(songInfo2.getSongId())
                                                .chain(sf2 -> soundFragmentRepository.getFirstFile(sf2.getId())
                                                        .chain(meta2 -> meta2.materializeFileStream(tempBaseDir)
                                                                .chain(tempPath2 -> {
                                                                    String outputPath = outputDir + "/filler_jingle_" +
                                                                            System.currentTimeMillis() + ".wav";
                                                                    return audioConcatenator.concatenate(
                                                                                    tempPath1.toString(),
                                                                                    tempPath2.toString(),
                                                                                    outputPath,
                                                                                    ConcatenationType.DIRECT_CONCAT,
                                                                                    0
                                                                            )
                                                                            .chain(finalPath -> {
                                                                                SoundFragment concatenatedFragment = new SoundFragment();
                                                                                if (sf1.getType() == PlaylistItemType.JINGLE) {
                                                                                    concatenatedFragment.setId(sf2.getId());
                                                                                    concatenatedFragment.setTitle(sf2.getTitle());
                                                                                    concatenatedFragment.setArtist(sf2.getArtist());
                                                                                } else {
                                                                                    concatenatedFragment.setId(sf1.getId());
                                                                                    concatenatedFragment.setTitle(sf1.getTitle() + " → " + sf2.getTitle());
                                                                                    concatenatedFragment.setArtist(sf1.getArtist() + " / " + sf2.getArtist());
                                                                                }
                                                                                concatenatedFragment.setSource(SourceType.TEMPORARY_MIX);
                                                                                concatenatedFragment.setType(PlaylistItemType.MIX_SONG_1_SONG_2);

                                                                                FileMetadata fileMetadata = new FileMetadata();
                                                                                fileMetadata.setTemporaryFilePath(Path.of(finalPath));
                                                                                concatenatedFragment.setFileMetadataList(List.of(fileMetadata));

                                                                                return playlistManager.addFragmentToQueue(
                                                                                        concatenatedFragment,
                                                                                        toQueueDTO.getPriority(),
                                                                                        toQueueDTO.getTraceId()
                                                                                ).replaceWith(Boolean.TRUE);
                                                                            });
                                                                }))))));
    }


    public Uni<String> createOutroIntroMix(String mainSongPath, String introSongPath, String outputPath, MixingProfile settings, double gainValue) {
        return Uni.createFrom().item(() -> {
            try {
                double mainDuration = getAudioDuration(mainSongPath);
                double introDuration = getAudioDuration(introSongPath);

                LOGGER.info("Main song duration: {}s, Intro duration: {}s", mainDuration, introDuration);

                // When should the intro start? (e.g., 5 seconds before end)
                double introStartSeconds = settings.introStartEarly > 0
                        ? Math.max(0, mainDuration - settings.introStartEarly)
                        : Math.max(0, mainDuration - 5); // default: start 5s before end

                // When should fade-out of main song begin?
                // Fade starts a bit *before* intro (e.g., 3 seconds before intro starts)
                double fadeLeadIn = 3.0; // seconds before intro starts, fade begins
                double fadeStartTime = Math.max(0, introStartSeconds - fadeLeadIn);

                // Fade duration: from fadeStartTime to end of main song
                double fadeDuration = mainDuration - fadeStartTime;


                String filterComplex = buildFilter(
                        fadeStartTime,
                        fadeDuration,
                        introStartSeconds,
                        settings.fadeCurve,
                        settings.fadeToVolume,
                        gainValue
                );

                FFmpegBuilder builder = new FFmpegBuilder()
                        .setInput(mainSongPath)
                        .addInput(introSongPath)
                        .setComplexFilter(filterComplex)
                        .addOutput(outputPath)
                        .setAudioCodec("pcm_s16le")
                        .setAudioSampleRate(SAMPLE_RATE)
                        .setAudioChannels(2)
                        .done();

                executor.createJob(builder).run();
                LOGGER.info("Successfully created outro-intro mix: {}", outputPath);
                return outputPath;

            } catch (Exception e) {
                LOGGER.error("Error creating outro-intro mix with FFmpeg: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to create outro-intro mix", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private Uni<String> mixSongPlusIntro(String songFile, String introFile, String outputFile,
                                         double fadeLengthSeconds, boolean fadeOutBack,
                                         double tail, double minDuck) {


        return convertToWav(songFile).chain(songWav ->
                convertToWav(introFile).chain(introWav ->
                        Uni.createFrom().item(() -> {
                            try (AudioInputStream song  = AudioSystem.getAudioInputStream(songWav.file);
                                 AudioInputStream intro = AudioSystem.getAudioInputStream(introWav.file)) {

                                double introStartSeconds = songWav.durationSeconds - introWav.durationSeconds - tail;
                                if (introStartSeconds < 0) introStartSeconds = 0;

                                AudioFormat targetFormat = new AudioFormat(
                                        AudioFormat.Encoding.PCM_SIGNED,
                                        44100, 16, 2, 4, 44100, false
                                );

                                AudioInputStream convertedSong  = AudioSystem.getAudioInputStream(targetFormat, song);
                                AudioInputStream convertedIntro = AudioSystem.getAudioInputStream(targetFormat, intro);

                                byte[] songBytes  = convertedSong.readAllBytes();
                                byte[] introBytes = convertedIntro.readAllBytes();

                                int frameSize = targetFormat.getFrameSize();
                                int songFrames  = songBytes.length / frameSize;
                                int introFrames = introBytes.length / frameSize;

                                int introStartFrame = (int) (introStartSeconds * targetFormat.getFrameRate());
                                if (introStartFrame < 0) introStartFrame = 0;
                                if (introStartFrame > songFrames - introFrames) {
                                    introStartFrame = songFrames - introFrames;
                                }

                                int introStartBytes = introStartFrame * frameSize;
                                int fadeFrames = (int) (fadeLengthSeconds * targetFormat.getFrameRate());
                                int fadeBytes  = fadeFrames * frameSize;

                                byte[] mixed = Arrays.copyOf(songBytes, songBytes.length);
                                double maxDuck = 1.0;
                                
                                int fadeStart = Math.max(0, introStartBytes - fadeBytes);
                                int fadeEnd   = introStartBytes + introBytes.length;

                                for (int i = 0; i < introBytes.length && (i + introStartBytes + 1) < mixed.length; i += 2) {
                                    int pos = i + introStartBytes;
                                    
                                    if (pos < 0 || pos + 1 >= mixed.length) {
                                        continue;
                                    }

                                    double duckFactor;
                                    if (pos < introStartBytes) {
                                        if (pos >= fadeStart) {
                                            double progress = (double) (pos - fadeStart) / fadeBytes;
                                            double eased = smoothFade(progress);
                                            duckFactor = maxDuck - eased * (maxDuck - minDuck);
                                        } else {
                                            duckFactor = maxDuck;
                                        }
                                    } else if (fadeOutBack && pos >= fadeEnd && pos < fadeEnd + fadeBytes) {
                                        double progress = (double) (pos - fadeEnd) / fadeBytes;
                                        double eased = smoothFade(progress);
                                        duckFactor = maxDuck - eased * (maxDuck - minDuck);
                                    } else if (pos >= introStartBytes && pos < fadeEnd) {
                                        duckFactor = minDuck;
                                    } else {
                                        duckFactor = fadeOutBack ? maxDuck : minDuck;
                                    }

                                    short s1 = (short) ((mixed[pos+1] << 8) | (mixed[pos] & 0xff));
                                    short s2 = (short) ((introBytes[i+1] << 8) | (introBytes[i] & 0xff));

                                    int scaledSong = (int) Math.round(s1 * duckFactor);
                                    int sum = scaledSong + s2;

                                    if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
                                    if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;

                                    mixed[pos]   = (byte) (sum & 0xff);
                                    mixed[pos+1] = (byte) ((sum >> 8) & 0xff);
                                }

                                try (ByteArrayInputStream bais = new ByteArrayInputStream(mixed);
                                     AudioInputStream mixedStream = new AudioInputStream(bais, targetFormat, mixed.length / frameSize)) {
                                    AudioSystem.write(mixedStream, AudioFileFormat.Type.WAVE, new File(outputFile));
                                } catch (IOException e) {
                                    throw new RuntimeException("Failed to write mixed audio file", e);
                                }

                                return outputFile;
                            } catch (IOException | UnsupportedAudioFileException e) {
                                throw new RuntimeException("Failed to process audio streams", e);
                            } finally {
                                songWav.file.delete();
                                introWav.file.delete();
                            }
                        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                )
        );
    }

    private Uni<String> mixIntroSongPlusIntro(String intro1, String song, String intro2, String outputFile) {
        String firstConcat = outputDir + "/temp_intro_song_" + System.currentTimeMillis() + ".wav";

        return audioConcatenator.concatenate(intro1, song, firstConcat,
                        ConcatenationType.DIRECT_CONCAT, 1.0)
                .chain(temp -> mixSongPlusIntro(temp, intro2, outputFile,
                        2.0, false, -3, 0.2));
    }


    public Uni<String> mixContentWithBackgroundAndIntros(
            String contentTtsPath,
            String outputPath,
            double backgroundVolume,
            String introJingleResource,
            String backgroundMusicResource) {
        return Uni.createFrom().item(() -> {
            File tempIntroJingle = null;
            File tempBackgroundMusic = null;
            File tempContentWithBg = null;
            InputStream introJingleStream = null;
            InputStream backgroundMusicStream = null;

            try {
                introJingleStream = getClass().getClassLoader().getResourceAsStream(introJingleResource);
                backgroundMusicStream = getClass().getClassLoader().getResourceAsStream(backgroundMusicResource);
                if (introJingleStream == null || backgroundMusicStream == null) {
                    throw new RuntimeException("Required audio resources not found in classpath");
                }

                tempIntroJingle = File.createTempFile("intro_jingle_", ".wav", new File(outputDir));
                tempBackgroundMusic = File.createTempFile("bg_music_", ".wav", new File(outputDir));
                tempContentWithBg = File.createTempFile("content_bg_", ".wav", new File(outputDir));

                String introJinglePath = tempIntroJingle.getAbsolutePath();
                String backgroundMusicPath = tempBackgroundMusic.getAbsolutePath();
                String tempContentWithBgPath = tempContentWithBg.getAbsolutePath();

                Files.copy(introJingleStream, tempIntroJingle.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(backgroundMusicStream, tempBackgroundMusic.toPath(), StandardCopyOption.REPLACE_EXISTING);

                double contentDuration = getAudioDuration(contentTtsPath);
                double mainVolume = 3.0;

                String contentBgFilter = String.format(
                        "[0:a]volume=%.3f,aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo[content];" +
                                "[1:a]aloop=loop=-1:size=2e+09,atrim=duration=%.3f,aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo,volume=%.3f[bg];" +
                                "[content][bg]amix=inputs=2:duration=first:dropout_transition=0:normalize=0", mainVolume, contentDuration, backgroundVolume
                );

                FFmpegBuilder contentWithBgBuilder = new FFmpegBuilder()
                        .setInput(contentTtsPath)
                        .addInput(backgroundMusicPath)
                        .setComplexFilter(contentBgFilter)
                        .addOutput(tempContentWithBgPath)
                        .setAudioCodec("pcm_s16le")
                        .setAudioSampleRate(SAMPLE_RATE)
                        .setAudioChannels(2)
                        .done();

                executor.createJob(contentWithBgBuilder).run();
                LOGGER.info("Mixed content with background: {}", tempContentWithBgPath);

                String finalFilterComplex =
                        "[0:a]aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo," +
                                "silenceremove=start_periods=1:start_duration=0.01:start_threshold=-40dB:" +
                                "stop_periods=-1:stop_duration=0.01:stop_threshold=-40dB[intro];" +
                                "[1:a]aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo[content_bg];" +
                                "[2:a]aformat=sample_rates=44100:sample_fmts=s16:channel_layouts=stereo," +
                                "silenceremove=start_periods=1:start_duration=0.01:start_threshold=-40dB:" +
                                "stop_periods=-1:stop_duration=0.01:stop_threshold=-40dB[outro];" +
                                "[intro][content_bg][outro]concat=n=3:v=0:a=1";

                FFmpegBuilder finalBuilder = new FFmpegBuilder()
                        .setInput(introJinglePath)
                        .addInput(tempContentWithBgPath)
                        .addInput(introJinglePath)
                        .setComplexFilter(finalFilterComplex)
                        .addOutput(outputPath)
                        .setAudioCodec("pcm_s16le")
                        .setAudioSampleRate(SAMPLE_RATE)
                        .setAudioChannels(2)
                        .done();

                executor.createJob(finalBuilder).run();
                LOGGER.info("Final content mix created: {}", outputPath);

                cleanupQuietly(tempContentWithBg);
                cleanupQuietly(tempIntroJingle);
                cleanupQuietly(tempBackgroundMusic);

                return outputPath;

            } catch (Exception e) {
                LOGGER.error("Error creating content mix: {}", e.getMessage(), e);
                cleanupQuietly(tempContentWithBg);
                cleanupQuietly(tempIntroJingle);
                cleanupQuietly(tempBackgroundMusic);
                throw new RuntimeException("Failed to create content mix", e);
            } finally {
                closeQuietly(introJingleStream);
                closeQuietly(backgroundMusicStream);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private void cleanupQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            LOGGER.warn("Failed to delete temp file: {}", file.getAbsolutePath());
        }
    }

    private void closeQuietly(InputStream stream) {
        if (stream != null) {
            try { stream.close(); } catch (IOException e) {
                LOGGER.warn("Failed to close stream", e);
            }
        }
    }

    private static double smoothFade(double progress) {
        return Math.pow(progress, 3.5);  // try 2.0 → 4.0, stronger = steeper
        //return 1.0 / (1.0 + Math.exp(-12 * (progress - 0.5)));
    }

    private String buildFilter(
            double fadeStartTime,
            double fadeDuration,
            double introStartTime,
            FadeCurve curve,
            double fadeDownTo,
            double gainValue
    ) {

        String mainFilter;
        if (fadeDownTo == 0.0) {
            mainFilter = String.format("afade=t=out:st=%.2f:d=%.2f:curve=%s",
                    fadeStartTime, fadeDuration, curve.getFfmpegValue());
        } else {
            //TODO it is not working
            mainFilter = String.format(
                    "volume='min(1,max(%.2f,1-(1-%.2f)*(t-%.2f)/%.2f))':eval=frame",
                    fadeDownTo, fadeDownTo, fadeStartTime, fadeDuration);

        }
        return String.format("[0:a]%s[mainfaded];",mainFilter) +
                String.format("[1:a]volume=%.2f,adelay=%.0f|%.0f[intro];",
                        gainValue,
                        introStartTime * 1000,
                        introStartTime * 1000) +
                "[mainfaded][intro]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0";
    }

}