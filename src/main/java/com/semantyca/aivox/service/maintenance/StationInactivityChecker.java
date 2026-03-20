package com.semantyca.aivox.service.maintenance;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.messaging.MetricPublisher;
import com.semantyca.aivox.model.stream.OneTimeStream;
import com.semantyca.aivox.service.BrandService;
import com.semantyca.aivox.streaming.RadioStationPool;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.stream.IStream;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StationInactivityChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(StationInactivityChecker.class);
    private static final int INTERVAL_SECONDS = 60;
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);

    private static final int IDLE_THRESHOLD_MINUTES = 5;
    private static final int IDLE_TO_OFFLINE_THRESHOLD_MINUTES = 120;
    private static final int REMOVAL_DELAY_MINUTES = 1;

    private static final Set<StreamStatus> ACTIVE_STATUSES = Set.of(
            StreamStatus.ON_LINE,
            StreamStatus.WARMING_UP,
            StreamStatus.QUEUE_SATURATED,
            StreamStatus.SYSTEM_ERROR
    );

    private static final Set<StreamStatus> TERMINAL_STATUSES = Set.of(
            StreamStatus.OFF_LINE,
            StreamStatus.FINISHED
    );

    @Inject
    BrandService brandService;

    @Inject
    RadioStationPool radioStationPool;

    @Inject
    AivoxConfig aivoxConfig;

    @Inject
    MetricPublisher metricPublisher;

    private Cancellable cleanupSubscription;
    private final ConcurrentHashMap<String, Instant> stationsMarkedForRemoval = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> idleStatusTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> stationStartTime = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent event) {
        LOGGER.info("=== Starting station inactivity checker ===");
        stopCleanupTask();
        startCleanupTask();
    }

    void onShutdown(@Observes ShutdownEvent event) {
        LOGGER.info("Shutting down station inactivity checker.");
        stopCleanupTask();
    }

    private void startCleanupTask() {
        cleanupSubscription = getTicker()
                .onItem().call(this::checkStationActivity)
                .onFailure().invoke(error -> LOGGER.error("Timer error", error))
                .onFailure().retry().withBackOff(Duration.ofSeconds(10), Duration.ofMinutes(5)).indefinitely()
                .subscribe().with(item -> {}, failure -> LOGGER.error("Subscription failed", failure));
    }

    private Multi<Long> getTicker() {
        return Multi.createFrom().ticks()
                .startingAfter(INITIAL_DELAY)
                .every(Duration.ofSeconds(INTERVAL_SECONDS))
                .onOverflow().drop();
    }

    public void stopCleanupTask() {
        if (cleanupSubscription != null) {
            cleanupSubscription.cancel();
            cleanupSubscription = null;
        }
    }

    private Uni<Void> checkStationActivity(Long tick) {
        Instant now = Instant.now();
        Instant idleThreshold = now.minusSeconds(IDLE_THRESHOLD_MINUTES * 60L);
        Instant idleToOfflineThreshold = now.minusSeconds(IDLE_TO_OFFLINE_THRESHOLD_MINUTES * 60L);
        Instant removalThreshold = now.minusSeconds(REMOVAL_DELAY_MINUTES * 60L);

        return Multi.createFrom().iterable(stationsMarkedForRemoval.entrySet())
                .onItem().transformToUni(entry -> {
                    String slug = entry.getKey();
                    Instant markedTime = entry.getValue();
                    if (markedTime.isBefore(removalThreshold)) {
                        LOGGER.info("[{}] Removing station after {} minutes delay", slug, REMOVAL_DELAY_MINUTES);
                        metricPublisher.publishMetric(slug, MetricEventType.WARNING, "station_removed",
                                Map.of("reason", "inactivity", "offlineMinutes", REMOVAL_DELAY_MINUTES));
                        stationsMarkedForRemoval.remove(slug);
                        idleStatusTime.remove(slug);
                        stationStartTime.remove(slug);
                        return radioStationPool.stopAndRemoveStation(slug).replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                })
                .merge()
                .toUni()
                .replaceWithVoid()
                .chain(() -> {
                    Collection<IStream> currentOnlineStations = radioStationPool.getOnlineStationsSnapshot();

                    return Multi.createFrom().iterable(currentOnlineStations)
                            .onItem().transformToUni(radioStation -> {
                                String slug = radioStation.getSlugName();
                                StreamStatus currentStatus = radioStation.getStatus();

                                return brandService.findLastAccessTimeByStationName(slug)
                                        .onItem().transformToUni(lastAccessTime -> {
                                            if (lastAccessTime != null) {
                                                Instant lastAccessInstant = lastAccessTime.toInstant();
                                                boolean isPastIdleThreshold = lastAccessInstant.isBefore(idleThreshold);

                                                if (!isPastIdleThreshold && currentStatus != StreamStatus.OFF_LINE) {
                                                    if (currentStatus != StreamStatus.ON_LINE) {
                                                        radioStation.setStatus(StreamStatus.ON_LINE);
                                                        metricPublisher.publishMetric(slug, MetricEventType.INFORMATION, "station_reactivated",
                                                                Map.of("previousStatus", currentStatus.toString(), "newStatus", "ON_LINE"));
                                                        stationsMarkedForRemoval.remove(slug);
                                                        idleStatusTime.remove(slug);
                                                    }
                                                    return Uni.createFrom().voidItem();
                                                }

                                                if (TERMINAL_STATUSES.contains(currentStatus))
                                                    return Uni.createFrom().voidItem();

                                                if (currentStatus == StreamStatus.IDLE) {
                                                    Instant idleStartTime = idleStatusTime.get(slug);
                                                    if (idleStartTime != null && idleStartTime.isBefore(idleToOfflineThreshold)) {
                                                        long idleMinutes = Duration.between(idleStartTime, now).toMinutes();
                                                        radioStation.setStatus(StreamStatus.OFF_LINE);
                                                        metricPublisher.publishMetric(slug, MetricEventType.WARNING, "station_offline",
                                                                Map.of("reason", "idle_timeout", "idleMinutes", idleMinutes));
                                                        stationsMarkedForRemoval.put(slug, now);
                                                        idleStatusTime.remove(slug);
                                                        stationStartTime.remove(slug);
                                                    }
                                                } else if (ACTIVE_STATUSES.contains(currentStatus)) {
                                                    if (isPastIdleThreshold) {
                                                        long inactiveMinutes = Duration.between(lastAccessInstant, now).toMinutes();
                                                        radioStation.setStatus(StreamStatus.IDLE);
                                                        metricPublisher.publishMetric(slug, MetricEventType.INFORMATION, "station_idle",
                                                                Map.of("previousStatus", currentStatus.toString(), "inactiveMinutes", inactiveMinutes));
                                                        idleStatusTime.put(slug, now);
                                                    }
                                                } else if (currentStatus == StreamStatus.FINISHED && radioStation instanceof OneTimeStream) {
                                                    metricPublisher.publishMetric(slug, MetricEventType.INFORMATION, "onetime_stream_finished",
                                                            Map.of("streamType", "OneTimeStream"));
                                                    stationsMarkedForRemoval.put(slug, now);
                                                    idleStatusTime.remove(slug);
                                                    stationStartTime.remove(slug);
                                                }
                                            } else {
                                                stationStartTime.putIfAbsent(slug, now);
                                                Instant startTime = stationStartTime.get(slug);
                                                boolean hasBeenRunning5Min = startTime.isBefore(idleThreshold);
                                                
                                                if (ACTIVE_STATUSES.contains(currentStatus) && hasBeenRunning5Min) {
                                                    radioStation.setStatus(StreamStatus.IDLE);
                                                    metricPublisher.publishMetric(slug, MetricEventType.INFORMATION, "station_idle",
                                                            Map.of("previousStatus", currentStatus.toString(), "reason", "no_access_data"));
                                                    idleStatusTime.put(slug, now);
                                                }
                                                
                                                if (currentStatus == StreamStatus.IDLE) {
                                                    Instant idleStartTime = idleStatusTime.get(slug);
                                                    if (idleStartTime != null && idleStartTime.isBefore(idleToOfflineThreshold)) {
                                                        long idleMinutes = Duration.between(idleStartTime, now).toMinutes();
                                                        radioStation.setStatus(StreamStatus.OFF_LINE);
                                                        metricPublisher.publishMetric(slug, MetricEventType.WARNING, "station_offline",
                                                                Map.of("reason", "idle_timeout_no_access_data", "idleMinutes", idleMinutes));
                                                        stationsMarkedForRemoval.put(slug, now);
                                                        idleStatusTime.remove(slug);
                                                        stationStartTime.remove(slug);
                                                    }
                                                }
                                            }
                                            return Uni.createFrom().voidItem();
                                        })
                                        .onFailure().recoverWithUni(failure -> Uni.createFrom().voidItem());
                            })
                            .merge()
                            .toUni()
                            .replaceWithVoid();
                });
    }
}
