package com.hasnat.remotephone.service.network;

import android.content.Context;
import android.util.Log;

import com.hasnat.remotephone.service.BroadcastManager;
import com.hasnat.remotephone.service.NotificationHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcpServer {
    private static final String TAG = "TcpServer";
    private static final int TCP_SERVER_PORT = 8080;
    private final Context context;
    private final IncomingCommandListener commandListener;
    private final BroadcastManager broadcastManager;
    private final NotificationHelper notificationHelper;
    private ServerSocket controlServerSocket;
    private Thread tcpServerThread;
    private ExecutorService clientExecutor;
    private final List<PrintWriter> clientWriters = new ArrayList<>();

    public interface IncomingCommandListener {
        void onCommandReceived(String command);
    }

    public TcpServer(Context context, IncomingCommandListener listener, BroadcastManager broadcastManager, NotificationHelper notificationHelper) {
        this.context = context;
        this.commandListener = listener;
        this.broadcastManager = broadcastManager;
        this.notificationHelper = notificationHelper;
        this.clientExecutor = Executors.newCachedThreadPool();
    }

    public void startServer() {
        tcpServerThread = new Thread(new TcpServerRunnable());
        tcpServerThread.start();
    }

    public void stopServer() {
        if (tcpServerThread != null) {
            tcpServerThread.interrupt();
        }
        clientExecutor.shutdownNow();
        try {
            if (controlServerSocket != null) {
                controlServerSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing control server socket", e);
        }
    }

    public void broadcastToClients(String message) {
        Log.d(TAG, "Attempting to broadcast message: " + message);
        synchronized (clientWriters) {
            List<PrintWriter> disconnectedClients = new ArrayList<>();
            for (PrintWriter writer : clientWriters) {
                if (writer.checkError()) {
                    disconnectedClients.add(writer);
                    continue;
                }
                writer.println(message);
                if (writer.checkError()) {
                    disconnectedClients.add(writer);
                }
            }
            clientWriters.removeAll(disconnectedClients);
            broadcastManager.sendClientCountUpdate(clientWriters.size());
        }
    }

    class TcpServerRunnable implements Runnable {
        @Override
        public void run() {
            try {
                controlServerSocket = new ServerSocket(TCP_SERVER_PORT);
                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = controlServerSocket.accept();
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                    synchronized (clientWriters) {
                        clientWriters.add(out);
                        broadcastManager.sendClientCountUpdate(clientWriters.size());
                    }
                    notificationHelper.updateNotification("Remote Phone Host", "Client connected from " + clientSocket.getInetAddress().getHostAddress());
                    broadcastManager.sendHostStatus("Host: Client connected from " + clientSocket.getInetAddress().getHostAddress());
                    clientExecutor.execute(new ClientHandler(clientSocket, out));
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Control server socket error", e);
                }
            }
        }
    }

    class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final PrintWriter clientWriter;

        public ClientHandler(Socket socket, PrintWriter writer) {
            this.clientSocket = socket;
            this.clientWriter = writer;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                String command;
                while ((command = reader.readLine()) != null) {
                    commandListener.onCommandReceived(command);
                }
            } catch (IOException e) {
                Log.e(TAG, "Client disconnected or I/O error: " + e.getMessage(), e);
            } finally {
                try {
                    clientSocket.close();
                    synchronized (clientWriters) {
                        if (clientWriter != null) {
                            clientWriters.remove(clientWriter);
                            broadcastManager.sendClientCountUpdate(clientWriters.size());
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing client socket", e);
                }
            }
        }
    }
}