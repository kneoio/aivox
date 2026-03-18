package com.semantyca.aivox.agent;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.dto.AgentRespDTO;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.core.model.cnst.TranslationType;
import com.semantyca.officeframe.model.cnst.CountryCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AgentClient {

    @Inject
    AivoxConfig config;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }


    public Uni<AgentRespDTO> translate(String toTranslate, TranslationType translationType, LanguageTag languageTag, CountryCode countryCode) {
        String endpoint = config.getAgentUrl() + "/translate";

        JsonObject payload = new JsonObject();
        payload.put("toTranslate", toTranslate);
        payload.put("translationType", translationType);
        payload.put("language", languageTag.name());
        payload.put("country", countryCode.name());

        return webClient
                .postAbs(endpoint)
                .putHeader("Content-Type", "application/json")
                .putHeader("X-API-Key", config.agentApiKey())
                .sendJsonObject(payload)
                .map(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject body = response.bodyAsJsonObject();
                        if (body == null) {
                            throw new RuntimeException("Empty response body");
                        }
                        return body.mapTo(AgentRespDTO.class);
                    } else {
                        throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.bodyAsString());
                    }
                });
    }

    public Uni<Void> sendJesoosCommand(String brand, String command) {
        if (brand == null || brand.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Brand must be provided"));
        }
        if (command == null || command.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Command must be provided"));
        }

        String normalizedBrand = brand.toLowerCase();
        String normalizedCommand = command.toLowerCase();
        String endpoint = String.format("%s/jesoos/%s/%s", config.getJesoosUrl(), normalizedBrand, normalizedCommand);

        return webClient
                .postAbs(endpoint)
                .addQueryParam("brand", normalizedBrand)
                .putHeader("Content-Type", "application/json")
                .send()
                .onItem().transform(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return (Void) null;
                    }
                    throw new RuntimeException(String.format(
                            "Failed to send Jesoos command '%s' for brand '%s'. HTTP %d: %s",
                            normalizedCommand,
                            normalizedBrand,
                            response.statusCode(),
                            response.bodyAsString()
                    ));
                });
    }

    public Uni<Boolean> checkHealth() {
        String endpoint = config.getAgentUrl() + "/health";
        
        return webClient
                .getAbs(endpoint)
                .timeout(3000)
                .send()
                .map(response -> response.statusCode() == 200)
                .onFailure().recoverWithItem(false);
    }
}