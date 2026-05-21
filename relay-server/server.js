const http = require("http");
const { WebSocketServer, WebSocket } = require("ws");

const PORT = Number(process.env.PORT) || 8080;
const rooms = new Map();

function sendJson(ws, payload) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function getRoomState(roomId) {
  let room = rooms.get(roomId);
  if (!room) {
    room = {
      android: null,
      windows: null,
    };
    rooms.set(roomId, room);
  }
  return room;
}

function cleanupRoomIfEmpty(roomId) {
  const room = rooms.get(roomId);
  if (!room) {
    return;
  }

  if (!room.android && !room.windows) {
    rooms.delete(roomId);
  }
}

function clearExistingRole(room, role, incomingSocket) {
  const existing = room[role];
  if (existing && existing !== incomingSocket) {
    sendJson(existing, {
      type: "error",
      message: `Another ${role} client joined this room.`,
    });
    existing.close(4009, `duplicate ${role}`);
  }
}

function getPeer(room, role) {
  return role === "android" ? room.windows : room.android;
}

function validateJoin(message) {
  if (!message || typeof message !== "object") {
    return "Invalid payload.";
  }
  if (message.type !== "join") {
    return "First message must be a join message.";
  }
  if (message.role !== "android" && message.role !== "windows") {
    return "Role must be android or windows.";
  }
  if (typeof message.roomId !== "string" || !message.roomId.trim()) {
    return "roomId is required.";
  }
  return null;
}

function handleJoin(ws, message) {
  const roomId = message.roomId.trim();
  const role = message.role;
  const room = getRoomState(roomId);

  clearExistingRole(room, role, ws);
  room[role] = ws;

  ws.meta = {
    roomId,
    role,
  };

  sendJson(ws, {
    type: "joined",
    roomId,
    role,
  });

  const peer = getPeer(room, role);
  if (peer) {
    sendJson(ws, {
      type: "paired",
      roomId,
      peerRole: peer.meta.role,
    });
    sendJson(peer, {
      type: "paired",
      roomId,
      peerRole: role,
    });
  }
}

function handleRelay(ws, message) {
  if (!ws.meta) {
    sendJson(ws, {
      type: "error",
      message: "Join the room before sending relay messages.",
    });
    return;
  }

  const room = rooms.get(ws.meta.roomId);
  if (!room) {
    sendJson(ws, {
      type: "error",
      message: "Room not found.",
    });
    return;
  }

  const peer = getPeer(room, ws.meta.role);
  if (!peer) {
    sendJson(ws, {
      type: "peer_missing",
      message: "Peer is not connected.",
    });
    return;
  }

  sendJson(peer, {
    ...message,
    from: ws.meta.role,
    roomId: ws.meta.roomId,
  });
}

function handleDisconnect(ws) {
  if (!ws.meta) {
    return;
  }

  const { roomId, role } = ws.meta;
  const room = rooms.get(roomId);
  if (!room) {
    return;
  }

  if (room[role] === ws) {
    room[role] = null;
  }

  const peer = getPeer(room, role);
  if (peer) {
    sendJson(peer, {
      type: "peer_disconnected",
      roomId,
      peerRole: role,
    });
    if (role === "android") {
      sendJson(peer, {
        type: "release_all",
        roomId,
        reason: "android_disconnected",
      });
    }
  }

  cleanupRoomIfEmpty(roomId);
}

const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    return;
  }

  res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
  res.end("Android/Windows relay server is running.\n");
});

const wss = new WebSocketServer({ server });

wss.on("connection", (ws) => {
  ws.isAlive = true;
  ws.meta = null;

  ws.on("pong", () => {
    ws.isAlive = true;
  });

  ws.on("message", (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      sendJson(ws, {
        type: "error",
        message: "Message must be valid JSON.",
      });
      return;
    }

    if (!ws.meta) {
      const validationError = validateJoin(message);
      if (validationError) {
        sendJson(ws, {
          type: "error",
          message: validationError,
        });
        return;
      }
      handleJoin(ws, message);
      return;
    }

    handleRelay(ws, message);
  });

  ws.on("close", () => {
    handleDisconnect(ws);
  });

  ws.on("error", () => {
    handleDisconnect(ws);
  });
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      ws.terminate();
      continue;
    }

    ws.isAlive = false;
    ws.ping();
  }
}, 30000);

wss.on("close", () => {
  clearInterval(heartbeat);
});

server.listen(PORT, () => {
  console.log(`Relay server listening on port ${PORT}`);
});
