package uk.hamdigital.icom;
/**
 * 简单封装的udp协议处理
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import android.net.Network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class IcomUdpClient {
    private static final String TAG = "RadioUdpSocket";


    private final int MAX_BUFFER_SIZE = 1024 * 2;
    private volatile DatagramSocket sendSocket;
    //private int remotePort;
    private int localPort = -1;
    private volatile String expectedRemoteIp;
    private volatile int expectedRemotePort = -1;
    private final Network preferredNetwork;
    private boolean activated = false;
    private OnUdpEvents onUdpEvents = null;
    private final ExecutorService doReceiveThreadPool = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "icom-udp-rx");
        thread.setDaemon(true);
        return thread;
    });
    private DoReceiveRunnable doReceiveRunnable = new DoReceiveRunnable(this);
    // HF Digital Modes: one sender thread, so packets go out in order and close() can wait for them (see sendData).
    private final ExecutorService sendDataThreadPool = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "icom-udp-tx");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed = false;

    public IcomUdpClient() {
        this(-1, null);
    }

    public IcomUdpClient(int localPort) {
        this(localPort, null);
    }

    public IcomUdpClient(int localPort, Network preferredNetwork) {
        this.localPort = localPort;
        this.preferredNetwork = preferredNetwork;
    }

    public void sendData(byte[] data, String ip, int port) throws UnknownHostException {
        if (!activated || closed) return;

        InetAddress address = InetAddress.getByName(ip);
        expectedRemoteIp = address.getHostAddress();
        expectedRemotePort = port;
        // HF Digital Modes: each packet its own copy and its own task. FT8CN reused one shared SendDataRunnable and
        // overwrote its data before the pool had sent it, so packets close together could be lost or sent twice.
        SendDataRunnable r = new SendDataRunnable(this);
        r.address = address;
        r.data = Arrays.copyOf(data, data.length);
        r.port = port;
        try {
            sendDataThreadPool.execute(r);
        } catch (RejectedExecutionException ignored) {
        }
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
//                synchronized (this) {
//                    try {
//                        sendSocket.send(packet);
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                        Log.e(TAG, "IComUdpClient: " + e.getMessage());
//                        if (onUdpEvents!=null){
//                            onUdpEvents.OnUdpSendIOException(e);
//                        }
//                    }
//                }
//            }
//        }).start();
    }

    private static class SendDataRunnable implements Runnable {
        byte[] data;
        int port;
        InetAddress address;
        IcomUdpClient client;

        public SendDataRunnable(IcomUdpClient client) {
            this.client = client;
        }

        @Override
        public void run() {
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            synchronized (this) {
                try {
                    if (client.sendSocket != null) client.sendSocket.send(packet);

                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "IComUdpClient: " + e.getMessage());
                    if (client != null) {
                        if (client.onUdpEvents != null) {
                            client.onUdpEvents.OnUdpSendIOException(e);
                        }
                    }
                }
            }
        }
    }

    public boolean isActivated() {
        return activated;
    }

    public synchronized void setActivated(boolean activated) throws SocketException {
        if (activated && closed) throw new SocketException("IcomUdpClient is closed");
        if (activated) {
            try {
                sendSocket = new DatagramSocket();
                sendSocket.setReuseAddress(true);
                if (localPort != -1) sendSocket.bind(new InetSocketAddress(localPort));
                if (preferredNetwork != null) preferredNetwork.bindSocket(sendSocket);
                localPort = sendSocket.getLocalPort();
                this.activated = true;
                receiveData();
            } catch (SocketException error) {
                this.activated = false;
                if (sendSocket != null) { sendSocket.close(); sendSocket = null; }
                throw error;
            } catch (IOException error) {
                this.activated = false;
                if (sendSocket != null) { sendSocket.close(); sendSocket = null; }
                throw new SocketException("UDP setup failed: " + error.getMessage());
            } catch (RuntimeException error) {
                this.activated = false;
                if (sendSocket != null) { sendSocket.close(); sendSocket = null; }
                throw new SocketException("UDP setup failed: " + error.getMessage());
            }
        } else {
            // HF Digital Modes: let what is queued (the disconnect and logout packets) go out before the socket closes -
            // FT8CN closed it at once, so the radio never heard the app leave and kept the session for a minute or two.
            try { sendDataThreadPool.submit(() -> { }).get(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (Exception e) { Log.e(TAG, "close: send queue not empty: " + e); }
            this.activated = false;                   // now the receiver may stop (it closes the socket when it does)
            if (sendSocket != null) {
                sendSocket.close();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /** Idempotent shutdown: drain the final logout packets, close the socket and release both thread pools. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            setActivated(false);
        } catch (SocketException e) {
            Log.e(TAG, "close: " + e.getMessage());
        }
        doReceiveThreadPool.shutdownNow();
        sendDataThreadPool.shutdownNow();
        try {
            doReceiveThreadPool.awaitTermination(300, TimeUnit.MILLISECONDS);
            sendDataThreadPool.awaitTermination(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void receiveData() {
        doReceiveThreadPool.execute(doReceiveRunnable);
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                while (activated) {
//                    byte[] data = new byte[MAX_BUFFER_SIZE];
//                    DatagramPacket packet = new DatagramPacket(data, data.length);
//                    try {
//                        sendSocket.receive(packet);
//                        if (onUdpEvents != null) {
//                            byte[] temp = Arrays.copyOf(packet.getData(), packet.getLength());
//                            onUdpEvents.OnReceiveData(sendSocket, packet, temp);
//                        }
//                        //Log.d(TAG, "receiveData:host ip: " + packet.getAddress().getHostName());
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                        Log.e(TAG, "receiveData: error:" + e.getMessage());
//                    }
//
//                }
//                Log.e(TAG, "udpClient: is exit!");
//                sendSocket.close();
//                sendSocket = null;
//            }
//        }).start();

    }

    public void setOnUdpEvents(OnUdpEvents onUdpEvents) {
        this.onUdpEvents = onUdpEvents;
    }

    public interface OnUdpEvents {
        void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data);

        void OnUdpSendIOException(IOException e);
    }

    public int getLocalPort() {
        if (sendSocket != null) {
            return sendSocket.getLocalPort();
        } else {
            return 0;
        }
    }

    public String getLocalIp() {
        if (sendSocket != null) {
            return sendSocket.getLocalAddress().toString();
        } else {
            return "127.0.0.1";
        }
    }

    public DatagramSocket getSendSocket() {
        return sendSocket;
    }


    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }

    private static boolean wrongSource(java.net.DatagramPacket packet, IcomUdpClient client) {
        String expected = client.expectedRemoteIp;
        if (expected != null && (packet.getAddress() == null || !expected.equals(packet.getAddress().getHostAddress()))) return true;
        return client.expectedRemotePort > 0 && packet.getPort() != client.expectedRemotePort;
    }

    private static class DoReceiveRunnable implements Runnable {
        IcomUdpClient icomUdpClient;

        public DoReceiveRunnable(IcomUdpClient icomUdpClient) {
            this.icomUdpClient = icomUdpClient;
        }

        @Override
        public void run() {
            while (icomUdpClient.activated) {
                byte[] data = new byte[icomUdpClient.MAX_BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                try {
                    icomUdpClient.sendSocket.receive(packet);
                    if (wrongSource(packet, icomUdpClient)) continue;
                    if (icomUdpClient.onUdpEvents != null) {
                        byte[] temp = Arrays.copyOf(packet.getData(), packet.getLength());
                        icomUdpClient.onUdpEvents.OnReceiveData(icomUdpClient.sendSocket, packet, temp);
                    }
                    //Log.d(TAG, "receiveData:host ip: " + packet.getAddress().getHostName());
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "receiveData: error:" + e.getMessage());
                }

            }
            Log.e(TAG, "udpClient: is exit!");
            icomUdpClient.sendSocket.close();
            icomUdpClient.sendSocket = null;
        }
    }

}

