package org.etwas.streamtweaks.presentation.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;

class GifFrameDecoderTest {

    @Test
    void decodesSimpleTwoFrameGif() throws Exception {
        byte[] gif = buildGif(
                4,
                4,
                List.of(
                        new FrameSpec(Color.RED, "none", 10, 0, 0, 4, 4),
                        new FrameSpec(Color.BLUE, "none", 25, 0, 0, 4, 4)));

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        try {
            assertEquals(2, frames.size());
            assertEquals(100, frames.get(0).delayMs());
            assertEquals(250, frames.get(1).delayMs());
            assertArgbEquals(Color.RED, frames.get(0).image(), 2, 2);
            assertArgbEquals(Color.BLUE, frames.get(1).image(), 2, 2);
        } finally {
            frames.forEach(f -> f.image().close());
        }
    }

    @Test
    void delayZeroFallsBackToMinimum() throws Exception {
        byte[] gif = buildGif(2, 2, List.of(new FrameSpec(Color.GREEN, "none", 0, 0, 0)));

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        try {
            assertEquals(1, frames.size());
            assertEquals(GifFrameDecoder.MIN_FRAME_DELAY_MS, frames.get(0).delayMs());
        } finally {
            frames.forEach(f -> f.image().close());
        }
    }

    @Test
    void delayBelow20MsFallsBackToMinimum() throws Exception {
        // delayCenti=1 -> 10ms は「フルスピード」の意図とみなし MIN_FRAME_DELAY_MS にフォールバック
        byte[] gif = buildGif(2, 2, List.of(new FrameSpec(Color.GREEN, "none", 1, 0, 0)));

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        try {
            assertEquals(1, frames.size());
            assertEquals(GifFrameDecoder.MIN_FRAME_DELAY_MS, frames.get(0).delayMs());
        } finally {
            frames.forEach(f -> f.image().close());
        }
    }

    @Test
    void fastDelayIsRespectedWithoutStretching() throws Exception {
        // delayCenti=3 -> 30ms のような通常のアニメーション絵文字向けの短い delay は
        // 100ms に引き伸ばさずそのまま尊重する
        byte[] gif = buildGif(2, 2, List.of(new FrameSpec(Color.GREEN, "none", 3, 0, 0)));

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        try {
            assertEquals(1, frames.size());
            assertEquals(30, frames.get(0).delayMs());
        } finally {
            frames.forEach(f -> f.image().close());
        }
    }

    @Test
    void restoreToBackgroundColorClearsPreviousFrameRegion() throws Exception {
        // frame0: 全面赤で塗る。frame1: disposal=restoreToBackgroundColorで(0,0)-(2x2)を青で上書き。
        // frame2: 何も描画しない差分フレーム(領域外の1x1)。frame1が占めた矩形はframe2描画前にクリアされ、透明になっているはず。
        byte[] gif = buildGif(
                4,
                4,
                List.of(
                        new FrameSpec(Color.RED, "none", 10, 0, 0, 4, 4),
                        new FrameSpec(Color.BLUE, "restoreToBackgroundColor", 10, 0, 0, 2, 2),
                        new FrameSpec(Color.GREEN, "none", 10, 3, 3, 1, 1)));

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        try {
            assertEquals(3, frames.size());
            // frame2ではframe1が占めた(0,0)-(2,2)領域がクリアされ透明(alpha=0)になっているはず
            int argb = frames.get(2).image().getPixel(0, 0);
            int alpha = (argb >>> 24) & 0xFF;
            assertEquals(0, alpha, "restoreToBackgroundColor region should be cleared to transparent");
            // frame1が描画されなかった(3,3)より外側、frame0の赤が残っているはず
            assertArgbEquals(Color.RED, frames.get(2).image(), 3, 0);
        } finally {
            frames.forEach(f -> f.image().close());
        }
    }

