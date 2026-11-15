package com.phantom.checker.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,       // 來源 App（LINE / IG / 簡訊…）
    val appLabel: String,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val riskScore: Int = 0,       // AI 分析結果 0-100
    val riskLabel: String = "",   // safe / suspicious / scam
    val riskReason: String = "",
    val isFiltered: Boolean = false, // 命中關鍵字過濾
    val isRead: Boolean = false,   // 幻訊內的已讀標記（不影響原 App）
    val dedupKey: String? = null,  // 通知去重雜湊鍵
    val isStarred: Boolean = false // 星標
)

@Entity(tableName = "filter_rules")
data class FilterRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val isActive: Boolean = true,
    val packageName: String? = null, // null = 全部 App 生效
    val action: String = ACTION_FILTER // filter / drop / scam
) {
    companion object {
        const val ACTION_FILTER = "filter"
        const val ACTION_DROP = "drop"
        const val ACTION_SCAM = "scam"
    }
}

@Entity(
    tableName = "ai_summaries",
    indices = [Index(value = ["date"], unique = true)]
)
data class AiSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,               // yyyyMMdd 格式（例如 20261115）
    val summary: String,
    val generatedAt: Long
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "message_tags",
    primaryKeys = ["messageId", "tagId"],
    indices = [Index(value = ["tagId"])]
)
data class MessageTagCrossRef(
    val messageId: Long,
    val tagId: Long
)

data class AppGroupRow(
    val packageName: String,
    val appLabel: String,
    val cnt: Int,
    val lastTs: Long
)

data class SenderGroupRow(
    val sender: String,
    val cnt: Int,
    val unreadCnt: Int,
    val lastTs: Long
)

data class AppCountRow(
    val packageName: String,
    val cnt: Int
)

data class AppRiskRow(
    val packageName: String,
    val appLabel: String,
    val cnt: Int
)

