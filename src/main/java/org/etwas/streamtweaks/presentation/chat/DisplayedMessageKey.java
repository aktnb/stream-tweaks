package org.etwas.streamtweaks.presentation.chat;

import org.etwas.streamtweaks.platform.PlatformId;

/**
 * 表示済みチャットメッセージを一意に特定するための複合キー。
 *
 * <p>{@code messageId} はプラットフォームごとに独立した採番であり、異なるプラットフォーム間で
 * 衝突しうる（例: Twitchのメッセージ1件とYouTubeのメッセージ1件が偶然同じIDを持つ）。
 * {@link PlatformId} を含めることで、複数プラットフォームを同時に扱っても索引・削除処理が
 * 互いに独立して動作することを保証する。
 */
public record DisplayedMessageKey(PlatformId platform, String messageId) {}
