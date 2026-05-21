# Windows Client

`VirtualKeypadWindowsClient` is a C# console app that:

- connects to the relay server with WebSocket
- joins as the `windows` peer for a `roomId`
- receives `key_down`, `key_up`, and `release_all`
- sends keyboard input with `SendInput`
- releases all pressed keys on disconnect

## Files

- `VirtualKeypadWindowsClient/VirtualKeypadWindowsClient.csproj`
- `VirtualKeypadWindowsClient/Program.cs`

## Run

Requires the .NET 8 SDK on Windows.

```powershell
dotnet run --project .\VirtualKeypadWindowsClient\VirtualKeypadWindowsClient.csproj -- --url wss://virtualkeypadandwin-production.up.railway.app --room test123
```

Or use environment variables:

```powershell
$env:VKP_SERVER_URL='wss://virtualkeypadandwin-production.up.railway.app'
$env:VKP_ROOM_ID='test123'
dotnet run --project .\VirtualKeypadWindowsClient\VirtualKeypadWindowsClient.csproj
```

## Supported Keys

- Arrow keys
- `A-Z`
- `0-9`
- `Space`
- `Enter`
- `Escape`
- `Shift`
- `Control`
- `Alt`
- `Tab`
- `Backspace`
