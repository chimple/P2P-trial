package chimple.org.p2p.wifi.direct;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import chimple.org.p2p.errors.FailureReason;

public class WifiDirectHandler extends WifiDirectIntentService implements
        WifiP2pManager.ConnectionInfoListener,
        Handler.Callback {

    private static final String ANDROID_SERVICE_NAME = "Chimple P2P";
    public static final String TAG = "ch_p2p_";
    private final IBinder binder = new WifiTesterBinder();

    public static final String SERVICE_MAP_KEY = "serviceMapKey";
    public static final String TXT_MAP_KEY = "txtMapKey";
    public static final String MESSAGE_KEY = "messageKey";
    private final String PEERS = "peers";
    private final String WIFI_STATE = "wifiState";

    private Map<String, DnsSdTxtRecord> dnsSdTxtRecordMap;
    private Map<String, DnsSdService> dnsSdServiceMap;
    private List<ServiceDiscoveryTask> serviceDiscoveryTasks;
    private WifiP2pDeviceList peers;
    private LocalBroadcastManager localBroadcastManager;
    private BroadcastReceiver p2pBroadcastReceiver;
    private BroadcastReceiver wifiBroadcastReceiver;
    private WifiP2pServiceInfo wifiP2pServiceInfo;
    private WifiP2pServiceRequest serviceRequest;
    private Boolean isWifiP2pEnabled;
    private Handler handler = new Handler((Handler.Callback) this);
    private Thread socketHandler;
    private SocketManager socketManager = null;
    public static final int MESSAGE_READ = 0x400 + 1;
    public static final int MY_HANDLE = 0x400 + 2;
    public static final int COMMUNICATION_DISCONNECTED = 0x400 + 3;
    public static final int SERVER_PORT = 4545;
    private final int SERVICE_DISCOVERY_TIMEOUT = 30000;
    private boolean hadConnection;

    private boolean isDiscovering = false;
    private boolean isGroupOwner = false;
    private boolean groupFormed = false;
    private boolean serviceDiscoveryRegistered = false;
    private boolean stopDiscoveryAfterGroupFormed = true;


    private Object peerDiscoveryLock = new Object();
    private Object wifiP2pDeviceLock = new Object();
    private WifiP2pDeviceList wifiP2pDeviceList;
    //    private boolean isDiscoveringPeers = false;
    private Runnable postedPeerDiscoveryRunnable = null;
    //handle running peer discovery on an interval. Also required to rebroadcast any local service
    private int peerDiscoveryInterval = 30000;
    private Handler peerDiscoveryHandler = new Handler();
    private boolean localServicePeerDiscoveryKickEnabled = false; //FORNOW....


    // Flag for creating a no prompt service
    private boolean isCreatingNoPrompt = false;
    private WifiDirectServiceData noPromptServiceData;

    // Variables created in onCreate()
    private WifiP2pManager.Channel channel;
    private WifiP2pManager wifiP2pManager;
    private WifiManager wifiManager;

    private WifiP2pDevice thisDevice;
    private WifiP2pGroup wifiP2pGroup;
    private List<ScanResult> wifiScanResults;

    /**
     * Constructor
     **/
    public WifiDirectHandler() {
        super(ANDROID_SERVICE_NAME);
        dnsSdTxtRecordMap = new HashMap<>();
        dnsSdServiceMap = new HashMap<>();
        peers = new WifiP2pDeviceList();
    }

    /**
     * Registers the Wi-Fi manager, registers the app with the Wi-Fi P2P framework, registers the
     * P2P BroadcastReceiver, and registers a local BroadcastManager
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Creating WifiDirectHandler");

        // Registers the Wi-Fi Manager and the Wi-Fi BroadcastReceiver
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        registerWifiReceiver();

        // Scans for available Wi-Fi networks
        wifiManager.startScan();

        if (wifiManager.isWifiEnabled()) {
            Log.i(TAG, "Wi-Fi enabled on load");
        } else {
            Log.i(TAG, "Wi-Fi disabled on load");
        }

        // Registers a local BroadcastManager that is used to broadcast Intents to Activities
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        Log.i(TAG, "WifiDirectHandler created");
    }

    /**
     * Registers the application with the Wi-Fi P2P framework
     * Initializes the P2P manager and gets a P2P communication channel
     */
    public void registerP2p() {
        // Manages Wi-Fi P2P connectivity
        wifiP2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);

        // initialize() registers the app with the Wi-Fi P2P framework
        // Channel is used to communicate with the Wi-Fi P2P framework
        // Main Looper is the Looper for the main thread of the current process
        if (wifiP2pManager != null) {
            channel = wifiP2pManager.initialize(this, getMainLooper(), null);
            Log.i(TAG, "Registered with Wi-Fi P2P framework");
        }
    }

    /**
     * Unregisters the application with the Wi-Fi P2P framework
     */
    public void unregisterP2p() {
        if (wifiP2pManager != null) {
            wifiP2pManager = null;
            channel = null;
            thisDevice = null;
            localBroadcastManager.sendBroadcast(new Intent(Action.DEVICE_CHANGED));
            Log.i(TAG, "Unregistered with Wi-Fi P2P framework");
        }
    }

    /**
     * Registers a WifiDirectBroadcastReceiver with an IntentFilter listening for P2P Actions
     */
    public void registerP2pReceiver() {
        if (p2pBroadcastReceiver == null) {
            p2pBroadcastReceiver = new WifiDirectBroadcastReceiver();
            IntentFilter intentFilter = new IntentFilter();

            // Indicates a change in the list of available peers
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            // Indicates a change in the Wi-Fi P2P status
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            // Indicates the state of Wi-Fi P2P connectivity has changed
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            // Indicates this device's details have changed.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            registerReceiver(p2pBroadcastReceiver, intentFilter);
            Log.i(TAG, "P2P BroadcastReceiver registered");
        }


    }

    /**
     * Unregisters the WifiDirectBroadcastReceiver and IntentFilter
     */
    public void unregisterP2pReceiver() {
        if (p2pBroadcastReceiver != null) {
            unregisterReceiver(p2pBroadcastReceiver);
            p2pBroadcastReceiver = null;
            Log.i(TAG, "P2P BroadcastReceiver unregistered");
        }
    }

    public void registerWifiReceiver() {
        wifiBroadcastReceiver = new WifiBroadcastReceiver();
        IntentFilter wifiIntentFilter = new IntentFilter();

        // Indicates that Wi-Fi has been enabled, disabled, enabling, disabling, or unknown
        wifiIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wifiIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        wifiIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(wifiBroadcastReceiver, wifiIntentFilter);
        Log.i(TAG, "Wi-Fi BroadcastReceiver registered");
    }

    public void unregisterWifiReceiver() {
        if (wifiBroadcastReceiver != null) {
            unregisterReceiver(wifiBroadcastReceiver);
            wifiBroadcastReceiver = null;
            Log.i(TAG, "Wi-Fi BroadcastReceiver unregistered");
        }
    }

    public void unregisterWifi() {
        if (wifiManager != null) {
            wifiManager = null;
            Log.i(TAG, "Wi-Fi manager unregistered");
        }
    }

    /**
     * The requested connection info is available
     *
     * @param wifiP2pInfo Wi-Fi P2P connection info
     */
    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
        Log.i(TAG, "Connection info available");
        Log.i(TAG, "WifiP2pInfo: ");
        Log.i(TAG, p2pInfoToString(wifiP2pInfo));
        this.groupFormed = wifiP2pInfo.groupFormed;
        this.isGroupOwner = wifiP2pInfo.isGroupOwner;

        if (wifiP2pInfo.groupFormed) {
            if (stopDiscoveryAfterGroupFormed) {
                stopServiceDiscovery();
                stopPeerDiscovery(); //stop all discovery once group is formed
            }

//            Thread handler;
            if (wifiP2pInfo.isGroupOwner && socketHandler == null) {
                Log.i(TAG, "Connected as group owner");
                try {
                    socketHandler = new WifiDirectServerSocketHandler(this.getHandler());
                    socketHandler.start();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create a server thread - " + e.getMessage());
                    return;
                }
            } else {
                Log.i(TAG, "Connected as peer");
                socketHandler = new WifiDirectClientSocketHandler(this.getHandler(), wifiP2pInfo.groupOwnerAddress);
                socketHandler.start();
            }

//            localBroadcastManager.sendBroadcast(new Intent(Action.SERVICE_CONNECTED));
        } else {
            Log.w(TAG, "Group not formed");
        }
        localBroadcastManager.sendBroadcast(new Intent(Action.DEVICE_CHANGED));
    }

    // TODO add JavaDoc
    public void addLocalService(String serviceName, HashMap<String, String> serviceRecord) {

        // Logs information about local service
        Log.i(TAG, "Adding local service: " + serviceName);

        // Service information
        wifiP2pServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                serviceName,
                ServiceType.PRESENCE_TCP.toString(),
                serviceRecord
        );

        // Only add a local service if clearLocalServices succeeds
        if(wifiP2pManager != null) {
            wifiP2pManager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    // Add the local service
                    wifiP2pManager.addLocalService(channel, wifiP2pServiceInfo, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "Local service added");
                            synchronized (peerDiscoveryLock) {
                                localServicePeerDiscoveryKickEnabled = true; // Enable discover peers on local service
                            }
                            discoverPeers(peerDiscoveryInterval);
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.e(TAG, "Failure adding local service: " + FailureReason.fromInteger(reason).toString());
                            wifiP2pServiceInfo = null;
                        }
                    });
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure clearing local services: " + FailureReason.fromInteger(reason).toString());
                    wifiP2pServiceInfo = null;
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopServiceDiscovery();
        removeGroup();
        removePersistentGroups();
        removeService();
        unregisterP2pReceiver();
        unregisterP2p();
        unregisterWifiReceiver();
        unregisterWifi();
        Log.i(TAG, "Wifi Handler service destroyed");
    }

    /**
     * Removes persistent/remembered groups
     * <p>
     * Source: https://android.googlesource.com/platform/cts/+/jb-mr1-dev%5E1%5E2..jb-mr1-dev%5E1/
     * Author: Nick  Kralevich <nnk@google.com>
     * <p>
     * WifiP2pManager.java has a method deletePersistentGroup(), but it is not accessible in the
     * SDK. According to Vinit Deshpande <vinitd@google.com>, it is a common Android paradigm to
     * expose certain APIs in the SDK and hide others. This allows Android to maintain stability and
     * security. As a workaround, this removePersistentGroups() method uses Java reflection to call
     * the hidden method. We can list all the methods in WifiP2pManager and invoke "deletePersistentGroup"
     * if it exists. This is used to remove all possible persistent/remembered groups.
     */
    private void removePersistentGroups() {
        try {
            Method[] methods = WifiP2pManager.class.getMethods();
            for (int i = 0; i < methods.length; i++) {
                if (methods[i].getName().equals("deletePersistentGroup")) {
                    // Remove any persistent group
                    for (int netid = 0; netid < 32; netid++) {
                        methods[i].invoke(wifiP2pManager, channel, netid, null);
                        Log.i(TAG, "deletePersistentGroup groups netid:" + netid);
                    }
                }
            }
            Log.i(TAG, "Persistent groups removed");
        } catch (Exception e) {
            Log.e(TAG, "Failure removing persistent groups: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Removes the current WifiP2pGroup in the WifiP2pChannel.
     */
    public void removeGroup() {
        if (wifiP2pGroup != null) {
            wifiP2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    wifiP2pGroup = null;
                    groupFormed = false;
                    isGroupOwner = false;
                    Log.i(TAG, "Group removed");
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure removing group: " + FailureReason.fromInteger(reason).toString());
                }
            });
        }
    }

    /*
     * Registers listeners for DNS-SD services. These are callbacks invoked
     * by the system when a service is actually discovered.
     */
    private void registerServiceDiscoveryListeners() {
        // DnsSdTxtRecordListener
        // Interface for callback invocation when Bonjour TXT record is available for a service
        // Used to listen for incoming records and get peer device information
        WifiP2pManager.DnsSdTxtRecordListener txtRecordListener = new WifiP2pManager.DnsSdTxtRecordListener() {
            @Override
            public void onDnsSdTxtRecordAvailable(String fullDomainName, Map<String, String> txtRecordMap, WifiP2pDevice srcDevice) {
                // Records of peer are available
                Log.i(TAG, "Peer DNS-SD TXT Record available");

                Intent intent = new Intent(Action.DNS_SD_TXT_RECORD_AVAILABLE);
                intent.putExtra(TXT_MAP_KEY, srcDevice.deviceAddress);
                localBroadcastManager.sendBroadcast(intent);
                dnsSdTxtRecordMap.put(srcDevice.deviceAddress, new DnsSdTxtRecord(fullDomainName, txtRecordMap, srcDevice));
            }
        };

        // DnsSdServiceResponseListener
        // Interface for callback invocation when Bonjour service discovery response is received
        // Used to get service information
        WifiP2pManager.DnsSdServiceResponseListener serviceResponseListener = new WifiP2pManager.DnsSdServiceResponseListener() {
            @Override
            public void onDnsSdServiceAvailable(String instanceName, String registrationType, WifiP2pDevice srcDevice) {
                // Not sure if we want to track the map here or just send the service in the request to let the caller do
                // what it wants with it

                if (registrationType.startsWith(ServiceType.PRESENCE_TCP.toString())) {

                    Log.i(TAG, "DNS-SD service available");
                    Log.i(TAG, "Local service found: " + instanceName);
                    Log.i("TAG", "Source device: ");
                    Log.i(TAG, p2pDeviceToString(srcDevice));
                    dnsSdServiceMap.put(srcDevice.deviceAddress, new DnsSdService(instanceName, registrationType, srcDevice));
                    Intent intent = new Intent(Action.DNS_SD_SERVICE_AVAILABLE);
                    intent.putExtra(SERVICE_MAP_KEY, srcDevice.deviceAddress);
                    localBroadcastManager.sendBroadcast(intent);
                } else {
                    Log.i(TAG, "Not our Service, :" + ServiceType.PRESENCE_TCP.toString() + "!=" + registrationType + ":");
                }

            }
        };

        wifiP2pManager.setDnsSdResponseListeners(channel, serviceResponseListener, txtRecordListener);
        Log.i(TAG, "Service discovery listeners registered");
    }

    private void addServiceDiscoveryRequest() {
        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(ServiceType.PRESENCE_TCP.toString());

        // Tell the framework we want to scan for services. Prerequisite for discovering services
        wifiP2pManager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "Service discovery request added");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failure adding service discovery request: " + FailureReason.fromInteger(reason).toString());
                serviceRequest = null;
            }
        });
    }

    /**
     * By default after a group is formed service discovery will be stopped automatically. If you
     * wish to continue discovery after forming a group set this to false
     *
     * @param stopDiscoveryAfterGroupFormed true to stop discovery automatically after a group is formed; false otherwise
     */
    public void setStopDiscoveryAfterGroupFormed(boolean stopDiscoveryAfterGroupFormed) {
        this.stopDiscoveryAfterGroupFormed = stopDiscoveryAfterGroupFormed;
    }

    /**
     * By default after a group is formed service discovery will be stopped automatically.
     *
     * @return true if discovery will be stopped automatically after group is formed, false otherwise
     */
    public boolean isStopDiscoveryAfterGroupFormed() {
        return stopDiscoveryAfterGroupFormed;
    }

    /**
     * Initiates a service discovery. This has a 2 minute timeout. To continuously
     * discover services use continuouslyDiscoverServices
     */
    public void discoverServices() {
        // Initiates service discovery. Starts to scan for services we want to connect to
        wifiP2pManager.discoverServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "Service discovery initiated");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failure initiating service discovery: " + FailureReason.fromInteger(reason).toString());
                /*
                 * Very rarely, and for no apparent reason, even if service requests have been
                 * registered we can get a NO_SERVICE_REQUESTS error. In that case the best we
                 * can try to do is to reset service discovery.
                 */
                if (reason == WifiP2pManager.NO_SERVICE_REQUESTS && isDiscovering) {
                    Log.w(TAG, "Detected failure due to NO_SERVICE_REQUESTS whilst isDiscovering. Resetting service discovery");
                    //resetServiceDiscovery();
                }
            }
        });
    }

    public void disconnectDevices() {
        wifiP2pManager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "cancel Connect Service");
            }

            @Override
            public void onFailure(int i) {
                Log.i(TAG, "cancel Connect Failure");
            }
        });
    }

    /**
     * Calls initial services discovery call and submits the first
     * Discover task. This will continue until stopDiscoveringServices is called
     */
    public void continuouslyDiscoverServices() {
        Log.i(TAG, "Continuously Discover services called");

        if (serviceDiscoveryRegistered == false) {
            Log.i(TAG, "Setting up service discovery");
            registerServiceDiscoveryListeners();
            serviceDiscoveryRegistered = true;
        }

        // TODO Change this to give some sort of status
        if (isDiscovering) {
            Log.w(TAG, "Services are still discovering, do not need to make this call");
        } else {
            addServiceDiscoveryRequest();
            isDiscovering = true;
            // List to track discovery tasks in progress
            serviceDiscoveryTasks = new ArrayList<>();
            // Make discover call and first discover task submission
            discoverServices();
            submitServiceDiscoveryTask();
        }
    }

    public void stopServiceDiscovery() {
        Log.i(TAG, "Stopping service discovery");
        if (isDiscovering) {
            dnsSdServiceMap = new HashMap<>();
            dnsSdTxtRecordMap = new HashMap<>();
            // Cancel all discover tasks that may be in progress
            for (ServiceDiscoveryTask serviceDiscoveryTask : serviceDiscoveryTasks) {
                serviceDiscoveryTask.cancel();
            }
            serviceDiscoveryTasks = null;
            isDiscovering = false;
            Log.i(TAG, "Service discovery stopped");
            clearServiceDiscoveryRequests();
        }
    }

    public void resetServiceDiscovery() {
        Log.i(TAG, "Resetting service discovery");
        stopServiceDiscovery();
        continuouslyDiscoverServices();
    }

    /**
     * Submits a new task to initiate service discovery after the discovery
     * timeout period has expired
     */
    private void submitServiceDiscoveryTask() {
        Log.i(TAG, "Submitting service discovery task");
        // Discover times out after 2 minutes so we set the timer to that
        int timeToWait = SERVICE_DISCOVERY_TIMEOUT;
        ServiceDiscoveryTask serviceDiscoveryTask = new ServiceDiscoveryTask();
        Timer timer = new Timer();
        // Submit the service discovery task and add it to the list
        timer.schedule(serviceDiscoveryTask, timeToWait);
        serviceDiscoveryTasks.add(serviceDiscoveryTask);
    }

    /**
     * Timed task to initiate a new services discovery. Will recursively submit
     * a new task as long as isDiscovering is true
     */
    private class ServiceDiscoveryTask extends TimerTask {
        public void run() {
            discoverServices();
            // Submit the next task if a stop call hasn't been made
            if (isDiscovering) {
                submitServiceDiscoveryTask();
            }
            // Remove this task from the list since it's complete
            serviceDiscoveryTasks.remove(this);
        }
    }

    public Map<String, DnsSdService> getDnsSdServiceMap() {
        return dnsSdServiceMap;
    }

    public Map<String, DnsSdTxtRecord> getDnsSdTxtRecordMap() {
        return dnsSdTxtRecordMap;
    }

    /**
     * Uses wifiManager to determine if Wi-Fi is enabled
     *
     * @return Whether Wi-Fi is enabled or not
     */
    public boolean isWifiEnabled() {
        if(wifiManager != null) {
            return wifiManager.isWifiEnabled();
        }
        return false;
    }

    /**
     * Removes a registered local service.
     */
    public void removeService() {
        if (wifiP2pServiceInfo != null) {
            Log.i(TAG, "Removing local service");
            wifiP2pManager.removeLocalService(channel, wifiP2pServiceInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    wifiP2pServiceInfo = null;
                    localServicePeerDiscoveryKickEnabled = false; // No need to discover peers on local service
                    Intent intent = new Intent(Action.SERVICE_REMOVED);
                    localBroadcastManager.sendBroadcast(intent);
                    Log.i(TAG, "Local service removed");
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure removing local service: " + FailureReason.fromInteger(reason).toString());
                }
            });
            wifiP2pServiceInfo = null;
        } else {
            Log.w(TAG, "No local service to remove");
        }
    }

    public void clearServiceDiscoveryRequests() {
        if (serviceRequest != null) {
            wifiP2pManager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    serviceRequest = null;
                    Log.i(TAG, "Service discovery requests cleared");
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure clearing service discovery requests: " + FailureReason.fromInteger(reason).toString());
                }
            });
        }
    }

    /**
     * Initiates a connection to a service
     *
     * @param service The service to connect to
     */
    public void initiateConnectToService(DnsSdService service) {
        // Device info of peer to connect to
        WifiP2pConfig wifiP2pConfig = new WifiP2pConfig();
        wifiP2pConfig.deviceAddress = service.getSrcDevice().deviceAddress;
        wifiP2pConfig.wps.setup = WpsInfo.PBC;

        if(wifiP2pManager != null) {
            // Starts a peer-to-peer connection with a device with the specified configuration
            wifiP2pManager.connect(channel, wifiP2pConfig, new WifiP2pManager.ActionListener() {
                // The ActionListener only notifies that initiation of connection has succeeded or failed

                @Override
                public void onSuccess() {
                    Log.i(TAG, "Initiating connection to service");
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failure initiating connection to service: " + FailureReason.fromInteger(reason).toString());
                }
            });
        }
    }

    /**
     * Creates a service that can be connected to without prompting. This is possible by creating an
     * access point and broadcasting the password for peers to use. Peers connect via normal wifi, not
     * wifi direct, but the effect is the same.
     */
    public void startAddingNoPromptService(WifiDirectServiceData serviceData) {
        if (wifiP2pServiceInfo != null) {
            removeService();
        }
        isCreatingNoPrompt = true;
        noPromptServiceData = serviceData;

        wifiP2pManager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "Group created successfully");
                //Note that you will have to wait for WIFI_P2P_CONNECTION_CHANGED_INTENT for group info
            }

            @Override
            public void onFailure(int reason) {
                Log.i(TAG, "Group creation failed: " + FailureReason.fromInteger(reason));

            }
        });
    }

    /**
     * Connects to a no prompt service
     *
     * @param service The service to connect to
     */
    public void connectToNoPromptService(DnsSdService service) {
        removeService();
        WifiConfiguration configuration = new WifiConfiguration();
        DnsSdTxtRecord txtRecord = dnsSdTxtRecordMap.get(service.getSrcDevice().deviceAddress);
        if (txtRecord == null) {
            Log.e(TAG, "No dnsSdTxtRecord found for the no prompt service");
            return;
        }
        // Quotes around these are required
        configuration.SSID = "\"" + txtRecord.getRecord().get(Keys.NO_PROMPT_NETWORK_NAME) + "\"";
        configuration.preSharedKey = "\"" + txtRecord.getRecord().get(Keys.NO_PROMPT_NETWORK_PASS) + "\"";
        int netId = wifiManager.addNetwork(configuration);

        //disconnect form current network and connect to this one
        wifiManager.disconnect();
        wifiManager.enableNetwork(netId, true);
        wifiManager.reconnect();
        Log.i(TAG, "Connected to no prompt network");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();

        if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            // The list of discovered peers has changed
            handlePeersChanged(intent);
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            // The state of Wi-Fi P2P connectivity has changed
            handleConnectionChanged(intent);
        } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            // Indicates whether Wi-Fi P2P is enabled
            handleP2pStateChanged(intent);
        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            // Indicates this device's configuration details have changed
            intent.putExtra("chimple_user", "USER_12345");
            handleThisDeviceChanged(intent);
        } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
            handleWifiStateChanged(intent);
        } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
            handleScanResultsAvailable(intent);
        } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
            NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            if (info != null) {
                if (info.isConnected()) {
                    Log.i(TAG, "ConectionStateConnected");
                    hadConnection = true;
                } else if (info.isConnectedOrConnecting()) {
                    Log.i(TAG, "ConectionStateConnecting");
                } else {
                    if (hadConnection) {
                        Log.i(TAG, "ConectionStateDisconnected");
                    } else {
                        Log.i(TAG, "ConectionStatePreConnecting");
                    }
                }


            }
        }
    }

    private void handleWifiStateChanged(Intent intent) {
        Log.i(TAG, "Wi-Fi state changed");
        int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
        if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
            // Register app with Wi-Fi P2P framework, register WifiDirectBroadcastReceiver
            Log.i(TAG, "Wi-Fi enabled");
            registerP2p();
            registerP2pReceiver();
        } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
            // Remove local service, unregister app with Wi-Fi P2P framework, unregister P2pReceiver
            Log.i(TAG, "Wi-Fi disabled");
            clearServiceDiscoveryRequests();
            if (wifiP2pServiceInfo != null) {
                removeService();
            }
            unregisterP2pReceiver();
            unregisterP2p();
        }
        localBroadcastManager.sendBroadcast(new Intent(Action.WIFI_STATE_CHANGED));
    }

    private void handleScanResultsAvailable(Intent intent) {
        Log.i(TAG, "Wi-Fi scan results available");
        wifiScanResults = wifiManager.getScanResults();
        Log.i(TAG, "There are " + (wifiScanResults.size() - 1) + " available networks");
        for (ScanResult wifiScanResult : wifiScanResults) {
            Log.i(TAG, wifiScanResult.SSID);
        }

        // Unregister the Wi-Fi receiver and register it again without the SCAN_RESULTS action
        unregisterWifiReceiver();
        wifiBroadcastReceiver = new WifiBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();

        // Indicates that Wi-Fi has been enabled, disabled, enabling, disabling, or unknown
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiBroadcastReceiver, intentFilter);
        Log.i(TAG, "Wi-Fi BroadcastReceiver registered");
    }

    /**
     * The list of discovered peers has changed
     * Available extras: EXTRA_P2P_DEVICE_LIST
     *
     * @param intent
     */
    private void handlePeersChanged(Intent intent) {
        if (wifiP2pManager != null) {
            synchronized (peerDiscoveryLock) {
                boolean peerDiscoveryRequired = isPeerDiscoveryRequired();
                if (peerDiscoveryRequired) {
                    Log.i(TAG, "List of discovered peers changed");
                    wifiP2pManager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                        @Override
                        public void onPeersAvailable(WifiP2pDeviceList peers) {
                            synchronized (wifiP2pDeviceLock) {
                                wifiP2pDeviceList = peers;
                            }
                            WifiDirectHandler.this.peers = peers;
                            WifiDirectHandler.this.allDevicesAroundMe();
                            Intent intent = new Intent(Action.PEERS_CHANGED);
                            intent.putExtra(PEERS, peers);
                            localBroadcastManager.sendBroadcast(intent);
                        }
                    });
                }

            }
        }
    }

    /**
     * The state of Wi-Fi P2P connectivity has changed
     * Here is where you can request group info
     * Available extras: EXTRA_WIFI_P2P_INFO, EXTRA_NETWORK_INFO, EXTRA_WIFI_P2P_GROUP
     *
     * @param intent
     */
    private void handleConnectionChanged(Intent intent) {
        Log.i(TAG, "Wi-Fi P2P Connection Changed");

        if (wifiP2pManager == null) {
            return;
        }

        // Extra information from EXTRA_NETWORK_INFO
        NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
        if (networkInfo.isConnected()) {
            Log.i(TAG, "Connected to P2P network. Requesting connection info");
            wifiP2pManager.requestConnectionInfo(channel, WifiDirectHandler.this);
        } else {
            Intent disconnected = new Intent(Action.COMMUNICATION_DISCONNECTED);
            localBroadcastManager.sendBroadcast(disconnected);
        }

        // Requests peer-to-peer group information
        wifiP2pManager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup wifiP2pGroup) {
                if (wifiP2pGroup != null) {
                    Log.i(TAG, "Group info available");
                    Log.i(TAG, "WifiP2pGroup:");
                    Log.i(TAG, p2pGroupToString(wifiP2pGroup));
                    WifiDirectHandler.this.wifiP2pGroup = wifiP2pGroup;
                }
            }
        });

    }

    /**
     * Indicates whether Wi-Fi P2P is enabled
     * Determine if Wi-Fi P2P mode is enabled or not, alert the Activity
     * Available extras: EXTRA_WIFI_STATE
     * Sticky Intent
     *
     * @param intent
     */
    private void handleP2pStateChanged(Intent intent) {
        Log.i(TAG, "Wi-Fi P2P State Changed:");
        int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
            // Wi-Fi Direct is enabled
            isWifiP2pEnabled = true;
            Log.i(TAG, "- Wi-Fi Direct is enabled");
        } else {
            // Wi-Fi Direct is not enabled
            isWifiP2pEnabled = false;
            Log.i(TAG, "- Wi-Fi Direct is not enabled");
        }
    }

    /**
     * Indicates this device's configuration details have changed
     * Sticky Intent
     *
     * @param intent
     */
    private void handleThisDeviceChanged(Intent intent) {
        Log.i(TAG, "This device changed");

        // Extra information from EXTRA_WIFI_P2P_DEVICE
        thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
        // Logs extra information from EXTRA_WIFI_P2P_DEVICE
        Bundle extras = intent.getExtras();
        String userId = null;
        if (extras != null) {
            userId = extras.getString("chimple_user");
        }
        if (userId != null) {
            Log.i(TAG, p2pDeviceToString(thisDevice) + "user: " + userId);
        } else {
            Log.i(TAG, p2pDeviceToString(thisDevice));
        }

        localBroadcastManager.sendBroadcast(new Intent(Action.DEVICE_CHANGED));
    }

    /**
     * Toggle wifi
     *
     * @param wifiEnabled whether or not wifi should be enabled
     */
    public void setWifiEnabled(boolean wifiEnabled) {
        wifiManager.setWifiEnabled(wifiEnabled);
    }

    public Handler getHandler() {
        return handler;
    }

    // TODO: Add JavaDoc
    @Override
    public boolean handleMessage(Message msg) {
        Log.i(TAG, "handleMessage() called");
        switch (msg.what) {
            case MESSAGE_READ:
                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer
                String receivedMessage = new String(readBuf, 0, msg.arg1);
                Log.i(TAG, "Received message: " + receivedMessage);
                Intent messageReceivedIntent = new Intent(Action.MESSAGE_RECEIVED);
                messageReceivedIntent.putExtra(MESSAGE_KEY, readBuf);
                localBroadcastManager.sendBroadcast(messageReceivedIntent);
                break;
            case MY_HANDLE:
                Object messageObject = msg.obj;
                socketManager = (SocketManager) messageObject;
                localBroadcastManager.sendBroadcast(new Intent(Action.SERVICE_CONNECTED));
                break;
            case COMMUNICATION_DISCONNECTED:
                Log.i(TAG, "Handling communication disconnect");
                localBroadcastManager.sendBroadcast(new Intent(Action.COMMUNICATION_DISCONNECTED));
                break;
        }
        return true;
    }

    public SocketManager getSocketManager() {
        return socketManager;
    }

    /**
     * Allows for binding to the service.
     */
    public class WifiTesterBinder extends Binder {
        public WifiDirectHandler getService() {
            return WifiDirectHandler.this;
        }
    }

    /**
     * Actions that can be broadcast or received by the handler
     */
    public class Action {
        public static final String DNS_SD_TXT_RECORD_AVAILABLE = "dnsSdTxtRecordAdded",
                DNS_SD_SERVICE_AVAILABLE = "dnsSdServiceAvailable",
                SERVICE_REMOVED = "serviceRemoved",
                PEERS_CHANGED = "peersChanged",
                SERVICE_CONNECTED = "serviceConnected",
                DEVICE_CHANGED = "deviceChanged",
                MESSAGE_RECEIVED = "messageReceived",
                WIFI_STATE_CHANGED = "wifiStateChanged",
                COMMUNICATION_DISCONNECTED = "communicationDisconnected";
    }

    private class Keys {
        public static final String NO_PROMPT_NETWORK_NAME = "networkName",
                NO_PROMPT_NETWORK_PASS = "passphrase";
    }

    // TODO: Add JavaDoc
    private class WifiDirectBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            onHandleIntent(intent);
        }
    }

    // TODO: Add JavaDoc
    private class WifiBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            onHandleIntent(intent);
        }
    }

    /**
     * Takes a WifiP2pDevice and returns a String of readable device information
     *
     * @param wifiP2pDevice
     * @return
     */
    public String p2pDeviceToString(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            String strDevice = "Device name: " + wifiP2pDevice.deviceName;
            strDevice += "\nDevice address: " + wifiP2pDevice.deviceAddress;
            if (wifiP2pDevice.equals(thisDevice)) {
                strDevice += "\nIs group owner: " + isGroupOwner();
            } else {
                strDevice += "\nIs group owner: false";
            }
            strDevice += "\nStatus: " + deviceStatusToString(wifiP2pDevice.status) + "\n";
            return strDevice;
        } else {
            Log.e(TAG, "WifiP2pDevice is null");
            return "";
        }
    }

    public String p2pInfoToString(WifiP2pInfo wifiP2pInfo) {
        if (wifiP2pInfo != null) {
            String strWifiP2pInfo = "Group formed: " + wifiP2pInfo.groupFormed;
            strWifiP2pInfo += "\nIs group owner: " + wifiP2pInfo.isGroupOwner;
            strWifiP2pInfo += "\nGroup owner address: " + wifiP2pInfo.groupOwnerAddress;
            return strWifiP2pInfo;
        } else {
            Log.e(TAG, "WifiP2pInfo is null");
            return "";
        }
    }

    public String p2pGroupToString(WifiP2pGroup wifiP2pGroup) {
        if (wifiP2pGroup != null) {
            String strWifiP2pGroup = "Network name: " + wifiP2pGroup.getNetworkName();
            strWifiP2pGroup += "\nIs group owner: " + wifiP2pGroup.isGroupOwner();
            if (wifiP2pGroup.getOwner() != null) {
                strWifiP2pGroup += "\nGroup owner: ";
                strWifiP2pGroup += "\n" + p2pDeviceToString(wifiP2pGroup.getOwner());
            }
            if (wifiP2pGroup.getClientList() != null && !wifiP2pGroup.getClientList().isEmpty()) {
                for (WifiP2pDevice client : wifiP2pGroup.getClientList()) {
                    strWifiP2pGroup += "\nClient: ";
                    strWifiP2pGroup += "\n" + p2pDeviceToString(client);
                }
            }
            return strWifiP2pGroup;
        } else {
            Log.e(TAG, "WifiP2pGroup is null");
            return "";
        }
    }

    /**
     * Translates a device status code to a readable String status
     *
     * @param status
     * @return A readable String device status
     */
    public String deviceStatusToString(int status) {
        if (status == WifiP2pDevice.AVAILABLE) {
            return "Available";
        } else if (status == WifiP2pDevice.INVITED) {
            return "Invited";
        } else if (status == WifiP2pDevice.CONNECTED) {
            return "Connected";
        } else if (status == WifiP2pDevice.FAILED) {
            return "Failed";
        } else if (status == WifiP2pDevice.UNAVAILABLE) {
            return "Unavailable";
        } else {
            return "Unknown";
        }
    }

    public String getThisDeviceInfo() {
        if (thisDevice == null) {
            return "No Device Info";
        } else {
            if (thisDevice.deviceName.equals("")) {
                thisDevice.deviceName = "Android Device";
            }
            return p2pDeviceToString(thisDevice);
        }
    }

    public boolean isGroupOwner() {
        return this.isGroupOwner;
    }

    public boolean isGroupFormed() {
        return this.groupFormed;
    }

    public boolean isDiscovering() {
        return this.isDiscovering;
    }

    public WifiP2pDevice getThisDevice() {
        return this.thisDevice;
    }

    public WifiP2pServiceInfo getWifiP2pServiceInfo() {
        return this.wifiP2pServiceInfo;
    }

    public List<ScanResult> getWifiScanResults() {
        return wifiScanResults;
    }


    //Discover Peers

    /**
     * Peer discovery runnable. This serves two purposes. It can be used to discover peers, if
     * required.
     * <p>
     * Secondly adding a local service requires periodic UDP broadcasts for other devices to see it.
     * Calling discoverPeers is apparently needed to force rebroadcasting the service so it can be
     * reliably discovered.
     * <p>
     * see: http://stackoverflow.com/questions/26300889/wifi-p2p-service-discovery-works-intermittently
     */
    private Runnable peerDiscoveryRunnable = new Runnable() {

        WifiP2pManager.ActionListener peerDiscoveryActionListener;

        @Override
        public void run() {
            synchronized (peerDiscoveryLock) {
                boolean peerDiscoveryRequired = isPeerDiscoveryRequired();
                postedPeerDiscoveryRunnable = null;

                if (wifiP2pManager != null && peerDiscoveryRequired) {
                    Log.d(TAG, "Service rebroadcast kick - requesting");
                    wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "Service rebroadcast kick - onSuccess");
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.i(TAG, "Service rebroadcast kick - onFailure: " +
                                    FailureReason.fromInteger(reason));
                        }
                    });
                } else {
                    Log.w(TAG, "discoverPeersRunnable: wifiP2pManager is null or discovery not required");
                }

                if (peerDiscoveryRequired) {
                    postedPeerDiscoveryRunnable = peerDiscoveryRunnable;
                    peerDiscoveryHandler.postDelayed(
                            peerDiscoveryRunnable, peerDiscoveryInterval);
                } else {
                    Log.i(TAG, "discoverPeersRunnable: peer discovery no longer required.");
                }
            }

        }
    };

    /**
     * Start discovering peers.
     *
     * @param delay
     */
    public void discoverPeers(int delay) {
        synchronized (peerDiscoveryLock) {
            if (this.postedPeerDiscoveryRunnable == null && isPeerDiscoveryRequired()) {
                postedPeerDiscoveryRunnable = peerDiscoveryRunnable;
                peerDiscoveryHandler.postDelayed(
                        peerDiscoveryRunnable, delay);
                Log.i(TAG, "discoverPeers: posted runnable for peer discovery");
            } else if (!isPeerDiscoveryRequired()) {
                Log.i(TAG, "discoverPeers: peer discovery not required");
            } else if (postedPeerDiscoveryRunnable != null) {
                Log.i(TAG, "discoverPeers: post peer discovery runnable not null, no need to post again");
            }
        }
    }

    public void discoverPeers() {
        discoverPeers(100);
    }

    private boolean isPeerDiscoveryRequired() {
        synchronized (peerDiscoveryLock) {
            return (wifiP2pServiceInfo != null && localServicePeerDiscoveryKickEnabled);
        }
    }

    public void stopPeerDiscovery() {
        synchronized (peerDiscoveryLock) {
            Log.i(TAG, "stopPeerDiscovery");
//            isDiscoveringPeers = false;
            localServicePeerDiscoveryKickEnabled = false;
        }
    }

    public void allDevicesAroundMe() {
        synchronized (wifiP2pDeviceLock) {
            if (wifiP2pDeviceList != null) {
                for (WifiP2pDevice device : wifiP2pDeviceList.getDeviceList()) {
                    if (device.deviceAddress != null)
                        Log.i(TAG, "devices around me found with address: " + device.deviceAddress);
                    Log.i(TAG, "devices around me found with name: " + device.deviceName);
                }
            }
        }
    }

    public WifiP2pDevice getPeerByDeviceAddress(String deviceAddress) {
        synchronized (wifiP2pDeviceLock) {
            if (wifiP2pDeviceList != null) {
                if (Build.VERSION.SDK_INT >= 18) {
                    return wifiP2pDeviceList.get(deviceAddress);
                } else {
                    for (WifiP2pDevice device : wifiP2pDeviceList.getDeviceList()) {
                        if (device.deviceAddress != null && device.deviceAddress.equals(deviceAddress))
                            return device;
                    }

                    return null;
                }
            } else {
                return null;
            }
        }
    }

    public WifiP2pDeviceList getWifiP2pDeviceList() {
        return wifiP2pDeviceList;
    }

    /**
     * When a local service is added it should send out UDP broadcasts for anyone listening, and
     * thereafter it should respond automatically to service discovery requests sent out by peers.
     * Sometimes responding to service discovery requests from peers is flaky, leading to some online
     * to recommend regularly calling discoverPeers to force out a new UDP broadcast.
     * <p>
     * Unfortunately when this is done it appears to break being able to simultaneously both discover
     * service and having a local service (e.g. a true network of peers where peers can both discover
     * and be discovered).
     * <p>
     * If this node is only to be used to be discovered by others, and does not need to disover other
     * nodes it might be worth setting this to true.
     *
     * @param localServiceRequiresPeerDiscoveryKick true for enabled, false for disabled.
     */
    public void setLocalServicePeerDiscoveryKickEnabled(boolean localServiceRequiresPeerDiscoveryKick) {
        this.localServicePeerDiscoveryKickEnabled = localServiceRequiresPeerDiscoveryKick;
    }

    /**
     * Determine if discover peer kick is enabled or disabled
     *
     * @return True if enabled, false otherwise.
     * @see #setLocalServicePeerDiscoveryKickEnabled(boolean)
     */
    public boolean isLocalServicePeerDiscoveryKickEnabled() {
        return localServicePeerDiscoveryKickEnabled;
    }

//    public void continuouslyDiscoverPeers() {
//        if (!isDiscoveringPeers) {
//            Log.i(TAG, "Continuously discover peers: starting");
//            isDiscoveringPeers = true;
//            discoverPeers();
//        } else {
//            Log.i(TAG, "Continuously discover peers, already discovering.");
//        }
//    }
}
