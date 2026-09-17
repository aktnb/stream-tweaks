package org.etwas.streamtweaks.application;

import java.net.URI;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.etwas.streamtweaks.twitch.auth.AuthenticationOrchestrator;
import org.etwas.streamtweaks.twitch.auth.AuthenticationResult;
import org.etwas.streamtweaks.twitch.badge.BadgeCatalogRepository;
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.TwitchApiClient;
import org.etwas.streamtweaks.twitch.subscription.EventSubOrchestrator;

public final class TwitchApplicationService {
    private final AuthenticationOrchestrator authService;
    private final EventSubOrchestrator subscriptionService;
    private final TwitchApiClient apiClient;
    private final BadgeCatalogRepository badgeCatalogRepository;
    private final Set<Login> subscribedLogins = new CopyOnWriteArraySet<>();

    public TwitchApplicationService(
            AuthenticationOrchestrator authService,
            EventSubOrchestrator subscriptionService,
            TwitchApiClient apiClient,
            BadgeCatalogRepository badgeCatalogRepository) {
        this.authService = authService;
        this.subscriptionService = subscriptionService;
        this.apiClient = apiClient;
        this.badgeCatalogRepository = badgeCatalogRepository;
    }

    public CompletableFuture<AuthenticationResult> login(Consumer<URI> onUriReady) {
        return authService.startAuthentication(onUriReady);
    }

    public boolean isAuthenticated() {
        return authService.hasCredential();
    }

    /**
     * Returns the login name of the authenticated user, or null if not authenticated.
     */
    public String getAuthenticatedLogin() {
        return authService.getAuthenticatedDisplayName().orElse(null);
    }

    public CompletableFuture<Void> connect(Login login) {
        return apiClient
                .getUserId(login)
                .thenCompose(broadcasterId -> {
                    // バッジカタログの取得はベストエフォート・fire-and-forget。
                    // 接続のクリティカルパスには乗せず、失敗してもconnect()自体は成功として扱う
                    // （BadgeCatalogRepository内部で例外を握りつぶし正常完了するため、ここでは
                    // 戻り値を待ち合わせない）。
                    badgeCatalogRepository.ensureLoaded(broadcasterId);
                    return subscriptionService.subscribe(broadcasterId);
                })
                .thenApply(v -> {
                    subscribedLogins.add(login);
                    return v;
                });
    }

    public CompletableFuture<Void> disconnect(Login login) {
        return apiClient
                .getUserId(login)
                .thenCompose(subscriptionService::unsubscribe)
                .thenApply(v -> {
                    subscribedLogins.remove(login);
                    return v;
                });
    }

    public CompletableFuture<Void> logout() {
        return subscriptionService.unsubscribeAll().thenRun(() -> {
            subscribedLogins.clear();
            subscriptionService.close();
            authService.logout();
            // Twitchから完全に手を引く操作なので、チャンネル単位のバッジキャッシュも
            // ここで全クリアする（disconnect()単体では破棄しない。再接続時の再取得コストを避けるため）。
            badgeCatalogRepository.clearAll();
        });
    }

    /** Minecraft終了時に、資格情報を残したままModが保持するバックグラウンドリソースを破棄する。 */
    public void shutdown() {
        subscribedLogins.clear();
        subscriptionService.shutdown();
        authService.shutdown();
        badgeCatalogRepository.clearAll();
    }

    public Set<Login> getSubscribedLogins() {
        return Collections.unmodifiableSet(subscribedLogins);
    }

    /**
     * 自分のチャンネルへの接続をサジェストすべきかどうかを、認証情報の読み込み1回だけで判定する。
     *
     * <p>未認証、または認証済みだが既に自分のチャンネルへ接続済みの場合は {@link Optional#empty()} を返す。
     * 認証済みかつ未接続の場合のみ、自分のログイン名を返す。呼び出し側で {@code isAuthenticated()} /
     * {@code getAuthenticatedLogin()} を個別に呼び出すと、そのたびに認証情報ファイルへのディスクI/Oが発生するため、
     * このメソッドで判定を完結させることでI/O回数を1回に抑える。
     */
    public Optional<String> suggestOwnChannelLogin() {
        String authenticatedLogin = getAuthenticatedLogin();
        if (authenticatedLogin == null) {
            return Optional.empty();
        }
        boolean alreadyConnected =
                subscribedLogins.stream().anyMatch(login -> login.value().equalsIgnoreCase(authenticatedLogin));
        return alreadyConnected ? Optional.empty() : Optional.of(authenticatedLogin);
    }
}
