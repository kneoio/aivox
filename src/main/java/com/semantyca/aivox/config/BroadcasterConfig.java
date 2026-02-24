package com.semantyca.aivox.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "aivox")
public interface BroadcasterConfig {
    
    @WithName("host")
    @WithDefault("http://localhost:8080")
    String getHost();
    
    @WithName("path.uploads")
    @WithDefault("uploads")
    String getPathUploads();
    
    @WithName("path.temp")
    @WithDefault("temp")
    String getPathTemp();
}
