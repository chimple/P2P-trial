package chimple.org.p2p.db.dao;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.OnConflictStrategy;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import chimple.org.p2p.db.entity.P2PSyncInfo;

@Dao
public interface P2PSyncInfoDao
{
    @Query("SELECT * FROM P2PSyncInfo WHERE userId=:userId AND deviceId=:deviceId")
    public P2PSyncInfo[] getSyncInformationByUserIdAndDeviceId(Long userId, Long deviceId);

    @Query("SELECT * FROM P2PSyncInfo WHERE userId=:userId")
    public P2PSyncInfo[] getSyncInformationByUserId(Long userId);

    @Query("SELECT userId, deviceId,  MAX(sequence) FROM P2PSyncInfo GROUP BY userId, deviceId")
    public P2PSyncInfo[] getLatestMessageAvailableByUserIdAndDeviceId();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public Long insertP2PSyncInfo(P2PSyncInfo info);

    @Update
    public void updateP2PSyncInfo(P2PSyncInfo updateP2PSyncInfo);

}
