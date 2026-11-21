package com.terraduo.companion;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** One request to the TL Pro mod in the running game (127.0.0.1:38656, one JSON line each way). */
final class ModLink {
    static final int PORT = 38656;

    private ModLink() { }

    /** The mod's reply, or null when the game is not running a world with the mod enabled. */
    static JSONObject ask(JSONObject request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
            socket.setSoTimeout(1350);
            socket.getOutputStream().write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String reply = input.readLine();
            return reply == null ? null : new JSONObject(reply);
        } catch (Exception error) {
            return null;
        }
    }

    /** The mod's protocol number (0 for a mod from before the number existed), or -1 when nothing answers. */
    static int protocol() {
        try {
            JSONObject request = new JSONObject();
            request.put("op", "state");
            request.put("pane", "inventory");
            request.put("page", 0);
            JSONObject reply = ask(request);
            return reply == null ? -1 : reply.optInt("proto", 0);
        } catch (Exception error) {
            return -1;
        }
    }
}