    @Test
    void restoreToPreviousRestoresSnapshot() throws Exception {
        // frame0: 全面赤。frame1: disposal=restoreToPreviousで(0,0)-(2x2)を青で上書き。
        // frame2: 何も描画しない差分フレーム。frame1描画前(=frame0の状態)に復元されているはず。
        byte[] gif = buildGif(
                4,
                4,
                List.of(
                        new FrameSpec(Color.RED, "none", 10, 0, 0, 4, 4),
                        new FrameSpec(Color.BLUE, "restoreToPrevious", 10, 0, 0, 2, 2),
                        new FrameSpec(Color.GREEN, "none", 10, 3, 3, 1, 1)));

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        try {
            assertEquals(3, frames.size());
            assertArgbEquals(Color.RED, frames.get(2).image(), 0, 0);
        } finally {
            frames.forEach(f -> f.image().close());
        }
    }

    @Test
    void decodesTransparentPixelsAsZeroAlpha() throws Exception {
        byte[] gif = buildTransparentGif(4, 4, 10);

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        try {
            assertEquals(1, frames.size());
            int argb = frames.get(0).image().getPixel(0, 0);
            int alpha = (argb >>> 24) & 0xFF;
            assertEquals(0, alpha, "transparent pixel should decode to alpha=0");
            assertArgbEquals(Color.RED, frames.get(0).image(), 3, 3);
        } finally {
            frames.forEach(f -> f.image().close());
        }
    }

    @Test
    void invalidGifReturnsEmptyList() throws Exception {
        byte[] garbage = new byte[] {1, 2, 3, 4, 5};

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(garbage));

