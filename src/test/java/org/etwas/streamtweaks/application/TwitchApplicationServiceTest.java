package org.etwas.streamtweaks.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.etwas.streamtweaks.twitch.auth.AuthenticationOrchestrator;
import org.etwas.streamtweaks.twitch.auth.AuthenticationResult;
import org.etwas.streamtweaks.twitch.badge.BadgeCatalogRepository;
import org.etwas.streamtweaks.twitch.core.Login;
import org.etwas.streamtweaks.twitch.core.TwitchApiClient;
import org.etwas.streamtweaks.twitch.core.UserId;
import org.etwas.streamtweaks.twitch.subscription.EventSubOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TwitchApplicationServiceTest {

    @Mock
    AuthenticationOrchestrator authService;

    @Mock
    EventSubOrchestrator subscriptionService;

    @Mock
    TwitchApiClient apiClient;

    @Mock
    BadgeCatalogRepository badgeCatalogRepository;

    TwitchApplicationService service;

    @BeforeEach
    void setUp() {
        service = new TwitchApplicationService(authService, subscriptionService, apiClient, badgeCatalogRepository);
    }

    // --- login ---

    @Test
    void login_delegatesToOrchestrator() {
        when(authService.startAuthentication(any()))
                .thenReturn(CompletableFuture.completedFuture(AuthenticationResult.SUCCESS));

        var result = service.login(uri -> {}).join();

        assertEquals(AuthenticationResult.SUCCESS, result);
        verify(authService).startAuthentication(any());
    }

    // --- isAuthenticated / getAuthenticatedLogin ---

    @Test
    void isAuthenticated_delegatesToAuthService() {
        when(authService.hasCredential()).thenReturn(true);

        assertTrue(service.isAuthenticated());
    }

    @Test
    void isAuthenticated_whenAuthServiceReturnsFalse_returnsFalse() {
        when(authService.hasCredential()).thenReturn(false);

        assertFalse(service.isAuthenticated());
    }

    @Test
    void getAuthenticatedLogin_whenPresent_returnsValue() {
        when(authService.getAuthenticatedDisplayName()).thenReturn(Optional.of("testuser"));

        assertEquals("testuser", service.getAuthenticatedLogin());
    }

    @Test
    void getAuthenticatedLogin_whenEmpty_returnsNull() {
        when(authService.getAuthenticatedDisplayName()).thenReturn(Optional.empty());

        assertNull(service.getAuthenticatedLogin());
    }

    // --- connect ---

    @Test
    void connect_callsGetUserIdThenSubscribe() {
        when(apiClient.getUserId(new Login("streamer")))
                .thenReturn(CompletableFuture.completedFuture(new UserId("broadcaster1")));
        when(subscriptionService.subscribe(new UserId("broadcaster1")))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.connect(new Login("streamer")).join();

        verify(apiClient).getUserId(new Login("streamer"));
        verify(subscriptionService).subscribe(new UserId("broadcaster1"));
    }

    @Test
    void connect_triggersBestEffortBadgeCatalogLoad() {
        when(apiClient.getUserId(new Login("streamer")))
                .thenReturn(CompletableFuture.completedFuture(new UserId("broadcaster1")));
        when(subscriptionService.subscribe(new UserId("broadcaster1")))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.connect(new Login("streamer")).join();

        verify(badgeCatalogRepository).ensureLoaded(new UserId("broadcaster1"));
    }

    @Test
    void connect_whenGetUserIdFails_doesNotTriggerBadgeCatalogLoad() {
        when(apiClient.getUserId(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("user not found")));

        var result = service.connect(new Login("unknown"));

        assertThrows(Exception.class, result::join);
        verify(badgeCatalogRepository, never()).ensureLoaded(any());
    }

    @Test
    void connect_whenGetUserIdFails_propagatesException() {
        when(apiClient.getUserId(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("user not found")));

        var result = service.connect(new Login("unknown"));

        assertThrows(Exception.class, result::join);
        verify(subscriptionService, never()).subscribe(any());
    }

    @Test
    void connect_whenSubscribeFails_propagatesException() {
        when(apiClient.getUserId(new Login("streamer")))
                .thenReturn(CompletableFuture.completedFuture(new UserId("broadcaster1")));
        when(subscriptionService.subscribe(new UserId("broadcaster1")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("subscribe failed")));

        var result = service.connect(new Login("streamer"));

        assertThrows(Exception.class, result::join);
    }

    // --- disconnect ---

    @Test
    void disconnect_callsGetUserIdThenUnsubscribe() {
        when(apiClient.getUserId(new Login("streamer")))
                .thenReturn(CompletableFuture.completedFuture(new UserId("broadcaster1")));
        when(subscriptionService.unsubscribe(new UserId("broadcaster1")))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.disconnect(new Login("streamer")).join();

        verify(apiClient).getUserId(new Login("streamer"));
        verify(subscriptionService).unsubscribe(new UserId("broadcaster1"));
    }

    @Test
    void disconnect_whenGetUserIdFails_propagatesException() {
        when(apiClient.getUserId(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("user not found")));

        var result = service.disconnect(new Login("unknown"));

        assertThrows(Exception.class, result::join);
        verify(subscriptionService, never()).unsubscribe(any());
    }

    // --- logout ---

    @Test
    void logout_callsUnsubscribeAllThenCloseThenAuthServiceLogout() {
        when(subscriptionService.unsubscribeAll()).thenReturn(CompletableFuture.completedFuture(null));

        service.logout().join();

        InOrder inOrder = Mockito.inOrder(subscriptionService, authService);
        inOrder.verify(subscriptionService).unsubscribeAll();
        inOrder.verify(subscriptionService).close();
        inOrder.verify(authService).logout();
    }

    @Test
    void logout_clearsBadgeCatalogCache() {
        when(subscriptionService.unsubscribeAll()).thenReturn(CompletableFuture.completedFuture(null));

        service.logout().join();

        verify(badgeCatalogRepository).clearAll();
    }

    @Test
    void logout_whenUnsubscribeAllFails_doesNotCloseOrLogout() {
        when(subscriptionService.unsubscribeAll())
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disconnect error")));

        var result = service.logout();

        assertThrows(Exception.class, result::join);
        verify(subscriptionService, never()).close();
        verify(authService, never()).logout();
        verify(badgeCatalogRepository, never()).clearAll();
    }

    @Test
    void logout_calledTwice_closesAndLogsOutOnlyOncePerCall() {
        when(subscriptionService.unsubscribeAll()).thenReturn(CompletableFuture.completedFuture(null));

        service.logout().join();
        service.logout().join();

        verify(subscriptionService, times(2)).unsubscribeAll();
        verify(subscriptionService, times(2)).close();
        verify(authService, times(2)).logout();
    }

    // --- shutdown ---

    @Test
    void shutdown_releasesRuntimeResourcesWithoutDeletingCredential() {
        service.shutdown();

        verify(subscriptionService).shutdown();
        verify(authService).shutdown();
        verify(authService, never()).logout();
        verify(subscriptionService, never()).unsubscribeAll();
    }

    // --- suggestOwnChannelLogin ---

    @Test
    void suggestOwnChannelLogin_whenNotAuthenticated_returnsEmpty() {
        when(authService.getAuthenticatedDisplayName()).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), service.suggestOwnChannelLogin());
    }

    @Test
    void suggestOwnChannelLogin_whenAuthenticatedButNotConnected_returnsOwnLogin() {
        when(authService.getAuthenticatedDisplayName()).thenReturn(Optional.of("testuser"));
        when(apiClient.getUserId(new Login("otherchannel")))
                .thenReturn(CompletableFuture.completedFuture(new UserId("broadcaster1")));
        when(subscriptionService.subscribe(new UserId("broadcaster1")))
                .thenReturn(CompletableFuture.completedFuture(null));
        service.connect(new Login("otherchannel")).join();

        assertEquals(Optional.of("testuser"), service.suggestOwnChannelLogin());
    }

    @Test
    void suggestOwnChannelLogin_whenAuthenticatedAndAlreadyConnected_returnsEmpty() {
        when(authService.getAuthenticatedDisplayName()).thenReturn(Optional.of("testuser"));
        when(apiClient.getUserId(new Login("testuser")))
                .thenReturn(CompletableFuture.completedFuture(new UserId("broadcaster1")));
        when(subscriptionService.subscribe(new UserId("broadcaster1")))
                .thenReturn(CompletableFuture.completedFuture(null));
        service.connect(new Login("testuser")).join();

        assertEquals(Optional.empty(), service.suggestOwnChannelLogin());
    }

    @Test
    void suggestOwnChannelLogin_whenAuthenticatedAndAlreadyConnectedWithDifferentCase_returnsEmpty() {
        when(authService.getAuthenticatedDisplayName()).thenReturn(Optional.of("TestUser"));
        when(apiClient.getUserId(new Login("testuser")))
                .thenReturn(CompletableFuture.completedFuture(new UserId("broadcaster1")));
        when(subscriptionService.subscribe(new UserId("broadcaster1")))
                .thenReturn(CompletableFuture.completedFuture(null));
        service.connect(new Login("testuser")).join();

        assertEquals(Optional.empty(), service.suggestOwnChannelLogin());
    }

    @Test
    void suggestOwnChannelLogin_loadsCredentialOnlyOnce() {
        when(authService.getAuthenticatedDisplayName()).thenReturn(Optional.of("testuser"));

        service.suggestOwnChannelLogin();

        verify(authService, times(1)).getAuthenticatedDisplayName();
    }
}
