using System.Drawing;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;

ApplicationConfiguration.Initialize();
Application.Run(new MainForm());

internal sealed class MainForm : Form
{
    private readonly Label _serverUrlValueLabel;
    private readonly TextBox _roomIdTextBox;
    private readonly Button _connectButton;
    private readonly Button _disconnectButton;
    private readonly Button _toggleLogsButton;
    private readonly Label _serverStatusValueLabel;
    private readonly Label _roomStatusValueLabel;
    private readonly Label _peerStatusValueLabel;
    private readonly Label _lastKeyValueLabel;
    private readonly TextBox _logTextBox;
    private readonly Panel _logPanel;
    private readonly RelayClientController _controller = new();
    private bool _logsVisible;

    public MainForm()
    {
        Text = "Virtual Keypad Windows Client";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(620, 360);
        Size = new Size(720, 420);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(16)
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));

        var headerLabel = new Label
        {
            AutoSize = true,
            Font = new Font(SystemFonts.MessageBoxFont!, FontStyle.Bold),
            Text = "Android relay input receiver"
        };
        root.Controls.Add(headerLabel);

        var controlsLayout = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0, 12, 0, 12)
        };
        controlsLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        controlsLayout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        var inputPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 2,
            AutoSize = true
        };
        inputPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        inputPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));

        inputPanel.Controls.Add(CreateCaptionLabel("Server URL"), 0, 0);
        _serverUrlValueLabel = new Label
        {
            AutoSize = true,
            Text = RelayClientOptions.DefaultServerUrl
        };
        inputPanel.Controls.Add(_serverUrlValueLabel, 1, 0);

        inputPanel.Controls.Add(CreateCaptionLabel("Room ID"), 0, 1);
        _roomIdTextBox = new TextBox
        {
            Dock = DockStyle.Fill,
            Text = RelayClientOptions.DefaultRoomId
        };
        inputPanel.Controls.Add(_roomIdTextBox, 1, 1);

        controlsLayout.Controls.Add(inputPanel, 0, 0);

        var buttonPanel = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false,
            Dock = DockStyle.Fill,
            Margin = new Padding(12, 0, 0, 0)
        };

        _connectButton = new Button
        {
            AutoSize = true,
            Text = "Connect"
        };
        _connectButton.Click += async (_, _) => await ConnectAsync();

        _disconnectButton = new Button
        {
            AutoSize = true,
            Text = "Disconnect",
            Enabled = false
        };
        _disconnectButton.Click += async (_, _) => await DisconnectAsync();

        _toggleLogsButton = new Button
        {
            AutoSize = true,
            Text = "Show Debug Logs"
        };
        _toggleLogsButton.Click += (_, _) => ToggleLogs();

        buttonPanel.Controls.Add(_connectButton);
        buttonPanel.Controls.Add(_disconnectButton);
        buttonPanel.Controls.Add(_toggleLogsButton);
        controlsLayout.Controls.Add(buttonPanel, 1, 0);

        root.Controls.Add(controlsLayout);

        var statusCard = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 4,
            CellBorderStyle = TableLayoutPanelCellBorderStyle.Single,
            Margin = new Padding(0)
        };
        statusCard.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170));
        statusCard.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));

        statusCard.Controls.Add(CreateCaptionLabel("Server Connection"), 0, 0);
        _serverStatusValueLabel = CreateValueLabel("Disconnected");
        statusCard.Controls.Add(_serverStatusValueLabel, 1, 0);

        statusCard.Controls.Add(CreateCaptionLabel("Room Join"), 0, 1);
        _roomStatusValueLabel = CreateValueLabel("Not joined");
        statusCard.Controls.Add(_roomStatusValueLabel, 1, 1);

        statusCard.Controls.Add(CreateCaptionLabel("Android Peer"), 0, 2);
        _peerStatusValueLabel = CreateValueLabel("Not connected");
        statusCard.Controls.Add(_peerStatusValueLabel, 1, 2);

        statusCard.Controls.Add(CreateCaptionLabel("Last Input"), 0, 3);
        _lastKeyValueLabel = CreateValueLabel("-");
        statusCard.Controls.Add(_lastKeyValueLabel, 1, 3);

        root.Controls.Add(statusCard);

        _logTextBox = new TextBox
        {
            Dock = DockStyle.Fill,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            WordWrap = false
        };

        _logPanel = new Panel
        {
            Dock = DockStyle.Bottom,
            Height = 180,
            Visible = false,
            Padding = new Padding(0, 12, 0, 0)
        };
        _logPanel.Controls.Add(_logTextBox);
        root.Controls.Add(_logPanel);

        Controls.Add(root);

        _controller.ServerStateChanged += state => InvokeOnUi(() =>
        {
            _serverStatusValueLabel.Text = state;
            UpdateButtons();
        });
        _controller.RoomStateChanged += state => InvokeOnUi(() => _roomStatusValueLabel.Text = state);
        _controller.PeerStateChanged += state => InvokeOnUi(() => _peerStatusValueLabel.Text = state);
        _controller.LastInputChanged += value => InvokeOnUi(() => _lastKeyValueLabel.Text = value);
        _controller.LogReceived += message => InvokeOnUi(() => AppendLog(message));
        _controller.ConnectionEnded += reason => InvokeOnUi(() =>
        {
            _serverStatusValueLabel.Text = "Disconnected";
            _roomStatusValueLabel.Text = "Not joined";
            _peerStatusValueLabel.Text = "Not connected";
            _lastKeyValueLabel.Text = "-";
            UpdateButtons();

            if (!string.IsNullOrWhiteSpace(reason))
            {
                AppendLog($"[disconnect] {reason}");
            }
        });

        FormClosing += async (_, _) =>
        {
            await _controller.DisconnectAsync();
        };
    }

    private async Task ConnectAsync()
    {
        var roomId = _roomIdTextBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(roomId))
        {
            MessageBox.Show(this, "Room ID를 입력해주세요.", "Room ID Required", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            _roomIdTextBox.Focus();
            return;
        }

        _serverStatusValueLabel.Text = "Connecting";
        _roomStatusValueLabel.Text = "Waiting to join";
        _peerStatusValueLabel.Text = "Waiting";
        _lastKeyValueLabel.Text = "-";
        UpdateButtons();

        try
        {
            await _controller.ConnectAsync(new RelayClientOptions
            {
                ServerUrl = new Uri(RelayClientOptions.DefaultServerUrl),
                RoomId = roomId
            });
        }
        catch (Exception ex)
        {
            _serverStatusValueLabel.Text = "Connection failed";
            _roomStatusValueLabel.Text = "Not joined";
            _peerStatusValueLabel.Text = "Not connected";
            UpdateButtons();
            MessageBox.Show(this, ex.Message, "Connection Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            AppendLog($"[error] {ex.Message}");
        }
    }

    private async Task DisconnectAsync()
    {
        await _controller.DisconnectAsync();
    }

    private void ToggleLogs()
    {
        _logsVisible = !_logsVisible;
        _logPanel.Visible = _logsVisible;
        _toggleLogsButton.Text = _logsVisible ? "Hide Debug Logs" : "Show Debug Logs";
    }

    private void UpdateButtons()
    {
        var connected = _controller.IsConnected;
        _connectButton.Enabled = !connected;
        _disconnectButton.Enabled = connected;
        _roomIdTextBox.Enabled = !connected;
    }

    private void AppendLog(string message)
    {
        var line = $"[{DateTime.Now:HH:mm:ss}] {message}";
        _logTextBox.AppendText(line + Environment.NewLine);
    }

    private void InvokeOnUi(Action action)
    {
        if (IsDisposed)
        {
            return;
        }

        if (InvokeRequired)
        {
            BeginInvoke(action);
            return;
        }

        action();
    }

    private static Label CreateCaptionLabel(string text)
    {
        return new Label
        {
            AutoSize = true,
            Text = text,
            Margin = new Padding(0, 8, 12, 8),
            Font = new Font(SystemFonts.MessageBoxFont!, FontStyle.Bold)
        };
    }

    private static Label CreateValueLabel(string text)
    {
        return new Label
        {
            AutoSize = true,
            Text = text,
            Margin = new Padding(0, 8, 0, 8)
        };
    }
}

