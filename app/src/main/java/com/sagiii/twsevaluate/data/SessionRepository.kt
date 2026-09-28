package com.sagiii.twsevaluate.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * セッションのメタデータ(sessions.json)と、セッションごとのメディアファイル
 * (filesDir/sessions/<id>/配下)を管理する。DBを使うほどの規模ではないため
 * JSONファイル1本で完結させている。
 */
class SessionRepository(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val indexFile: File
        get() = File(context.filesDir, "sessions.json")

    fun sessionDir(sessionId: String): File =
        File(context.filesDir, "sessions/$sessionId").apply { mkdirs() }

    fun newSessionId(): String = UUID.randomUUID().toString()

    suspend fun loadAll(): List<Session> = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<Session>>(indexFile.readText())
        }.getOrDefault(emptyList()).sortedByDescending { it.createdAtEpochMs }
    }

    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        val current = runCatching {
            json.decodeFromString<List<Session>>(indexFile.readText())
        }.getOrDefault(emptyList())
        val updated = current.filterNot { it.id == session.id } + session
        indexFile.writeText(json.encodeToString(updated))
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        val current = runCatching {
            json.decodeFromString<List<Session>>(indexFile.readText())
        }.getOrDefault(emptyList())
        indexFile.writeText(json.encodeToString(current.filterNot { it.id == sessionId }))
        sessionDir(sessionId).deleteRecursively()
    }
}
