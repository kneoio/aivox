package com.semantyca.aivox.data;

import com.semantyca.aivox.data.enums.ManagedBy;
import com.semantyca.aivox.data.enums.SubmissionPolicy;
import io.kneo.officeframe.cnst.CountryCode;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Setter
@Getter
@Entity
@Table(name = "kneobroadcaster__brands")
public class Brand extends PanacheEntityBase {
    
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private java.util.UUID id;
    
    @Column(name = "author", nullable = false)
    private Long author;
    
    @Column(name = "reg_date", nullable = false)
    private LocalDateTime regDate;
    
    @Column(name = "last_mod_user", nullable = false)
    private Long lastModUser;
    
    @Column(name = "last_mod_date", nullable = false)
    private LocalDateTime lastModDate;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "country")
    private CountryCode country;
    
    @Column(name = "time_zone", nullable = false, length = 100)
    private String timeZone = "Europe/Lisbon";
    
    @Enumerated(EnumType.STRING)
    @Column(name = "managing_mode", nullable = false)
    private ManagedBy managedBy;
    
    @Column(name = "color", nullable = false, length = 9)
    private String color;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "loc_name", columnDefinition = "JSONB")
    private Map<String, Object> locName;
    
    @Column(name = "search_name", nullable = false, length = 1000)
    private String searchName = "";
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mixing", columnDefinition = "JSONB")
    private Map<String, Object> mixing = Map.of();
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_overriding", columnDefinition = "JSONB")
    private Map<String, Object> aiOverriding = Map.of();
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_overriding", columnDefinition = "JSONB")
    private Map<String, Object> profileOverriding = Map.of();
    
    @Column(name = "bit_rate", columnDefinition = "JSONB")
    private List<String> bitRate = List.of("128000");
    
    @Column(name = "slug_name", nullable = false, unique = true, length = 255)
    private String slugName;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "title_font", length = 64)
    private String titleFont;
    
    @Column(name = "profile_id")
    private java.util.UUID profileId;
    
    @Column(name = "ai_agent_id")
    private java.util.UUID aiAgentId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "submission_policy", nullable = false)
    private SubmissionPolicy submissionPolicy = SubmissionPolicy.NOT_ALLOWED;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "messaging_policy", nullable = false)
    private SubmissionPolicy messagingPolicy = SubmissionPolicy.NOT_ALLOWED;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "one_time_stream_policy", nullable = false)
    private SubmissionPolicy oneTimeStreamPolicy = SubmissionPolicy.NOT_ALLOWED;
    
    @Column(name = "is_temporary", nullable = false)
    private Integer isTemporary = 0;
    
    @Column(name = "source_brand_id")
    private java.util.UUID sourceBrandId;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "external_source", columnDefinition = "JSONB")
    private Map<String, Object> externalSource = Map.of();
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "song_source", columnDefinition = "JSONB")
    private Map<String, Object> songSource = Map.of();
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "owner", columnDefinition = "JSONB")
    private Map<String, Object> owner = Map.of();
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "appearance", columnDefinition = "JSONB")
    private Map<String, Object> appearance = Map.of();
    
    @Column(name = "popularity_rate", nullable = false, precision = 3, scale = 1)
    private BigDecimal popularityRate = BigDecimal.ZERO;
    
    @Column(name = "archived")
    private Integer archived = 0;
}
