package com.example.data.gemini

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AIAnalysisResult(
    val isImportant: Boolean,
    val title: String,
    val content: String,
    val detectedPeople: List<String> = emptyList(),
    val detectedTask: String? = null,
    val detectedDeadline: String? = null,
    val importanceLevel: String = Memory.IMPORTANCE_NORMAL
)

data class AISearchResult(
    val answer: String,
    val matchingMemoryIds: List<String>
)

object GeminiService {
    private const val TAG = "GeminiService"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun getApiKey(): String {
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun analyzeSpeech(rawSpeech: String, isRecallCommand: Boolean): AIAnalysisResult = withContext(Dispatchers.IO) {
        val trimmed = rawSpeech.trim()
        if (trimmed.isEmpty()) {
            return@withContext AIAnalysisResult(
                isImportant = false,
                title = "",
                content = ""
            )
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API key is blank or placeholder, using local heuristic parsing.")
            return@withContext fallbackParsing(trimmed, isRecallCommand)
        }

        val prompt = if (isRecallCommand) {
            """
            The user explicitly triggered the voice command 'Recall Me Please' with this speech:
            "$trimmed"

            CRITICAL DIRECTIVE:
            1. This MUST be marked isImportant: true. Do not discard it under any circumstances.
            2. Remove any accidental mentions of the command words 'Recall Me Please'.
            3. Clean up minor transcription errors or speech stutters while preserving 100% of the original meaning.
            4. Generate a concise, informative title (3-6 words).
            5. Extract any mentioned person names in 'detectedPeople' array.
            6. Extract any specific task or action in 'detectedTask' string (or null).
            7. Extract any date, time, or deadline in 'detectedDeadline' string (or null).
            8. Set 'importanceLevel' to "High".

            Return ONLY raw JSON with no Markdown wrappers matching this schema:
            {
              "isImportant": true,
              "title": "Short Title",
              "content": "Cleaned note text",
              "detectedPeople": ["Name1"],
              "detectedTask": "Task description or null",
              "detectedDeadline": "Date or null",
              "importanceLevel": "High"
            }
            """.trimIndent()
        } else {
            """
            The following conversation excerpt was transcribed from ambient speech:
            "$trimmed"

            Analyze if this contains genuinely useful, important personal or business information:
            - Tasks or to-dos
            - Deadlines or appointments
            - Promises or commitments
            - Decisions or agreements
            - Creative ideas or insights
            - Names, contacts, or important numbers
            - Follow-up actions or plans

            Normal chit-chat, filler speech, background noise, or meaningless comments should be marked isImportant: false.

            If it is important (isImportant: true), provide:
            - title: A short title (3-6 words)
            - content: Cleaned, coherent statement
            - detectedPeople: List of people names
            - detectedTask: Specific task if present, or null
            - detectedDeadline: Date or time reference if present, or null
            - importanceLevel: "High", "Medium", or "Normal"

            Return ONLY raw JSON with no Markdown wrappers:
            {
              "isImportant": true,
              "title": "Title",
              "content": "Content",
              "detectedPeople": [],
              "detectedTask": null,
              "detectedDeadline": null,
              "importanceLevel": "Medium"
            }
            """.trimIndent()
        }

        try {
            val jsonPayload = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                val genConfig = JSONObject().apply {
                    put("temperature", 0.2)
                    val respFormat = JSONObject().apply {
                        put("mimeType", "application/json")
                    }
                    put("responseFormat", respFormat)
                }
                put("generationConfig", genConfig)
            }

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error: ${response.code} $responseBody")
                return@withContext fallbackParsing(trimmed, isRecallCommand)
            }

            val rootJson = JSONObject(responseBody)
            val candidates = rootJson.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val rawText = parts?.optJSONObject(0)?.optString("text") ?: ""

            parseJsonResponse(rawText, trimmed, isRecallCommand)
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling Gemini API: ${e.message}", e)
            fallbackParsing(trimmed, isRecallCommand)
        }
    }

    private fun parseJsonResponse(rawText: String, fallbackSpeech: String, isRecallCommand: Boolean): AIAnalysisResult {
        try {
            val cleanedText = rawText.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JSONObject(cleanedText)
            val isImportant = if (isRecallCommand) true else json.optBoolean("isImportant", false)
            val title = json.optString("title", "").ifBlank {
                generateFallbackTitle(fallbackSpeech)
            }
            val content = json.optString("content", "").ifBlank { fallbackSpeech }

            val peopleList = mutableListOf<String>()
            val peopleArr = json.optJSONArray("detectedPeople")
            if (peopleArr != null) {
                for (i in 0 until peopleArr.length()) {
                    val p = peopleArr.optString(i)
                    if (!p.isNullOrBlank()) peopleList.add(p)
                }
            }

            val task = json.optString("detectedTask").takeIf { it.isNotBlank() && it != "null" }
            val deadline = json.optString("detectedDeadline").takeIf { it.isNotBlank() && it != "null" }
            val importance = if (isRecallCommand) {
                Memory.IMPORTANCE_HIGH
            } else {
                json.optString("importanceLevel", Memory.IMPORTANCE_NORMAL)
            }

            return AIAnalysisResult(
                isImportant = isImportant,
                title = title,
                content = content,
                detectedPeople = peopleList,
                detectedTask = task,
                detectedDeadline = deadline,
                importanceLevel = importance
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini JSON: ${e.message}")
            return fallbackParsing(fallbackSpeech, isRecallCommand)
        }
    }

    private fun fallbackParsing(speech: String, isRecallCommand: Boolean): AIAnalysisResult {
        val lower = speech.lowercase()
        val isImportant = if (isRecallCommand) {
            true
        } else {
            // Local keyword check for important indicators
            listOf("need to", "must", "have to", "remember", "meeting", "call", "send", "tomorrow", "friday", "deadline", "idea", "project", "client", "contract")
                .any { lower.contains(it) }
        }

        val cleanSpeech = speech.replace(Regex("(?i)\\brecall\\s+me\\s+please\\b[.,!]?"), "").trim()
        val title = generateFallbackTitle(cleanSpeech.ifBlank { speech })

        return AIAnalysisResult(
            isImportant = isImportant,
            title = title,
            content = cleanSpeech.ifBlank { speech },
            detectedPeople = emptyList(),
            detectedTask = if (lower.contains("need to") || lower.contains("call") || lower.contains("send")) cleanSpeech else null,
            detectedDeadline = if (lower.contains("tomorrow") || lower.contains("today") || lower.contains("pm") || lower.contains("am")) "Detected in speech" else null,
            importanceLevel = if (isRecallCommand) Memory.IMPORTANCE_HIGH else Memory.IMPORTANCE_NORMAL
        )
    }

    private fun generateFallbackTitle(speech: String): String {
        val words = speech.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return if (words.size <= 5) {
            words.joinToString(" ").replaceFirstChar { it.uppercase() }
        } else {
            words.take(5).joinToString(" ").replaceFirstChar { it.uppercase() } + "..."
        }
    }

    suspend fun searchMemories(query: String, memories: List<Memory>): AISearchResult = withContext(Dispatchers.IO) {
        if (memories.isEmpty()) {
            return@withContext AISearchResult("No memories found to search through.", emptyList())
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            // Fallback keyword search
            return@withContext localMemorySearch(query, memories)
        }

        val memoriesJson = JSONArray().apply {
            memories.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.memoryId)
                    put("title", m.title)
                    put("content", m.content)
                    put("date", m.date)
                    put("time", m.time)
                    put("source", m.source)
                    put("task", m.detectedTask ?: "")
                    put("deadline", m.detectedDeadline ?: "")
                    put("people", JSONArray(m.detectedPeople))
                })
            }
        }

        val prompt = """
        The user is searching their personal memories with the question:
        "$query"

        Here is the user's saved memory database:
        $memoriesJson

        Instructions:
        1. Synthesize a direct, helpful, natural language answer to the question based ONLY on the stored memories.
        2. If the memories do not contain an answer, state that clearly and politely.
        3. Identify the specific memory IDs that are directly relevant to this query.
        4. Return ONLY valid JSON:
        {
          "answer": "Concise natural language answer.",
          "matchingMemoryIds": ["memoryId1", "memoryId2"]
        }
        """.trimIndent()

        try {
            val jsonPayload = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                val genConfig = JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseFormat", JSONObject().apply { put("mimeType", "application/json") })
                }
                put("generationConfig", genConfig)
            }

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext localMemorySearch(query, memories)
            }

            val rootJson = JSONObject(responseBody)
            val candidates = rootJson.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val rawText = candidate?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""

            val cleaned = rawText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val resultJson = JSONObject(cleaned)
            val answer = resultJson.optString("answer", "Here are the matching memories:")
            val idList = mutableListOf<String>()
            val idsArr = resultJson.optJSONArray("matchingMemoryIds")
            if (idsArr != null) {
                for (i in 0 until idsArr.length()) {
                    idList.add(idsArr.optString(i))
                }
            }

            AISearchResult(answer, idList)
        } catch (e: Exception) {
            Log.e(TAG, "Search Gemini exception: ${e.message}")
            localMemorySearch(query, memories)
        }
    }

    private fun localMemorySearch(query: String, memories: List<Memory>): AISearchResult {
        val qLower = query.lowercase().trim()
        val keywords = qLower.split("\\s+".toRegex()).filter { it.length > 2 }

        val matches = memories.filter { memory ->
            val text = "${memory.title} ${memory.content} ${memory.date} ${memory.detectedTask ?: ""} ${memory.detectedDeadline ?: ""} ${memory.detectedPeople.joinToString(" ")}".lowercase()
            keywords.any { text.contains(it) } || text.contains(qLower)
        }

        val answer = if (matches.isEmpty()) {
            "No memories matched '$query'. Try searching by name, task, or date."
        } else {
            "Found ${matches.size} relevant ${if (matches.size == 1) "memory" else "memories"} for '$query'."
        }

        return AISearchResult(answer, matches.map { it.memoryId })
    }
}