internal sealed class RelayClientController
{
    private RelaySession? _session;

    public bool IsConnected => _session?.IsConnected == true;

    public event Action<string>? ServerStateChanged;
    public event Action<string>? RoomStateChanged;
    public event Action<string>? PeerStateChanged;
    public event Action<string>? LastInputChanged;
    public event Action<string>? LogReceived;
    public event Action<string>? ConnectionEnded;

    public async Task ConnectAsync(RelayClientOptions options)
    {
        if (_session is not null)
        {
            throw new InvalidOperationException("Already connected.");
        }

        var session = new RelaySession(options, new KeyboardInputSender());
        session.ServerStateChanged += value => ServerStateChanged?.Invoke(value);
        session.RoomStateChanged += value => RoomStateChanged?.Invoke(value);
        session.PeerStateChanged += value => PeerStateChanged?.Invoke(value);
        session.LastInputChanged += value => LastInputChanged?.Invoke(value);
        session.LogReceived += value => LogReceived?.Invoke(value);
        session.ConnectionEnded += reason =>
        {
            _session = null;
            ConnectionEnded?.Invoke(reason);
        };

        _session = session;
        await session.StartAsync();
    }

    public async Task DisconnectAsync()
    {
        if (_session is null)
        {
            return;
        }

        await _session.DisconnectAsync("manual_disconnect");
        _session = null;
    }
}

