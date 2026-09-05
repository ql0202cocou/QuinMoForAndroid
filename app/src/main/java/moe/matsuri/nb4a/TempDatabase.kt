package moe.matsuri.nb4a

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import java.util.concurrent.Executors

@Database(entities = [KeyValuePair::class], version = 1)
abstract class TempDatabase : RoomDatabase() {

    companion object {
        private val instance by lazy {
            Room.inMemoryDatabaseBuilder(SagerNet.application, TempDatabase::class.java)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration(true)
                // single thread keeps the submitted runnables in order, off the main thread
                .setQueryExecutor(Executors.newSingleThreadExecutor())
                .build()
        }

        val profileCacheDao get() = instance.profileCacheDao()

    }

    abstract fun profileCacheDao(): KeyValuePair.Dao
}