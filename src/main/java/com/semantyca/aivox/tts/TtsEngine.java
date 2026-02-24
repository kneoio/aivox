package com.semantyca.aivox.tts;

import com.semantyca.aivox.model.TtsDTO;
import io.smallrye.mutiny.Uni;

public interface TtsEngine {
    Uni<byte[]> generateSpeech(String text, String voiceId, String languageCode);
    Uni<byte[]> generateDialogue(String dialogueJson, TtsDTO ttsConfig);
}
