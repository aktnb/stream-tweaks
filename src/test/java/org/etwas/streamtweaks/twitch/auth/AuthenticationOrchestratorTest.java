package org.etwas.streamtweaks.twitch.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthenticationOrchestratorTest {

    @Mock
    CallbackServer callbackServer;

    @Mock
    TwitchCredentialRepository credentialRepository;

    @Mock
    TokenValidator tokenValidator;

    AuthenticationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AuthenticationOrchestrator(callbackServer, credentialRepository, tokenValidator);
    }

    @Test
    void startAuthentication_onSuccess_savesCredentialAndReturnsSuccess() {
        CompletableFuture<OAuthCallback> callbackFuture = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture);
        when(tokenValidator.validateToken(any(), any()))
                .thenReturn(TokenValidationResult.valid(new UserId("user123"), new Login("testuser")));

        CompletableFuture<AuthenticationResult> result = orchestrator.startAuthentication(uri -> {
            callbackFuture.complete(new OAuthCallback(new AccessToken("token123"), extractState(uri)));
        });

        assertEquals(AuthenticationResult.SUCCESS, result.join());
        verify(credentialRepository)
                .saveCredential(argThat(c -> c.accessToken().equals(new AccessToken("token123"))
                        && c.userId().equals(new UserId("user123"))
                        && c.login().equals(new Login("testuser"))));
        verify(callbackServer).stop();
    }

    @Test
    void startAuthentication_whenAlreadyInProgress_returnsAlreadyInProgress() {
        when(callbackServer.onCallback()).thenReturn(new CompletableFuture<>());

        orchestrator.startAuthentication(uri -> {});
        CompletableFuture<AuthenticationResult> second = orchestrator.startAuthentication(uri -> {});

        assertEquals(AuthenticationResult.ALREADY_IN_PROGRESS, second.join());
    }

    @Test
    void startAuthentication_whenTokenValidationFails_returnsFailure() {
        CompletableFuture<OAuthCallback> callbackFuture = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture);
        when(tokenValidator.validateToken(any(), any())).thenReturn(TokenValidationResult.INVALID);

        CompletableFuture<AuthenticationResult> result = orchestrator.startAuthentication(uri -> {
            callbackFuture.complete(new OAuthCallback(new AccessToken("bad_token"), extractState(uri)));
        });

        assertEquals(AuthenticationResult.FAILURE, result.join());
        verify(credentialRepository, never()).saveCredential(any());
        verify(callbackServer).stop();
    }

    @Test
    void startAuthentication_whenStateMismatch_returnsFailure() {
        CompletableFuture<OAuthCallback> callbackFuture = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture);

        CompletableFuture<AuthenticationResult> result = orchestrator.startAuthentication(uri -> {
            callbackFuture.complete(new OAuthCallback(new AccessToken("token123"), "WRONG_STATE"));
        });

        assertEquals(AuthenticationResult.FAILURE, result.join());
        verify(credentialRepository, never()).saveCredential(any());
        verify(callbackServer).stop();
    }

    @Test
    void startAuthentication_whenCallbackErrors_returnsTimeout() {
        CompletableFuture<OAuthCallback> callbackFuture = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture);

        CompletableFuture<AuthenticationResult> result = orchestrator.startAuthentication(uri -> {
            callbackFuture.completeExceptionally(new TimeoutException("timed out"));
        });

        assertEquals(AuthenticationResult.TIMEOUT, result.join());
        verify(callbackServer).stop();
    }

    @Test
    void logout_deletesCredentialAndAllowsReAuthentication() {
        orchestrator.logout();
        verify(callbackServer).stop();
        verify(credentialRepository).deleteCredential();

        CompletableFuture<OAuthCallback> callbackFuture = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture);
        when(tokenValidator.validateToken(any(), any()))
                .thenReturn(TokenValidationResult.valid(new UserId("user123"), new Login("testuser")));

        CompletableFuture<AuthenticationResult> result = orchestrator.startAuthentication(uri -> {
            callbackFuture.complete(new OAuthCallback(new AccessToken("token123"), extractState(uri)));
        });

        assertEquals(AuthenticationResult.SUCCESS, result.join());
    }

    @Test
    void shutdown_stopsCallbackServerWithoutDeletingCredential() {
        orchestrator.shutdown();

        verify(callbackServer).stop();
        verify(credentialRepository, never()).deleteCredential();
    }

    @Test
    void startAuthentication_afterFailure_canRetry() {
        CompletableFuture<OAuthCallback> callbackFuture1 = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture1);
        when(tokenValidator.validateToken(any(), any())).thenReturn(TokenValidationResult.INVALID);

        CompletableFuture<AuthenticationResult> first = orchestrator.startAuthentication(uri -> {
            callbackFuture1.complete(new OAuthCallback(new AccessToken("bad_token"), extractState(uri)));
        });
        assertEquals(AuthenticationResult.FAILURE, first.join());

        CompletableFuture<OAuthCallback> callbackFuture2 = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture2);
        when(tokenValidator.validateToken(any(), any()))
                .thenReturn(TokenValidationResult.valid(new UserId("user123"), new Login("testuser")));

        CompletableFuture<AuthenticationResult> second = orchestrator.startAuthentication(uri -> {
            callbackFuture2.complete(new OAuthCallback(new AccessToken("good_token"), extractState(uri)));
        });
        assertEquals(AuthenticationResult.SUCCESS, second.join());
    }

    @Test
    void startAuthentication_afterTimeout_canRetry() {
        CompletableFuture<OAuthCallback> callbackFuture1 = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture1);

        CompletableFuture<AuthenticationResult> first = orchestrator.startAuthentication(uri -> {
            callbackFuture1.completeExceptionally(new java.util.concurrent.TimeoutException("timed out"));
        });
        assertEquals(AuthenticationResult.TIMEOUT, first.join());

        CompletableFuture<OAuthCallback> callbackFuture2 = new CompletableFuture<>();
        when(callbackServer.onCallback()).thenReturn(callbackFuture2);
        when(tokenValidator.validateToken(any(), any()))
                .thenReturn(TokenValidationResult.valid(new UserId("user123"), new Login("testuser")));

        CompletableFuture<AuthenticationResult> second = orchestrator.startAuthentication(uri -> {
            callbackFuture2.complete(new OAuthCallback(new AccessToken("good_token"), extractState(uri)));
        });
        assertEquals(AuthenticationResult.SUCCESS, second.join());
    }

    @Test
    void independentInstances_haveIndependentState() {
        CallbackServer callbackServer2 = mock(CallbackServer.class);
        AuthenticationOrchestrator orchestrator2 =
                new AuthenticationOrchestrator(callbackServer2, credentialRepository, tokenValidator);

        when(callbackServer.onCallback()).thenReturn(new CompletableFuture<>());
        orchestrator.startAuthentication(uri -> {});

        CompletableFuture<OAuthCallback> callbackFuture2 = new CompletableFuture<>();
        when(callbackServer2.onCallback()).thenReturn(callbackFuture2);
        when(tokenValidator.validateToken(any(), any()))
                .thenReturn(TokenValidationResult.valid(new UserId("user123"), new Login("testuser")));

        CompletableFuture<AuthenticationResult> result = orchestrator2.startAuthentication(uri -> {
            callbackFuture2.complete(new OAuthCallback(new AccessToken("token123"), extractState(uri)));
        });

        assertEquals(AuthenticationResult.SUCCESS, result.join());
    }

    // --- hasCredential ---

    @Test
    void hasCredential_whenCredentialNull_returnsFalse() {
        when(credentialRepository.loadCredential()).thenReturn(null);

        assertEquals(false, orchestrator.hasCredential());
    }

    @Test
    void hasCredential_whenUserIdNull_returnsFalse() {
        when(credentialRepository.loadCredential())
                .thenReturn(new TwitchCredential(
                        new AccessToken("token"), java.util.List.of(), null, new Login("testuser")));

        assertEquals(false, orchestrator.hasCredential());
    }

    @Test
    void hasCredential_whenCredentialExists_returnsTrue() {
        when(credentialRepository.loadCredential())
                .thenReturn(new TwitchCredential(
                        new AccessToken("token"), java.util.List.of(), new UserId("user123"), new Login("testuser")));

        assertEquals(true, orchestrator.hasCredential());
    }

    // --- getAuthenticatedDisplayName ---

    @Test
    void getAuthenticatedDisplayName_whenCredentialNull_returnsEmpty() {
        when(credentialRepository.loadCredential()).thenReturn(null);

        assertEquals(Optional.empty(), orchestrator.getAuthenticatedDisplayName());
    }

    @Test
    void getAuthenticatedDisplayName_whenLoginNull_returnsEmpty() {
        when(credentialRepository.loadCredential())
                .thenReturn(new TwitchCredential(
                        new AccessToken("token"), java.util.List.of(), new UserId("user123"), null));

        assertEquals(Optional.empty(), orchestrator.getAuthenticatedDisplayName());
    }

    @Test
    void getAuthenticatedDisplayName_whenCredentialExists_returnsLoginValue() {
        when(credentialRepository.loadCredential())
                .thenReturn(new TwitchCredential(
                        new AccessToken("token"), java.util.List.of(), new UserId("user123"), new Login("testuser")));

        assertEquals(Optional.of("testuser"), orchestrator.getAuthenticatedDisplayName());
    }

    private String extractState(URI uri) {
        for (String param : uri.getQuery().split("&")) {
            if (param.startsWith("state=")) {
                return param.substring("state=".length());
            }
        }
        throw new IllegalArgumentException("No state param in: " + uri);
    }
}
