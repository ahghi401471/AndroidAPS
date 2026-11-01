package app.aaps.plugins.sync.tidepool.auth

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.net.toUri
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.crypto.CryptoUtil
import app.aaps.plugins.sync.tidepool.compose.TidepoolRepository
import app.aaps.plugins.sync.tidepool.keys.TidepoolBooleanKey
import app.aaps.plugins.sync.tidepool.keys.TidepoolStringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.browser.BrowserDescriptor
import net.openid.appauth.browser.BrowserMatcher
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Handler for Tidepool OpenID authentication.
 */
@SingleIn(AppScope::class)
@Inject
class AuthFlowOut(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val context: Context,
    private val cryptoUtil: CryptoUtil,
    private val tidepoolRepository: TidepoolRepository
) {

    companion object {

        private const val CLIENT_ID = "aaps"
        private const val REDIRECT_URI = "aaps://callback/tidepool"
        private const val INTEGRATION_BASE_URL =
            "https://auth.integration.tidepool.org/realms/integration"
        private const val PRODUCTION_BASE_URL =
            "https://auth.tidepool.org/realms/tidepool"

        private const val CUSTOM_BROWSER_PACKAGE = "com.tidbrowser"

        /**
         * Network, server and JSON errors can be transient.
         * Rejected credentials still require a new login.
         */
        fun isTransientTokenError(ex: AuthorizationException?): Boolean =
            ex != null &&
                ex.type == AuthorizationException.TYPE_GENERAL_ERROR &&
                (
                    ex.code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code ||
                        ex.code == AuthorizationException.GeneralErrors.SERVER_ERROR.code ||
                        ex.code == AuthorizationException.GeneralErrors.JSON_DESERIALIZATION_ERROR.code
                    )
    }

    private class PackageNameBrowserMatcher(
        private val packageName: String
    ) : BrowserMatcher {

        override fun matches(descriptor: BrowserDescriptor): Boolean =
            descriptor.packageName == packageName && descriptor.useCustomTab
    }

    /**
     * Created on first use to avoid inspecting Android browsers
     * while constructing the dependency graph.
     */
    val authService: AuthorizationService by lazy {
        AuthorizationService(
            context,
            AppAuthConfiguration.Builder()
                .setBrowserMatcher(
                    PackageNameBrowserMatcher(CUSTOM_BROWSER_PACKAGE)
                )
                .build()
        )
    }

    enum class ConnectionStatus {
        NONE,
        BLOCKED,
        NOT_LOGGED_IN,
        NO_SESSION,
        FETCHING_TOKEN,
        SESSION_ESTABLISHED,
        FAILED
    }

    var connectionStatus: ConnectionStatus = ConnectionStatus.NONE
        private set
        get() =
            if (field == ConnectionStatus.NONE) {
                if (hasSavedLogin) ConnectionStatus.NO_SESSION
                else ConnectionStatus.NOT_LOGGED_IN
            } else field

    var authState = AuthState()

    val hasSavedLogin: Boolean
        get() = authState.lastTokenResponse != null

    fun updateConnectionStatus(
        newStatus: ConnectionStatus? = null,
        message: String? = null
    ) {
        aapsLogger.debug(
            LTag.TIDEPOOL,
            "updateConnectionStatus: $newStatus $message"
        )
        newStatus?.let { connectionStatus = it }
        message?.let { tidepoolRepository.addLog(it) }
        tidepoolRepository.updateConnectionStatus(connectionStatus)
    }

    fun saveAuthState() {
        preferences.put(
            TidepoolStringNonKey.AuthState,
            authState.jsonSerializeString()
        )
    }

    fun eraseAuthState(message: String?) {
        preferences.put(TidepoolStringNonKey.AuthState, "")
        initAuthState()
        updateConnectionStatus(ConnectionStatus.NONE, message)
    }

    @Synchronized
    fun initAuthState() {
        authState = AuthState()

        try {
            val serviceConfig =
                preferences.get(TidepoolStringNonKey.ServiceConfiguration)

            if (serviceConfig.isNotEmpty()) {
                authState = AuthState(
                    AuthorizationServiceConfiguration.fromJson(serviceConfig)
                )
            }

            val savedAuthState =
                preferences.get(TidepoolStringNonKey.AuthState)

            if (savedAuthState.isNotEmpty()) {
                authState = AuthState.jsonDeserialize(savedAuthState)
            }
        } catch (e: Exception) {
            aapsLogger.error(
                LTag.TIDEPOOL,
                "Failed to restore auth state, resetting",
                e
            )
            authState = AuthState()
            preferences.put(TidepoolStringNonKey.AuthState, "")
            preferences.put(TidepoolStringNonKey.ServiceConfiguration, "")
        }

        // Do not log access or refresh tokens.
        aapsLogger.debug(
            LTag.TIDEPOOL,
            "Using auth state: authorized=${authState.isAuthorized} " +
                "needsTokenRefresh=${authState.needsTokenRefresh}"
        )
    }

    @Synchronized
    fun clearAllSavedData() {
        preferences.put(TidepoolStringNonKey.ServiceConfiguration, "")
        eraseAuthState("Credentials cleared")
    }

    fun doTidePoolInitialLogin(@Suppress("unused") from: String) {
        tidepoolRepository.addLog("Opening login screen")

        AuthorizationServiceConfiguration.fetchFromIssuer(
            if (preferences.get(TidepoolBooleanKey.UseTestServers)) {
                INTEGRATION_BASE_URL.toUri()
            } else {
                PRODUCTION_BASE_URL.toUri()
            },
            AuthorizationServiceConfiguration.RetrieveConfigurationCallback {
                    serviceConfiguration, exception ->

                if (exception != null || serviceConfiguration == null) {
                    tidepoolRepository.addLog(
                        "Failed to fetch configuration $exception"
                    )
                    return@RetrieveConfigurationCallback
                }

                preferences.put(
                    TidepoolStringNonKey.ServiceConfiguration,
                    serviceConfiguration.toJsonString()
                )
                initAuthState()

                val codeVerifierChallengeMethod = "S256"
                val encoding =
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                val challenge = cryptoUtil.getRandomKey(64)
                val codeVerifier =
                    Base64.encodeToString(challenge, encoding)
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(
                    codeVerifier.toByteArray(StandardCharsets.UTF_8)
                )
                val codeChallenge =
                    Base64.encodeToString(hash, encoding)

                val authRequest =
                    AuthorizationRequest.Builder(
                        serviceConfiguration,
                        CLIENT_ID,
                        ResponseTypeValues.CODE,
                        REDIRECT_URI.toUri()
                    )
                        .setScopes("openid")
                        .setCodeVerifier(
                            codeVerifier,
                            codeChallenge,
                            codeVerifierChallengeMethod
                        )
                        .setPrompt("login")
                        .build()

                try {
                    authService.performAuthorizationRequest(
                        authRequest,
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, AuthFlowIn::class.java),
                            PendingIntent.FLAG_MUTABLE
                        ),
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, AuthFlowIn::class.java),
                            PendingIntent.FLAG_MUTABLE
                        )
                    )
                } catch (e: ActivityNotFoundException) {
                    aapsLogger.error(
                        LTag.TIDEPOOL,
                        "Tidepool Browser is not available for Tidepool login",
                        e
                    )
                    tidepoolRepository.addLog(
                        "Tidepool Browser is not available for Tidepool login."
                    )
                }
            }
        )
    }
}
