package club.taptappers.telly.gmail

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class EmailResult(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: String,
    val to: String,
    val date: Long,
    val snippet: String,
    val body: String,
    val attachments: List<AttachmentInfo>
)

data class AttachmentInfo(
    val filename: String,
    val mimeType: String,
    val size: Long,
    val attachmentId: String,
    val data: String? = null // Base64 encoded, only for small attachments or when explicitly requested
)

@Singleton
class GmailHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GmailHelper"
        private const val MAX_INLINE_ATTACHMENT_SIZE = 5 * 1024 * 1024 // 5MB max for inline base64
    }

    private val gsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = NetHttpTransport()

    fun getSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(GmailScopes.GMAIL_READONLY))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, Scope(GmailScopes.GMAIL_READONLY))
    }

    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    private fun getGmailService(account: GoogleSignInAccount): Gmail {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(GmailScopes.GMAIL_READONLY)
        )
        credential.selectedAccount = account.account

        return Gmail.Builder(httpTransport, gsonFactory, credential)
            .setApplicationName("Telly")
            .build()
    }

    suspend fun fetchEmails(
        searchQuery: String,
        windowStartMs: Long,
        windowEndMs: Long,
        maxResults: Int = 50
    ): List<EmailResult> = withContext(Dispatchers.IO) {
        val account = getSignedInAccount()
        if (account == null) {
            Log.e(TAG, "Not signed in to Google")
            return@withContext emptyList()
        }

        try {
            val gmail = getGmailService(account)

            // Build Gmail query with time window
            // Gmail uses epoch seconds for after/before
            val afterSeconds = windowStartMs / 1000
            val beforeSeconds = windowEndMs / 1000
            val fullQuery = "$searchQuery after:$afterSeconds before:$beforeSeconds"

            Log.d(TAG, "Searching Gmail with query: $fullQuery")

            // List messages matching the query
            val listResponse = gmail.users().messages()
                .list("me")
                .setQ(fullQuery)
                .setMaxResults(maxResults.toLong())
                .execute()

            val messages = listResponse.messages ?: emptyList()
            Log.d(TAG, "Found ${messages.size} messages")

            // Fetch full details for each message
            messages.mapNotNull { messageRef ->
                try {
                    fetchEmailDetails(gmail, messageRef.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching message ${messageRef.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching emails", e)
            emptyList()
        }
    }

    private fun fetchEmailDetails(gmail: Gmail, messageId: String): EmailResult {
        val message = gmail.users().messages()
            .get("me", messageId)
            .setFormat("full")
            .execute()

        val headers = message.payload?.headers ?: emptyList()
        val subject = headers.find { it.name.equals("Subject", ignoreCase = true) }?.value ?: "(No Subject)"
        val from = headers.find { it.name.equals("From", ignoreCase = true) }?.value ?: ""
        val to = headers.find { it.name.equals("To", ignoreCase = true) }?.value ?: ""
        val dateHeader = headers.find { it.name.equals("Date", ignoreCase = true) }?.value

        val date = message.internalDate ?: System.currentTimeMillis()

        // Extract body
        val body = extractBody(message.payload)

        // Extract attachments info
        val attachments = extractAttachments(gmail, messageId, message.payload)

        return EmailResult(
            id = messageId,
            threadId = message.threadId ?: "",
            subject = subject,
            from = from,
            to = to,
            date = date,
            snippet = message.snippet ?: "",
            body = body,
            attachments = attachments
        )
    }

    private fun extractBody(payload: com.google.api.services.gmail.model.MessagePart?): String {
        if (payload == null) return ""

        // Check if this part has a body
        val bodyData = payload.body?.data
        if (bodyData != null && payload.mimeType?.startsWith("text/") == true) {
            return String(Base64.decode(bodyData, Base64.URL_SAFE))
        }

        // Check parts recursively
        val parts = payload.parts ?: return ""

        // Prefer text/plain, then text/html
        val textPart = parts.find { it.mimeType == "text/plain" }
        if (textPart?.body?.data != null) {
            return String(Base64.decode(textPart.body.data, Base64.URL_SAFE))
        }

        val htmlPart = parts.find { it.mimeType == "text/html" }
        if (htmlPart?.body?.data != null) {
            // Strip HTML tags for plain text
            val html = String(Base64.decode(htmlPart.body.data, Base64.URL_SAFE))
            return html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        }

        // Recurse into multipart
        for (part in parts) {
            val body = extractBody(part)
            if (body.isNotBlank()) return body
        }

        return ""
    }

    private fun extractAttachments(
        gmail: Gmail,
        messageId: String,
        payload: com.google.api.services.gmail.model.MessagePart?
    ): List<AttachmentInfo> {
        if (payload == null) return emptyList()

        val attachments = mutableListOf<AttachmentInfo>()
        extractAttachmentsRecursive(gmail, messageId, payload, attachments)
        return attachments
    }

    private fun extractAttachmentsRecursive(
        gmail: Gmail,
        messageId: String,
        part: com.google.api.services.gmail.model.MessagePart,
        attachments: MutableList<AttachmentInfo>
    ) {
        val filename = part.filename
        val attachmentId = part.body?.attachmentId
        val size = part.body?.size?.toLong() ?: 0

        // If this part has an attachment
        if (!filename.isNullOrBlank() && attachmentId != null) {
            var data: String? = null

            // Only fetch attachment data if it's reasonably small (to avoid OOM)
            // For PDFs and other large files, we fetch them but with size limits
            if (size <= MAX_INLINE_ATTACHMENT_SIZE) {
                try {
                    val attachment = gmail.users().messages().attachments()
                        .get("me", messageId, attachmentId)
                        .execute()
                    data = attachment.data
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching attachment $filename", e)
                }
            } else {
                Log.w(TAG, "Attachment $filename too large ($size bytes), skipping data fetch")
            }

            attachments.add(
                AttachmentInfo(
                    filename = filename,
                    mimeType = part.mimeType ?: "application/octet-stream",
                    size = size,
                    attachmentId = attachmentId,
                    data = data
                )
            )
        }

        // Recurse into parts
        part.parts?.forEach { subPart ->
            extractAttachmentsRecursive(gmail, messageId, subPart, attachments)
        }
    }

    fun emailResultsToJson(emails: List<EmailResult>): JSONArray {
        val jsonArray = JSONArray()
        for (email in emails) {
            val emailJson = JSONObject().apply {
                put("id", email.id)
                put("threadId", email.threadId)
                put("subject", email.subject)
                put("from", email.from)
                put("to", email.to)
                put("date", email.date)
                put("date_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault()).format(Date(email.date)))
                put("snippet", email.snippet)
                put("body", email.body.take(10000)) // Limit body size in JSON

                val attachmentsArray = JSONArray()
                for (attachment in email.attachments) {
                    val attachmentJson = JSONObject().apply {
                        put("filename", attachment.filename)
                        put("mimeType", attachment.mimeType)
                        put("size", attachment.size)
                        // Include data only for PDFs and small files
                        if (attachment.data != null &&
                            (attachment.mimeType == "application/pdf" || attachment.size < 1024 * 1024)) {
                            put("data", attachment.data)
                        }
                    }
                    attachmentsArray.put(attachmentJson)
                }
                put("attachments", attachmentsArray)
            }
            jsonArray.put(emailJson)
        }
        return jsonArray
    }
}
