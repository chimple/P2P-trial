package chimple.org.p2p.wifi.direct;

public enum ServiceType {

    PRESENCE_TCP("_wdm_p2p._tcp");

    private final String serviceType;

    ServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    @Override
    public String toString() {
        return serviceType;
    }
}
