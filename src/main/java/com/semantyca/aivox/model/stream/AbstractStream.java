package com.semantyca.aivox.model.stream;

import com.semantyca.aivox.model.IStream;
import com.semantyca.aivox.model.brand.AiOverriding;
import com.semantyca.aivox.model.brand.Brand;
import com.semantyca.aivox.model.brand.BrandScriptEntry;
import com.semantyca.aivox.streaming.StreamManager;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.kneo.core.localization.LanguageCode;
import io.kneo.officeframe.cnst.CountryCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Setter
@Getter
public abstract class AbstractStream implements IStream {
    protected UUID id;
    protected Brand masterBrand;
    protected String slugName;
    protected EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    protected StreamStatus status = StreamStatus.OFF_LINE;
    protected List<StatusChangeRecord> statusHistory = new LinkedList<>();
    protected LocalDateTime startTime;
    protected StreamManager streamManager;
    protected ZoneId timeZone;
    protected long bitRate;
    protected ManagedBy managedBy = ManagedBy.ITSELF;
    protected String color;
    protected CountryCode country;
    protected double popularityRate = 5;
    protected List<BrandScriptEntry> scripts;
    protected LocalDateTime createdAt;
    protected LocalDateTime expiresAt;
    protected AiAgentStatus aiAgentStatus;
    protected long lastAgentContactAt;
    protected LanguageTag streamLanguage;
    protected final Map<UUID, Set<UUID>> fetchedSongsByScene = new HashMap<>();

    @Override
    public void setStatus(StreamStatus newStatus) {
        if (this.status != newStatus) {
            StatusChangeRecord record = new StatusChangeRecord(
                    LocalDateTime.now(timeZone),
                    this.status,
                    newStatus
            );
            if (statusHistory.isEmpty()) {
                startTime = record.timestamp();
            }
            statusHistory.add(record);
            this.status = newStatus;
        }
    }

    @Override
    public UUID getAiAgentId() {
        return null;
    }

    @Override
    public AiOverriding getAiOverriding() {
        return null;
    }
}
