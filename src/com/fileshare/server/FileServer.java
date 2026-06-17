//FileServer.java
package com.fileshare.server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class FileServer {
    private static final int PORT = 5000;
    private static final String STORAGE_DIR = "server_storage/";
    private static List<ClientInfo> connectedClients = new CopyOnWriteArrayList<>();

    static class ClientInfo {
        String clientId;
        String ipAddress;
        int port;
        Socket socket;
        
        ClientInfo(String clientId, String ipAddress, int port, Socket socket) {
            this.clientId = clientId;
            this.ipAddress = ipAddress;
            this.port = port;
            this.socket = socket;
        }
    }

    public static void main(String[] args) {
        // Create storage directory if it doesn't exist
        File dir = new File(STORAGE_DIR);
        if (!dir.exists()) dir.mkdirs();

        System.out.println("=========================================");
        System.out.println("File Sharing Server Started");
        System.out.println("TCP & UDP Listening on port: " + PORT);
        System.out.println("Storage directory: " + new File(STORAGE_DIR).getAbsolutePath());
        System.out.println("=========================================");

        ExecutorService pool = Executors.newCachedThreadPool();

        // 1. TCP Server Thread
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                System.out.println("✓ TCP Server listening on port " + PORT);
                while (true) {
                    Socket client = serverSocket.accept();
                    System.out.println("New TCP connection from: " + client.getInetAddress().getHostAddress());
                    pool.execute(() -> handleTcpRequest(client));
                }
            } catch (IOException e) { 
                System.err.println("TCP Server error: " + e.getMessage());
                e.printStackTrace(); 
            }
        }).start();

        // 2. UDP Server Thread
        new Thread(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket(PORT)) {

                System.out.println("✓ UDP Server listening on port " + PORT);

                byte[] buffer = new byte[65536];

                Map<String, FileOutputStream> fileStreams = new HashMap<>();
                Map<String, Integer> lastSequence = new HashMap<>();

                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);

                    String clientKey = packet.getAddress() + ":" + packet.getPort();

                    ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                    DataInputStream dis = new DataInputStream(bais);

                    String dataStr = new String(packet.getData(), 0, packet.getLength());

                    // START
                    if (dataStr.startsWith("START:")) {
                        String[] parts = dataStr.split(":");
                        String fileName = parts[1];

                        if (fileStreams.containsKey(clientKey)) {
                            fileStreams.get(clientKey).close();
                        }

                        FileOutputStream fos = new FileOutputStream(STORAGE_DIR + fileName);
                        fileStreams.put(clientKey, fos);
                        lastSequence.put(clientKey, -1);

                        System.out.println("UDP Receiving: " + fileName + " from " + clientKey);
                    }

                    // END
                    else if (dataStr.equals("END")) {
                        FileOutputStream fos = fileStreams.get(clientKey);
                        if (fos != null) {
                            fos.close();
                            fileStreams.remove(clientKey);
                            lastSequence.remove(clientKey);
                            System.out.println("✓ UDP File transfer completed from " + clientKey);
                        }
                    }

                    // DATA packets
                    else {
                        try {
                            String type = dis.readUTF();

                            if (type.equals("DATA")) {
                                int seq = dis.readInt();
                                int length = dis.readInt();

                                byte[] fileData = new byte[length];
                                dis.readFully(fileData);

                                FileOutputStream fos = fileStreams.get(clientKey);
                                if (fos != null) {

                                    // Detect packet loss or disorder (for demonstration only)
                                    int lastSeq = lastSequence.getOrDefault(clientKey, -1);
                                    if (seq != lastSeq + 1) {
                                        System.out.println("⚠ UDP packet issue from " + clientKey +
                                                " Expected: " + (lastSeq + 1) + " but got: " + seq);
                                    }

                                    lastSequence.put(clientKey, seq);
                                    fos.write(fileData);
                                }
                            }
                        } catch (Exception e) {
                            // Ignore malformed packets
                        }
                    }
                }

            } catch (IOException e) {
                System.err.println("UDP Server error: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
//        new Thread(() -> {
//            try (DatagramSocket udpSocket = new DatagramSocket(PORT)) {
//                System.out.println("✓ UDP Server listening on port " + PORT);
//                byte[] buffer = new byte[65536];
//                Map<String, FileOutputStream> udpFileStreams = new HashMap<>();
//                Map<String, String> udpFileNames = new HashMap<>();
//
//                while (true) {
//                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
//                    udpSocket.receive(packet);
//                    String data = new String(packet.getData(), 0, packet.getLength());
//                    InetAddress clientAddress = packet.getAddress();
//                    int clientPort = packet.getPort();
//                    String clientKey = clientAddress.toString() + ":" + clientPort;
//
//                    if (data.startsWith("START:")) {
//                        String currentFileName = data.substring(6);
//                        // Close old stream if exists
//                        if (udpFileStreams.containsKey(clientKey)) {
//                            try {
//                                udpFileStreams.get(clientKey).close();
//                            } catch (IOException e) {}
//                        }
//                        // Create new file output stream
//                        try {
//                            FileOutputStream fos = new FileOutputStream(STORAGE_DIR + currentFileName);
//                            udpFileStreams.put(clientKey, fos);
//                            udpFileNames.put(clientKey, currentFileName);
//                            System.out.println("UDP Receiving: " + currentFileName + " from " + clientAddress);
//                        } catch (FileNotFoundException e) {
//                            System.err.println("Error creating UDP file: " + e.getMessage());
//                        }
//                    } else {
//                        // Append bytes to the file
//                        FileOutputStream fos = udpFileStreams.get(clientKey);
//                        if (fos != null) {
//                            try {
//                                fos.write(packet.getData(), 0, packet.getLength());
//                            } catch (IOException e) {
//                                System.err.println("Error writing UDP data: " + e.getMessage());
//                            }
//                        }
//                    }
//                }
//            } catch (IOException e) {
//                System.err.println("UDP Server error: " + e.getMessage());
//                e.printStackTrace();
//            }
//        }).start();
    }

    private static void handleTcpRequest(Socket client) {
        try (DataInputStream dis = new DataInputStream(client.getInputStream());
             DataOutputStream dos = new DataOutputStream(client.getOutputStream())) {

            String cmd = dis.readUTF();
            System.out.println("TCP Command received: " + cmd + " from " + client.getInetAddress().getHostAddress());
            
            // Handle client registration
            if (cmd.equals("REGISTER")) {
                String clientId = dis.readUTF();
                String clientIp = client.getInetAddress().getHostAddress();
                int clientPort = dis.readInt();
                
                // Remove old entry if exists
                connectedClients.removeIf(c -> c.clientId.equals(clientId));
                connectedClients.add(new ClientInfo(clientId, clientIp, clientPort, client));
                
                System.out.println("✓ Client registered: " + clientId + " at " + clientIp + ":" + clientPort);
                System.out.println("  Total online clients: " + connectedClients.size());
                dos.writeUTF("REGISTERED");
                dos.writeInt(connectedClients.size() - 1); // Send number of other clients
                
            } 
            // Handle get clients list
            else if (cmd.equals("GET_CLIENTS")) {
                String myId = dis.readUTF();
                List<ClientInfo> otherClients = new ArrayList<>();
                for (ClientInfo c : connectedClients) {
                    if (!c.clientId.equals(myId)) {
                        otherClients.add(c);
                    }
                }
                
                dos.writeInt(otherClients.size());
                for (ClientInfo clientInfo : otherClients) {
                    dos.writeUTF(clientInfo.clientId);
                    dos.writeUTF(clientInfo.ipAddress);
                    dos.writeInt(clientInfo.port);
                }
                System.out.println("Sent " + otherClients.size() + " other clients to " + myId);
                
            } 
            // Handle directory listing
            else if (cmd.equals("DIR")) {
                File[] files = new File(STORAGE_DIR).listFiles();
                int fileCount = 0;
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile()) fileCount++;
                    }
                }
                dos.writeInt(fileCount);
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile()) {
                            dos.writeUTF(f.getName() + " (" + (f.length()/1024) + " KB)");
                        }
                    }
                }
                System.out.println("Listed " + fileCount + " server files");
                
            } 
            // Handle file upload (PUT)
            else if (cmd.equals("PUT")) {
                String name = dis.readUTF();
                long size = dis.readLong();
                System.out.println("Receiving file: " + name + " (" + (size/1024) + " KB)");
                
                try (FileOutputStream fos = new FileOutputStream(STORAGE_DIR + name)) {
                    byte[] buf = new byte[8192];
                    int r; 
                    long received = 0;
                    while (received < size && (r = dis.read(buf, 0, (int)Math.min(buf.length, size - received))) != -1) {
                        fos.write(buf, 0, r);
                        received += r;
                        if (received % (1024 * 1024) == 0) { // Print every 1MB
                            System.out.println("  Progress: " + (received * 100 / size) + "%");
                        }
                    }
                }
                System.out.println("✓ TCP File received: " + name);
                dos.writeUTF("UPLOAD_SUCCESS");
                
            } 
            // Handle file download from server
            else if (cmd.equals("DOWNLOAD")) {
                String fileName = dis.readUTF();
                File fileToDownload = new File(STORAGE_DIR + fileName);
                
                if (fileToDownload.exists() && fileToDownload.isFile()) {
                    dos.writeUTF("FILE_EXISTS");
                    dos.writeLong(fileToDownload.length());
                    System.out.println("Sending file: " + fileName + " (" + (fileToDownload.length()/1024) + " KB)");
                    
                    try (FileInputStream fis = new FileInputStream(fileToDownload)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        long sent = 0;
                        while ((read = fis.read(buffer)) != -1) {
                            dos.write(buffer, 0, read);
                            sent += read;
                            if (sent % (1024 * 1024) == 0) { // Print every 1MB
                                System.out.println("  Progress: " + (sent * 100 / fileToDownload.length()) + "%");
                            }
                        }
                    }
                    System.out.println("✓ File sent: " + fileName);
                } else {
                    dos.writeUTF("FILE_NOT_FOUND");
                    System.out.println("✗ File not found: " + fileName);
                }
                
            } 
            // Handle peer-to-peer client lookup for direct transfer
            else if (cmd.equals("DOWNLOAD_FROM_CLIENT")) {
                String targetClientId = dis.readUTF();
                String fileName = dis.readUTF();
                
                ClientInfo targetClient = null;
                for (ClientInfo c : connectedClients) {
                    if (c.clientId.equals(targetClientId)) {
                        targetClient = c;
                        break;
                    }
                }
                
                if (targetClient != null) {
                    dos.writeUTF("CLIENT_FOUND");
                    dos.writeUTF(targetClient.ipAddress);
                    dos.writeInt(targetClient.port);
                    System.out.println("Looked up client: " + targetClientId + " at " + targetClient.ipAddress + ":" + targetClient.port);
                } else {
                    dos.writeUTF("CLIENT_NOT_FOUND");
                    System.out.println("✗ Client not found: " + targetClientId);
                }
            }
            
        } catch (IOException e) { 
            System.out.println("TCP Client session ended: " + e.getMessage()); 
            // Remove client from list if disconnected
            connectedClients.removeIf(c -> c.socket == client);
        }
    }
}