package org.etwas.streamtweaks.presentation.chat;

/**
 * チャット欄へ表示済みのメッセージについて、削除判定に必要な情報だけを保持する。
 *
 * <p>{@code channelId} は削除通知を適用してよいかの照合（多層防御）に使う。
 * {@code authorId} は現在の削除ロジックでは使わないが、
 * 将来 timeout/ban による一括削除に対応する際に必要となるため保持しておく。
 *
 * <p>メッセージ本文は保持しない（ログ・メモリ双方で不要な保持を避けるため）。
 */
public record DisplayedChatMessage(String channelId, String authorId) {}
