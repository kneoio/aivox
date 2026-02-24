package com.semantyca.aivox.tts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class TtsManager {
    
    private static final Logger LOGGER = Logger.getLogger(TtsManager.class);
    
    private final Map<TtsProvider, TtsEngine> engines = new HashMap<>();
    
    @Inject
    public TtsManager(ElevenLabsTtsEngine elevenLabs, AzureTtsEngine azure) {
        engines.put(TtsProvider.ELEVENLABS, elevenLabs);
        engines.put(TtsProvider.AZURE, azure);
        LOGGER.info("TTS Manager initialized with providers: " + engines.keySet());
    }
    
    public TtsEngine getEngine(TtsProvider provider) {
        TtsEngine engine = engines.get(provider);
        if (engine == null) {
            throw new IllegalArgumentException("No TTS engine found for provider: " + provider);
        }
        return engine;
    }
    
    public TtsEngine getDefaultEngine() {
        // Default to ElevenLabs, but you can make this configurable
        return getEngine(TtsProvider.ELEVENLABS);
    }
}
