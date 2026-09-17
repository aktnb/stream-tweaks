# Streamer Tweaks

> Connect Minecraft with Twitch

StreamTweaks is a client-side Minecraft mod for Fabric and NeoForge that bridges your Minecraft gameplay with Twitch, making streaming more interactive and engaging. It allows you to see Twitch chat directly inside Minecraft and provides tools for smoother interaction with your audience.

## Features

- 💬 **Display Twitch Chat in Minecraft** — Stay connected with your viewers without leaving the game.
- 🔐 **Twitch OAuth Authentication** — Securely sign in with your Twitch account directly from the game.
- 🗑️ **Deleted Messages Disappear** — When a moderator deletes a message on Twitch, the corresponding line is removed from the Minecraft chat.
- 😀 **Twitch Emotes** — Static and animated (GIF) Twitch emotes render inline in the Minecraft chat.
- 🎨 **Chat Name Colors** — Usernames are displayed using each viewer's Twitch chat color.
- ⚙️ **ModMenu Integration (Fabric only)** — Check your authentication status and connected channels, and log in/out or connect/disconnect channels from a GUI config screen.

> **Scope of message deletion:** Only individual message deletions by moderators (`channel.chat.message_delete`) are supported. Bulk removals caused by a **timeout**, **ban**, or **chat clear** are *not* mirrored — those messages stay visible in the Minecraft chat.
>
> **Note on logs:** Chat messages are written to `logs/latest.log` as they are displayed, the same as any other Minecraft chat line. A message deleted on Twitch is removed from the chat display only — its text remains in the log file in plain text.

### Planned Enhancements

- Chat moderation tools (ban, etc.)
- Event notifications (follows, subs, raids)
- Audience interaction features

## Requirements

- Minecraft 26.2
- Java 25+
- Fabric:
  - [Fabric Mod Loader](https://fabricmc.net/use/installer/) 0.19.3+
  - [Fabric API](https://modrinth.com/mod/fabric-api)
  - [ModMenu](https://modrinth.com/mod/modmenu) (optional, Fabric only) — enables the in-game config screen
- NeoForge:
  - NeoForge 26.2.0.25-beta+
- Port **7654** available on localhost (used for Twitch OAuth callback)

## Installation

1. Install Fabric or NeoForge for Minecraft 26.2.
2. For Fabric, download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your `mods` folder.
3. Download the matching StreamTweaks `.jar` from the [Releases](../../releases) page:
   - Fabric: `stream-tweaks-fabric-mc26.2-<version>.jar`
   - NeoForge: `stream-tweaks-neoforge-mc26.2-<version>.jar`
4. Place the StreamTweaks `.jar` into your `.minecraft/mods` folder.
5. Launch Minecraft with the matching Fabric or NeoForge profile.

## Usage

The `/twitch` commands are available on both Fabric and NeoForge. The ModMenu configuration screen is only available in the Fabric build.

### Step 1 — Authenticate with Twitch

```
/twitch login
```

A clickable link appears in chat. Click it to open the Twitch authorization page in your browser. After approving, a success message is shown in Minecraft. If you don't complete the authorization in time, the request will time out — just run `/twitch login` again.

> **Note:** No Twitch Developer account or app setup is required. StreamTweaks uses its own built-in Twitch application. Your credentials are saved locally and persist across game restarts, so you only need to log in once.

### Step 2 — Connect to a Twitch Channel

```
/twitch connect <channel>
```

Replace `<channel>` with the Twitch channel name (e.g., `/twitch connect etwas`).
Chat messages from that channel will start appearing inside Minecraft.

### Step 3 — Disconnect from a Channel

```
/twitch disconnect <channel>
```

Stop receiving chat from the specified channel. Tab completion is supported to list your currently connected channels.

### Logging Out

```
/twitch logout
```

Logs out of Twitch and automatically disconnects from all subscribed channels.

## License

MIT License — see [LICENSE.txt](LICENSE.txt).
