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
        // Use a single thread to accept connections
        audioServerThread = new Thread(new AudioServerRunnable());
        audioServerThread.start();
        streamingExecutor = Executors.newFixedThreadPool(2); // Two threads for bidirectional streaming
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
     * Initiates the bidirectional audio bridge by starting the streaming threads.
     */
    public void startAudioBridge() {
        if (clientAudioSocket == null || clientAudioSocket.isClosed()) {
            Log.e(TAG, "Cannot start audio bridge: No client socket connected.");
            return;
        }

        isStreaming = true;
        Log.d(TAG, "Starting bidirectional audio bridge.");

        // Host microphone -> Client speaker (OUTGOING STREAM)
        hostToClientStreamFuture = streamingExecutor.submit(this::streamHostMicToClient);

        // Client microphone -> Host speaker (INCOMING STREAM)
        clientToHostStreamFuture = streamingExecutor.submit(this::streamClientMicToHost);
    }

    /**
     * Stops the audio streaming threads by setting the flag and canceling futures.
     */
    public void stopAudioBridge() {
        isStreaming = false;
        Log.d(TAG, "Stopping host audio bridge.");
        if (hostToClientStreamFuture != null) {
            hostToClientStreamFuture.cancel(true);
        }
        if (clientToHostStreamFuture != null) {
            clientToHostStreamFuture.cancel(true);
        }
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
                    // Accept a single client connection
                    clientAudioSocket = audioServerSocket.accept();
                    Log.d(TAG, "Client connected to audio server: " + clientAudioSocket.getInetAddress());
                    // We don't start the bridge here, the NetworkServerService will call startAudioBridge()
                    // when the client sends the "AUDIO_READY" command.
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Audio server socket error", e);
                }
            } finally {
                if (clientAudioSocket != null) {
                    try {
                        clientAudioSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing client audio socket", e);
                    }
                }
            }
        }
    }

    /**
     * Streams audio from the host's microphone to the connected client's speaker.
     */
    private void streamHostMicToClient() {
        int bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Cannot stream mic to client.");
            return;
        }
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        try (OutputStream out = clientAudioSocket.getOutputStream()) {
            recorder.startRecording();
            byte[] buffer = new byte[bufferSize];
            Log.d(TAG, "Host to client audio streaming started.");
            while (isStreaming && !Thread.currentThread().isInterrupted()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in host mic streaming thread", e);
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
            recorder.release();
            Log.d(TAG, "Host to client audio streaming stopped.");
        }
    }

    /**
     * Streams audio from the client's microphone to the host's speaker.
     */
    private void streamClientMicToHost() {
        int bufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack player = new AudioTrack(AudioManager.STREAM_VOICE_CALL, 16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
        try (InputStream in = clientAudioSocket.getInputStream()) {
            player.play();
            byte[] buffer = new byte[bufferSize];
            Log.d(TAG, "Client to host audio streaming started.");
            while (isStreaming && !Thread.currentThread().isInterrupted()) {
                int read = in.read(buffer);
                if (read > 0) {
                    player.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in client mic streaming thread", e);
        } finally {
            player.stop();
            player.release();
            Log.d(TAG, "Client to host audio streaming stopped.");
        }
    }
}
