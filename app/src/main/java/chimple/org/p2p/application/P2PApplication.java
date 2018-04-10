package chimple.org.p2p.application;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import chimple.org.p2p.db.AppDatabase;

public class P2PApplication extends Application {
    private static final String TAG = P2PApplication.class.getName();
    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        initialize();
        context = this;
    }

    private void initialize() {
        Log.d(TAG, "Initializing...");

        Thread initializationThread = new Thread() {
            @Override
            public void run() {
                // Initialize all of the important frameworks and objects
                P2PContext.getInstance().initialize(P2PApplication.this);
                //TODO: for now force the creation here
                AppDatabase.getInstance(P2PApplication.this);

                initializationComplete();
            }
        };

        initializationThread.start();
    }

    private void initializationComplete() {
        Log.i(TAG, "Initialization complete...");
    }

    public static Context getContext() {
        return context;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }
}