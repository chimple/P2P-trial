package chimple.org.p2p.wifi.direct;

import android.net.wifi.p2p.WifiP2pDevice;

import java.util.Map;

public class NoPromptRecord {
    private Map<String, String> record;

    public NoPromptRecord(Map<String, String> record) {
        this.record = record;
    }

    public Map getRecord() {
        return record;
    }
}
