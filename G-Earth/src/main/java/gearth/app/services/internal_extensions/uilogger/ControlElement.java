package gearth.app.services.internal_extensions.uilogger;

import gearth.protocol.HMessage;

class ControlElement extends Element {
    final String kind;
    final byte[] packetBytes;
    final HMessage.Direction direction;

    ControlElement(String kind, String displayText, byte[] packetBytes, HMessage.Direction direction) {
        super(displayText, kind);
        this.kind = kind;
        this.packetBytes = packetBytes;
        this.direction = direction;
    }
}
