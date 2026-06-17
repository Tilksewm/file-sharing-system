# FileShare System — Client-to-Client File Transfer

A Java-based peer-to-peer file sharing application with a central server for coordination. Supports both TCP and UDP transfers, direct client-to-client file exchange, and a Swing GUI.

---

## Project Structure

```
fileshare/
├── com/fileshare/server/
│   └── FileServer.java          # Central coordination server
└── com/fileshare/client/
    ├── FileClientGUI.java        # Client instance 1
    ├── FileClientGUI2.java       # Client instance 2 (replica)
    └── FileClientGUI3.java       # Client instance 3 (replica)
```

> `FileClientGUI2.java` and `FileClientGUI3.java` are replicas of `FileClientGUI.java`. Run each in a separate JVM process to simulate multiple peers on the same machine.

---

## How It Works

### Architecture

```
         ┌─────────────────────┐
         │     FileServer      │  (Port 5000 — TCP & UDP)
         │  - Client registry  │
         │  - File storage     │
         │  - Peer lookup      │
         └────────┬────────────┘
                  │  TCP (register, list, upload, download)
        ┌─────────┴──────────────────┐
        │                            │
  ┌─────┴──────┐              ┌──────┴─────┐
  │  Client 1  │◄────────────►│  Client 2  │
  │  Port 12346│   Direct P2P │  Port 12346│
  └────────────┘   TCP socket └────────────┘
```

1. Each client **registers** with the server on startup.
2. Clients query the server for a **list of online peers**.
3. Files can be transferred via the server (PUT/DOWNLOAD) or **directly peer-to-peer** (port 12346).
4. Each client runs its own embedded file-sharing server on port `12346`.

---

## Components

### `FileServer.java`

The central server listens on **port 5000** for both TCP and UDP traffic.

**TCP Commands handled:**

| Command | Description |
|---|---|
| `REGISTER` | Registers a client with its ID, IP, and port |
| `GET_CLIENTS` | Returns a list of all other connected clients |
| `DIR` | Lists files stored on the server |
| `PUT` | Receives a file upload from a client |
| `DOWNLOAD` | Sends a file to a client |
| `DOWNLOAD_FROM_CLIENT` | Looks up a peer's address for direct P2P transfer |

**UDP Transfer:**

- Client sends a `START:<filename>` signal to begin.
- Data is sent in sequenced `DATA` packets (sequence number + length + bytes).
- Client sends `END` when done.
- The server detects and logs out-of-order or missing packets.

**Storage:** Files are saved to `server_storage/` in the working directory.

---

### `FileClientGUI.java` (and replicas)

A Swing-based GUI client. Each instance represents one peer in the network.

**Key features:**

- **Connection Page** — Enter server IP, port, and a unique Client ID before connecting.
- **Protocol Selection** — Choose TCP (reliable) or UDP (fast) for server uploads.
- **Server Files tab** — Browse and download files stored on the server.
- **Online Clients tab** — See peers currently online, browse their shared files, or push a file directly to them.
- **My Shared Files** — View files in your local shared folder (`client_shared_<ClientID>/`).
- **Progress Bar + Speed Meter** — Live transfer speed (MB/s) and progress during any transfer.

**Ports used by the client:**

| Port | Purpose |
|---|---|
| `5000` | Communication with the central server |
| `12346` | Embedded P2P server (receives files from other clients) |

**Shared folder:** `client_shared_<ClientID>/` in the working directory. Files uploaded to the server or downloaded from it are automatically copied here so other peers can access them.

---

## Running the Application

### Prerequisites

- Java 11 or higher
- No external dependencies (uses standard Java libraries + Swing)

### 1. Compile

```bash
javac -d out com/fileshare/server/FileServer.java
javac -d out com/fileshare/client/FileClientGUI.java
# Repeat for FileClientGUI2.java, FileClientGUI3.java
```

### 2. Start the Server

```bash
java -cp out com.fileshare.server.FileServer
```

### 3. Start Client Instances

Open separate terminals for each client:

```bash
# Terminal 1
java -cp out com.fileshare.client.FileClientGUI

# Terminal 2
java -cp out com.fileshare.client.FileClientGUI2

# Terminal 3
java -cp out com.fileshare.client.FileClientGUI3
```

Each client will show the connection screen. Enter a unique **Client ID** (e.g. `Alice`, `Bob`, `Charlie`) before clicking **CONNECT TO SERVER**.

---

## Creating Additional Client Instances

To add more clients beyond the three provided, copy `FileClientGUI.java` and change the class name:

```bash
cp com/fileshare/client/FileClientGUI.java com/fileshare/client/FileClientGUI4.java
```

Then edit the new file:
```java
// Change line:
public class FileClientGUI extends JFrame {
// To:
public class FileClientGUI4 extends JFrame {

// And update main():
SwingUtilities.invokeLater(() -> new FileClientGUI4());
```

> Each client instance must be run in its own JVM process. Running two instances of the same class in the same JVM will cause port `12346` conflicts.

---

## Transfer Modes

### TCP Upload (to server)
Reliable, ordered delivery. Recommended for critical files. Uses a persistent socket stream with progress tracking.

### UDP Upload (to server)
Faster for large files on reliable local networks. Packets are sequenced and monitored — the server logs any gaps, but **does not retransmit**. Use TCP if data integrity is essential.

### Peer-to-Peer (client to client)
Always uses TCP via a direct socket connection to the peer's embedded server on port `12346`. Two modes:

- **Browse & Download** — View a peer's shared folder and pull a specific file.
- **Send File** — Push a file directly to a peer; the recipient is prompted to accept or decline.

---

## Known Limitations

- The UDP server does not retransmit lost packets — packet loss on unreliable networks will corrupt the file.
- All clients sharing a machine use the same P2P port (`12346`). When running multiple instances locally, ensure each replica uses a unique port (modify `dos.writeInt(12346)` in `registerWithServer()` and the `new ServerSocket(12346)` in `startFileSharingServer()`).
- Client registration is not persistent — if the server restarts, clients must reconnect.
- No authentication or encryption is implemented.
