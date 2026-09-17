package org.etwas.streamtweaks.twitch.subscription.domain;

import org.etwas.streamtweaks.twitch.core.UserId;
import org.etwas.streamtweaks.twitch.subscription.event.EventSubEventType;

/**
 * EventSub購読1件を一意に識別するキー。
 *
 * <p>ユーザーの意図（desired）はチャンネル単位（{@link DesiredSubscriptionStore}）だが、
 * 実際に作成される購読は「チャンネル × イベントタイプ」単位になるため、
 * actual側（{@link ActualSubscriptionRegistry}）はこのキーで管理する。
 * 片方のイベントタイプだけ作成に失敗しても、次のreconcileで欠けている組み合わせだけが再作成される。
 *
 * <p>{@code ConcurrentHashMap} のキーとして使うため record（値等価）である必要がある。
 */
public record SubscriptionKey(UserId broadcasterId, EventSubEventType eventType) {}
