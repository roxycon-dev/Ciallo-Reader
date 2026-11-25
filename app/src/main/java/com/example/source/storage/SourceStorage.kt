package com.example.source.storage

import com.example.source.SourceOrdering

interface SourceStorage {
    suspend fun getSourceOrdering(): SourceOrdering = SourceOrdering()
    suspend fun saveSourceOrdering(ordering: SourceOrdering) {}

    suspend fun saveSourceState(sourceId: String, enabled: Boolean)
    suspend fun getSourceStates(): Map<String, Boolean>
    suspend fun saveActiveSourceId(sourceId: String)
    suspend fun getActiveSourceId(): String?
    suspend fun saveCustomSourceJson(sourceId: String, jsonContent: String)
    suspend fun getCustomSourceJsons(): Map<String, String>
    suspend fun removeCustomSourceJson(sourceId: String)
}
