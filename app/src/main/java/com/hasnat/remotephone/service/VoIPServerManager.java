package com.hasnat.remotephone.service;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class VoIPServerManager {

    private static final String TAG = "VoIPServerManager";
    private static final int SAMPLE_RATE = 8000;
    private static final int BUFFER_SIZE = 160; // 20ms of audio at 8kHz, 16-bit mono

    private AudioRecord audioRecorder;
    private AudioTrack audioPlayer;
    private DatagramSocket sendSocket, receiveSocket;
    private Thread sendThread, receiveThread;
    private boolean isRunning = false;

    private String clientIp;
    private int clientPort;
    private int hostPort;

    public VoIPServerManager(String clientIp, int hostPort) {
        this.clientIp = clientIp;
        this.hostPort = hostPort;
        this.clientPort = hostPort + 1; // Corresponds to the port the client is listening on
    }

    public void startVoIP() {
        if (isRunning) return;

        isRunning = true;

        // Initialize audio components
        initAudio();

        // Start the UDP threads
        startThreads();
    }

    public void stopVoIP() {
        if (!isRunning) return;

        isRunning = false;

        // Stop and release audio components
        releaseAudio();

        // Close UDP sockets
        closeSockets();

        // Interrupt threads
        if (sendThread != null) sendThread.interrupt();
        if (receiveThread != null) receiveThread.interrupt();
    }

    private void initAudio() {
        // Initialize AudioRecord (cellular call input)
        int minRecordBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minRecordBufSize);
        audioRecorder.startRecording();

        // Initialize AudioTrack (cellular call output)
        int minPlayBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioPlayer = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minPlayBufSize, AudioTrack.MODE_STREAM);
        audioPlayer.play();
    }

    private void releaseAudio() {
        if (audioRecorder != null) {
            audioRecorder.stop();
            audioRecorder.release();
            audioRecorder = null;
        }
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer.release();
            audioPlayer = null;
        }
    }

    private void startThreads() {
        // Thread for sending cellular audio to the client
        sendThread = new Thread(() -> {
            try {
                sendSocket = new DatagramSocket();
                InetAddress clientAddress = InetAddress.getByName(clientIp);
                byte[] buffer = new byte[BUFFER_SIZE];
                while (isRunning) {
                    int bytesRead = audioRecorder.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        DatagramPacket packet = new DatagramPacket(buffer, bytesRead, clientAddress, clientPort);
                        sendSocket.send(packet);
                    }
                }
            } catch (SocketException | UnknownHostException e) {
                Log.e(TAG, "Error in sendThread: " + e.getMessage());
            } catch (IOException e) {
                Log.e(TAG, "IOException in sendThread: " + e.getMessage());
            } finally {
                if (sendSocket != null) sendSocket.close();
            }
        });

        // Thread for receiving client audio and playing it to the cellular call
        receiveThread = new Thread(() -> {
            try {
                receiveSocket = new DatagramSocket(hostPort);
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (isRunning) {
                    receiveSocket.receive(packet);
                    audioPlayer.write(packet.getData(), 0, packet.getLength());
                }
            } catch (SocketException e) {
                Log.e(TAG, "Error in receiveThread: " + e.getMessage());
            } catch (IOException e) {
                Log.e(TAG, "IOException in receiveThread: " + e.getMessage());
            } finally {
                if (receiveSocket != null) receiveSocket.close();
            }
        });

        sendThread.start();
        receiveThread.start();
    }

    private void closeSockets() {
        if (sendSocket != null) {
            sendSocket.close();
            sendSocket = null;
        }
        if (receiveSocket != null) {
            receiveSocket.close();
            receiveSocket = null;
        }
    }
}