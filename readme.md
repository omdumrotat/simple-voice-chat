# Simple Voice Chat – Bukkit/Paper plugin with standalone server (Minecraft 1.20.6)

Fork of [henkelmax/simple-voice-chat](https://github.com/henkelmax/simple-voice-chat) (voice chat 2.5.x,
**compatibility version 18**, i.e. works with the 1.20.6 client mod `voicechat-1.20.6-2.5.x`).

Modules:

| Module | Output |
| --- | --- |
| `bukkit` | `bukkit/build/libs/voicechat-bukkit-*.jar` – the plugin (Spigot/Paper, runs on 1.8 – 1.21.x incl. 1.20.6) |
| `common-proxy` | `common-proxy/build/libs/*-standalone.jar` – standalone voice chat server (`java -jar`) |
| `api` | Voice chat API |

## Standalone server

The standalone server handles the UDP voice traffic so it can run on a different host/port than the game server
(e.g. behind a BungeeCord/Velocity network without installing anything on the proxy).
The game server plugin pushes secrets, positions and groups to it over a TCP control connection (default: same port number).

1. Start the standalone server once: `java -jar voicechat-...-standalone.jar`. It writes
   `config/voicechat-standalone.properties`. If `control_password` is empty, a long random one is generated, saved to that
   file and printed once in the log (it refuses to start if the file can't be written).
2. Copy that value (or set your own long random `control_password` and restart).
3. In the plugin's `voicechat-server.properties` set:
   ```
   standalone_server=true
   standalone_host=<public host clients use for voice chat>
   standalone_port=24454
   standalone_password=<same value as control_password>
   ```
4. Open the UDP port to players. **Firewall the TCP control port so only the game server can reach it**
   (it is password protected, but there is no reason to expose it).

The control connection (game server → standalone) is authenticated and encrypted (AES-256-GCM, keys derived from the shared password), so
it is safe to use over untrusted networks as long as the password is long and random. The UDP voice traffic is encrypted per player as usual.

Notes: the standalone server does not know about sneaking, so the 'sneak' distance reduction is not applied.

## Docker

```
./gradlew :common-proxy:shadowJar
docker build -t voicechat-standalone .      # or: podman build ...
docker run -d -p 24454:24454/udp -p 24454:24454/tcp -v ./voicechat-config:/app/config voicechat-standalone
```

The container runs as uid 1000, so the mounted config directory must be writable by uid 1000
(with rootless Podman add `--userns=keep-id:uid=1000,gid=1000`). Create `voicechat-config/voicechat-standalone.properties`
with at least `control_password=...` before the first start.

## Build

Requires JDK 21.

```
./gradlew :bukkit:shadowJar :common-proxy:shadowJar
```

The build scripts are downloaded from `raw.githubusercontent.com`, so network access is required.
CI (`.gitlab-ci.yml`) builds both jars and keeps them as pipeline artifacts.
