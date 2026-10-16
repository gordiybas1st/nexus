package com.example.assistant.data.remote

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.assistant.BuildConfig
import com.example.assistant.data.prefs.UserPreferences
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when calendar access must be (re)granted by the user in the UI. */
class AuthRequiredException(message: String = "Google Calendar access must be granted again") : Exception(message)

/** Result of [GoogleAuthManager.signIn]. */
sealed interface SignInResult {
    /** Signed in; [email] may be null when only authorization will provide it. */
    data class Success(val email: String?, val displayName: String?) : SignInResult
    data object Cancelled : SignInResult
    data object NoAccount : SignInResult
    data class Failure(val message: String) : SignInResult
}

/** Result of [GoogleAuthManager.requestCalendarAccess]. */
sealed interface CalendarAccessResult {
    data class Granted(val email: String?) : CalendarAccessResult

    /** The user must confirm access: launch [pendingIntent] and pass the result to [GoogleAuthManager.handleConsentResult]. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : CalendarAccessResult
    data class Failure(val message: String) : CalendarAccessResult
}

/**
 * Google sign-in and Calendar authorization.
 *
 * 1. Sign-in uses Credential Manager ([GetGoogleIdOption]) and yields an ID token + email.
 *    It needs the *Web* OAuth client id ([BuildConfig.GOOGLE_WEB_CLIENT_ID]); when that is not
 *    configured, sign-in is skipped and the account is chosen in the authorization step.
 * 2. Calendar access uses [com.google.android.gms.auth.api.identity.AuthorizationClient], which
 *    returns an OAuth **access token** (an ID token does not work for the Calendar API).
 *
 * Refresh: on Android the access token is renewed by calling `authorize()` again. Once the user
 * has granted the scope it returns a fresh token silently, so no refresh token (which would need
 * a client secret on a server) is stored on the device.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferences,
) {
    private val authorizationClient get() = Identity.getAuthorizationClient(context)

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedTokenExpiresAt: Long = 0L

    /** True when a Web client id is configured and Credential Manager sign-in can be used. */
    val isCredentialSignInConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** Shows the Google account sheet (Credential Manager). Must be called with an Activity. */
    suspend fun signIn(activity: Activity): SignInResult {
        if (!isCredentialSignInConfigured) return SignInResult.Success(email = null, displayName = null)
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                SignInResult.Success(email = google.id, displayName = google.displayName)
            } else {
                SignInResult.Failure("Unexpected credential type: ${credential.type}")
            }
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: NoCredentialException) {
            SignInResult.NoAccount
        } catch (e: GetCredentialException) {
            SignInResult.Failure(e.message ?: e.type)
        } catch (e: GoogleIdTokenParsingException) {
            SignInResult.Failure(e.message ?: "Invalid Google ID token")
        }
    }

    /**
     * Asks for the Calendar scope. Returns [CalendarAccessResult.NeedsConsent] when Google
     * must show a consent screen; the UI launches it and calls [handleConsentResult].
     */
    suspend fun requestCalendarAccess(email: String? = null): CalendarAccessResult = try {
        val result = authorizationClient.authorize(buildRequest(email)).await()
        val pendingIntent = result.pendingIntent
        if (result.hasResolution() && pendingIntent != null) {
            CalendarAccessResult.NeedsConsent(pendingIntent)
        } else {
            remember(result)
            CalendarAccessResult.Granted(result.email() ?: email)
        }
    } catch (e: ApiException) {
        CalendarAccessResult.Failure(describe(e))
    } catch (e: Exception) {
        CalendarAccessResult.Failure(e.message ?: e.javaClass.simpleName)
    }

    /** Completes the consent flow started from [CalendarAccessResult.NeedsConsent]. */
    fun handleConsentResult(data: Intent?): CalendarAccessResult = try {
        val result = authorizationClient.getAuthorizationResultFromIntent(data)
        if (result.accessToken.isNullOrBlank()) {
            CalendarAccessResult.Failure("No access token returned")
        } else {
            remember(result)
            CalendarAccessResult.Granted(result.email())
        }
    } catch (e: ApiException) {
        CalendarAccessResult.Failure(describe(e))
    }

    /**
     * Returns a usable access token, silently renewing it when needed.
     *
     * @throws AuthRequiredException when the user has to grant access again in the UI.
     */
    suspend fun getValidAccessToken(): String {
        val token = cachedToken
        if (token != null && System.currentTimeMillis() < cachedTokenExpiresAt) return token

        val email = preferences.syncSettings.first().accountEmail
        val result = try {
            authorizationClient.authorize(buildRequest(email)).await()
        } catch (e: ApiException) {
            throw AuthRequiredException(describe(e))
        }
        if (result.hasResolution()) throw AuthRequiredException()
        remember(result)
        return result.accessToken ?: throw AuthRequiredException("No access token returned")
    }

    /** Drops a token rejected by the API (HTTP 401) so the next call fetches a new one. */
    suspend fun invalidateToken(token: String) {
        if (cachedToken == token) {
            cachedToken = null
            cachedTokenExpiresAt = 0L
        }
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, token) } }
    }

    /** True if an account is connected (email known locally). */
    suspend fun isSignedIn(): Boolean = preferences.syncSettings.first().accountEmail != null

    /** Forgets the account locally and clears cached Google credentials. */
    suspend fun signOut() {
        cachedToken?.let { invalidateToken(it) }
        cachedToken = null
        cachedTokenExpiresAt = 0L
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        preferences.clearAccount()
    }

    private fun buildRequest(email: String?): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(CALENDAR_SCOPE)))
        if (!email.isNullOrBlank()) builder.setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
        return builder.build()
    }

    private fun remember(result: AuthorizationResult) {
        val token = result.accessToken ?: return
        cachedToken = token
        cachedTokenExpiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MS
    }

    @Suppress("DEPRECATION")
    private fun AuthorizationResult.email(): String? = toGoogleSignInAccount()?.email

    private fun describe(e: ApiException): String = "Google API error ${e.statusCode}: ${e.message.orEmpty()}"

    companion object {
        /** Read/write access to events. Use `calendar.app.created` to avoid Google verification. */
        const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"

        /** Tokens live 60 min; renew a bit earlier. */
        private const val TOKEN_LIFETIME_MS = 50 * 60 * 1000L
    }
}

/** Suspends until a Play Services [Task] completes. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
