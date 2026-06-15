package gearth.app.services.nitro.hotels;

import gearth.app.protocol.connection.proxy.nitro.NitroPacketEvent;
import gearth.app.services.nitro.NitroAsset;
import gearth.app.services.nitro.NitroHotel;
import gearth.app.services.nitro.NitroPacketModifier;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class Leet extends NitroHotel {

    private static final long MAX_TIMESTAMP_SKEW_SECONDS = 600;

    private final HashSet<String> socketUrls;
    private final HashMap<String, Boolean> secureFrames;

    public Leet() {
        super("leet",
                List.of("wss://proxy.leet.city/*",
                        "wss://proxy.leethotel.biz/*",
                        "wss://game.habblet.city/*"),
                List.of(new NitroAsset("images.leet.city", "/leet-asset-bundles/config/renderer-config-new.json"),
                        new NitroAsset("images.habblet.city", "/habblet-asset-bundles/config/renderer-config.json"),
                        new NitroAsset("images.leethotel.biz", "/leet-asset-bundles/config/renderer-config.json")));

        socketUrls = new HashSet<>();
        secureFrames = new HashMap<>();
    }

    @Override
    public NitroPacketModifier createPacketModifier(String websocketUrl) {
        if (secureFrames.getOrDefault(websocketUrl, false)) {
            return new LeetNLPacketModifier();
        }

        return null;
    }

    @Override
    public boolean skipWebsocket(String websocketUrl) {
        if (socketUrls.contains(websocketUrl)) {
            return false;
        }

        return this.hasWebsocket(websocketUrl);
    }

    @Override
    protected void loadAsset(String host, String uri, byte[] data) {
        final String jsonData = new String(data);
        final JSONObject jsonObject = new JSONObject(jsonData);

        if (jsonObject.has("socket.url")) {
            socketUrls.add(jsonObject.getString("socket.url"));
        }
    }

    @Override
    public boolean isInitialFrame(String websocketUrl, final byte[] data) {
        secureFrames.put(websocketUrl, isSecureFrame(data));
        return true;
    }

    private boolean isSecureFrame(final byte[] data) {
        if (data.length < 11) {
            return false;
        }

        final ByteBuffer payload = ByteBuffer.wrap(data);

        payload.getInt(); // magic
        payload.get(); // requestType

        final int textLen = payload.getShort();
        if (data.length != 11 + textLen) {
            return false;
        }

        final byte[] text = new byte[textLen];
        payload.get(text);
        for (int i = 0; i < textLen; i++) {
            final int b = text[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                return false;
            }
        }

        final long ts = payload.getInt();
        final long now = System.currentTimeMillis() / 1000L;

        return Math.abs(now - ts) <= MAX_TIMESTAMP_SKEW_SECONDS;
    }

    public static class LeetNLPacketModifier implements NitroPacketModifier {

        private static final short OUTGOING_FIRST = 4000;
        private static final short INCOMING_FIRST = 1347;

        private final DirectionStateHolder client;
        private final DirectionStateHolder server;

        public LeetNLPacketModifier() {
            client = new DirectionStateHolder(OUTGOING_FIRST);
            server = new DirectionStateHolder(INCOMING_FIRST);
        }

        private void toGearth(final DirectionStateHolder holder, final NitroPacketEvent e) {
            if (holder.state == DirectionState.BOOTSTRAP) {
                holder.state = DirectionState.HANDSHAKE;
                e.bypass = true;
                return;
            }

            final ByteBuffer payload = ByteBuffer.wrap(e.buffer.clone());

            if (holder.state == DirectionState.HANDSHAKE) {
                holder.state = DirectionState.CONNECTED;
                holder.offset = (short) (payload.getShort(4) - holder.firstId);
            }

            payload.putShort(4, (short) (payload.getShort(4) - holder.offset));

            e.buffer = payload.array();
        }

        private void fromGearth(final DirectionStateHolder holder, final NitroPacketEvent e) {
            if (holder.state != DirectionState.CONNECTED) {
                return;
            }

            final ByteBuffer payload = ByteBuffer.wrap(e.buffer.clone());

            payload.putShort(4, (short) (payload.getShort(4) + holder.offset));

            e.buffer = payload.array();
        }

        @Override public void clientToGearth(final NitroPacketEvent e) { toGearth(client, e); }
        @Override public void serverToGearth(final NitroPacketEvent e) { toGearth(server, e); }
        @Override public void gearthToClient(final NitroPacketEvent e) { fromGearth(server, e); }
        @Override public void gearthToServer(final NitroPacketEvent e) { fromGearth(client, e); }

        private static final class DirectionStateHolder {
            final short firstId;
            DirectionState state;
            short offset;

            public DirectionStateHolder(short firstId) {
                this.state = DirectionState.BOOTSTRAP;
                this.firstId = firstId;
            }
        }

        private enum DirectionState {
            BOOTSTRAP,
            HANDSHAKE,
            CONNECTED
        }
    }
}
