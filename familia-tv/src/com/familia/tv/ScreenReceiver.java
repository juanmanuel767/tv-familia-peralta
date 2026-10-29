package com.familia.tv;

import android.content.Context;
import android.content.Intent;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Listens for the computer screen stream (H.264 over TCP) and opens ScreenActivity. */
final class ScreenReceiver {
    private static final int PORT = 8081;
    private static final int TIMEOUT_MS = 15000;
    private static final byte[] MAGIC = "FTV1".getBytes(StandardCharsets.US_ASCII);

    private static final Object LOCK = new Object();
    private static Connection current;
    private static boolean busy;

    private final Context context;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread thread;

    ScreenReceiver(Context context) {
        this.context = context.getApplicationContext();
    }

    static final class Connection {
        final Socket socket;
        final int width;
        final int height;

        Connection(Socket socket, int width, int height) {
            this.socket = socket;
            this.width = width;
            this.height = height;
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Returns the pending connection, if any, and leaves it owned by the caller. */
    static Connection takeConnection() {
        synchronized (LOCK) {
            Connection connection = current;
            current = null;
            return connection;
        }
    }

    /** Called by ScreenActivity when the stream is over, to free the receiver again. */
    static void releaseConnection(Connection connection) {
        synchronized (LOCK) {
            if (current == connection) current = null;
            busy = false;
            LOCK.notifyAll();
        }
        if (connection != null) connection.close();
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "screen-receiver");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        ServerSocket server = serverSocket;
        serverSocket = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        Connection connection;
        synchronized (LOCK) {
            connection = current;
            current = null;
            busy = false;
            LOCK.notifyAll();
        }
        if (connection != null) connection.close();
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    private void acceptLoop() {
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            serverSocket = server;
            while (running) {
                final Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException e) {
                    if (!running) break;
                    continue;
                }
                Thread reader = new Thread(new Runnable() {
                    @Override public void run() {
                        readHeader(socket);
                    }
                }, "screen-header");
                reader.setDaemon(true);
                reader.start();
            }
        } catch (IOException ignored) {
        } finally {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void readHeader(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] magic = new byte[4];
            in.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) throw new IOException("bad header");
            }
            int width = in.readUnsignedShort();
            int height = in.readUnsignedShort();
            if (width <= 0 || height <= 0) throw new IOException("bad size");
            Connection connection = new Connection(socket, width, height);
            Connection previous;
            synchronized (LOCK) {
                if (!running) throw new IOException("stopped");
                previous = current;
                current = connection;
                busy = true;
                LOCK.notifyAll();
            }
            if (previous != null && previous != connection) previous.close();
            Intent intent = new Intent(context, ScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Exception e) {
            closeQuietly(socket);
            synchronized (LOCK) {
                if (current != null && current.socket == socket) current = null;
                busy = false;
                LOCK.notifyAll();
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
