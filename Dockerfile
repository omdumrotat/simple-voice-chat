# =============================================================================
# Dockerfile for Simple Voice Chat Standalone Server
# =============================================================================
#
# The standalone JAR is pre-built by CI (or locally via Gradle) and packaged
# into this minimal runtime image. This avoids a multi-stage build that would
# download ~4 GB of Minecraft tooling just to configure unrelated subprojects.
#
# Build locally:
#   ./gradlew common-proxy:shadowJar
#   docker build -t voicechat-standalone .
#
# Override JAR path at build time:
#   docker build --build-arg JAR_FILE=path/to/custom.jar -t voicechat-standalone .
#
# =============================================================================

FROM eclipse-temurin:21-jre-jammy

# OCI image labels
LABEL org.opencontainers.image.title="Simple Voice Chat Standalone Server" \
      org.opencontainers.image.description="Standalone voice chat server for Minecraft" \
      org.opencontainers.image.url="https://github.com/henkelmax/simple-voice-chat" \
      org.opencontainers.image.source="https://github.com/henkelmax/simple-voice-chat" \
      org.opencontainers.image.licenses="Apache-2.0"

# Install dumb-init for proper PID 1 signal handling
RUN apt-get update && \
    apt-get install -y --no-install-recommends dumb-init && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd --gid 1000 voicechat && \
    useradd --uid 1000 --gid voicechat --shell /bin/bash --create-home voicechat

# Application directory
WORKDIR /app

# Copy the pre-built standalone fat JAR
ARG JAR_FILE=common-proxy/build/libs/*-standalone.jar
COPY ${JAR_FILE} /app/voicechat-standalone.jar

# Create config directory and set ownership
RUN mkdir -p /app/config && \
    chown -R voicechat:voicechat /app

# Voice chat UDP port + TCP control port
EXPOSE 24454/udp
EXPOSE 24454/tcp

# Mount point for persistent configuration
VOLUME ["/app/config"]

# Switch to non-root user
USER voicechat

# JVM tuning flags for containers
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+ParallelRefProcEnabled \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom"

# Use dumb-init as PID 1 for proper signal forwarding (SIGTERM → shutdown hook)
ENTRYPOINT ["dumb-init", "--"]

# Run the standalone server
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/voicechat-standalone.jar"]
