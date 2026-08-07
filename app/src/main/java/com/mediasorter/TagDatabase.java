package com.mediasorter;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Update;
import androidx.room.Delete;
import androidx.room.Query;
import androidx.room.OnConflictStrategy;
import com.mediasorter.models.Tag;
import java.util.List;

@Database(entities = {Tag.class}, version = 1, exportSchema = false)
public abstract class TagDatabase extends RoomDatabase {

    private static TagDatabase instance;

    public abstract TagDao tagDao();

    public static synchronized TagDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(
                context.getApplicationContext(),
                TagDatabase.class,
                "tag_database"
            )
            .fallbackToDestructiveMigration()
            .build();
        }
        return instance;
    }

    @Dao
    public interface TagDao {

        @Insert(onConflict = OnConflictStrategy.IGNORE)
        void insert(Tag tag);

        @Update
        void update(Tag tag);

        @Delete
        void delete(Tag tag);

        @Query("SELECT * FROM tags ORDER BY usageCount DESC")
        List<Tag> getAllByUsage();

        @Query("SELECT * FROM tags ORDER BY name ASC")
        List<Tag> getAllAlphabetical();

        @Query("SELECT * FROM tags WHERE name LIKE :query")
        List<Tag> search(String query);

        @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
        Tag getByName(String name);

        @Query("DELETE FROM tags")
        void deleteAll();
    }
}
