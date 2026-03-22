FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y wget apt-transport-https gnupg && \
    wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && apt-get install -y temurin-21-jre ffmpeg && \
    rm -rf /var/lib/apt/lists/*
RUN groupadd -r aivox && useradd -r -g aivox aivox
RUN mkdir -p /app/segmented /app/merged /app/controller-uploads /app/external /app/file-uploads /var/log/aivox \
    && chown -R aivox:aivox /app /var/log/aivox
WORKDIR /app
COPY third_party/ffmpeg/linux_x86_64/ffmpeg /usr/bin/ffmpeg
COPY third_party/ffmpeg/linux_x86_64/ffprobe /usr/bin/ffprobe
RUN chmod +x /usr/bin/ffmpeg /usr/bin/ffprobe
COPY target/aivox-*-runner.jar app.jar
RUN chown aivox:aivox app.jar
USER aivox
EXPOSE 8080 38798
ENTRYPOINT ["java", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "-jar", "app.jar"]