internal sealed class RelaySession
{
    private readonly RelayClientOptions _options;
    private readonly KeyboardInputSender _keyboard;
    private readonly ClientWebSocket _socket = new();
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _shutdownLock = new(1, 1);
    private Task? _receiveLoopTask;
    private bool _shutdownRaised;

    public RelaySession(RelayClientOptions options, KeyboardInputSender keyboard)
    {
        _options = options;
        _keyboard = keyboard;
    }

    public bool IsConnected => _socket.State == WebSocketState.Open;

    public event Action<string>? ServerStateChanged;
    public event Action<string>? RoomStateChanged;
    public event Action<string>? PeerStateChanged;
    public event Action<string>? LastInputChanged;
    public event Action<string>? LogReceived;
    public event Action<string>? ConnectionEnded;

    public async Task StartAsync()
    {
        LogReceived?.Invoke($"[connect] {_options.ServerUrl} room={_options.RoomId}");
        ServerStateChanged?.Invoke("Connecting to relay server");
        PeerStateChanged?.Invoke("Waiting");
        RoomStateChanged?.Invoke("Joining room");

        await _socket.ConnectAsync(_options.ServerUrl, _cts.Token);
        ServerStateChanged?.Invoke("Connected to relay server");

        await SendJoinAsync();
        _receiveLoopTask = Task.Run(ReceiveLoopAsync);
    }

    public async Task DisconnectAsync(string reason)
    {
        await ShutdownAsync(reason);
    }

    private async Task SendJoinAsync()
    {
        var payload = JsonSerializer.Serialize(new
        {
            type = "join",
            role = "windows",
            roomId = _options.RoomId
        });

        var bytes = Encoding.UTF8.GetBytes(payload);
        await _socket.SendAsync(bytes, WebSocketMessageType.Text, true, _cts.Token);
        LogReceived?.Invoke($"> {payload}");
    }

