package io.nekohasekai.sagernet.database.preference

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import java.util.concurrent.Executors

@Database(entities = [KeyValuePair::class], version = 1)
abstract class PublicDatabase : RoomDatabase() {
    companion object {
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, PublicDatabase::class.java, Key.DB_PUBLIC)
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                // see SagerDatabase: a destructive fallback would wipe every setting
                // on a downgrade instead of failing loudly
                // single thread keeps the submitted runnables in order, off the main thread
                .setQueryExecutor(Executors.newSingleThreadExecutor())
                .build()
        }

        val kvPairDao get() = instance.keyValuePairDao()
    }

    abstract fun keyValuePairDao(): KeyValuePair.Dao

}
