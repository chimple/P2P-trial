package chimple.org.p2p.ui.network;

import java.io.Serializable;

public class Message implements Serializable {

    public MessageType messageType;
    public byte[] message;

    public Message(MessageType messageType, byte[] message) {
        this.messageType = messageType;
        this.message = message;
    }
}
