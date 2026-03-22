FROM eclipse-temurin:21-jre-jammy
  RUN groupadd -r aivox && useradd -r -g aivox aivox
  RUN apt-get update && apt-get install -y libavdevice58 libavfilter7 libavformat58 libavcodec58 libswresample3 libswscale5 && rm -rf /var/lib/apt/lists/*
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