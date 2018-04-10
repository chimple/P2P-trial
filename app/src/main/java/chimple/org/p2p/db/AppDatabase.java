package chimple.org.p2p.db;


import android.content.Context;
import android.arch.persistence.room.Database;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.arch.persistence.room.TypeConverters;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.support.annotation.VisibleForTesting;
import android.util.Log;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;

import chimple.org.p2p.db.converter.DateConverter;
import chimple.org.p2p.db.dao.P2PSyncInfoDao;
import chimple.org.p2p.db.entity.P2PSyncInfo;


@Database(entities = {P2PSyncInfo.class},
        version = 1
)
@TypeConverters(DateConverter.class)
public abstract class AppDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "p2p_db";

    /**
     * The only instance
     */
    private static AppDatabase sInstance;

    public abstract P2PSyncInfoDao p2pSyncDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (sInstance == null) {
            sInstance = Room
                    .databaseBuilder(context.getApplicationContext(), AppDatabase.class, DATABASE_NAME)
                    .build();
            DatabaseInitializer.populateAsync(sInstance, context);
        }
        return sInstance;
    }

    /**
     * Switches the internal implementation with an empty in-memory database.
     *
     * @param context The context.
     */
    @VisibleForTesting
    public static void switchToInMemory(Context context) {
        sInstance = Room.inMemoryDatabaseBuilder(context.getApplicationContext(),
                AppDatabase.class).build();
    }

    public static void destroyInstance() {
        sInstance = null;
    }
}
