package com.semantyca.aivox.memory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BrandMemoryManager {
    
    private static final Logger LOGGER = Logger.getLogger(BrandMemoryManager.class);
    
    private final Map<String, List<MemoryEntry>> inMemoryCache = new ConcurrentHashMap<>();
    
    @Inject BrandMemoryRepository repository;
    @Inject com.semantyca.aivox.llm.LlmClient llmClient;
    
    @ConfigProperty(name = "memory.max.entries", defaultValue = "20")
    int maxEntries;
    
    @ConfigProperty(name = "memory.summarize.every", defaultValue = "5")
    int summarizeEvery;
    
    private final Map<String, Integer> processingCount = new ConcurrentHashMap<>();
    
    public Uni<String> getMemory(String brand) {
        return repository.findByBrandAndDate(brand, LocalDate.now())
            .map(memory -> {
                if (memory != null) {
                    return memory.getSummary();
                }
                
                // If no stored memory, build from cache
                List<MemoryEntry> entries = inMemoryCache.get(brand);
                if (entries != null && !entries.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Recent interactions:\n");
                    for (MemoryEntry entry : entries) {
                        sb.append("- ").append(entry.getText()).append("\n");
                    }
                    return sb.toString();
                }
                
                return null;
            });
    }
    
    public void add(String brand, String text) {
        inMemoryCache.computeIfAbsent(brand, k -> new ArrayList<>())
            .add(new MemoryEntry(text, Instant.now()));
        
        // Keep only recent entries
        List<MemoryEntry> entries = inMemoryCache.get(brand);
        if (entries.size() > maxEntries) {
            entries.subList(0, entries.size() - maxEntries).clear();
        }
        
        // Increment processing count
        processingCount.merge(brand, 1, Integer::sum);
        
        // Check if we need to summarize
        if (processingCount.get(brand) >= summarizeEvery) {
            summarizeMemories(brand);
            processingCount.put(brand, 0);
        }
    }
    
    public Uni<Void> summarizeMemories(String brand) {
        List<MemoryEntry> entries = inMemoryCache.get(brand);
        if (entries == null || entries.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        
        // Build summary prompt
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Summarize the following radio DJ interactions into a concise memory:\n\n");
        
        for (MemoryEntry entry : entries) {
            promptBuilder.append("- ").append(entry.getText()).append("\n");
        }
        
        promptBuilder.append("\nProvide a brief summary that captures the key patterns and context.");
        
        return llmClient.invoke(promptBuilder.toString(), "GROQ")
            .chain(summary -> {
                BrandMemory memory = new BrandMemory();
                memory.setBrand(brand);
                memory.setDate(LocalDate.now());
                memory.setSummary(summary);
                
                return repository.save(memory)
                    .invoke(() -> {
                        // Clear cache after successful save
                        inMemoryCache.remove(brand);
                        LOGGER.info("Summarized and saved memory for brand: " + brand);
                    });
            })
            .onFailure().invoke(e -> 
                LOGGER.error("Failed to summarize memories for brand: " + brand, e)
            )
            .replaceWithVoid();
    }
    
    @io.quarkus.scheduler.Scheduled(every = "5m")
    public Uni<Void> scheduledSummarizeMemories() {
        LOGGER.info("Running scheduled memory summarization");
        
        List<Uni<Void>> tasks = new ArrayList<>();
        for (String brand : new ArrayList<>(inMemoryCache.keySet())) {
            if (processingCount.getOrDefault(brand, 0) >= summarizeEvery) {
                tasks.add(summarizeMemories(brand));
                processingCount.put(brand, 0);
            }
        }
        
        if (tasks.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        
        return Uni.join().all(tasks).andFailFast().replaceWithVoid();
    }
}
