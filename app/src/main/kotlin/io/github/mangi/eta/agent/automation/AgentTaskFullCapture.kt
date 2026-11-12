package io.github.mangi.eta.agent.automation

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The separate, high-sensitivity store for scheduled automation only. Every payload is projected,
 * secret-filtered and byte-bounded before it enters the async queue. Normal chat never calls this.
 */
internal object AgentTaskFullCapture {
    internal const val MAX_EVENT_BYTES = 96 * 1024
    internal const val MAX_TASK_BYTES = 2 * 1024 * 1024
    internal const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    internal const val MAX_TASKS = 32
    internal const val MAX_EXPORT_BYTES = 8 * 1024 * 1024
    private const val MAX_EVENTS_PER_TASK = 256
    private const val MAX_DEPTH = 8
    private const val MAX_OBJECT_FIELDS = 80
    private const val MAX_ARRAY_ITEMS = 64
    private const val MAX_KEY_BYTES = 128
    private const val DIRECTORY = "agent_task_diagnostics_full"
    private const val PREFS_NAME = "agent_task_full_capture"
    private const val QUEUE_SIZE = 256
    private const val FULL_SUFFIX = ".full.json"
    private const val BAK_SUFFIX = ".bak"
    private const val NEW_SUFFIX = ".new"

    private val lock = Any()
    private val writeQueue = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_SIZE),
        { task -> Thread(task, "eta-task-full-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private data class RunCapture(val taskId: String, val taskKey: String, val generation: Long?)
    private val runCaptures = ConcurrentHashMap<String, RunCapture>()

    private val sensitiveKeys = setOf(
        "apikey", "password", "passwd", "secret", "clientsecret", "clientidsecret",
        "token", "accesstoken", "refreshtoken", "idtoken", "authorization", "authheader",
        "cookie", "setcookie", "privatekey", "signingkey", "credential", "credentials",
        "accesskey", "secretaccesskey", "sessiontoken", "xapikey",
    )
    private val binaryKeys = setOf(
        "imagebytes", "imagedata", "imagedataurl", "screenshot", "screenshotdata",
        "audiobytes", "audiodata", "videobytes", "videodata", "filebytes", "filedata",
        "binary", "binarydata", "base64", "base64data", "contentbytes",
    )
    private val bearerPattern = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]+=*")
    private val jwtPattern = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{15,}\\.[A-Za-z0-9_-]{15,}\\.[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])")
    private const val SECRET_KEY_PATTERN =
        "(?:api[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token|id[_-]?token|password|passwd|secret|authorization|auth[_-]?header|cookie|set-cookie|private[_-]?key|signing[_-]?key|credential|session[_-]?token|token)"
    private val quotedDoubleSecretPattern = Regex(
        "(?i)((?:\\\\?\")?$SECRET_KEY_PATTERN(?:\\\\?\")?\\s*[:=]\\s*\\\\?\")(?:\\\\.|[^\"\\\\])*(\\\\?\")"
    )
    private val partialQuotedDoubleSecretPattern = Regex(
        "(?i)((?:\\\\?\")?$SECRET_KEY_PATTERN(?:\\\\?\")?\\s*[:=]\\s*\\\\?\")(?:\\\\.|[^\"\\\\])*$"
    )
    private val partialPemPattern = Regex("(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*$")
    private val quotedSingleSecretPattern = Regex(
        "(?i)('?$SECRET_KEY_PATTERN'?\\s*[:=]\\s*')(?:\\\\.|[^'\\\\])*(')"
    )
    private val keyValuePattern = Regex("(?i)(\\b$SECRET_KEY_PATTERN\\s*[:=]\\s*)([^\\s,;&\\\"'{}]+)")
    private val pemPattern = Regex("(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----")

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(taskId: String): String = sha256(taskId)
    private fun enabledKey(key: String) = "enabled:$key"
    private fun generationKey(key: String) = "generation:$key"
    private fun cleanupKey(key: String) = "cleanup_pending:$key"
    private fun droppedKey(key: String, generation: Long) = "dropped:$key:$generation"

    fun isEnabled(context: Context, taskId: String): Boolean =
        preferences(context).getBoolean(enabledKey(key(taskId)), true)

    /** Captures the run's entry generation so disable/re-enable cannot revive queued old events. */
    fun registerRun(context: Context, taskId: String, runId: String) {
        if (taskId.isBlank() || runId.isBlank()) return
        val taskKey = key(taskId)
        val prefs = preferences(context)
        val capture = synchronized(lock) {
            val enabled = prefs.getBoolean(enabledKey(taskKey), true)
            RunCapture(taskId, taskKey, if (enabled) prefs.getLong(generationKey(taskKey), 0L) else null)
        }
        runCaptures[runId] = capture
    }

    fun unregisterRun(runId: String) {
        runCaptures.remove(runId)
    }

    /** Must be invoked on Dispatchers.IO. False means disabled persisted, but old files remain pending cleanup. */
    fun setEnabled(context: Context, taskId: String, enabled: Boolean): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Full-capture settings must be updated on IO" }
        val appContext = context.applicationContext
        val taskKey = key(taskId)
        val prefs = preferences(appContext)
        val generation: Long
        synchronized(lock) {
            val previousEnabled = prefs.getBoolean(enabledKey(taskKey), true)
            val previousGeneration = prefs.getLong(generationKey(taskKey), 0L).coerceAtLeast(0L)
            generation = if (previousEnabled == enabled) previousGeneration else previousGeneration + 1L
            val edit = prefs.edit()
                .putBoolean(enabledKey(taskKey), enabled)
                .putLong(generationKey(taskKey), generation)
                .putBoolean(cleanupKey(taskKey), true)
            if (!edit.commit()) throw IllegalStateException("Could not persist full-capture preference")
        }

        // Generation changes make prior payloads invisible immediately. File cleanup runs only on IO
        // and outside the capture lock, so no slow filesystem work can stall task logging or exports.
        val cleaned = cleanupGenerations(appContext, taskKey, generation)
        synchronized(lock) {
            val currentGeneration = prefs.getLong(generationKey(taskKey), 0L)
            val pending = !cleaned
            if (!prefs.edit().putBoolean(cleanupKey(taskKey), pending).commit()) {
                throw IllegalStateException("Could not persist full-capture cleanup status")
            }
            if (!enabled && currentGeneration == generation && pending) return false
        }
        return true
    }

    fun recordRunContext(
        context: Context,
        taskId: String,
        runId: String,
        taskName: String,
        taskPrompt: String,
        runtimePrompt: String,
        model: String,
        modelParameters: JSONObject,
    ) = bestEffortRecord {
        val fields = JSONObject()
            .put("taskName", taskName)
            .put("taskPrompt", taskPrompt)
            .put("runtimePrompt", runtimePrompt)
            .put("model", model)
            .put("modelParameters", modelParameters)
        record(context, taskId, runId, "run_context", fields)
    }

    fun recordRunContextForRun(
        context: Context,
        runId: String,
        taskName: String,
        taskPrompt: String,
        runtimePrompt: String,
        model: String,
        modelParameters: JSONObject,
    ) = bestEffortRecord {
        val fields = JSONObject()
            .put("taskName", taskName)
            .put("taskPrompt", taskPrompt)
            .put("runtimePrompt", runtimePrompt)
            .put("model", model)
            .put("modelParameters", modelParameters)
        recordForRun(context, runId, "run_context", fields)
    }

    fun recordToolCallForRun(
        context: Context,
        runId: String,
        callId: String,
        toolName: String,
        round: Int,
        argumentsJson: String,
    ) = bestEffortRecord {
        recordForRun(
            context,
            runId,
            "tool_call",
            JSONObject()
                .put("callId", callId)
                .put("toolName", toolName)
                .put("round", round.coerceIn(0, 10_000))
                .put("arguments", parseJsonOrText(argumentsJson)),
        )
    }

    fun recordToolResultForRun(
        context: Context,
        runId: String,
        callId: String,
        toolName: String,
        result: String,
        sensitive: Boolean,
        command: String?,
        images: JSONArray,
    ) = bestEffortRecord {
        recordForRun(
            context,
            runId,
            "tool_result",
            JSONObject()
                .put("callId", callId)
                .put("toolName", toolName)
                .put("result", parseJsonOrText(result))
                .put("sensitive", sensitive)
                .put("command", command ?: JSONObject.NULL)
                .put("images", projectImageMetadata(images)),
        )
    }

    fun recordToolExceptionForRun(context: Context, runId: String, callId: String, toolName: String, error: Throwable) = bestEffortRecord {
        recordForRun(
            context,
            runId,
            "tool_exception",
            JSONObject()
                .put("callId", callId)
                .put("toolName", toolName)
                .put("exceptionClass", error.javaClass.name)
                .put("exceptionMessage", error.message ?: ""),
        )
    }

    fun recordRunResponseForRun(context: Context, runId: String, response: String) = bestEffortRecord {
        recordForRun(context, runId, "run_completed", JSONObject().put("response", response))
    }

    fun recordRunFailureForRun(context: Context, runId: String, error: Throwable, cancelled: Boolean) = bestEffortRecord {
        recordForRun(
            context,
            runId,
            if (cancelled) "run_cancelled" else "run_failed",
            JSONObject()
                .put("exceptionClass", error.javaClass.name)
                .put("exceptionMessage", error.message ?: ""),
        )
    }

    private fun projectImageMetadata(images: JSONArray): JSONArray {
        val projected = JSONArray()
        for (index in 0 until minOf(images.length(), MAX_ARRAY_ITEMS)) {
            val image = images.optJSONObject(index) ?: continue
            val item = JSONObject().put("omitted", true)
            val mimeType = image.optString("mimeType")
                .takeIf { Regex("(?i)^(image|audio|video)/[a-z0-9.+-]{1,64}$").matches(it) }
            mimeType?.let { item.put("mimeType", it.lowercase(Locale.ROOT)) }
            image.optInt("width", -1).takeIf { it in 0..16_384 }?.let { item.put("width", it) }
            image.optInt("height", -1).takeIf { it in 0..16_384 }?.let { item.put("height", it) }
            image.optLong("byteLength", image.optLong("bytes", -1L))
                .takeIf { it in 0..MAX_TASK_BYTES.toLong() }
                ?.let { item.put("byteLength", it) }
            projected.put(item)
        }
        return projected
    }

    fun recordDeviceState(context: Context, taskId: String, runId: String, state: JSONObject) {
        record(context, taskId, runId, "device_state", state)
    }

    fun recordDisplayFailureForRun(context: Context, runId: String, detail: JSONObject) {
        recordForRun(context, runId, "display_failure_detail", detail)
    }

    private fun makeEvent(taskId: String, runId: String, type: String, fields: JSONObject): JSONObject {
        val input = JSONObject()
            .put("type", type.take(64))
            .put("atEpochMs", System.currentTimeMillis())
            .put("taskId", taskId)
            .put("runId", runId)
        val keys = fields.keys()
        var added = 0
        while (keys.hasNext() && added < MAX_OBJECT_FIELDS) {
            val name = keys.next().take(MAX_KEY_BYTES)
            input.put(name, fields.opt(name))
            added += 1
        }
        return sanitizeEvent(input)
    }

    private inline fun bestEffortRecord(block: () -> Unit) {
        runCatching(block)
    }

    private data class Budget(var remaining: Int, var truncated: Boolean = false)

    private fun sanitizeEvent(raw: JSONObject): JSONObject {
        val budget = Budget(MAX_EVENT_BYTES - 12 * 1024)
        val output = JSONObject()
        val iterator = raw.keys()
        var count = 0
        while (iterator.hasNext() && count < MAX_OBJECT_FIELDS) {
            val key = iterator.next().take(MAX_KEY_BYTES)
            if (isSensitiveKey(key)) {
                output.put(key, "[REDACTED]")
            } else {
                output.put(key, sanitizeValue(raw.opt(key), key, budget, 0) ?: JSONObject.NULL)
            }
            count += 1
        }
        if (iterator.hasNext()) budget.truncated = true
        output.put("truncated", budget.truncated)
        // Structural/key encoding overhead is reserved above. This fallback guarantees the hard cap
        // even for pathological control characters or adversarially deep/narrow JSON.
        val encoded = output.toString()
        if (encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_EVENT_BYTES) return output
        return JSONObject()
            .put("type", raw.optString("type").take(64))
            .put("atEpochMs", raw.optLong("atEpochMs", 0L).coerceAtLeast(0L))
            .put("taskId", sanitizeText(raw.optString("taskId"), MAX_KEY_BYTES))
            .put("runId", sanitizeText(raw.optString("runId"), MAX_KEY_BYTES))
            .put("payloadOmitted", true)
            .put("truncated", true)
    }

    private fun sanitizeValue(value: Any?, key: String, budget: Budget, depth: Int): Any? {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL
        if (isSensitiveKey(key)) return "[REDACTED]"
        if (isBinaryKey(key)) {
            val length = when (value) {
                is String -> value.length
                is JSONArray -> value.length()
                else -> -1
            }
            budget.truncated = true
            return JSONObject().put("omitted", true).put("reason", "binary_payload").put("sourceLength", length)
        }
        if (depth >= MAX_DEPTH) {
            budget.truncated = true
            return "[TRUNCATED_DEPTH]"
        }
        return when (value) {
            is String -> {
                if (looksLikeBinaryData(value)) {
                    budget.truncated = true
                    JSONObject().put("omitted", true).put("reason", "binary_payload").put("sourceLength", value.length)
                } else {
                    val nested = parseNestedJson(value, key, budget, depth + 1)
                    if (nested != null) nested else {
                        val inputLimit = minOf(MAX_EVENT_BYTES, budget.remaining.coerceAtLeast(0))
                        val boundedInput = takeUtf8Prefix(value, inputLimit)
                        if (boundedInput.length < value.length) budget.truncated = true
                        val cleaned = sanitizeText(boundedInput)
                        val bounded = fitStringBudget(cleaned, budget)
                        if (bounded.length != cleaned.length) budget.truncated = true
                        bounded
                    }
                }
            }
            is JSONObject -> sanitizeObject(value, budget, depth + 1)
            is JSONArray -> sanitizeArray(value, budget, depth + 1)
            is Boolean -> value
            is Number -> value
            else -> "[OMITTED_UNSUPPORTED_VALUE]".also { budget.truncated = true }
        }
    }

    private fun sanitizeObject(raw: JSONObject, budget: Budget, depth: Int): JSONObject {
        val result = JSONObject()
        val keys = raw.keys()
        var count = 0
        while (keys.hasNext() && count < MAX_OBJECT_FIELDS && budget.remaining > 0) {
            val original = keys.next()
            val key = fitKey(original, budget)
            if (key.isBlank()) {
                budget.truncated = true
                continue
            }
            if (isSensitiveKey(original)) {
                result.put(key, "[REDACTED]")
            } else {
                result.put(key, sanitizeValue(raw.opt(original), original, budget, depth) ?: JSONObject.NULL)
            }
            count += 1
        }
        if (keys.hasNext()) budget.truncated = true
        return result
    }

    private fun sanitizeArray(raw: JSONArray, budget: Budget, depth: Int): JSONArray {
        val result = JSONArray()
        val count = minOf(raw.length(), MAX_ARRAY_ITEMS)
        for (index in 0 until count) {
            if (budget.remaining <= 0) {
                budget.truncated = true
                break
            }
            result.put(sanitizeValue(raw.opt(index), "", budget, depth) ?: JSONObject.NULL)
        }
        if (raw.length() > count) budget.truncated = true
        return result
    }

    private fun parseNestedJson(value: String, key: String, budget: Budget, depth: Int): Any? {
        val prefix = value.take(1024).trimStart()
        val candidateShape = prefix.startsWith("{") || prefix.startsWith("[") ||
            (prefix.startsWith("\"") && prefix.contains("\\\"") &&
                (prefix.contains("{") || prefix.contains("[")))
        if (!candidateShape) return null
        val tooLarge = exceedsUtf8Limit(value, MAX_EVENT_BYTES)
        if (depth >= MAX_DEPTH || tooLarge || !hasBoundedJsonStructure(value)) {
            budget.truncated = true
            return JSONObject()
                .put("omitted", true)
                .put("reason", if (tooLarge) "structured_payload_too_large" else "structured_payload_too_deep")
                .put("sourceLength", value.length)
                .put("truncated", true)
        }
        val parsed = runCatching { JSONTokener(value.trim()).nextValue() }.getOrNull()
        val nested = when (parsed) {
            is JSONObject, is JSONArray -> parsed
            is String -> {
                val unwrapped = parsed.take(1024).trimStart()
                if (unwrapped.startsWith("{") || unwrapped.startsWith("[")) {
                    parseNestedJson(parsed, key, budget, depth + 1)
                } else null
            }
            else -> null
        }
        if (nested == null) {
            budget.truncated = true
            return JSONObject().put("omitted", true).put("reason", "invalid_structured_payload").put("truncated", true)
        }
        return if (nested is JSONObject || nested is JSONArray) {
            sanitizeValue(nested, key, budget, depth + 1)
        } else nested
    }

    private fun hasBoundedJsonStructure(raw: String): Boolean {
        if (raw.length > MAX_EVENT_BYTES) return false
        var depth = 0
        var separators = 0
        var quoted = false
        var escaped = false
        for (character in raw) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '\"' -> quoted = false
                }
                continue
            }
            when (character) {
                '\"' -> quoted = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_DEPTH) return false
                }
                '}', ']' -> {
                    depth -= 1
                    if (depth < 0) return false
                }
                ',', ':' -> {
                    separators += 1
                    if (separators > MAX_OBJECT_FIELDS * MAX_ARRAY_ITEMS) return false
                }
            }
        }
        return !quoted && depth == 0
    }

    private fun fitStringBudget(value: String, budget: Budget): String {
        if (budget.remaining <= 0) return "[TRUNCATED]"
        val fullCost = encodedStringCost(value)
        if (fullCost <= budget.remaining) {
            budget.remaining -= fullCost
            return value
        }
        val marker = "…[TRUNCATED]"
        var low = 0
        var high = value.length
        var best = marker
        while (low <= high) {
            val mid = (low + high) ushr 1
            val prefix = prefixByUtf8Bytes(value, mid, (budget.remaining / 6).coerceAtLeast(0))
            val candidate = prefix + marker
            if (encodedStringCost(candidate) <= budget.remaining) {
                best = candidate
                low = mid + 1
            } else high = mid - 1
        }
        budget.remaining -= encodedStringCost(best).coerceAtMost(budget.remaining)
        budget.truncated = true
        return best
    }

    private fun fitKey(value: String, budget: Budget): String {
        val safe = sanitizeText(value).take(64)
        val maxRaw = (MAX_KEY_BYTES / 6).coerceAtLeast(1)
        val bounded = prefixByUtf8Bytes(safe, safe.length, maxRaw)
        val cost = encodedStringCost(bounded)
        if (cost > budget.remaining) {
            budget.truncated = true
            return ""
        }
        budget.remaining -= cost
        return bounded
    }

    private fun takeUtf8Prefix(value: String, maxBytes: Int): String =
        prefixByUtf8Bytes(value, value.length, maxBytes)

    private fun exceedsUtf8Limit(value: String, maxBytes: Int): Boolean =
        takeUtf8Prefix(value, maxBytes).length < value.length

    private fun prefixByUtf8Bytes(value: String, maxChars: Int, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val end = maxChars.coerceIn(0, value.length)
        var index = 0
        var bytes = 0
        while (index < end) {
            val codePoint = value.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (index + charCount > end) break
            val cost = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + cost > maxBytes) break
            bytes += cost
            index += charCount
        }
        return value.substring(0, index)
    }

    private fun encodedStringCost(value: String): Int =
        JSONObject().put("v", value).toString().toByteArray(StandardCharsets.UTF_8).size - 8

    private fun sanitizeText(raw: String, maxChars: Int = MAX_EVENT_BYTES): String {
        val byteLimit = maxChars.coerceAtLeast(0)
        val scanLimit = minOf(MAX_EVENT_BYTES, maxOf(1024, byteLimit * 2))
        val input = takeUtf8Prefix(raw, scanLimit)
        var value = input
            .replace(pemPattern, "[REDACTED_PRIVATE_KEY]")
            .replace(partialPemPattern, "[REDACTED_PRIVATE_KEY][TRUNCATED]")
            .replace(bearerPattern, "$1 [REDACTED]")
            .replace(jwtPattern, "[REDACTED_JWT]")
            .replace(quotedDoubleSecretPattern, "$1[REDACTED]$2")
            .replace(partialQuotedDoubleSecretPattern, "$1[REDACTED][TRUNCATED]")
            .replace(quotedSingleSecretPattern, "$1[REDACTED]$2")
            .replace(keyValuePattern, "$1[REDACTED]")
        if (input.length < raw.length || exceedsUtf8Limit(value, byteLimit)) {
            val marker = "[TRUNCATED]"
            val markerPart = takeUtf8Prefix(marker, byteLimit)
            value = takeUtf8Prefix(value, (byteLimit - markerPart.toByteArray(StandardCharsets.UTF_8).size).coerceAtLeast(0)) + markerPart
        }
        return value
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return normalized in sensitiveKeys
    }

    private fun isBinaryKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return normalized in binaryKeys || normalized.endsWith("base64") || normalized.endsWith("dataurl")
    }

    private fun looksLikeBinaryData(raw: String): Boolean {
        val prefix = raw.take(128).trimStart()
        if (prefix.startsWith("data:image/", ignoreCase = true) ||
            prefix.startsWith("data:audio/", ignoreCase = true) ||
            prefix.startsWith("data:video/", ignoreCase = true)
        ) return true
        if (raw.length < 256) return false
        val sample = takeUtf8Prefix(raw, 8192).trim()
        return sample.length >= 2048 && sample.all { it.isLetterOrDigit() || it in "+/=\r\n" }
    }

    private fun parseJsonOrText(raw: String): Any {
        if (looksLikeBinaryData(raw)) {
            return JSONObject().put("omitted", true).put("reason", "binary_payload").put("sourceLength", raw.length)
        }
        val budget = Budget(MAX_EVENT_BYTES - 12 * 1024)
        val structured = parseNestedJson(raw, "", budget, 0)
        if (structured != null) return structured
        val inputLimit = minOf(MAX_EVENT_BYTES, budget.remaining.coerceAtLeast(0))
        val bounded = takeUtf8Prefix(raw, inputLimit)
        val cleaned = sanitizeText(bounded)
        if (bounded.length == raw.length) return cleaned
        return JSONObject()
            .put("text", cleaned)
            .put("truncated", true)
            .put("sourceLength", raw.length)
    }

    private fun record(context: Context, taskId: String, runId: String, type: String, fields: JSONObject) =
        bestEffortRecord {
            if (taskId.isBlank() || runId.isBlank()) return@bestEffortRecord
            val capture = runCaptures[runId] ?: return@bestEffortRecord
            if (capture.taskId != taskId) return@bestEffortRecord
            recordForRun(context, runId, type, fields)
        }

    private fun recordForRun(context: Context, runId: String, type: String, fields: JSONObject) =
        bestEffortRecord {
            if (runId.isBlank()) return@bestEffortRecord
            val capture = runCaptures[runId] ?: return@bestEffortRecord
            val generation = capture.generation ?: return@bestEffortRecord
            val prefs = preferences(context)
            if (!prefs.getBoolean(enabledKey(capture.taskKey), true) ||
                prefs.getLong(generationKey(capture.taskKey), 0L) != generation
            ) return@bestEffortRecord
            val encoded = makeEvent(capture.taskId, runId, type, fields).toString()
            if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_EVENT_BYTES) {
                runCatching { prefs.edit().putBoolean(droppedKey(capture.taskKey, generation), true).apply() }
                return@bestEffortRecord
            }
            try {
                writeQueue.execute {
                    try {
                        persistIfEnabled(context.applicationContext, capture.taskKey, generation, encoded)
                    } catch (_: Throwable) {
                        runCatching { prefs.edit().putBoolean(droppedKey(capture.taskKey, generation), true).apply() }
                    }
                }
            } catch (_: Throwable) {
                runCatching { prefs.edit().putBoolean(droppedKey(capture.taskKey, generation), true).apply() }
            }
        }

    private fun persistIfEnabled(context: Context, taskKey: String, generation: Long, encoded: String) {
        synchronized(lock) {
            val prefs = preferences(context)
            if (!prefs.getBoolean(enabledKey(taskKey), true) ||
                prefs.getLong(generationKey(taskKey), 0L) != generation
            ) return
            val event = runCatching { JSONObject(encoded) }.getOrNull() ?: return
            persist(context, taskKey, generation, event)
        }
    }

    private fun persist(context: Context, taskKey: String, generation: Long, event: JSONObject) {
        val directory = context.getDir(DIRECTORY, Context.MODE_PRIVATE)
        if (!directory.exists() && !directory.mkdirs()) return
        val file = fullFile(directory, taskKey, generation)
        val atomic = AtomicFile(file)
        val oldRoot = readRoot(atomic, file, generation)
        val events = oldRoot.optJSONArray("events") ?: JSONArray()
        events.put(event)
        var truncated = oldRoot.optBoolean("truncated", false) ||
            preferences(context).getBoolean(droppedKey(taskKey, generation), false)
        while (events.length() > MAX_EVENTS_PER_TASK || encodedSize(generation, events, truncated) > MAX_TASK_BYTES) {
            if (events.length() == 0) break
            events.remove(0)
            truncated = true
        }
        val data = JSONObject()
            .put("schemaVersion", AgentTaskDiagnostics.SCHEMA_VERSION)
            .put("generation", generation)
            .put("truncated", truncated)
            .put("events", events)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        writeAtomic(atomic, data)
        preferences(context).edit().remove(droppedKey(taskKey, generation)).apply()
        enforceLimits(directory, taskKey, file)
    }

    private fun writeAtomic(atomic: AtomicFile, bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
            stream = null
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }

    private fun readRoot(atomic: AtomicFile, file: File, generation: Long): JSONObject {
        if (!file.exists() && !File(file.path + BAK_SUFFIX).exists()) return JSONObject()
        return try {
            atomic.openRead().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_TASK_BYTES) return JSONObject().put("generation", generation).put("truncated", true)
                    out.write(buffer, 0, read)
                }
                JSONObject(String(out.toByteArray(), StandardCharsets.UTF_8))
                    .takeIf { it.optLong("generation", -1L) == generation }
                    ?: JSONObject().put("generation", generation)
            }
        } catch (_: Exception) {
            JSONObject().put("generation", generation).put("truncated", true)
        }
    }

    private fun encodedSize(generation: Long, events: JSONArray, truncated: Boolean): Int =
        JSONObject().put("schemaVersion", AgentTaskDiagnostics.SCHEMA_VERSION)
            .put("generation", generation).put("truncated", truncated).put("events", events)
            .toString().toByteArray(StandardCharsets.UTF_8).size

    private fun enforceLimits(directory: File, currentTaskKey: String, currentFile: File) {
        while (true) {
            val files = fullFiles(directory)
            val groups = files.groupBy { taskKeyFromFile(it.name) ?: "" }.filterKeys { it.isNotBlank() }
            if (groups.size <= MAX_TASKS && files.sumOf(File::length) <= MAX_TOTAL_BYTES) break
            val candidates = groups.filter { (taskKey, _) -> taskKey != currentTaskKey }
            val oldest = candidates.minByOrNull { (_, group) -> group.maxOfOrNull(File::lastModified) ?: Long.MIN_VALUE }
                ?: break
            oldest.value.forEach { file -> if (file.exists()) file.delete() }
        }
        // A task may have old generations retained after failed cleanup. Enforce the cap across all.
        val taskFiles = fullFiles(directory).filter { taskKeyFromFile(it.name) == currentTaskKey }
        val otherBytes = (taskFiles - currentFile).sumOf(File::length)
        val currentLimit = (MAX_TASK_BYTES - otherBytes).coerceAtLeast(0L)
        if (currentFile.length() > currentLimit) {
            val root = readRoot(AtomicFile(currentFile), currentFile, extractGeneration(currentFile.name) ?: 0L)
            val events = root.optJSONArray("events") ?: JSONArray()
            var truncated = true
            while (events.length() > 0 && encodedSize(root.optLong("generation"), events, truncated) > currentLimit) {
                events.remove(0)
            }
            if (events.length() == 0 && currentLimit < 256) {
                currentFile.delete()
            } else {
                val replacement = JSONObject()
                    .put("schemaVersion", AgentTaskDiagnostics.SCHEMA_VERSION)
                    .put("generation", root.optLong("generation"))
                    .put("truncated", truncated)
                    .put("events", events)
                    .toString().toByteArray(StandardCharsets.UTF_8)
                writeAtomic(AtomicFile(currentFile), replacement)
            }
        }
    }

    private fun fullFiles(directory: File): List<File> = directory.listFiles { file ->
        file.isFile && (file.name.endsWith(FULL_SUFFIX) || file.name.endsWith(FULL_SUFFIX + BAK_SUFFIX) || file.name.endsWith(FULL_SUFFIX + NEW_SUFFIX))
    }.orEmpty().toList()

    private fun taskKeyFromFile(name: String): String? {
        val normalized = name.removeSuffix(BAK_SUFFIX).removeSuffix(NEW_SUFFIX)
        if (!normalized.endsWith(FULL_SUFFIX)) return null
        val withoutSuffix = normalized.removeSuffix(FULL_SUFFIX)
        val separator = withoutSuffix.lastIndexOf('.')
        if (separator < 64) return null
        val taskKey = withoutSuffix.substring(0, separator)
        return taskKey.takeIf { Regex("[0-9a-f]{64}").matches(it) }
    }

    private fun extractGeneration(name: String): Long? {
        val normalized = name.removeSuffix(BAK_SUFFIX).removeSuffix(NEW_SUFFIX)
        val withoutSuffix = normalized.removeSuffix(FULL_SUFFIX)
        val separator = withoutSuffix.lastIndexOf('.')
        if (separator < 0 || separator == withoutSuffix.lastIndex) return null
        return withoutSuffix.substring(separator + 1).toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun fullFile(directory: File, taskKey: String, generation: Long): File =
        File(directory, "$taskKey.$generation$FULL_SUFFIX")

    internal fun awaitPendingWritesForTests() {
        writeQueue.submit {}.get(5, TimeUnit.SECONDS)
    }

    internal fun retainedFilesForTests(context: Context): List<File> =
        fullFiles(context.applicationContext.getDir(DIRECTORY, Context.MODE_PRIVATE))

    internal fun clearForTests(context: Context) {
        awaitPendingWritesForTests()
        synchronized(lock) {
            val directory = context.applicationContext.getDir(DIRECTORY, Context.MODE_PRIVATE)
            fullFiles(directory).forEach { it.delete() }
            preferences(context).edit().clear().commit()
            runCaptures.clear()
        }
    }

    private fun cleanupGenerations(context: Context, taskKey: String, keepGeneration: Long): Boolean {
        val directory = context.getDir(DIRECTORY, Context.MODE_PRIVATE)
        if (!directory.exists()) return true
        var success = true
        fullFiles(directory).filter { file ->
            taskKeyFromFile(file.name) == taskKey && (extractGeneration(file.name) ?: Long.MAX_VALUE) < keepGeneration
        }.forEach { file ->
            if (file.exists() && !file.delete()) success = false
        }
        return success
    }

    internal data class ExportSnapshot(
        val generation: Long,
        val json: String,
        val truncated: Boolean,
        val status: String,
    )

    suspend fun snapshotForExport(context: Context, taskId: String, includeSensitive: Boolean): ExportSnapshot? {
        if (!includeSensitive) return null
        val appContext = context.applicationContext
        val taskKey = key(taskId)
        val prefs = preferences(appContext)
        val requestedGeneration = synchronized(lock) {
            if (prefs.getBoolean(enabledKey(taskKey), true)) prefs.getLong(generationKey(taskKey), 0L) else null
        } ?: return ExportSnapshot(0L, "", false, "disabled_at_snapshot")
        awaitPendingWrites()
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!prefs.getBoolean(enabledKey(taskKey), true) ||
                    prefs.getLong(generationKey(taskKey), 0L) != requestedGeneration
                ) return@synchronized ExportSnapshot(
                    requestedGeneration, "", false, "generation_changed_or_disabled",
                )
                val directory = appContext.getDir(DIRECTORY, Context.MODE_PRIVATE)
                val file = fullFile(directory, taskKey, requestedGeneration)
                val root = readRoot(AtomicFile(file), file, requestedGeneration)
                val rawEvents = root.optJSONArray("events") ?: JSONArray()
                val events = projectEvents(rawEvents, taskId, requestedGeneration)
                val snapshot = JSONObject()
                    .put("schemaVersion", AgentTaskDiagnostics.SCHEMA_VERSION)
                    .put("taskId", taskId)
                    .put("generation", requestedGeneration)
                    .put("diagnosticLogPresent", file.isFile)
                    .put("truncated", root.optBoolean("truncated", false) || events.second)
                    .put("omissionsPossible", true)
                    .put("secretRedaction", "Best-effort patterns only; arbitrary free text may still contain secrets.")
                    .put("binaryPayloads", "Binary/media/base64 payloads are omitted; metadata only.")
                    .put("events", events.first)
                ExportSnapshot(
                    generation = requestedGeneration,
                    json = snapshot.toString(),
                    truncated = snapshot.optBoolean("truncated"),
                    status = if (file.isFile) "included" else "no_retained_log",
                )
            }
        }
    }

    private fun projectEvents(raw: JSONArray, taskId: String, generation: Long): Pair<JSONArray, Boolean> {
        val result = JSONArray()
        var omitted = raw.length() > MAX_EVENTS_PER_TASK
        val start = (raw.length() - MAX_EVENTS_PER_TASK).coerceAtLeast(0)
        for (index in start until raw.length()) {
            val event = raw.optJSONObject(index)
            if (event == null) {
                omitted = true
                continue
            }
            if (event.optString("taskId") != taskId || event.optLong("generation", generation) != generation) {
                omitted = true
                continue
            }
            val type = event.optString("type")
            val allowed = when (type) {
                "run_context" -> setOf("taskName", "taskPrompt", "runtimePrompt", "model", "modelParameters")
                "tool_call" -> setOf("callId", "toolName", "round", "arguments")
                "tool_result" -> setOf("callId", "toolName", "result", "sensitive", "command", "images")
                "tool_exception" -> setOf("callId", "toolName", "exceptionClass", "exceptionMessage")
                "run_completed" -> setOf("response")
                "run_failed", "run_cancelled" -> setOf("exceptionClass", "exceptionMessage")
                "device_state", "display_failure_detail" -> setOf(
                    "isInteractive", "isDeviceIdleMode", "isPowerSaveMode", "keyguardLocked",
                    "isIgnoringBatteryOptimizations", "backgroundRestricted", "isLowPowerStandbyEnabled",
                    "reason", "stage", "code", "exceptionKind", "causeKind", "sdkInt", "processUid", "branch",
                )
                else -> { omitted = true; continue }
            }
            val projected = JSONObject()
                .put("type", type)
                .put("atEpochMs", event.optLong("atEpochMs", 0L).coerceAtLeast(0L))
                .put("taskId", taskId)
                .put("runId", event.optString("runId"))
                .put("generation", generation)
            allowed.forEach { name ->
                if (event.has(name)) {
                    val safe = sanitizeValue(event.opt(name), name, Budget(MAX_EVENT_BYTES - 12 * 1024), 0)
                    if (safe != null) projected.put(name, safe)
                }
            }
            projected.put("truncated", event.optBoolean("truncated", false))
            result.put(projected)
        }
        return result to omitted
    }

    private suspend fun awaitPendingWrites() = withContext(Dispatchers.IO) {
        val completed = CountDownLatch(1)
        try {
            writeQueue.execute { completed.countDown() }
            completed.await(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Coverage metadata remains best-effort if the queue cannot accept a barrier.
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}
