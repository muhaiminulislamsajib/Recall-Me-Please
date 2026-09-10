package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.Memory
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

class MemoryRepository(private val context: Context) {
    private val TAG = "MemoryRepository"

    private var firestore: FirebaseFirestore? = null

    private val prefs = context.getSharedPreferences("recall_me_memories_prefs", Context.MODE_PRIVATE)

    // In-memory cache & fallback when Firebase is offline or not configured
    private val _localMemories = MutableStateFlow<List<Memory>>(emptyList())
    val localMemories = _localMemories.asStateFlow()

    init {
        loadPersistedMemories()
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                firestore = FirebaseFirestore.getInstance()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Firestore not available: ${e.message}")
        }
    }

    private fun loadPersistedMemories() {
        try {
            val rawJson = prefs.getString("saved_memories_json", null) ?: return
            val array = org.json.JSONArray(rawJson)
            val list = mutableListOf<Memory>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val map = mutableMapOf<String, Any>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.get(k)
                    if (v is org.json.JSONArray) {
                        val subList = mutableListOf<String>()
                        for (j in 0 until v.length()) {
                            subList.add(v.getString(j))
                        }
                        map[k] = subList
                    } else {
                        map[k] = v
                    }
                }
                val memoryId = obj.optString("memoryId", "")
                if (memoryId.isNotBlank()) {
                    list.add(Memory.fromMap(memoryId, map))
                }
            }
            _localMemories.value = list
        } catch (e: Exception) {
            Log.w(TAG, "Error loading persisted memories: ${e.message}")
        }
    }

    private fun persistMemories(list: List<Memory>) {
        try {
            val array = org.json.JSONArray()
            for (m in list) {
                val obj = org.json.JSONObject()
                val map = m.toMap()
                for ((k, v) in map) {
                    if (v is List<*>) {
                        val arr = org.json.JSONArray()
                        v.forEach { arr.put(it.toString()) }
                        obj.put(k, arr)
                    } else {
                        obj.put(k, v)
                    }
                }
                obj.put("memoryId", m.memoryId)
                array.put(obj)
            }
            prefs.edit().putString("saved_memories_json", array.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error persisting memories: ${e.message}")
        }
    }

    val isFirestoreAvailable: Boolean
        get() = firestore != null

    fun getMemoriesFlow(userId: String): Flow<List<Memory>> = callbackFlow {
        val db = firestore
        if (db == null || userId.startsWith("demo_")) {
            // Local fallback flow
            val subscription = localMemories.collect { list ->
                val userList = list.filter { it.userId == userId }.sortedByDescending { it.timestamp }
                trySend(userList)
            }
            awaitClose { }
            return@callbackFlow
        }

        val collectionRef = db.collection("users").document(userId).collection("memories")
        var registration: ListenerRegistration? = null

        try {
            registration = collectionRef
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Firestore listen failed: ${error.message}")
                        // Fall back to local list on error
                        val fallback = _localMemories.value.filter { it.userId == userId }
                        trySend(fallback)
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val memories = snapshot.documents.mapNotNull { doc ->
                            doc.data?.let { Memory.fromMap(doc.id, it) }
                        }
                        // Update local cache too
                        _localMemories.value = memories
                        trySend(memories)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching snapshot listener: ${e.message}")
            trySend(_localMemories.value.filter { it.userId == userId })
        }

        awaitClose {
            registration?.remove()
        }
    }

    suspend fun saveMemory(memory: Memory): Result<Memory> {
        val memoryId = if (memory.memoryId.isNotBlank()) memory.memoryId else UUID.randomUUID().toString()
        val finalMemory = memory.copy(memoryId = memoryId)

        // Always update local memory cache immediately
        val currentList = _localMemories.value.toMutableList()
        currentList.removeAll { it.memoryId == memoryId }
        currentList.add(0, finalMemory)
        _localMemories.value = currentList
        persistMemories(currentList)

        val db = firestore
        if (db != null && !memory.userId.startsWith("demo_") && memory.userId.isNotBlank()) {
            return try {
                db.collection("users")
                    .document(memory.userId)
                    .collection("memories")
                    .document(memoryId)
                    .set(finalMemory.toMap())
                    .await()
                Result.success(finalMemory)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save memory to Firestore: ${e.message}", e)
                // Successfully saved to local cache even if cloud write fails temporarily
                Result.success(finalMemory)
            }
        }

        return Result.success(finalMemory)
    }

    suspend fun deleteMemory(userId: String, memoryId: String): Result<Unit> {
        val currentList = _localMemories.value.toMutableList()
        currentList.removeAll { it.memoryId == memoryId }
        _localMemories.value = currentList
        persistMemories(currentList)

        val db = firestore
        if (db != null && !userId.startsWith("demo_") && userId.isNotBlank()) {
            return try {
                db.collection("users")
                    .document(userId)
                    .collection("memories")
                    .document(memoryId)
                    .delete()
                    .await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete memory from Firestore: ${e.message}", e)
                Result.failure(e)
            }
        }

        return Result.success(Unit)
    }

    suspend fun deleteAllMemories(userId: String): Result<Unit> {
        val currentList = _localMemories.value.toMutableList()
        currentList.removeAll { it.userId == userId }
        _localMemories.value = currentList
        persistMemories(currentList)

        val db = firestore
        if (db != null && !userId.startsWith("demo_") && userId.isNotBlank()) {
            return try {
                val collection = db.collection("users").document(userId).collection("memories")
                val snapshot = collection.get().await()
                val batch = db.batch()
                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.commit().await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete all memories: ${e.message}", e)
                Result.failure(e)
            }
        }

        return Result.success(Unit)
    }
}
