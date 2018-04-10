package chimple.org.p2p.db.entity;

import android.arch.persistence.room.Entity;
import android.arch.persistence.room.Ignore;
import android.arch.persistence.room.Index;
import android.arch.persistence.room.PrimaryKey;

import java.util.Date;

@Entity(indices = {
        @Index("userId"),
        @Index("deviceId"),
        @Index("sequence")
}
)
public class P2PSyncInfo {
    @PrimaryKey(autoGenerate = true)
    public Long id;

    public String userId;

    public String deviceId;

    public Long sequence;

    public String messageType;

    public String senderUserId;

    public String receipientUserId;

    public String message;

    public String fileName;

    public Date loggedAt;

    @Ignore
    public P2PSyncInfo(String userId, String deviceId, Long sequence, String senderUserId, String receipientUserId,
                       String message, String messageType, String fileName) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.sequence = sequence;
        this.senderUserId = senderUserId;
        this.receipientUserId = receipientUserId;
        this.message = message;
        this.fileName = fileName;
        this.messageType = messageType;
        this.loggedAt = new Date();
    }

    public P2PSyncInfo() {

    }
}