    private async Task ReceiveLoopAsync()
    {
        var buffer = new byte[8192];

        try
        {
            while (!_cts.IsCancellationRequested && _socket.State == WebSocketState.Open)
            {
                var message = await ReceiveMessageAsync(buffer);
                if (message is null)
                {
                    break;
                }

                HandleMessage(message);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            LogReceived?.Invoke($"[error] {ex.Message}");
        }
        finally
        {
            await ShutdownAsync("connection_closed");
        }
    }

    private async Task<string?> ReceiveMessageAsync(byte[] buffer)
    {
        using var stream = new MemoryStream();

        while (true)
        {
            var result = await _socket.ReceiveAsync(buffer, _cts.Token);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                return null;
            }

            stream.Write(buffer, 0, result.Count);

            if (result.EndOfMessage)
            {
                break;
            }
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    private void HandleMessage(string rawMessage)
    {
        LogReceived?.Invoke($"< {rawMessage}");

        using var document = JsonDocument.Parse(rawMessage);
        var root = document.RootElement;
        var type = root.TryGetProperty("type", out var typeNode) ? typeNode.GetString() : null;
        if (string.IsNullOrWhiteSpace(type))
        {
            return;
        }

        switch (type)
        {
            case "joined":
                RoomStateChanged?.Invoke($"Joined room '{_options.RoomId}'");
                PeerStateChanged?.Invoke("Waiting for Android");
                return;

            case "paired":
                RoomStateChanged?.Invoke($"Joined room '{_options.RoomId}'");
                PeerStateChanged?.Invoke("Android connected");
                return;

            case "peer_missing":
                PeerStateChanged?.Invoke("Android not connected");
                return;

            case "peer_disconnected":
                PeerStateChanged?.Invoke("Android disconnected");
                _keyboard.ReleaseAll();
                return;

            case "release_all":
                LastInputChanged?.Invoke("release_all");
                _keyboard.ReleaseAll();
                return;

            case "key_down":
                if (TryGetKey(root, out var downKey))
                {
                    LastInputChanged?.Invoke($"key_down {downKey}");
                    _keyboard.KeyDown(downKey, LogReceived);
                }
                return;

            case "key_up":
                if (TryGetKey(root, out var upKey))
                {
                    LastInputChanged?.Invoke($"key_up {upKey}");
                    _keyboard.KeyUp(upKey, LogReceived);
                }
                return;

            case "error":
                var message = root.TryGetProperty("message", out var messageNode)
                    ? messageNode.GetString()
                    : "Unknown server error.";
                LogReceived?.Invoke($"[server-error] {message}");
                return;
        }
    }

    private static bool TryGetKey(JsonElement root, out string key)
    {
        key = string.Empty;
        if (!root.TryGetProperty("key", out var keyNode))
        {
            return false;
        }

        key = keyNode.GetString() ?? string.Empty;
        return !string.IsNullOrWhiteSpace(key);
    }

    private async Task ShutdownAsync(string reason)
    {
        await _shutdownLock.WaitAsync();

        try
        {
            if (_shutdownRaised)
            {
                return;
            }

            _shutdownRaised = true;
            _cts.Cancel();
            _keyboard.ReleaseAll();

            if (_socket.State == WebSocketState.Open || _socket.State == WebSocketState.CloseReceived)
            {
                try
                {
                    await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, reason, CancellationToken.None);
                }
                catch
                {
                }
            }
        }
        finally
        {
            _shutdownLock.Release();
        }

        ConnectionEnded?.Invoke(reason);
    }
}

internal sealed class RelayClientOptions
{
    public const string DefaultServerUrl = "wss://virtualkeypadandwin-production.up.railway.app";
    public const string DefaultRoomId = "test123";

    public required Uri ServerUrl { get; init; }
    public required string RoomId { get; init; }
}

internal sealed class KeyboardInputSender
{
    private readonly HashSet<ushort> _pressedKeys = [];

    public void KeyDown(string key, Action<string>? log)
    {
        if (!VirtualKeyMap.TryResolve(key, out var virtualKey))
        {
            log?.Invoke($"[warn] Unsupported key: {key}");
            return;
        }

        if (_pressedKeys.Contains(virtualKey))
        {
            return;
        }

        Send(virtualKey, keyUp: false, log);
        _pressedKeys.Add(virtualKey);
    }