        assertTrue(frames.isEmpty());
    }

    @Test
    void tooManyFramesReturnsEmptyList() throws Exception {
        List<FrameSpec> specs = new java.util.ArrayList<>();
        for (int i = 0; i < GifFrameDecoder.MAX_FRAMES + 1; i++) {
            specs.add(new FrameSpec(Color.RED, "none", 10, 0, 0));
        }
        byte[] gif = buildGif(1, 1, specs);

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        assertTrue(frames.isEmpty());
    }

    @Test
    void oversizedDimensionReturnsEmptyList() throws Exception {
        int oversized = GifFrameDecoder.MAX_DIMENSION + 1;
        byte[] gif = buildGif(
                oversized, oversized, List.of(new FrameSpec(Color.RED, "none", 10, 0, 0, oversized, oversized)));

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        assertTrue(frames.isEmpty());
    }

    @Test
    void doNotDisposeAccumulatesLikeNone() throws Exception {
        byte[] gif = buildGif(
                4,
                4,
                List.of(
                        new FrameSpec(Color.RED, "none", 10, 0, 0, 4, 4),
                        new FrameSpec(Color.BLUE, "doNotDispose", 10, 0, 0, 2, 2)));

        List<EmoteFrame> frames = GifFrameDecoder.decode(new ByteArrayInputStream(gif));

        try {
            assertEquals(2, frames.size());
            assertArgbEquals(Color.BLUE, frames.get(1).image(), 0, 0);
            assertArgbEquals(Color.RED, frames.get(1).image(), 3, 3);
        } finally {
            frames.forEach(f -> f.image().close());
        }
    }

    private static void assertArgbEquals(Color expected, NativeImage image, int x, int y) {
        int argb = image.getPixel(x, y);
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        assertEquals(expected.getRed(), r, "red channel");
        assertEquals(expected.getGreen(), g, "green channel");
        assertEquals(expected.getBlue(), b, "blue channel");
    }

    private record FrameSpec(Color color, String disposal, int delayCenti, int left, int top, int w, int h) {
        FrameSpec(Color color, String disposal, int delayCenti, int left, int top) {
            this(color, disposal, delayCenti, left, top, 2, 2);
        }
    }

    private static byte[] buildGif(int canvasW, int canvasH, List<FrameSpec> specs) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);

            IIOMetadata streamMetadata = writer.getDefaultStreamMetadata(writer.getDefaultWriteParam());
            String streamFormatName = streamMetadata.getNativeMetadataFormatName();
            IIOMetadataNode streamRoot = (IIOMetadataNode) streamMetadata.getAsTree(streamFormatName);
            IIOMetadataNode lsd = getOrCreateNode(streamRoot, "LogicalScreenDescriptor");
            lsd.setAttribute("logicalScreenWidth", String.valueOf(canvasW));
            lsd.setAttribute("logicalScreenHeight", String.valueOf(canvasH));
            streamMetadata.setFromTree(streamFormatName, streamRoot);

            writer.prepareWriteSequence(streamMetadata);
            for (FrameSpec spec : specs) {
                BufferedImage frame = new BufferedImage(spec.w(), spec.h(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = frame.createGraphics();
                try {
                    g.setColor(spec.color());
                    g.fillRect(0, 0, spec.w(), spec.h());
                } finally {
                    g.dispose();
                }

                ImageWriteParam params = writer.getDefaultWriteParam();
                IIOMetadata metadata =
                        writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), params);
                String formatName = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formatName);

                IIOMetadataNode gce = getOrCreateNode(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", spec.disposal());
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(spec.delayCenti()));
                gce.setAttribute("transparentColorIndex", "0");

                IIOMetadataNode imageDescriptor = getOrCreateNode(root, "ImageDescriptor");
                imageDescriptor.setAttribute("imageLeftPosition", String.valueOf(spec.left()));
                imageDescriptor.setAttribute("imageTopPosition", String.valueOf(spec.top()));
                imageDescriptor.setAttribute("interlaceFlag", "FALSE");

                metadata.setFromTree(formatName, root);
                writer.writeToSequence(new IIOImage(frame, null, metadata), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    /**
     * インデックスカラー画像を明示的に構築し、index 0 を透過色として GIF に書き込む。
     * 左上 2x2 を index 0（透過）、残りを index 1（赤）にする。
     *
     * <p>{@code getDefaultImageMetadata} が返すデフォルトの {@code interlaceFlag=TRUE} を
     * そのまま使うと、書き込み時と読み込み時でインターレース処理が噛み合わずピクセルが
     * 破損することを確認したため、{@code interlaceFlag=FALSE} を明示している。
     */
    private static byte[] buildTransparentGif(int canvasW, int canvasH, int delayCenti) throws IOException {
        byte[] reds = {0, (byte) 255};
        byte[] greens = {0, 0};
        byte[] blues = {0, 0};
        IndexColorModel icm = new IndexColorModel(8, 2, reds, greens, blues, 0);
        BufferedImage frame = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_BYTE_INDEXED, icm);
        WritableRaster raster = frame.getRaster();
        for (int y = 0; y < canvasH; y++) {
            for (int x = 0; x < canvasW; x++) {
                boolean transparent = x < 2 && y < 2;
                raster.setSample(x, y, 0, transparent ? 0 : 1);
            }
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);

            IIOMetadata streamMetadata = writer.getDefaultStreamMetadata(writer.getDefaultWriteParam());
            String streamFormatName = streamMetadata.getNativeMetadataFormatName();
            IIOMetadataNode streamRoot = (IIOMetadataNode) streamMetadata.getAsTree(streamFormatName);
            IIOMetadataNode lsd = getOrCreateNode(streamRoot, "LogicalScreenDescriptor");
            lsd.setAttribute("logicalScreenWidth", String.valueOf(canvasW));
            lsd.setAttribute("logicalScreenHeight", String.valueOf(canvasH));
            streamMetadata.setFromTree(streamFormatName, streamRoot);

            writer.prepareWriteSequence(streamMetadata);

            ImageWriteParam params = writer.getDefaultWriteParam();
            IIOMetadata metadata =
                    writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), params);
            String formatName = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formatName);

            IIOMetadataNode gce = getOrCreateNode(root, "GraphicControlExtension");
            gce.setAttribute("disposalMethod", "none");
            gce.setAttribute("userInputFlag", "FALSE");
            gce.setAttribute("transparentColorFlag", "TRUE");
            gce.setAttribute("delayTime", String.valueOf(delayCenti));
            gce.setAttribute("transparentColorIndex", "0");

            IIOMetadataNode descriptor = getOrCreateNode(root, "ImageDescriptor");
            descriptor.setAttribute("interlaceFlag", "FALSE");

            metadata.setFromTree(formatName, root);
            writer.writeToSequence(new IIOImage(frame, null, metadata), params);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private static IIOMetadataNode getOrCreateNode(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            org.w3c.dom.Node child = root.item(i);
            if (name.equals(child.getNodeName())) {
                return (IIOMetadataNode) child;
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
