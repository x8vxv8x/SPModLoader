# SubModLoader

SubModLoader is a Java agent for Cleanroom/Forge 1.12.2. It extends the legacy mod discovery directories so mods can be read from additional folders, such as `mods/submods`, while the normal Forge/Cleanroom loading pipeline continues to handle scanning, sorting, coremods, mixin containers, and regular mods.

## Implementation

The agent patches:

```text
net.minecraftforge.fml.relauncher.libraries.LibraryManager.gatherLegacyCanidates(File)
```

It expands the original legacy mod directory array from:

```text
mods
mods/1.12.2
```

to include configured extra directories.

## Configuration

Create or edit `submodloader.properties` in the game directory:

```properties
dirs=mods/submods
```

Multiple directories can be separated with commas.

## Build

```powershell
.\gradlew.bat jar
```
