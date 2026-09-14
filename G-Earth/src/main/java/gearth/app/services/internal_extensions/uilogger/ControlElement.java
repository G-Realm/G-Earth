package gearth.app.services.internal_extensions.uilogger;

import gearth.protocol.HMessage;

class ControlElement extends Element {
    final byte[] packetBytes;
    final HMessage.Direction direction;

    ControlElement(String displayText, byte[] packetBytes, HMessage.Direction direction) {
        super(displayText, "copy");
        this.packetBytes = packetBytes;
        this.direction = direction;
    }
}
