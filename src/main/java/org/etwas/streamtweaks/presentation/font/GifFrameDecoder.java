package org.etwas.streamtweaks.presentation.font;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

/**
 * GIF バイト列をフレーム列（{@link EmoteFrame}）にデコードする。
 *
 * <p>追加ライブラリを使わず {@code javax.imageio} 標準の GIF リーダーを利用する。
 * disposal method（none/doNotDispose は蓄積合成、restoreToBackgroundColor は前フレーム矩形クリア、
 * restoreToPrevious はスナップショット復元）を反映したうえで、各フレームを同一キャンバスサイズの
 * 完成画像として合成する。以降の GPU アップロードは常に同一矩形で済む。
 */
public final class GifFrameDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(GifFrameDecoder.class);

    static final int MAX_FRAMES = 64;
    static final int MAX_DIMENSION = 256;
    static final int MIN_FRAME_DELAY_MS = 100;

    private static final String DISPOSAL_BACKGROUND = "restoreToBackgroundColor";
    private static final String DISPOSAL_PREVIOUS = "restoreToPrevious";

    private GifFrameDecoder() {}

    public static List<EmoteFrame> decode(InputStream is) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            LOGGER.error("No GIF ImageReader available in this JVM");
            return List.of();
        }
        ImageReader reader = readers.next();
        List<NativeImage> allocated = new ArrayList<>();
        try (ImageInputStream iis = ImageIO.createImageInputStream(is)) {
            reader.setInput(iis, false);
            return decodeFrames(reader, allocated);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to decode GIF", e);
            allocated.forEach(NativeImage::close);
            return List.of();
        } finally {
            reader.dispose();
        }
    }

    private static List<EmoteFrame> decodeFrames(ImageReader reader, List<NativeImage> allocated) throws IOException {
        int numFrames = reader.getNumImages(true);
        if (numFrames <= 0 || numFrames > MAX_FRAMES) {
            LOGGER.warn("GIF frame count out of bounds: {}", numFrames);
            return abort(allocated);
        }

        int canvasWidth = readScreenDescriptorSize(reader, "logicalScreenWidth");
        int canvasHeight = readScreenDescriptorSize(reader, "logicalScreenHeight");

        List<EmoteFrame> frames = new ArrayList<>(numFrames);
        BufferedImage canvas = null;
        BufferedImage previousSnapshot = null;
        String previousDisposal = "none";
        int previousLeft = 0;
        int previousTop = 0;
        int previousWidth = 0;
        int previousHeight = 0;

        for (int i = 0; i < numFrames; i++) {
            IIOMetadataNode imageRoot = getImageMetadataRoot(reader, i);
            IIOMetadataNode gce = getChild(imageRoot, "GraphicControlExtension");
            IIOMetadataNode descriptor = getChild(imageRoot, "ImageDescriptor");

            int delayMs = readDelayMs(gce);
            String disposal = readDisposal(gce);
            int left = readIntAttribute(descriptor, "imageLeftPosition", 0);
            int top = readIntAttribute(descriptor, "imageTopPosition", 0);

            // reader.getWidth/getHeight はヘッダのみ読む軽量呼び出しで、フル画素デコードを伴わない。
            // reader.read(i) より前に寸法を検証することで、フレームが巨大サイズを宣言しているだけの
            // GIF（圧縮爆弾）が実デコードに到達する前に弾かれるようにする。
            int frameWidth = reader.getWidth(i);
            int frameHeight = reader.getHeight(i);
            if (frameWidth <= 0 || frameHeight <= 0 || frameWidth > MAX_DIMENSION || frameHeight > MAX_DIMENSION) {
                LOGGER.warn("GIF frame {} dimension out of bounds: {}x{}", i, frameWidth, frameHeight);
                return abort(allocated);
            }

            if (canvasWidth <= 0 || canvasHeight <= 0) {
                canvasWidth = Math.max(canvasWidth, left + frameWidth);
                canvasHeight = Math.max(canvasHeight, top + frameHeight);
            }
            if (canvasWidth <= 0 || canvasHeight <= 0 || canvasWidth > MAX_DIMENSION || canvasHeight > MAX_DIMENSION) {
                LOGGER.warn("GIF canvas dimension out of bounds: {}x{}", canvasWidth, canvasHeight);
                return abort(allocated);
            }

            BufferedImage frameImage = reader.read(i);

            if (canvas == null) {
                canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
            } else {
                applyDisposal(
                        canvas,
                        previousDisposal,
                        previousLeft,
                        previousTop,
                        previousWidth,
                        previousHeight,
                        previousSnapshot);
            }

            previousSnapshot = DISPOSAL_PREVIOUS.equals(disposal) ? deepCopy(canvas) : null;

            Graphics2D g = canvas.createGraphics();
            try {
                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(frameImage, left, top, null);
            } finally {
                g.dispose();
            }

            NativeImage nativeImage = toNativeImage(canvas);
            allocated.add(nativeImage);
            frames.add(new EmoteFrame(nativeImage, delayMs));

            previousDisposal = disposal;
            previousLeft = left;
            previousTop = top;
            previousWidth = frameImage.getWidth();
            previousHeight = frameImage.getHeight();
        }

        return frames;
    }

    /**
     * デコードを中断する際に、それまでに確保済みの NativeImage を解放してから空リストを返す。
     * 早期 return の全経路をこのヘルパーに統一することで、将来ロジックが変わって allocated に
     * 要素が入った状態で中断されるようになっても、静かなネイティブメモリリークを防ぐ。
     */
    private static List<EmoteFrame> abort(List<NativeImage> allocated) {
        allocated.forEach(NativeImage::close);
        return List.of();
    }

    private static void applyDisposal(
            BufferedImage canvas, String disposal, int left, int top, int width, int height, BufferedImage snapshot) {
        if (DISPOSAL_BACKGROUND.equals(disposal)) {
            Graphics2D g = canvas.createGraphics();
            try {
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(left, top, width, height);
            } finally {
                g.dispose();
            }
        } else if (DISPOSAL_PREVIOUS.equals(disposal) && snapshot != null) {
            Graphics2D g = canvas.createGraphics();
            try {
                g.setComposite(AlphaComposite.Src);
                g.drawImage(snapshot, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        // "none" / "doNotDispose" / 未指定: 何もしない（蓄積合成のまま次フレームへ）
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static NativeImage toNativeImage(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        NativeImage image = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setPixel(x, y, src.getRGB(x, y));
            }
        }
        return image;
    }

    private static int readDelayMs(IIOMetadataNode gce) {
        if (gce == null) {
            return MIN_FRAME_DELAY_MS;
        }
        int delayCenti = readIntAttribute(gce, "delayTime", 0);
        int delayMs = delayCenti * 10;
        // 主要ブラウザの慣習に倣い、delay 未指定または極端に小さい値（<20ms）のみ
        // 「フルスピード」の意図とみなして MIN_FRAME_DELAY_MS にフォールバックする。
        // 通常の delay 値（Twitch のアニメーション絵文字は数十ms程度が多い）は
        // そのまま尊重し、一律 100ms に引き伸ばして遅く見えないようにする。
        return delayMs < 20 ? MIN_FRAME_DELAY_MS : delayMs;
    }

    private static String readDisposal(IIOMetadataNode gce) {
        if (gce == null) {
            return "none";
        }
        String value = gce.getAttribute("disposalMethod");
        return value == null || value.isEmpty() ? "none" : value;
    }

    private static int readScreenDescriptorSize(ImageReader reader, String attribute) {
        try {
            IIOMetadata streamMetadata = reader.getStreamMetadata();
            if (streamMetadata == null) {
                return -1;
            }
            IIOMetadataNode root = (IIOMetadataNode) streamMetadata.getAsTree("javax_imageio_gif_stream_1.0");
            IIOMetadataNode lsd = getChild(root, "LogicalScreenDescriptor");
            return readIntAttribute(lsd, attribute, -1);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static IIOMetadataNode getImageMetadataRoot(ImageReader reader, int index) throws IOException {
        IIOMetadata metadata = reader.getImageMetadata(index);
        if (metadata == null) {
            return null;
        }
        return (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
    }

    private static IIOMetadataNode getChild(IIOMetadataNode parent, String name) {
        if (parent == null) {
            return null;
        }
        for (int i = 0; i < parent.getLength(); i++) {
            Node child = parent.item(i);
            if (name.equals(child.getNodeName())) {
                return (IIOMetadataNode) child;
            }
        }
        return null;
    }

    private static int readIntAttribute(IIOMetadataNode node, String attribute, int fallback) {
        if (node == null) {
            return fallback;
        }
        String value = node.getAttribute(attribute);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
