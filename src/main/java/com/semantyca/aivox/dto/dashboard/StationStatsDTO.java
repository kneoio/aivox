package com.semantyca.aivox.dto.dashboard;

import com.semantyca.aivox.model.stats.PlaylistManagerStats;
import com.semantyca.aivox.model.stream.StatusChangeRecord;
import com.semantyca.aivox.service.stats.HLSSongStats;
import com.semantyca.mixpla.model.cnst.GeneratedContentStatus;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.cnst.SceneStatus;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Setter
public class StationStatsDTO {
    @Getter
    private String brandName;
    @Getter
    private String zoneId; // IANA zone, e.g., "Europe/Riga"
    @Getter
    private StreamStatus status;
    @Getter
    private ManagedBy managedBy;
    @Getter
    private PlaylistManagerStats playlistManagerStats;
    @Getter
    private boolean heartbeat;
    @Getter
    private HLSSongStats songStatistics;
    @Getter
    private long currentListeners;
    @Getter
    private List<CountryStatsDTO> listenersByCountry;
    @Getter
    private List<StatusChangeRecord> statusHistory = new LinkedList<>();
    @Getter
    private AiDjStatsDTO aiDjStats;
    @Getter
    private ScheduleDTO schedule;

    @Setter
    @Getter
    public static class ScheduleDTO {
        private LocalDateTime createdAt;
        private List<ScheduleEntryDTO> entries;
    }

    @Setter
    @Getter
    public static class ScheduleEntryDTO {
        private UUID sceneId;
        private String sceneTitle;
        private LocalTime startTime;
        private LocalTime endTime;
        private boolean active;
        private double dayPercentage;
        private String searchInfo;
        private int songsCount;
        private int fetchedSongsCount;
        private LocalTime actualStartTime;
        private LocalTime actualEndTime;
        private SceneStatus status;
        private Long timingOffsetSeconds;
        private UUID generatedFragmentId;
        private LocalDateTime generatedContentTimestamp;
        private GeneratedContentStatus generatedContentStatus;
        private boolean oneTimeRun;
        private List<SongEntryDTO> songs;
    }

    @Setter
    @Getter
    public static class SongEntryDTO {
        private UUID songId;
        private String title;
        private String artist;
        private LocalDateTime scheduledStartTime;
    }
}