    public void KeyUp(string key, Action<string>? log)
    {
        if (!VirtualKeyMap.TryResolve(key, out var virtualKey))
        {
            log?.Invoke($"[warn] Unsupported key: {key}");
            return;
        }

        if (!_pressedKeys.Contains(virtualKey))
        {
            return;
        }

        Send(virtualKey, keyUp: true, log);
        _pressedKeys.Remove(virtualKey);
    }

    public void ReleaseAll()
    {
        foreach (var virtualKey in _pressedKeys.ToArray())
        {
            Send(virtualKey, keyUp: true, log: null);
            _pressedKeys.Remove(virtualKey);
        }
    }

    private static void Send(ushort virtualKey, bool keyUp, Action<string>? log)
    {
        var input = new INPUT
        {
            type = 1,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = virtualKey,
                    dwFlags = keyUp ? 0x0002u : 0u
                }
            }
        };

        var sent = SendInput(1, [input], Marshal.SizeOf<INPUT>());
        if (sent == 0)
        {
            var error = Marshal.GetLastWin32Error();
            log?.Invoke($"[error] SendInput failed for vk={virtualKey}, error={error}");
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)]
        public KEYBDINPUT ki;

        [FieldOffset(0)]
        public MOUSEINPUT mi;

        [FieldOffset(0)]
        public HARDWAREINPUT hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public nint dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public nint dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HARDWAREINPUT
    {
        public uint uMsg;
        public ushort wParamL;
        public ushort wParamH;
    }
}

internal static class VirtualKeyMap
{
    private static readonly Dictionary<string, ushort> KeyMap =
        new(StringComparer.OrdinalIgnoreCase)
        {
            ["ArrowUp"] = 0x26,
            ["ArrowDown"] = 0x28,
            ["ArrowLeft"] = 0x25,
            ["ArrowRight"] = 0x27,
            ["Up"] = 0x26,
            ["Down"] = 0x28,
            ["Left"] = 0x25,
            ["Right"] = 0x27,
            ["Space"] = 0x20,
            ["Enter"] = 0x0D,
            ["Escape"] = 0x1B,
            ["Esc"] = 0x1B,
            ["Shift"] = 0x10,
            ["Control"] = 0x11,
            ["Ctrl"] = 0x11,
            ["Alt"] = 0x12,
            ["Tab"] = 0x09,
            ["Backspace"] = 0x08,
            ["NumPad0"] = 0x60,
            ["NumPad1"] = 0x61,
            ["NumPad2"] = 0x62,
            ["NumPad3"] = 0x63,
            ["NumPad4"] = 0x64,
            ["NumPad5"] = 0x65,
            ["NumPad6"] = 0x66,
            ["NumPad7"] = 0x67,
            ["NumPad8"] = 0x68,
            ["NumPad9"] = 0x69,
            ["Numpad0"] = 0x60,
            ["Numpad1"] = 0x61,
            ["Numpad2"] = 0x62,
            ["Numpad3"] = 0x63,
            ["Numpad4"] = 0x64,
            ["Numpad5"] = 0x65,
            ["Numpad6"] = 0x66,
            ["Numpad7"] = 0x67,
            ["Numpad8"] = 0x68,
            ["Numpad9"] = 0x69
        };

    public static bool TryResolve(string key, out ushort virtualKey)
    {
        if (KeyMap.TryGetValue(key, out virtualKey))
        {
            return true;
        }

        if (key.Length == 1)
        {
            var ch = char.ToUpperInvariant(key[0]);
            if (ch is >= 'A' and <= 'Z')
            {
                virtualKey = ch;
                return true;
            }

            if (ch is >= '0' and <= '9')
            {
                virtualKey = ch;
                return true;
            }
        }

        virtualKey = 0;
        return false;
    }
}
