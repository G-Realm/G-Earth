package gearth.app.protocol.packethandler.shockwave;

import gearth.app.protocol.crypto.BobbaChaChaKey;
import gearth.app.protocol.crypto.BobbaCrypto;
import gearth.app.protocol.packethandler.ByteArrayUtils;
import gearth.app.protocol.packethandler.PayloadBuffer;
import gearth.app.protocol.packethandler.shockwave.buffers.ShockwaveBuffer;
import gearth.app.protocol.packethandler.shockwave.buffers.ShockwaveProperBuffer;
import gearth.encoding.Base64Encoding;
import gearth.protocol.HPacket;
import gearth.protocol.HPacketFormat;

import java.util.concurrent.ThreadLocalRandom;

public class ShockwavePacketModifier {

    private static final int C2S_GENERATEKEY = 202;
    private static final int S2C_SECRETKEY = 1;

    private static final byte[] PACKET_END = new byte[] {0x01};

    private final BobbaCrypto client;
    private final BobbaCrypto server;

    private PayloadBuffer clientBuffer;
    private PayloadBuffer serverBuffer;

    private boolean clientCryptoEnabled;
    private boolean serverCryptoEnabled;

    public ShockwavePacketModifier() {
        this.client = new BobbaCrypto();
        this.clientBuffer = new ShockwaveProperBuffer();

        this.server = new BobbaCrypto();
        this.serverBuffer = new ShockwaveBuffer();
    }

    private void enableClientCrypto() {
        if (!this.client.hasKeys()) {
            throw new IllegalStateException("Cannot enable client crypto without shared key.");
        }

        this.clientBuffer = this.swapBuffer(this.clientBuffer, this.client.getC2sHeader());
        this.clientCryptoEnabled = true;
    }

    private void enableServerCrypto() {
        if (!this.server.hasKeys()) {
            throw new IllegalStateException("Cannot enable server crypto without shared key.");
        }

        this.serverBuffer = this.swapBuffer(this.serverBuffer, this.server.getS2cHeader());
        this.serverCryptoEnabled = true;
    }

    private ShockwaveProperBuffer swapBuffer(final PayloadBuffer oldBuffer, final BobbaChaChaKey key) {
        final ShockwaveProperBuffer newBuffer = new ShockwaveProperBuffer();

        newBuffer.push(oldBuffer.getBuffer());
        newBuffer.setCipher((data, offset, length) -> {
            final byte[] header = Base64Encoding.decode(data, offset, length);
            return BobbaCrypto.applyChaCha(header, 0, header.length, key);
        });

        return newBuffer;
    }

    /**
     * @param data Raw data from client.
     * @return Plaintext delimited packets.
     */
    public byte[][] clientToGearth(byte[] data) {
        this.clientBuffer.push(data);

        final byte[][] packets = this.clientBuffer.receive();

        if (this.clientCryptoEnabled) {
            return decryptPackets(packets, this.client.getC2sData());
        }

        for (byte[] packet : packets) {
            final HPacket message = HPacketFormat.WEDGIE_OUTGOING.createPacket(packet);

            if (message.headerId() == C2S_GENERATEKEY) {
                this.client.setServerPublicKey(message.readString());
            }
        }

        return packets;
    }

    public byte[] gearthToServer(byte[] data) {
        if (this.serverCryptoEnabled) {
            return encryptPacket(data, this.server.getC2sHeader(), this.server.getC2sData());
        }

        final HPacket message = HPacketFormat.WEDGIE_OUTGOING.createPacket(data);

        if (message.headerId() == C2S_GENERATEKEY) {
            data = HPacketFormat.WEDGIE_OUTGOING
                    .createPacket(C2S_GENERATEKEY)
                    .appendString(this.server.generatePublicKey())
                    .toBytes();
        }

        final byte[] bufferLen = Base64Encoding.encode(data.length, 3);
        final byte[] buffer = ByteArrayUtils.combineByteArrays(bufferLen, data);

        return buffer;
    }

    public byte[][] serverToGearth(byte[] data) {
        this.serverBuffer.push(data);

        final byte[][] packets = this.serverBuffer.receive();

        if (this.serverCryptoEnabled) {
            return decryptPackets(packets, this.server.getS2cData());
        }

        for (byte[] packet : packets) {
            final HPacket message = HPacketFormat.WEDGIE_INCOMING.createPacket(packet);

            if (message.headerId() == S2C_SECRETKEY) {
                this.server.setServerPublicKey(message.readString());
                this.enableServerCrypto();
            }
        }

        return packets;
    }

    public byte[] gearthToClient(byte[] data) {
        if (this.clientCryptoEnabled) {
            return encryptPacket(data, this.client.getS2cHeader(), this.client.getS2cData());
        }

        final HPacket message = HPacketFormat.WEDGIE_INCOMING.createPacket(data);

        if (message.headerId() == S2C_SECRETKEY) {
            data = HPacketFormat.WEDGIE_INCOMING
                    .createPacket(S2C_SECRETKEY)
                    .appendString(this.client.generatePublicKey())
                    .toBytes();

            this.enableClientCrypto();
        }

        return ByteArrayUtils.combineByteArrays(data, PACKET_END);
    }

    private static byte[] encryptPacket(byte[] packet, BobbaChaChaKey headerKey, BobbaChaChaKey dataKey) {
        // Encrypt data.
        packet = BobbaCrypto.applyChaCha(packet, 0, packet.length, dataKey);
        packet = Base64Encoding.encode(packet, 0, packet.length);

        // Encode header.
        final byte[] newPacketLen = Base64Encoding.encode(packet.length, 3);
        byte[] header = new byte[4];

        header[0] = (byte) ThreadLocalRandom.current().nextInt(1, 127);
        header[1] = newPacketLen[0];
        header[2] = newPacketLen[1];
        header[3] = newPacketLen[2];

        // Encrypt header.
        header = BobbaCrypto.applyChaCha(header, 0, header.length, headerKey);
        header = Base64Encoding.encode(header, 0, header.length);

        return ByteArrayUtils.combineByteArrays(header, packet);
    }

    private static byte[][] decryptPackets(byte[][] packets, BobbaChaChaKey key) {
        final byte[][] decryptedPackets = new byte[packets.length][];

        for (int i = 0; i < packets.length; i++) {
            byte[] packet = packets[i];

            packet = Base64Encoding.decode(packet, 0, packet.length);
            packet = BobbaCrypto.applyChaCha(packet, 0, packet.length, key);

            decryptedPackets[i] = packet;
        }

        return decryptedPackets;
    }
}
