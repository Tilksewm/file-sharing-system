//FileClient2GUI.java
package com.fileshare.client;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class FileClient2GUI extends JFrame {
    private long transferStartTime = 0;
    private CardLayout cardLayout = new CardLayout();
    private JPanel mainPanel = new JPanel(cardLayout);
    private JLabel speedLabel = new JLabel("Speed: 0 MB/s");

    // Colors
    private Color lightBlue = new Color(173, 216, 230);
    private Color lightBrown = new Color(210, 180, 140);
    private Color white = Color.WHITE;

    private JTextField ipField = new JTextField("127.0.0.1"), portField = new JTextField("5000");
    private JTextField clientIdField = new JTextField("Client-" + new Random().nextInt(1000));
    private JRadioButton tcpBtn = new JRadioButton("TCP (Reliable)", true), udpBtn = new JRadioButton("UDP (Efficient)");
    private DefaultTableModel serverFileModel = new DefaultTableModel(new String[]{"Filename", "Size", "Action"}, 0);
    private DefaultTableModel clientListModel = new DefaultTableModel(new String[]{"Client ID", "IP Address", "Port", "Status", "Action"}, 0);
    private JProgressBar progressBar = new JProgressBar(0, 100);
    private JTextArea statusLog = new JTextArea(5, 20);
    private String myClientId;
    private JTabbedPane tabbedPane = new JTabbedPane();
    private Map<String, ClientInfo> onlineClients = new HashMap<>();

    static class ClientInfo {
        String clientId;
        String ip;
        int port;
        boolean isOnline;

        ClientInfo(String clientId, String ip, int port) {
            this.clientId = clientId;
            this.ip = ip;
            this.port = port;
            this.isOnline = true;
        }
    }

    public FileClient2GUI() {
        setTitle("FileShare: Client-to-Client File Transfer");
        setSize(1000, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        initConnectionPage();
        initDashboardPage();

        add(mainPanel);
        setVisible(true);
    }

    private void initConnectionPage() {
        JPanel panel = new JPanel(new GridLayout(8, 1, 10, 10));
        panel.setBackground(lightBlue);
        panel.setBorder(BorderFactory.createEmptyBorder(50, 80, 50, 80));

        ButtonGroup group = new ButtonGroup();
        group.add(tcpBtn); group.add(udpBtn);
        tcpBtn.setBackground(lightBlue); udpBtn.setBackground(lightBlue);

        panel.add(new JLabel("FileShare System - Client to Client Sharing", SwingConstants.CENTER));
        JPanel p = new JPanel();
        p.setBackground(lightBlue);
        p.add(tcpBtn);
        p.add(udpBtn);
        panel.add(p);

        panel.add(new JLabel("Client ID:", SwingConstants.CENTER));
        panel.add(clientIdField);
        panel.add(new JLabel("Server IP:", SwingConstants.CENTER));
        panel.add(ipField);
        panel.add(new JLabel("Port:", SwingConstants.CENTER));
        panel.add(portField);

        JButton connectBtn = new JButton("CONNECT TO SERVER");
        connectBtn.setBackground(lightBrown);
        connectBtn.addActionListener(e -> {
            myClientId = clientIdField.getText().trim();
            if (registerWithServer()) {
                cardLayout.show(mainPanel, "DASHBOARD");
                refreshFileList();
                refreshClientList();
                startFileSharingServer();

                // Auto-refresh client list every 10 seconds using Swing Timer
                javax.swing.Timer swingTimer = new javax.swing.Timer(10000, ev -> refreshClientList());
                swingTimer.start();
            }
        });
        panel.add(connectBtn);

        mainPanel.add(panel, "CONN");
    }

    private boolean registerWithServer() {
        try (Socket socket = new Socket(ipField.getText(), Integer.parseInt(portField.getText()));
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            dos.writeUTF("REGISTER");
            dos.writeUTF(myClientId);
            dos.writeInt(12346);

            String response = dis.readUTF();
            if (response.equals("REGISTERED")) {
                int otherClients = dis.readInt();
                statusLog.append("Registered successfully! " + otherClients + " other clients online.\n");
                return true;
            }
        } catch (Exception e) {
            statusLog.append("Registration failed: " + e.getMessage() + "\n");
        }
        return false;
    }

    private void refreshClientList() {
        clientListModel.setRowCount(0);
        onlineClients.clear();

        try (Socket socket = new Socket(ipField.getText(), Integer.parseInt(portField.getText()));
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            dos.writeUTF("GET_CLIENTS");
            dos.writeUTF(myClientId);

            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                String clientId = dis.readUTF();
                String ip = dis.readUTF();
                int port = dis.readInt();

                ClientInfo info = new ClientInfo(clientId, ip, port);
                onlineClients.put(clientId, info);

                // Create buttons for each client
                JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
                buttonPanel.setOpaque(false);

                JButton browseBtn = new JButton("📁 Browse Files");
                JButton sendBtn = new JButton("📤 Send File");

                browseBtn.addActionListener(e -> browseClientFiles(clientId, ip, port));
                sendBtn.addActionListener(e -> sendFileToClient(clientId, ip, port));

                buttonPanel.add(browseBtn);
                buttonPanel.add(sendBtn);

                clientListModel.addRow(new Object[]{clientId, ip, port, "● Online", buttonPanel});
            }
            statusLog.append("Found " + count + " clients online\n");
        } catch (Exception e) {
            statusLog.append("Error getting client list: " + e.getMessage() + "\n");
        }
    }

    private void sendFileToClient(String clientId, String ip, int port) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select file to send to " + clientId);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();

            int confirm = JOptionPane.showConfirmDialog(this,
                    "Send file '" + file.getName() + "' to " + clientId + "?",
                    "Confirm Send",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                new Thread(() -> sendFileDirect(ip, port, file, clientId)).start();
            }
        }
    }

    private void sendFileDirect(String targetIp, int targetPort, File file, String targetClientId) {
        try (Socket socket = new Socket(targetIp, targetPort);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileInputStream fis = new FileInputStream(file)) {

            // Send command and file info
            dos.writeUTF("RECEIVE_FILE");
            dos.writeUTF(myClientId);
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());

            // Send file data
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, read);
                total += read;
                long finalTotal = total;
                long finalLength = file.length();
                SwingUtilities.invokeLater(() -> updateProgress(finalTotal, finalLength));
            }

            // Wait for confirmation
            String response = dis.readUTF();
            if (response.equals("FILE_RECEIVED")) {
                statusLog.append("✓ File '" + file.getName() + "' sent successfully to " + targetClientId + "\n");
                JOptionPane.showMessageDialog(this, "File sent successfully to " + targetClientId);
            } else if (response.equals("FILE_REJECTED")) {
                statusLog.append("✗ File transfer rejected by " + targetClientId + "\n");
                JOptionPane.showMessageDialog(this, "File transfer rejected by " + targetClientId);
            }

        } catch (Exception e) {
            statusLog.append("✗ Failed to send file to " + targetClientId + ": " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this, "Failed to send file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void browseClientFiles(String clientId, String ip, int port) {
        JDialog dialog = new JDialog(this, "Files from " + clientId, true);
        dialog.setSize(600, 500);
        dialog.setLayout(new BorderLayout());

        DefaultTableModel fileModel = new DefaultTableModel(new String[]{"Filename", "Size", "Action"}, 0);
        JTable fileTable = new JTable(fileModel);
        fileTable.setRowHeight(35);

        JLabel statusLabel = new JLabel("Loading files from " + clientId + "...", SwingConstants.CENTER);
        dialog.add(statusLabel, BorderLayout.NORTH);

        new Thread(() -> {
            try (Socket socket = new Socket(ip, port);
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 DataInputStream dis = new DataInputStream(socket.getInputStream())) {

                dos.writeUTF("LIST_FILES");
                int fileCount = dis.readInt();

                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Found " + fileCount + " files from " + clientId);
                    fileModel.setRowCount(0);
                });

                for (int i = 0; i < fileCount; i++) {
                    String fileName = dis.readUTF();
                    long fileSize = dis.readLong();
                    String sizeStr = (fileSize / 1024) + " KB";

                    JButton downloadBtn = new JButton("⬇ Download");
                    downloadBtn.setBackground(new Color(100, 200, 255));
                    String finalFileName = fileName;
                    downloadBtn.addActionListener(e -> {
                        dialog.dispose();
                        downloadFromClient(clientId, ip, port, finalFileName);
                    });

                    final String fFileName = fileName;
                    final String fSizeStr = sizeStr;
                    SwingUtilities.invokeLater(() -> {
                        fileModel.addRow(new Object[]{fFileName, fSizeStr, downloadBtn});
                    });
                }

                // Set button renderer for the action column
                SwingUtilities.invokeLater(() -> {
                    fileTable.getColumnModel().getColumn(2).setCellRenderer(new ButtonRenderer());
                    fileTable.getColumnModel().getColumn(2).setCellEditor(new ButtonEditor(new JCheckBox()));
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error: " + e.getMessage());
                    JOptionPane.showMessageDialog(dialog, "Error connecting to client: " + e.getMessage());
                });
            }
        }).start();

        dialog.add(new JScrollPane(fileTable), BorderLayout.CENTER);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void downloadFromClient(String clientId, String ip, int port, String fileName) {
        new Thread(() -> downloadFileDirect(ip, port, fileName, clientId)).start();
    }

    private void downloadFileDirect(String clientIp, int clientPort, String fileName, String clientId) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(fileName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File saveFile = chooser.getSelectedFile();

        try (Socket socket = new Socket(clientIp, clientPort);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileOutputStream fos = new FileOutputStream(saveFile)) {

            dos.writeUTF("DOWNLOAD_FILE");
            dos.writeUTF(fileName);

            String response = dis.readUTF();
            if (response.equals("FILE_FOUND")) {
                long fileSize = dis.readLong();
                byte[] buffer = new byte[8192];
                int read;
                long received = 0;

                statusLog.append("Downloading '" + fileName + "' from " + clientId + "...\n");

                while (received < fileSize && (read = dis.read(buffer, 0, (int)Math.min(buffer.length, fileSize - received))) != -1) {
                    fos.write(buffer, 0, read);
                    received += read;
                    long finalReceived = received;
                    long finalFileSize = fileSize;
                    SwingUtilities.invokeLater(() -> updateProgress(finalReceived, finalFileSize));
                }

                statusLog.append("✓ Downloaded: " + fileName + " from " + clientId + " to " + saveFile.getAbsolutePath() + "\n");
                JOptionPane.showMessageDialog(this, "Download complete!\nFile saved to: " + saveFile.getAbsolutePath());
            } else {
                statusLog.append("✗ File not found on remote client: " + fileName + "\n");
            }

        } catch (Exception e) {
            statusLog.append("✗ Download error: " + e.getMessage() + "\n");
        }
    }

    private void initDashboardPage() {

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(white);

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT));
        header.setBackground(lightBlue);

        JButton refreshServerBtn = new JButton("🔄 Refresh Server Files");
        refreshServerBtn.setBackground(lightBrown);
        refreshServerBtn.addActionListener(e -> refreshFileList());

        JButton refreshClientsBtn = new JButton("🔄 Refresh Online Clients");
        refreshClientsBtn.setBackground(lightBrown);
        refreshClientsBtn.addActionListener(e -> refreshClientList());

        JButton mySharedFilesBtn = new JButton("📁 My Shared Files");
        mySharedFilesBtn.setBackground(new Color(100, 200, 100));
        mySharedFilesBtn.addActionListener(e -> showMySharedFiles());

        header.add(refreshServerBtn);
        header.add(refreshClientsBtn);
        header.add(mySharedFilesBtn);
        panel.add(header, BorderLayout.NORTH);

        JPanel serverFilesPanel = createServerFilesPanel();
        JPanel clientsPanel = createClientsPanel();

        tabbedPane.addTab("Server Files", serverFilesPanel);
        tabbedPane.addTab("Online Clients (" + myClientId + ")", clientsPanel);

        panel.add(tabbedPane, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(white);

        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statsPanel.add(speedLabel);

        footer.add(statsPanel, BorderLayout.WEST);

        JPanel uploadPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton uploadBtn = new JButton("📤 UPLOAD FILE TO SERVER");
        uploadBtn.setBackground(lightBrown);
        uploadBtn.addActionListener(e -> handleUpload());
        uploadPanel.add(uploadBtn);

        JButton shareFolderBtn = new JButton("📁 Open Shared Folder");
        shareFolderBtn.addActionListener(e -> openSharedFolder());
        uploadPanel.add(shareFolderBtn);

        footer.add(uploadPanel, BorderLayout.NORTH);
        footer.add(progressBar, BorderLayout.CENTER);
        statusLog.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(statusLog);
        scrollPane.setPreferredSize(new Dimension(900, 120));
        footer.add(scrollPane, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);

        mainPanel.add(panel, "DASHBOARD");
    }

    private void showMySharedFiles() {
        JDialog dialog = new JDialog(this, "My Shared Files", true);
        dialog.setSize(500, 400);
        dialog.setLayout(new BorderLayout());

        DefaultTableModel model = new DefaultTableModel(new String[]{"Filename", "Size", "Path"}, 0);
        JTable table = new JTable(model);

        File sharedDir = new File("client_shared_" + myClientId);
        if (sharedDir.exists()) {
            File[] files = sharedDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        model.addRow(new Object[]{
                                f.getName(),
                                (f.length() / 1024) + " KB",
                                f.getAbsolutePath()
                        });
                    }
                }
            }
        }

        JLabel label = new JLabel("Files available for sharing: " + model.getRowCount(), SwingConstants.CENTER);
        dialog.add(label, BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void openSharedFolder() {
        File sharedDir = new File("client_shared_" + myClientId);
        if (!sharedDir.exists()) sharedDir.mkdir();

        try {
            Desktop.getDesktop().open(sharedDir);
            statusLog.append("Opened shared folder: " + sharedDir.getAbsolutePath() + "\n");
        } catch (IOException e) {
            statusLog.append("Cannot open folder: " + e.getMessage() + "\n");
        }
    }

    private JPanel createServerFilesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JTable table = new JTable(serverFileModel);
        table.setRowHeight(45);

        // Set column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(400);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);

        // Set custom renderer and editor for the button column (column 2)
        table.getColumnModel().getColumn(2).setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(2).setCellEditor(new ButtonEditor(new JCheckBox()));

        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createClientsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JTable table = new JTable(clientListModel);
        table.setRowHeight(50);

        // Set column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        table.getColumnModel().getColumn(4).setPreferredWidth(250);

        table.getColumnModel().getColumn(4).setCellRenderer(new PanelRenderer());
        table.getColumnModel().getColumn(4).setCellEditor(new PanelEditor());

        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private void startFileSharingServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(12346)) {
                statusLog.append("✓ File sharing server started on port 12346\n");
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleSharingRequest(clientSocket)).start();
                }
            } catch (IOException e) {
                statusLog.append("✗ Error starting sharing server: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private void handleSharingRequest(Socket socket) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            String command = dis.readUTF();

            if (command.equals("LIST_FILES")) {
                File sharedDir = new File("client_shared_" + myClientId);
                if (!sharedDir.exists()) sharedDir.mkdir();

                File[] files = sharedDir.listFiles();
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
                            dos.writeUTF(f.getName());
                            dos.writeLong(f.length());
                        }
                    }
                }

            } else if (command.equals("DOWNLOAD_FILE")) {
                String fileName = dis.readUTF();
                File fileToSend = new File("client_shared_" + myClientId + "/" + fileName);

                if (fileToSend.exists() && fileToSend.isFile()) {
                    dos.writeUTF("FILE_FOUND");
                    dos.writeLong(fileToSend.length());

                    try (FileInputStream fis = new FileInputStream(fileToSend)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            dos.write(buffer, 0, read);
                        }
                    }
                    statusLog.append("✓ File sent: " + fileName + " to " + socket.getInetAddress().getHostAddress() + "\n");
                } else {
                    dos.writeUTF("FILE_NOT_FOUND");
                }

            } else if (command.equals("RECEIVE_FILE")) {
                String senderId = dis.readUTF();
                String fileName = dis.readUTF();
                long fileSize = dis.readLong();

                SwingUtilities.invokeLater(() -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setSelectedFile(new File(fileName));
                    chooser.setDialogTitle("Receive file from " + senderId);

                    if (chooser.showSaveDialog(FileClient2GUI.this) == JFileChooser.APPROVE_OPTION) {
                        File saveFile = chooser.getSelectedFile();

                        new Thread(() -> {
                            try {
                                byte[] buffer = new byte[8192];
                                int read;
                                long received = 0;
                                try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                                    while (received < fileSize && (read = dis.read(buffer, 0, (int)Math.min(buffer.length, fileSize - received))) != -1) {
                                        fos.write(buffer, 0, read);
                                        received += read;
                                        long finalReceived = received;
                                        long finalFileSize = fileSize;
                                        SwingUtilities.invokeLater(() -> updateProgress(finalReceived, finalFileSize));
                                    }
                                }

                                dos.writeUTF("FILE_RECEIVED");
                                statusLog.append("✓ Received file '" + fileName + "' from " + senderId + " saved to " + saveFile.getAbsolutePath() + "\n");
                                JOptionPane.showMessageDialog(FileClient2GUI.this, "File received from " + senderId + "!\nSaved to: " + saveFile.getAbsolutePath());
                            } catch (Exception e) {
                                statusLog.append("✗ Error receiving file: " + e.getMessage() + "\n");
                            }
                        }).start();
                    } else {
                        try {
                            dos.writeUTF("FILE_REJECTED");
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    }
                });
            }

        } catch (IOException e) {
            statusLog.append("Error handling request: " + e.getMessage() + "\n");
        }
    }

    private void refreshFileList() {
        serverFileModel.setRowCount(0);
        try (Socket socket = new Socket(ipField.getText(), Integer.parseInt(portField.getText()));
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            dos.writeUTF("DIR");
            int count = dis.readInt();

            for (int i = 0; i < count; i++) {
                String fileInfo = dis.readUTF();
                String fileName = fileInfo.split(" \\(")[0];
                String size = fileInfo.substring(fileInfo.indexOf("(") + 1, fileInfo.indexOf(" KB"));

                JButton downloadBtn = new JButton("⬇ Download");
                downloadBtn.setBackground(new Color(100, 200, 255));
                String finalFileName = fileName;
                downloadBtn.addActionListener(e -> downloadFileFromServer(finalFileName));

                serverFileModel.addRow(new Object[]{fileName, size + " KB", downloadBtn});
            }
            statusLog.append("Server file list updated. Found " + count + " files.\n");
        } catch (Exception e) {
            statusLog.append("Error refreshing file list: " + e.getMessage() + "\n");
        }
    }

    private void downloadFileFromServer(String fileName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(fileName));
        chooser.setDialogTitle("Save file from server");

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File saveFile = chooser.getSelectedFile();
            new Thread(() -> downloadFileFromServerDirect(fileName, saveFile)).start();
        }
    }

    private void downloadFileFromServerDirect(String fileName, File saveFile) {
        try (Socket socket = new Socket(ipField.getText(), Integer.parseInt(portField.getText()));
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileOutputStream fos = new FileOutputStream(saveFile)) {

            dos.writeUTF("DOWNLOAD");
            dos.writeUTF(fileName);

            String response = dis.readUTF();
            if (response.equals("FILE_EXISTS")) {
                long fileSize = dis.readLong();
                byte[] buffer = new byte[8192];
                int read;
                long received = 0;

                statusLog.append("Downloading '" + fileName + "' from server...\n");

                while (received < fileSize && (read = dis.read(buffer, 0, (int)Math.min(buffer.length, fileSize - received))) != -1) {
                    fos.write(buffer, 0, read);
                    received += read;
                    long finalReceived = received;
                    long finalFileSize = fileSize;
                    SwingUtilities.invokeLater(() -> updateProgress(finalReceived, finalFileSize));
                }

                statusLog.append("✓ Downloaded from server: " + fileName + " saved to " + saveFile.getAbsolutePath() + "\n");
                JOptionPane.showMessageDialog(this, "Download complete!\nFile saved to: " + saveFile.getAbsolutePath());

                // Also add to shared folder for sharing with others
                try {
                    File sharedDir = new File("client_shared_" + myClientId);
                    if (!sharedDir.exists()) sharedDir.mkdir();
                    Files.copy(saveFile.toPath(), new File(sharedDir, fileName).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    statusLog.append("✓ File also added to your shared folder for others to access\n");
                } catch (IOException e) {
                    statusLog.append("Note: Could not add to shared folder: " + e.getMessage() + "\n");
                }

            } else {
                statusLog.append("✗ File not found on server: " + fileName + "\n");
                JOptionPane.showMessageDialog(this, "File not found on server!", "Error", JOptionPane.ERROR_MESSAGE);
            }

        } catch (Exception e) {
            statusLog.append("✗ Download error: " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this, "Download failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleUpload() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select file to upload to server");

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();

            // Also copy to shared folder for peer-to-peer sharing
            try {
                File sharedDir = new File("client_shared_" + myClientId);
                if (!sharedDir.exists()) sharedDir.mkdir();
                Files.copy(file.toPath(), new File(sharedDir, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                statusLog.append("✓ File added to shared folder: " + file.getName() + "\n");
            } catch (IOException e) {
                statusLog.append("✗ Error adding to shared folder: " + e.getMessage() + "\n");
            }

            if (tcpBtn.isSelected()) {
                new Thread(() -> uploadFileTCP(file)).start();
            } else {
                new Thread(() -> uploadFileUDP(file)).start();
            }
        }
    }

    private void uploadFileTCP(File file) {
        try (Socket socket = new Socket(ipField.getText(), Integer.parseInt(portField.getText()));
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileInputStream fis = new FileInputStream(file)) {

            dos.writeUTF("PUT");
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());
            transferStartTime = System.currentTimeMillis();

            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, read);
                total += read;
                long finalTotal = total;
                long finalFileLength = file.length();
                SwingUtilities.invokeLater(() -> updateProgress(finalTotal, finalFileLength));
            }
            String response = dis.readUTF();
            statusLog.append("✓ TCP Upload Success: " + file.getName() + " - " + response + "\n");
            long endTime = System.currentTimeMillis();
            double seconds = (endTime - transferStartTime) / 1000.0;
            double avgSpeed = (file.length() / 1024.0 / 1024.0) / seconds;

            JOptionPane.showMessageDialog(this,
                    String.format("File uploaded successfully!\nTime taken: %.2f seconds\nAverage speed: %.2f MB/s",
                            seconds, avgSpeed)
            );
            refreshFileList(); // Refresh the file list after upload

        } catch (Exception e) {
            statusLog.append("✗ TCP Error: " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this, "Upload failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    private void uploadFileUDP(File file) {
        try (DatagramSocket udpSocket = new DatagramSocket();
             FileInputStream fis = new FileInputStream(file)) {

            InetAddress addr = InetAddress.getByName(ipField.getText());
            int port = Integer.parseInt(portField.getText());

            // 1. START signal
            String startMsg = "START:" + file.getName() + ":" + file.length();
            udpSocket.send(new DatagramPacket(startMsg.getBytes(), startMsg.length(), addr, port));
            transferStartTime = System.currentTimeMillis();

            byte[] buffer = new byte[4096]; // balanced size
            int read;
            int sequence = 0;
            long total = 0;

            while ((read = fis.read(buffer)) != -1) {

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);

                dos.writeUTF("DATA");
                dos.writeInt(sequence++);
                dos.writeInt(read);
                dos.write(buffer, 0, read);

                byte[] packetData = baos.toByteArray();

                udpSocket.send(new DatagramPacket(packetData, packetData.length, addr, port));

                total += read;
                long finalTotal = total;
                long finalLength = file.length();

                SwingUtilities.invokeLater(() -> updateProgress(finalTotal, finalLength));

                // Light pacing (no heavy delay)
                if (sequence % 100 == 0) {
                    Thread.yield();
                }
            }

            // 3. END signal
            String endMsg = "END";
            udpSocket.send(new DatagramPacket(endMsg.getBytes(), endMsg.length(), addr, port));

            statusLog.append("✓ UDP Upload Done: " + file.getName() + "\n");
            long endTime = System.currentTimeMillis();
            double seconds = (endTime - transferStartTime) / 1000.0;
            double avgSpeed = (file.length() / 1024.0 / 1024.0) / seconds;

            JOptionPane.showMessageDialog(this,
                    String.format("File uploaded via UDP!\nTime taken: %.2f seconds\nAverage speed: %.2f MB/s",
                            seconds, avgSpeed)
            );
            refreshFileList();

        } catch (Exception e) {
            statusLog.append("✗ UDP Error: " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this, "UDP Upload failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /*private void uploadFileUDP(File file) {
        try (DatagramSocket udpSocket = new DatagramSocket();
             FileInputStream fis = new FileInputStream(file)) {
            InetAddress addr = InetAddress.getByName(ipField.getText());
            int port = Integer.parseInt(portField.getText());

            byte[] startMsg = ("START:" + file.getName()).getBytes();
            DatagramPacket startPacket = new DatagramPacket(startMsg, startMsg.length, addr, port);
            udpSocket.send(startPacket);

            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = fis.read(buffer)) != -1) {
                DatagramPacket packet = new DatagramPacket(buffer, read, addr, port);
                udpSocket.send(packet);
                total += read;
                long finalTotal = total;
                long finalFileLength = file.length();
                SwingUtilities.invokeLater(() -> updateProgress(finalTotal, finalFileLength));
                //Thread.sleep(1);
            }
            statusLog.append("✓ UDP Upload Done: " + file.getName() + "\n");
            JOptionPane.showMessageDialog(this, "File uploaded successfully via UDP!");
            refreshFileList(); // Refresh the file list after upload

        } catch (Exception e) {
            statusLog.append("✗ UDP Error: " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this, "UDP Upload failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }*/
    private long startTime = 0;
    private long lastTime = 0;
    private long lastBytes = 0;
    private void updateProgress(long current, long total) {
        long now = System.currentTimeMillis();

        if (startTime == 0) {
            startTime = now;
            lastTime = now;
            lastBytes = 0;
        }

        // Progress bar
        int progress = (int) ((current * 100) / total);
        progressBar.setValue(progress);

        // Update speed every ~500 ms
        if (now - lastTime >= 500) {
            long bytesDiff = current - lastBytes;
            double seconds = (now - lastTime) / 1000.0;

            double speed = (bytesDiff / 1024.0 / 1024.0) / seconds; // MB/s

            speedLabel.setText(String.format("Speed: %.2f MB/s", speed));

            lastTime = now;
            lastBytes = current;
        }

        // Reset when done
        if (progress == 100) {
            javax.swing.Timer resetTimer = new javax.swing.Timer(2000, e -> {
                progressBar.setValue(0);
                speedLabel.setText("Speed: 0 MB/s");
                startTime = 0;
            });
            resetTimer.setRepeats(false);
            resetTimer.start();
        }
    }

//    private void updateProgress(long current, long total) {
//        int progress = (int) ((current * 100) / total);
//        progressBar.setValue(progress);
//        if (progress == 100) {
//            // Reset progress after 2 seconds using Swing Timer
//            javax.swing.Timer resetTimer = new javax.swing.Timer(2000, e -> progressBar.setValue(0));
//            resetTimer.setRepeats(false);
//            resetTimer.start();
//        }
//    }

    // Button Renderer for JTable
    class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof JButton) {
                return (JButton) value;
            }
            setText(value != null ? value.toString() : "");
            return this;
        }
    }

    // Button Editor for JTable
    class ButtonEditor extends DefaultCellEditor {
        protected JButton button;
        private boolean isPushed;

        public ButtonEditor(JCheckBox checkBox) {
            super(checkBox);
            button = new JButton();
            button.setOpaque(true);
            button.addActionListener(e -> fireEditingStopped());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            if (value instanceof JButton) {
                button = (JButton) value;
            } else {
                button.setText(value != null ? value.toString() : "");
            }
            isPushed = true;
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            isPushed = false;
            return button;
        }

        @Override
        public boolean stopCellEditing() {
            isPushed = false;
            return super.stopCellEditing();
        }
    }
    class PanelRenderer implements TableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(
            JTable table,
            Object value,
            boolean isSelected,
            boolean hasFocus,
            int row,
            int column) {

        if (value instanceof JPanel) {
            return (JPanel) value;
        }

        return new JLabel(value == null ? "" : value.toString());
    }
}
class PanelEditor extends DefaultCellEditor {

    private JPanel panel;

    public PanelEditor() {
        super(new JCheckBox());
    }

    @Override
    public Component getTableCellEditorComponent(
            JTable table,
            Object value,
            boolean isSelected,
            int row,
            int column) {

        panel = (JPanel) value;
        return panel;
    }

    @Override
    public Object getCellEditorValue() {
        return panel;
    }
}

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        SwingUtilities.invokeLater(() -> new FileClient2GUI());
    }
}
