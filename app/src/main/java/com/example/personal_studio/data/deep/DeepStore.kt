package com.example.personal_studio.data.deep

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 主库和深度库分别提交，用草稿及消息边界识别一次被进程退出打断的 appendMessage。 */
@Serializable
internal data class Draft(
    val text: String,
    val paths: List<String>,
    val afterMessage: Long,
    val userId: Long? = null,
    /** 这条消息选的模型和推理等级；重发同一条时沿用，不跟着后来的选择变。 */
    val model: String? = null,
    val effort: String? = null,
)

internal enum class Phase { IDLE, PENDING, POSTING, RUNNING, FINISHING, FINISHED, EXPIRED, DELETING }

@Entity(tableName = "deep_sessions", indices = [Index(value = ["remoteId"], unique = true)])
internal data class DeepSession(
    @PrimaryKey val chatId: Long,
    val remoteId: String? = null,
    val lastSeq: Long = 0,
    val phase: Phase = Phase.IDLE,
    val draft: Draft? = null,
    val round: Round? = null,
    val activity: String? = null,
)

@Entity(
    tableName = "deep_steps",
    primaryKeys = ["chatId", "answerId", "position"],
    foreignKeys = [ForeignKey(
        entity = DeepSession::class, parentColumns = ["chatId"], childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class StoredStep(
    val chatId: Long,
    val answerId: Long,
    val position: Int,
    @Embedded val step: DeepStep,
)

internal data class SessionBadge(val chatId: Long, val phase: Phase, val activity: String?)

@Dao
internal interface DeepDao {
    @Upsert suspend fun saveHeader(row: DeepSession)
    @Query("SELECT * FROM deep_sessions WHERE chatId = :id") suspend fun header(id: Long): DeepSession?
    @Query("SELECT chatId FROM deep_sessions") suspend fun ids(): List<Long>
    @Query("SELECT chatId, phase, activity FROM deep_sessions") suspend fun badges(): List<SessionBadge>
    @Query("SELECT * FROM deep_steps WHERE chatId = :id AND answerId > 0 ORDER BY answerId, position")
    fun steps(id: Long): Flow<List<StoredStep>>
    @Query("SELECT * FROM deep_steps WHERE chatId = :id AND answerId = :answer ORDER BY position")
    suspend fun roundSteps(id: Long, answer: Long): List<StoredStep>
    @Upsert suspend fun saveSteps(steps: List<StoredStep>)
    @Query("DELETE FROM deep_steps WHERE chatId = :id AND answerId = 0 AND position >= :size")
    suspend fun trimDraftSteps(id: Long, size: Int)
    @Query("UPDATE deep_steps SET answerId = :answer WHERE chatId = :id AND answerId = 0")
    suspend fun linkSteps(id: Long, answer: Long)
    @Query("DELETE FROM deep_sessions WHERE chatId = :id") suspend fun remove(id: Long)

    // Room 自增消息 ID 从 1 开始。0 留给本轮草稿，工具输出各占一行，避免快照超出 CursorWindow。
    @Transaction
    suspend fun get(id: Long): DeepSession? {
        val row = header(id) ?: return null
        val round = row.round ?: return row
        val steps = roundSteps(id, round.savedAnswerId ?: 0).map { it.step }
        return row.copy(round = round.copy(steps = steps))
    }

    @Transaction
    suspend fun save(row: DeepSession, previous: List<DeepStep>? = null) {
        saveHeader(row)
        if (row.round?.savedAnswerId != null) return
        val steps = row.round?.steps.orEmpty()
        if (previous == null || previous.size > steps.size) trimDraftSteps(row.chatId, steps.size)
        val changes = steps.mapIndexedNotNull { index, step ->
            if (previous?.getOrNull(index) == step) null else StoredStep(row.chatId, 0, index, step)
        }
        if (changes.isNotEmpty()) saveSteps(changes)
    }

    @Transaction
    suspend fun finish(row: DeepSession) {
        row.round?.savedAnswerId?.let { linkSteps(row.chatId, it) }
        saveHeader(row)
    }
}

internal class DeepConverters {
    @TypeConverter fun draft(value: String?): Draft? = value?.let { Json.decodeFromString(it) }
    @TypeConverter fun draftJson(value: Draft?): String? = value?.let { Json.encodeToString(it) }
    @TypeConverter fun round(value: String?): Round? = value?.let { Json.decodeFromString(it) }
    @TypeConverter fun roundJson(value: Round?): String? = value?.let { Json.encodeToString(it) }
}

@Database(entities = [DeepSession::class, StoredStep::class], version = 1, exportSchema = true)
@TypeConverters(DeepConverters::class)
internal abstract class DeepDatabase : RoomDatabase() {
    abstract fun deepDao(): DeepDao
}
