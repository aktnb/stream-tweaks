package org.etwas.streamtweaks.twitch.auth;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class AuthenticationOrchestrator {
    private static final int AuthenticationTimeoutSeconds = 100;

    private final CallbackServer callbackServer;
    private final TwitchCredentialRepository credentialRepository;
    private final TokenValidator tokenValidator;

    private volatile AuthenticationState state = AuthenticationState.IDLE;
    private AuthenticationSession currentSession;
    private static final List<String> SCOPES = List.of("user:read:chat");

    public AuthenticationOrchestrator(
            CallbackServer callbackServer,
            TwitchCredentialRepository credentialRepository,
            TokenValidator tokenValidator) {
        this.callbackServer = callbackServer;
        this.credentialRepository = credentialRepository;
        this.tokenValidator = tokenValidator;
    }

    public void logout() {
        callbackServer.stop();
        credentialRepository.deleteCredential();
        state = AuthenticationState.IDLE;
    }

    /** Minecraft終了時に、進行中の認証用ローカルサーバーだけを停止する。 */
    public void shutdown() {
        callbackServer.stop();
        state = AuthenticationState.IDLE;
    }

    /**
     * 認証情報（credential）を保持しているかを問い合わせる。ローカルの軽量チェックであり、トークンの実際の有効性（失効等）は保証しない。
     */
    public boolean hasCredential() {
        var credential = credentialRepository.loadCredential();
        return credential != null && credential.userId() != null;
    }

    /**
     * 認証済みユーザーのログイン名を返す。未認証なら empty。
     */
    public Optional<String> getAuthenticatedDisplayName() {
        var credential = credentialRepository.loadCredential();
        if (credential == null || credential.login() == null) {
            return Optional.empty();
        }
        return Optional.of(credential.login().value());
    }

    /**
     * 認証フローを開始する。認可URLの提示方法は呼び出し元に委ねる。
     */
    public CompletableFuture<AuthenticationResult> startAuthentication(Consumer<URI> onAuthUrlReady) {
        synchronized (this) {
            if (state == AuthenticationState.WAITING_FOR_CALLBACK) {
                return CompletableFuture.completedFuture(AuthenticationResult.ALREADY_IN_PROGRESS);
            }
            state = AuthenticationState.WAITING_FOR_CALLBACK;
        }

        currentSession = AuthenticationSession.create(SCOPES);
        callbackServer.start();
        onAuthUrlReady.accept(currentSession.getAuthorizationUrl());

        return callbackServer
                .onCallback()
                .orTimeout(AuthenticationTimeoutSeconds, TimeUnit.SECONDS)
                .thenApply(callback -> {
                    try {
                        currentSession.verifyState(callback.state());

                        TokenValidationResult validationResult = tokenValidator.validateToken(callback.token(), SCOPES);
                        if (!validationResult.isValid()) {
                            state = AuthenticationState.FAILURE;
                            return AuthenticationResult.FAILURE;
                        }

                        credentialRepository.saveCredential(new TwitchCredential(
                                callback.token(), SCOPES, validationResult.userId(), validationResult.login()));
                        state = AuthenticationState.AUTHENTICATED;

                        return AuthenticationResult.SUCCESS;
                    } catch (IllegalStateException e) {
                        state = AuthenticationState.CANCELLED;
                        return AuthenticationResult.FAILURE;
                    } finally {
                        callbackServer.stop();
                    }
                })
                .exceptionally(ex -> {
                    state = AuthenticationState.TIMEOUT;
                    callbackServer.stop();
                    return AuthenticationResult.TIMEOUT;
                });
    }
}
