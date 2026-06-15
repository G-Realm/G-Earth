package gearth.app.services.nitro.hotels;

import gearth.app.protocol.connection.proxy.nitro.NitroPacketEvent;
import gearth.app.services.nitro.NitroHotel;
import gearth.app.services.nitro.NitroPacketModifier;

import java.util.Collections;

public class HubbeSt extends NitroHotel {

    public HubbeSt() {
        super("hubbe.st",
                Collections.singletonList("wss://ws.hubbe.st:2053/"),
                Collections.emptyList());
    }

    @Override
    public NitroPacketModifier createPacketModifier(String websocketUrl) {
        return new HubbeStModifier();
    }

    @Override
    protected void loadAsset(String host, String uri, byte[] data) {
    }

    public static class HubbeStModifier implements NitroPacketModifier {

        private boolean firstPacket;

        @Override
        public void clientToGearth(NitroPacketEvent e) {
            if (!firstPacket) {
                firstPacket = true;
                e.bypass = true;
            }
        }

        @Override public void gearthToClient(NitroPacketEvent e) {}
        @Override public void serverToGearth(NitroPacketEvent e) {}
        @Override public void gearthToServer(NitroPacketEvent e) {}
    }
}
