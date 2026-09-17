package org.etwas.streamtweaks.presentation.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.concurrent.Executor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.etwas.streamtweaks.application.TwitchApplicationService;
import org.etwas.streamtweaks.mod.StreamTweaksConfigScreen;
import org.etwas.streamtweaks.twitch.auth.AuthenticationResult;
import org.etwas.streamtweaks.twitch.core.Login;

public final class TwitchCommand<S> {
    private final TwitchApplicationService applicationService;
    private final Executor clientExecutor;

    public TwitchCommand(TwitchApplicationService applicationService, Executor clientExecutor) {
        this.applicationService = applicationService;
        this.clientExecutor = clientExecutor;
    }

    public LiteralArgumentBuilder<S> build(FeedbackSenderFactory<S> feedbackSenderFactory) {
        return LiteralArgumentBuilder.<S>literal("twitch")
                .then(LiteralArgumentBuilder.<S>literal("login")
                        .executes(ctx -> handleLogin(ctx, feedbackSenderFactory)))
                .then(LiteralArgumentBuilder.<S>literal("connect")
                        .then(RequiredArgumentBuilder.<S, String>argument("login", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    applicationService.suggestOwnChannelLogin().ifPresent(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> handleConnect(ctx, feedbackSenderFactory))))
                .then(LiteralArgumentBuilder.<S>literal("disconnect")
                        .then(RequiredArgumentBuilder.<S, String>argument("login", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    applicationService
                                            .getSubscribedLogins()
                                            .forEach(login -> builder.suggest(login.value()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> handleDisconnect(ctx, feedbackSenderFactory))))
                .then(LiteralArgumentBuilder.<S>literal("logout")
                        .executes(ctx -> handleLogout(ctx, feedbackSenderFactory)))
                .then(LiteralArgumentBuilder.<S>literal("config").executes(this::handleConfig));
    }

    private int handleConfig(CommandContext<S> ctx) {
        // ChatScreen はコマンド実行直後に自身を setScreen(null) で閉じるため、
        // ここで同期的に setScreen すると閉じる処理で上書きされてしまう。
        // clientExecutor で次の実行キューに回し、閉じる処理が終わってから画面を開く。
        clientExecutor.execute(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new StreamTweaksConfigScreen(minecraft.gui.screen()));
        });
        return 1;
    }

    private int handleLogin(CommandContext<S> ctx, FeedbackSenderFactory<S> feedbackSenderFactory) {
        FeedbackSender sender = feedbackSenderFactory.create(ctx.getSource());
        applicationService
                .login(uri -> {
                    var feedback = Component.translatable(
                            "message.stream-tweaks.promptAuthentication",
                            Component.translatable("message.stream-tweaks.here")
                                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                            .withUnderlined(true)
                                            .withClickEvent(new ClickEvent.OpenUrl(uri))
                                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable(
                                                            "message.stream-tweaks.authenticationLinkHover")
                                                    .withStyle(ChatFormatting.GRAY)))));
                    sendFeedback(sender, feedback);
                })
                .thenAccept(result -> {
                    if (result == AuthenticationResult.SUCCESS) {
                        sendFeedback(
                                sender,
                                Component.translatable("message.stream-tweaks.authenticationSuccess")
                                        .withStyle(ChatFormatting.GREEN));
                    }
                })
                .exceptionally(ex -> {
                    sendFeedback(
                            sender,
                            Component.translatable("message.stream-tweaks.authenticationError", rootMessage(ex))
                                    .withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    private int handleConnect(CommandContext<S> ctx, FeedbackSenderFactory<S> feedbackSenderFactory) {
        FeedbackSender sender = feedbackSenderFactory.create(ctx.getSource());
        Login login = new Login(StringArgumentType.getString(ctx, "login"));

        if (!applicationService.isAuthenticated()) {
            sendFeedback(
                    sender,
                    Component.translatable("message.stream-tweaks.notAuthenticated")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        sendFeedback(
                sender,
                Component.translatable("message.stream-tweaks.connecting", login.value())
                        .withStyle(ChatFormatting.YELLOW));

        applicationService
                .connect(login)
                .thenRun(() -> {
                    var feedback = Component.translatable("message.stream-tweaks.connected", login.value())
                            .withStyle(ChatFormatting.GREEN);
                    sendFeedback(sender, feedback);
                })
                .exceptionally(ex -> {
                    var feedback = Component.translatable(
                                    "message.stream-tweaks.connectError", login.value(), rootMessage(ex))
                            .withStyle(ChatFormatting.RED);
                    sendFeedback(sender, feedback);
                    return null;
                });

        return 1;
    }

    private int handleLogout(CommandContext<S> ctx, FeedbackSenderFactory<S> feedbackSenderFactory) {
        FeedbackSender sender = feedbackSenderFactory.create(ctx.getSource());
        applicationService
                .logout()
                .thenRun(() -> sendFeedback(
                        sender,
                        Component.translatable("message.stream-tweaks.loggedOut")
                                .withStyle(ChatFormatting.YELLOW)))
                .exceptionally(ex -> {
                    sendFeedback(
                            sender,
                            Component.translatable("message.stream-tweaks.logoutError", rootMessage(ex))
                                    .withStyle(ChatFormatting.RED));
                    return null;
                });
        return 1;
    }

    private int handleDisconnect(CommandContext<S> ctx, FeedbackSenderFactory<S> feedbackSenderFactory) {
        FeedbackSender sender = feedbackSenderFactory.create(ctx.getSource());
        Login login = new Login(StringArgumentType.getString(ctx, "login"));

        sendFeedback(
                sender,
                Component.translatable("message.stream-tweaks.disconnecting", login.value())
                        .withStyle(ChatFormatting.YELLOW));

        applicationService
                .disconnect(login)
                .thenRun(() -> {
                    var feedback = Component.translatable("message.stream-tweaks.disconnected", login.value())
                            .withStyle(ChatFormatting.GREEN);
                    sendFeedback(sender, feedback);
                })
                .exceptionally(ex -> {
                    var feedback = Component.translatable(
                                    "message.stream-tweaks.disconnectError", login.value(), rootMessage(ex))
                            .withStyle(ChatFormatting.RED);
                    sendFeedback(sender, feedback);
                    return null;
                });

        return 1;
    }

    private void sendFeedback(FeedbackSender sender, Component feedback) {
        clientExecutor.execute(() -> sender.sendFeedback(feedback));
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex.getCause();
        return cause != null ? cause.getMessage() : ex.getMessage();
    }

    @FunctionalInterface
    public interface FeedbackSender {
        void sendFeedback(Component feedback);
    }

    @FunctionalInterface
    public interface FeedbackSenderFactory<S> {
        FeedbackSender create(S source);
    }
}
