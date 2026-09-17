package org.etwas.streamtweaks.presentation.font;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * アニメーション emote の1フレーム分の画像とフレーム表示時間。
 *
 * @param image 当該フレームの画像。全フレームで同一の width/height を持つこと。
 * @param delayMs このフレームを表示し続ける時間（ミリ秒）
 */
public record EmoteFrame(NativeImage image, int delayMs) {}
