package com.semantyca.aivox.streaming;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;

@ApplicationScoped
public class SliderTimer {
    private static final Logger LOGGER = Logger.getLogger(SliderTimer.class);
    private static final int DEFAULT_INTERVAL_MS = 1000;
    private Multi<Long> ticker;
    private Cancellable subscription;

    private Multi<Long> createTicker() {
        Multi<Long> ticker = Multi.createFrom().ticks()
                .every(Duration.ofMillis(DEFAULT_INTERVAL_MS))
                .onOverflow().drop()
                .broadcast().toAllSubscribers();

        subscription = ticker.subscribe().with(
                timestamp -> {},
                throwable -> LOGGER.error("SliderTimer error", throwable)
        );
        return ticker;
    }

    public Multi<Long> getTicker() {
        if (ticker == null) {
            ticker = createTicker();
        }
        return ticker;
    }

    @PreDestroy
    void cleanup() {
        LOGGER.info("Shutting down SliderTimer");
        if (subscription != null) {
            LOGGER.debug("Cancelling subscription");
            subscription.cancel();
        }
        LOGGER.info("SliderTimer shutdown complete.");
    }
}
