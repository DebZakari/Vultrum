package dev.vultrum.bench;

import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A dedicated server's console, over RCON: the bench's commands on a multiplayer server run as the integrated
 * server's do in singleplayer, from a console source in the overworld. They run in order on a thread of their own,
 * since the server answers each once it has run it on its own thread.
 */
final class Rcon implements AutoCloseable {
    private static final int EXEC = 2, AUTH = 3, AUTH_FAILED = -1;
    // The server reads a request in one read of at most 1460 bytes, and sends a reply in packets of 4096 characters.
    private static final int MAX_REQUEST = 1460, MAX_REPLY = 1 << 16;

    private final Socket socket = new Socket();
    private final DataInputStream in;
    private final OutputStream out;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(task -> {
        var t = new Thread(task, "vultrum-bench-rcon");
        t.setDaemon(true);
        return t;
    });
    private int nextId = 1;

    Rcon(String address, String password) throws IOException {
        var parsed = ServerAddress.parseString(address);
        try {
            socket.connect(new InetSocketAddress(parsed.getHost(), parsed.getPort()), 10_000);
            socket.setSoTimeout(60_000);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
            if (exchange(AUTH, password).id() == AUTH_FAILED) throw new IOException("RCON refused the password at " + address);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** Queues a command. The future completes with the server's reply once it has run. */
    CompletableFuture<String> run(String command) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return exchange(EXEC, command).payload();
            } catch (IOException e) {
                throw new UncheckedIOException("RCON command failed: " + command, e);
            }
        }, thread);
    }

    /** Completes once every command queued before it has run. */
    CompletableFuture<Void> caughtUp() { return CompletableFuture.runAsync(() -> { }, thread); }

    private record Reply(int id, String payload) { }

    private Reply exchange(int type, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        if (body.length + 14 > MAX_REQUEST) throw new IOException("RCON request too long: " + payload);
        int id = nextId++;
        var request = ByteBuffer.allocate(body.length + 14).order(ByteOrder.LITTLE_ENDIAN);
        request.putInt(body.length + 10).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
        out.write(request.array());
        out.flush();
        // A long reply comes in several packets, all with the request's id. Only the first is kept: the rest are
        // still unread when the next request goes out, and its exchange drops them while it waits for its own id.
        while (true) {
            int length = Integer.reverseBytes(in.readInt());
            if (length < 10 || length > MAX_REPLY) throw new IOException("RCON reply of " + length + " bytes");
            var reply = ByteBuffer.wrap(in.readNBytes(length)).order(ByteOrder.LITTLE_ENDIAN);
            if (reply.remaining() < length) throw new IOException("RCON connection closed mid-reply");
            int replyId = reply.getInt();
            reply.getInt(); // type
            if (replyId != id && replyId != AUTH_FAILED) continue;
            byte[] text = new byte[length - 10];
            reply.get(text);
            return new Reply(replyId, new String(text, StandardCharsets.UTF_8));
        }
    }

    @Override
    public void close() throws IOException {
        thread.shutdownNow();
        socket.close();
    }
}
