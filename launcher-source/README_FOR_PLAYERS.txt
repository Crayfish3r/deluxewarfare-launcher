Deluxe Warfare Launcher
=======================

1. How to start
Run:

  DeluxeWarfareLauncher.exe

2. What the launcher installs
On first launch it downloads and prepares:

  Minecraft 1.20.1
  Forge 1.20.1 from the manifest
  Minecraft libraries
  Minecraft assets
  Native libraries
  Server modpack files from the manifest

3. Nickname
Enter your Minecraft nickname in the Nickname field.
Allowed characters: A-Z, a-z, 0-9, _
Length: 3-16 characters.

4. Java not found
The packaged EXE should include Java runtime.
For dev builds, install JDK 17 or newer.

5. Updates do not download
Check your internet connection and ask the administrator if the manifest server is online.
The manifest URL is stored in:

  %APPDATA%\.tactical-launcher\launcher.properties

Set:

  manifestUrl=https://your-domain.com/manifest

6. Logs
Send this file to the administrator:

  %APPDATA%\.tactical-launcher\logs\launcher-latest.log
7. Auto join server
The launcher can automatically connect to the configured server after Minecraft starts.
This is controlled by:

  %APPDATA%\.tactical-launcher\launcher.properties

Set:

  autoJoinServer=true

To open the Minecraft menu instead, set:

  autoJoinServer=false