data class MessageStatsRow(
    val total: Int,
    val today: Int,
    val unread: Int,
    val scam: Int
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun paged(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE isRead = 0 ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun unreadPaged(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE isStarred = 1 ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun starredPaged(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE riskLabel = 'scam' ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun scamsPaged(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE isFiltered = 1 ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun filteredPaged(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT m.* FROM messages m INNER JOIN message_tags mt ON m.id = mt.messageId WHERE mt.tagId = :tagId ORDER BY m.timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun byTagPaged(tagId: Long, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 200")
    suspend fun recent(): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    suspend fun all(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE (:packageName IS NULL OR packageName = :packageName) AND (:sinceTs IS NULL OR timestamp >= :sinceTs) ORDER BY timestamp DESC")
    suspend fun exportQuery(packageName: String?, sinceTs: Long?): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :q || '%' OR sender LIKE '%' || :q || '%' ORDER BY timestamp DESC LIMIT 200")
    suspend fun search(q: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE riskLabel = 'scam' ORDER BY timestamp DESC")
    suspend fun scams(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE isFiltered = 1 ORDER BY timestamp DESC")
    suspend fun filtered(): List<MessageEntity>

    @Query(
        "SELECT COUNT(*) as total, " +
            "COALESCE(SUM(CASE WHEN timestamp >= :startOfDay THEN 1 ELSE 0 END), 0) as today, " +
            "COALESCE(SUM(CASE WHEN isRead = 0 THEN 1 ELSE 0 END), 0) as unread, " +
            "COALESCE(SUM(CASE WHEN riskLabel = 'scam' AND timestamp >= :startOfDay THEN 1 ELSE 0 END), 0) as scam " +
            "FROM messages"
    )
    suspend fun statsOverview(startOfDay: Long): MessageStatsRow

    @Query("SELECT COUNT(*) FROM messages WHERE timestamp >= :startOfDay")
    suspend fun todayCount(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0")
    suspend fun unreadCount(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE riskLabel = 'scam' AND timestamp >= :startOfDay")
    suspend fun todayScamCount(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE riskLabel = 'scam' AND timestamp >= :sinceTs")
    suspend fun scamCountSince(sinceTs: Long): Int

    @Query("UPDATE messages SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllRead()

    @Query("UPDATE messages SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("SELECT packageName, appLabel, COUNT(*) as cnt, MAX(timestamp) as lastTs FROM messages GROUP BY packageName ORDER BY lastTs DESC")
    suspend fun groupByApp(): List<AppGroupRow>

    @Query("SELECT packageName, COUNT(*) as cnt FROM messages WHERE timestamp >= :startOfDay GROUP BY packageName")
    suspend fun todayCountsByApp(startOfDay: Long): List<AppCountRow>

    @Query("SELECT packageName, COUNT(*) as cnt FROM messages WHERE isRead = 0 AND timestamp >= :startOfDay GROUP BY packageName")
    suspend fun todayUnreadCountsByApp(startOfDay: Long): List<AppCountRow>

    @Query("SELECT * FROM messages WHERE packageName = :packageName ORDER BY timestamp DESC")
    suspend fun byApp(packageName: String): List<MessageEntity>

    @Query("SELECT sender, COUNT(*) as cnt, SUM(CASE WHEN isRead = 0 THEN 1 ELSE 0 END) as unreadCnt, MAX(timestamp) as lastTs FROM messages WHERE packageName = :packageName GROUP BY sender ORDER BY lastTs DESC")
    suspend fun groupBySender(packageName: String): List<SenderGroupRow>

    @Query("SELECT * FROM messages WHERE packageName = :packageName AND sender = :sender ORDER BY timestamp ASC")
    suspend fun byConversation(packageName: String, sender: String): List<MessageEntity>

    @Query("UPDATE messages SET isRead = 1 WHERE packageName = :packageName AND sender = :sender AND isRead = 0")
    suspend fun markConversationRead(packageName: String, sender: String)

    @Query("SELECT * FROM messages WHERE timestamp >= :sinceTs ORDER BY timestamp DESC")
    suspend fun since(sinceTs: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE riskLabel = :label")
    suspend fun countByRiskLabel(label: String): Int

    @Query("SELECT packageName, appLabel, COUNT(*) as cnt FROM messages WHERE riskLabel IN ('scam', 'suspicious') GROUP BY packageName ORDER BY cnt DESC LIMIT 5")
    suspend fun topRiskyApps(): List<AppRiskRow>

    @Insert
    suspend fun insert(msg: MessageEntity): Long

    @Update
    suspend fun update(msg: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE packageName = :packageName AND sender = :sender")
    suspend fun deleteByConversation(packageName: String, sender: String)

    @Query("DELETE FROM messages WHERE packageName = :packageName")
    suspend fun deleteByApp(packageName: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun deleteOlderThanCount(keepCount: Int)
}

@Dao
interface FilterDao {
    @Query("SELECT * FROM filter_rules WHERE isActive = 1")
    suspend fun active(): List<FilterRule>

    @Query("SELECT * FROM filter_rules")
    suspend fun all(): List<FilterRule>

    @Insert
    suspend fun insert(rule: FilterRule)

    @Update
    suspend fun update(rule: FilterRule)

    @Delete
    suspend fun delete(rule: FilterRule)
}

@Dao
interface AiSummaryDao {
    @Query("SELECT * FROM ai_summaries ORDER BY date DESC LIMIT 1")
    suspend fun latest(): AiSummaryEntity?

    @Query("SELECT * FROM ai_summaries WHERE date = :date LIMIT 1")
    suspend fun byDate(date: Long): AiSummaryEntity?

    @Query("SELECT * FROM ai_summaries WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    suspend fun byDateRange(startDate: Long, endDate: Long): List<AiSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(summary: AiSummaryEntity)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY sortOrder ASC, id ASC")
    suspend fun all(): List<TagEntity>

    @Insert
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteById(tagId: Long)

    @Query("DELETE FROM message_tags WHERE tagId = :tagId")
    suspend fun deleteCrossRefsByTag(tagId: Long)

    @Query("DELETE FROM message_tags WHERE messageId = :messageId")
    suspend fun deleteCrossRefsByMessage(messageId: Long)

    @Query("DELETE FROM message_tags")
    suspend fun deleteAllCrossRefs()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToMessage(ref: MessageTagCrossRef)

    @Query("DELETE FROM message_tags WHERE messageId = :messageId AND tagId = :tagId")
    suspend fun removeTagFromMessage(messageId: Long, tagId: Long)

    @Query("SELECT t.* FROM tags t INNER JOIN message_tags mt ON t.id = mt.tagId WHERE mt.messageId = :messageId ORDER BY t.sortOrder ASC, t.id ASC")
    suspend fun tagsForMessage(messageId: Long): List<TagEntity>

    @Query("SELECT * FROM message_tags")
    suspend fun allCrossRefs(): List<MessageTagCrossRef>
}

@Database(
    entities = [
        MessageEntity::class,
        FilterRule::class,
        AiSummaryEntity::class,
        TagEntity::class,
        MessageTagCrossRef::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun filterDao(): FilterDao
    abstract fun aiSummaryDao(): AiSummaryDao
    abstract fun tagDao(): TagDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filter_rules ADD COLUMN packageName TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN dedupKey TEXT DEFAULT NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_summaries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `summary` TEXT NOT NULL,
                        `generatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_summaries_date` ON `ai_summaries` (`date`)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isStarred INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_packageName` ON `messages` (`packageName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_timestamp` ON `messages` (`timestamp`)")
                db.execSQL("ALTER TABLE filter_rules ADD COLUMN action TEXT NOT NULL DEFAULT 'filter'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tags` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `colorHex` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_tags` (
                        `messageId` INTEGER NOT NULL,
                        `tagId` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`, `tagId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_tags_tagId` ON `message_tags` (`tagId`)")
            }
        }
    }
}
