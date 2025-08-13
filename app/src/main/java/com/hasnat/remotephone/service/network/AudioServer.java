package com.hasnat.remotephone.service.network;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Manages the host-side audio server to handle two-way audio streaming with a connected client.
 * It streams audio from the host's microphone to the client's speaker and vice versa.
 */
public class AudioServer {
    private static final String TAG = "AudioServer";
    private static final int AUDIO_SERVER_PORT = 8081;
    private final Context context;
    private ServerSocket audioServerSocket;
    private Thread audioServerThread;
    private volatile boolean isStreaming = false;
    private Socket clientAudioSocket; // Store the single client socket
    private ExecutorService streamingExecutor;
    private Future<?> hostToClientStreamFuture;
    private Future<?> clientToHostStreamFuture;

    public AudioServer(Context context) {
        this.context = context;
    }

    /**
     * Starts the audio server to listen for client connections.
     */
    public void startServer() {
        audioServerThread = new Thread(new AudioServerRunnable());
        audioServerThread.start();
        streamingExecutor = Executors.newFixedThreadPool(2);
    }

    /**
     * Stops the audio server and all related threads and sockets.
     */
    public void stopServer() {
        stopAudioBridge();
        if (audioServerThread != null) {
            audioServerThread.interrupt();
        }
        try {
            if (audioServerSocket != null && !audioServerSocket.isClosed()) {
                audioServerSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing audio server socket", e);
        }
        if (streamingExecutor != null) {
            streamingExecutor.shutdownNow();
        }
        Log.d(TAG, "Audio server stopped.");
    }

    /**
     * Helper to safely close the client audio socket.
     */
    private synchronized void closeClientSocketQuietly() {
        if (clientAudioSocket != null && !clientAudioSocket.isClosed()) {
            try { clientAudioSocket.close(); } catch (IOException ignored) {}
            clientAudioSocket = null;
        }
    }

    /**
     * Initiates the bidirectional audio bridge by starting the streaming threads.
     */
    public void startAudioBridge() {
        if (clientAudioSocket == null || clientAudioSocket.isClosed()) {
            Log.e(TAG, "Cannot start audio bridge: No client socket connected.");
            return;
        }

        try {
            clientAudioSocket.setKeepAlive(true);
            clientAudioSocket.setTcpNoDelay(true);
            clientAudioSocket.setSoTimeout(1500); // periodic unblock on reads
        } catch (Exception e) {
            Log.w(TAG, "Failed to set socket options", e);
        }

        isStreaming = true;
        Log.d(TAG, "Starting bidirectional audio bridge.");

        hostToClientStreamFuture  = streamingExecutor.submit(this::streamHostMicToClient);
        clientToHostStreamFuture  = streamingExecutor.submit(this::streamClientMicToHost);
    }

    /**
     * Stops the audio streaming threads by setting the flag and canceling futures.
     */
    public void stopAudioBridge() {
        isStreaming = false;
        Log.d(TAG, "Stopping host audio bridge.");

        if (hostToClientStreamFuture != null) hostToClientStreamFuture.cancel(true);
        if (clientToHostStreamFuture != null) clientToHostStreamFuture.cancel(true);

        // Nudge blocking I/O to exit quickly
        try { if (clientAudioSocket != null) clientAudioSocket.shutdownInput(); } catch (IOException ignored) {}
        try { if (clientAudioSocket != null) clientAudioSocket.shutdownOutput(); } catch (IOException ignored) {}

        closeClientSocketQuietly();
    }

    /**
     * Runnable for the main audio server thread, accepting new client connections.
     */
    class AudioServerRunnable implements Runnable {
        @Override
        public void run() {
            try {
                audioServerSocket = new ServerSocket(AUDIO_SERVER_PORT);
                Log.d(TAG, "Audio server started, waiting for client on port " + AUDIO_SERVER_PORT);
                while (!Thread.currentThread().isInterrupted()) {
                    Socket newClient = audioServerSocket.accept();
                    Log.d(TAG, "Client connected to audio server: " + newClient.getInetAddress());

                    if (clientAudioSocket != null && !clientAudioSocket.isClosed()) {
                        Log.d(TAG, "Replacing existing audio client");
                        stopAudioBridge();
                        closeClientSocketQuietly();
                    }
                    clientAudioSocket = newClient;
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Audio server socket error", e);
                }
            }
        }
    }

    /**
     * Streams audio from the host's microphone to the connected client's speaker.
     */
    private void streamHostMicToClient() {
        int buf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted");
            return;
        }
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
        OutputStream out = null;
        try {
            out = clientAudioSocket.getOutputStream();
            rec.startRecording();
            byte[] b = new byte[Math.max(640, buf)];
            Log.d(TAG, "Host->Client started");
            while (isStreaming && !Thread.currentThread().isInterrupted()) {
                int n = rec.read(b, 0, b.length);
                if (n > 0) {
                    out.write(b, 0, n);
                    out.flush();
                }
            }
            try { clientAudioSocket.shutdownOutput(); } catch (IOException ignored) {}
        } catch (IOException e) {
            Log.e(TAG, "Error in host mic streaming thread", e);
        } finally {
            if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) rec.stop();
            rec.release();
            Log.d(TAG, "Host->Client stopped");
        }
    }

    /**
     * Streams audio from the client's microphone to the host's speaker.
     */
    private void streamClientMicToHost() {
        int buf = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack player = new AudioTrack(AudioManager.STREAM_VOICE_CALL, 16000,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, buf, AudioTrack.MODE_STREAM);
        InputStream in = null;
        try {
            in = clientAudioSocket.getInputStream();
            player.play();
            byte[] b = new byte[Math.max(640, buf)];
            Log.d(TAG, "Client->Host started");
            while (isStreaming && !Thread.currentThread().isInterrupted()) {
                int n;
                try {
                    n = in.read(b);
                } catch (SocketTimeoutException ste) {
                    continue;
                }
                if (n == -1) break;
                if (n > 0) player.write(b, 0, n);
            }
            try { clientAudioSocket.shutdownInput(); } catch (IOException ignored) {}
        } catch (IOException e) {
            Log.e(TAG, "Error in client mic streaming thread", e);
        } finally {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            Log.d(TAG, "Client->Host stopped");
        }
    }
}
