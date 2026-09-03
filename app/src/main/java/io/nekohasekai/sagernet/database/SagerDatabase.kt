package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import java.util.concurrent.Executors

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8)
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
abstract class SagerDatabase : RoomDatabase() {

    companion object {

        // proxy_groups gains isSelector/frontProxy/landingProxy (NOT NULL, no SQL default):
        // recreate the table; proxy_entities only gains the nullable shadowTLSBean
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `proxy_groups_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userOrder` INTEGER NOT NULL,
                        `ungrouped` INTEGER NOT NULL,
                        `name` TEXT,
                        `type` INTEGER NOT NULL,
                        `subscription` BLOB,
                        `order` INTEGER NOT NULL,
                        `isSelector` INTEGER NOT NULL,
                        `frontProxy` INTEGER NOT NULL,
                        `landingProxy` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `proxy_groups_new` (
                        `id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`,
                        `isSelector`, `frontProxy`, `landingProxy`
                    ) SELECT `id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`,
                        0, -1, -1 FROM `proxy_groups`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `proxy_groups`")
                db.execSQL("ALTER TABLE `proxy_groups_new` RENAME TO `proxy_groups`")
                db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `shadowTLSBean` BLOB")
            }
        }

        // proxy_entities gains the nullable mieruBean
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `mieruBean` BLOB")
            }
        }

        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                // No destructive fallback: it silently drops every profile, group and
                // rule whenever a migration is missing — which a downgrade to an older
                // APK always is. Failing to open keeps the data recoverable.
                // single thread keeps the submitted runnables in order, off the main thread
                .setQueryExecutor(Executors.newSingleThreadExecutor())
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
