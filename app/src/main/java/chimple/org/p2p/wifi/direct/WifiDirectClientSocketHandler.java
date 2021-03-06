package chimple.org.p2p.wifi.direct;

import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class WifiDirectClientSocketHandler extends Thread {

    private static final int SOCKET_TIMEOUT = 5000;
    private static final String TAG = WifiDirectHandler.TAG + "WifiDirectClientSocketHandler";
    private Handler handler;
    private InetAddress inetAddress;

    public WifiDirectClientSocketHandler(Handler handler, InetAddress groupOwnerAddress) {
        this.handler = handler;
        this.inetAddress = groupOwnerAddress;
    }

    // TODO: Add JavaDoc
    @Override
    public void run() {
        Log.i(TAG, "Client socket thread running");
        Socket socket = new Socket();
        try {
            Log.i(TAG, "Opening client socket");
            socket.bind(null);
            socket.connect(new InetSocketAddress(inetAddress.getHostAddress(),
                    WifiDirectHandler.SERVER_PORT), SOCKET_TIMEOUT);
            Log.i(TAG, "Client socket - " + socket.isConnected());

            Log.i(TAG, "Launching the I/O handler");
            SocketManager socketManager = new SocketManager(socket, handler);
            new Thread(socketManager).start();
        } catch (IOException e) {
            Log.e(TAG, "Error launching I/O handler");
            Log.e(TAG, e.getMessage());
            try {
                socket.close();
                Log.i(TAG, "Client socket closed");
            } catch (IOException e1) {
                Log.e(TAG, "Error closing client socket");
                e1.printStackTrace();
            }
        }
    }
}

