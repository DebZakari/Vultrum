package dev.vultrum.bench;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RconTest {
    @Test
    void runsCommandsInOrderWithTheirReplies() throws Exception {
        try (var server = new FakeServer(); var rcon = new Rcon(server.address(), FakeServer.PASSWORD)) {
            var first = rcon.run("say one");
            var second = rcon.run("say two");
            rcon.caughtUp().get(5, TimeUnit.SECONDS);
            assertTrue(first.isDone() && second.isDone());
            assertEquals("ran say one", first.get());
            assertEquals("ran say two", second.get());
            assertEquals(List.of("say one", "say two"), server.commands);
        }
    }

    @Test
    void keepsTheFirstPacketOfALongReplyAndDropsTheRest() throws Exception {
        try (var server = new FakeServer(); var rcon = new Rcon(server.address(), FakeServer.PASSWORD)) {
            // 26.3 splits a reply into packets of 4096 characters, each with the request's id.
            String reply = rcon.run("long").get(5, TimeUnit.SECONDS);
            assertEquals("x".repeat(4096), reply);
            assertEquals("ran say after", rcon.run("say after").get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void refusesAWrongPasswordAndClosesItsSocket() throws Exception {
        try (var server = new FakeServer()) {
            var error = assertThrows(IOException.class, () -> new Rcon(server.address(), "wrong"));
            assertTrue(error.getMessage().contains("refused"), error.getMessage());
            assertTrue(server.closedByClient(), "the client's socket is still open");
        }
    }

    /** One connection, answered as 26.3's RconClient does. */
    private static final class FakeServer implements AutoCloseable {
        static final String PASSWORD = "secret";
        final List<String> commands = new CopyOnWriteArrayList<>();
        private final ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        private final Thread thread = Thread.ofVirtual().start(this::serve);
        private volatile boolean closedByClient;

        FakeServer() throws IOException { }

        String address() { return "127.0.0.1:" + socket.getLocalPort(); }

        boolean closedByClient() throws InterruptedException {
            thread.join(5000);
            return closedByClient;
        }

        private void serve() {
            try (Socket client = socket.accept()) {
                var in = new DataInputStream(client.getInputStream());
                var out = client.getOutputStream();
                while (true) {
                    int length;
                    try {
                        length = Integer.reverseBytes(in.readInt());
                    } catch (IOException e) {
                        closedByClient = true;
                        return;
                    }
                    var packet = ByteBuffer.wrap(in.readNBytes(length)).order(ByteOrder.LITTLE_ENDIAN);
                    int id = packet.getInt(), type = packet.getInt();
                    byte[] body = new byte[length - 10];
                    packet.get(body);
                    String text = new String(body, StandardCharsets.UTF_8);
                    if (type == 3) {
                        send(out, text.equals(PASSWORD) ? id : -1, "");
                    } else if (text.equals("long")) {
                        commands.add(text);
                        String reply = "x".repeat(4096) + "y".repeat(100);
                        send(out, id, reply.substring(0, 4096));
                        send(out, id, reply.substring(4096));
                    } else {
                        commands.add(text);
                        send(out, id, "ran " + text);
                    }
                }
            } catch (IOException e) {
                // The test ended.
            }
        }

        private static void send(OutputStream out, int id, String text) throws IOException {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            var packet = ByteBuffer.allocate(body.length + 14).order(ByteOrder.LITTLE_ENDIAN);
            packet.putInt(body.length + 10).putInt(id).putInt(2).put(body).put((byte) 0).put((byte) 0);
            out.write(packet.array());
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
