package chimple.org.p2p.db;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import chimple.org.p2p.db.entity.P2PSyncInfo;

public class DatabaseInitializer {

    private static final String TAG = DatabaseInitializer.class.getName();

    public static void populateAsync(@NonNull final AppDatabase db, @NonNull final Context context) {
        PopulateDbAsync task = new PopulateDbAsync(db, context);
        task.execute();
    }

    private static void populateWithTestData(AppDatabase db, Context context) {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream = null;
        try {
            inputStream = assetManager.open("database.csv");
        } catch (IOException e) {
            e.printStackTrace();
        }
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

        String line = "";
        db.beginTransaction();
        try {
            while ((line = bufferedReader.readLine()) != null) {
                String[] columns = line.split(",");

                if (columns.length < 1) {
                    Log.d("AppDatabase", "Skipping bad row");
                }

                String userId = columns[0];
                String deviceId = columns[1];
                Long sequence = Long.parseLong(columns[2]);
                String senderUserId = columns[3];
                String recepientUserId = columns[4];
                String message = columns[5];
                String messageType = columns[6];

                P2PSyncInfo info = new P2PSyncInfo(userId, deviceId, sequence, senderUserId, recepientUserId, message, messageType, null);
                db.p2pSyncDao().insertP2PSyncInfo(info);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }

    private static class PopulateDbAsync extends AsyncTask<Void, Void, Void> {

        private final AppDatabase mDb;
        private Context context;

        PopulateDbAsync(AppDatabase db, Context context) {
            mDb = db;
            this.context = context;
        }

        @Override
        protected Void doInBackground(final Void... params) {
            populateWithTestData(mDb, this.context);
            return null;
        }

    }
}