# AIVOX - AI Voice Broadcasting Microservice

## Overview

AIVOX is a Quarkus-based microservice that consumes radio station data from a queue, generates AI-powered DJ intros using LLM and TTS, and handles HLS streaming directly.

## Features

- **AI-Powered DJ Intros**: Generate radio intros using multiple LLM providers (Groq, OpenAI, Anthropic)
- **Text-to-Speech**: Support for ElevenLabs and Azure TTS engines
- **HLS Streaming**: Direct streaming with multiple bitrate support
- **Memory Management**: Brand-specific context memory for personalized intros
- **Reactive Architecture**: Built with Quarkus and Mutiny for non-blocking operations

## Architecture

### Core Components

1. **Data Models** (`com.semantyca.aivox.model`)
   - `LiveRadioStationDTO`: Radio station configuration
   - `SongPromptDTO`: Song metadata and prompt templates
   - `TtsDTO`: TTS configuration

2. **AI Pipeline** (`com.semantyca.aivox.service`)
   - `RadioDJProcessor`: Main processing pipeline
   - 3-step flow: Generate intros → Create audio → Queue for streaming

3. **LLM Integration** (`com.semantyca.aivox.llm`)
   - Support for Groq, OpenAI, and Anthropic
   - Configurable models per prompt

4. **TTS Integration** (`com.semantyca.aivox.tts`)
   - ElevenLabs (primary, supports dialogue)
   - Azure (fallback)

5. **HLS Streaming** (`com.semantyca.aivox.streaming`)
   - `StreamManager`: Core HLS streaming engine
   - `PlaylistManager`: Queue management
   - `AudioSegmentationService`: FFmpeg-based segmentation

6. **Memory Management** (`com.semantyca.aivox.memory`)
   - Brand-specific context memory
   - Automatic summarization

## Configuration

### Environment Variables

```bash
# LLM Providers
GROQ_API_KEY=your_groq_api_key
OPENAI_API_KEY=your_openai_api_key
ANTHROPIC_API_KEY=your_anthropic_api_key

# TTS
ELEVENLABS_API_KEY=your_elevenlabs_api_key
AZURE_TTS_KEY=your_azure_tts_key
AZURE_TTS_REGION=eastus

# Database
DB_URL=jdbc:postgresql://localhost:5432/aivox
DB_USER=aivox
DB_PASSWORD=aivox

# RabbitMQ (optional)
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest

# FFmpeg
FFMPEG_PATH=/usr/bin/ffmpeg
FFPROBE_PATH=/usr/bin/ffprobe
```

### Application Properties

Key configurations in `application.properties`:

- `hls.segment.duration`: Segment duration in seconds (default: 2)
- `hls.max.visible.segments`: Maximum segments in playlist (default: 20)
- `hls.bitrates`: Available bitrates (default: 128000,256000)
- `memory.max.entries`: Max memory entries before summarization (default: 20)
- `memory.summarize.every`: Summarize after N processing cycles (default: 5)

## API Endpoints

### Streaming Endpoints

- `GET /stream/{brand}/master.m3u8` - Master playlist with bitrate variants
- `GET /stream/{brand}/stream.m3u8?bitrate={bitrate}` - Media playlist for specific bitrate
- `GET /stream/{brand}/segments/{segmentFile}` - Individual TS segments

### Health & Monitoring

- `GET /q/health` - Health checks
- `GET /q/metrics` - Metrics endpoint
- `GET /api-docs` - OpenAPI documentation
- `GET /swagger-ui` - Swagger UI

## Running the Application

### Prerequisites

- Java 21+
- Maven 3.8+
- PostgreSQL
- FFmpeg/FFprobe
- RabbitMQ (optional)

### Development Mode

```bash
mvn quarkus:dev
```

### Production Mode

```bash
mvn clean package
java -jar target/aivox-runner.jar
```

### Docker

```bash
docker build -f src/main/docker/Dockerfile.jvm -t aivox .
docker run -i --rm -p 8081:8081 aivox
```

## Queue Integration

The service consumes `LiveRadioStationDTO` messages from the `live-stations` queue. Each message should contain:

- Station metadata (name, language, TTS configuration)
- List of song prompts with metadata
- LLM and TTS preferences

## Memory System

The Brand Memory Manager maintains context for each radio brand:

1. **In-Memory Cache**: Recent interactions
2. **Database Storage**: Summarized memories by date
3. **Automatic Summarization**: Every N processing cycles

## HLS Streaming

The streaming system provides:

- Multiple bitrate support (128k, 256k by default)
- Sliding window of segments
- Automatic segment feeding
- Priority queue for AI-generated content

## Development Notes

- The service is built with reactive programming using Mutiny
- All external API calls are non-blocking
- Error handling includes retry logic and fallbacks
- Logging is configured at DEBUG level for the package

## TODO

- [ ] Add authentication/authorization
- [ ] Implement proper audio merging for crossfade
- [ ] Add more TTS providers
- [ ] Implement streaming analytics
- [ ] Add unit and integration tests
- [ ] Create Kubernetes deployment manifests

