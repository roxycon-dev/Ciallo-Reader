package com.example.data

import com.example.ui.reader.NovelInlineImages
import com.example.ui.reader.NovelInlineImages.InlineBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块驱动架构的核心保证：图片按长宽高从文本流拆出、独立占空间（用户方案）。
 * splitIntoBlocks 的块 raw 顺序拼接必须 == 输入（分页/渲染/搜索三端共用此不变量）。
 */
class ImageBlockSplitTest {

    @Test
    fun splitIntoTextAndImageBlocks() {
        val text = "前文[IMG:/a/1.png|800|1600][IMG:/a/2.png|600|800]后文"
        val blocks = NovelInlineImages.splitIntoBlocks(text, 1000f, 2000f)

        assertEquals("文/图/图/文 四块（相邻图各自独立占位）", 4, blocks.size)
        assertTrue(blocks[0] is InlineBlock.Text)
        assertEquals("前文", (blocks[0] as InlineBlock.Text).text)
        assertTrue(blocks[1] is InlineBlock.Image)
        assertEquals("[IMG:/a/1.png|800|1600]", blocks[1].raw)
        assertTrue(blocks[2] is InlineBlock.Image)
        assertTrue(blocks[3] is InlineBlock.Text)
        assertEquals("后文", (blocks[3] as InlineBlock.Text).text)

        // 不变量：块 raw 拼接 == 输入
        assertEquals(text, blocks.joinToString("") { it.raw })
    }

    @Test
    fun imageBlocksSizedByAspectRatioClampedToMaxHeight() {
        // densityScale = 2f：两张图的原图固有尺寸都大于内容宽，走"撑满内容宽 + 页高收敛"
        val blocks = NovelInlineImages.splitIntoBlocks(
            "[IMG:/a/tall.png|800|3200][IMG:/a/mid.png|600|800]",
            contentWidthPx = 1000f,
            maxHeightPx = 2000f,
            densityScale = 2f
        )
        val tall = blocks[0] as InlineBlock.Image
        val mid = blocks[1] as InlineBlock.Image
        // 长图：高度收敛到 maxHeight 2000，宽度按长宽比同步缩（800:3200 → 500:2000）
        assertEquals(500f, tall.widthPx)
        assertEquals(2000f, tall.heightPx)
        // 中图：宽撑满 1000，高按长宽比 1000*800/600 ≈ 1333
        assertEquals(1000f, mid.widthPx)
        assertEquals(1333.33f, mid.heightPx, 0.5f)
    }

    @Test
    fun smallImageKeepsItsNaturalWidthInsteadOfFillingTheColumn() {
        // 20×20 的分隔符（EPUB 里 note.png 这种）：不能被拉成整栏宽
        val blocks = NovelInlineImages.splitIntoBlocks(
            "[IMG:/a/note.png|20|20]",
            contentWidthPx = 656f,
            maxHeightPx = 1000f,
            densityScale = 2f
        )
        val note = blocks[0] as InlineBlock.Image
        assertEquals("小图应按原图尺寸显示（20 * 2）", 40f, note.widthPx)
        assertEquals(40f, note.heightPx)

        // 180×120 的小插图同理
        val small = NovelInlineImages.splitIntoBlocks(
            "[IMG:/a/small.png|180|120]",
            contentWidthPx = 656f,
            maxHeightPx = 1000f,
            densityScale = 2f
        )[0] as InlineBlock.Image
        assertEquals(360f, small.widthPx)
        assertEquals(240f, small.heightPx)

        // 原图比内容宽时仍然撑满内容宽（大图行为不变）
        val big = NovelInlineImages.splitIntoBlocks(
            "[IMG:/a/big.png|1000|1480]",
            contentWidthPx = 656f,
            maxHeightPx = 1000f,
            densityScale = 2f
        )[0] as InlineBlock.Image
        assertEquals(656f, big.widthPx)
        assertEquals(971.0f, big.heightPx, 1f)
    }

    @Test
    fun plainTextAndNoImageFallback() {
        val plain = NovelInlineImages.splitIntoBlocks("纯文本没有图片。", 1000f, 2000f)
        assertEquals(1, plain.size)
        assertTrue(plain[0] is InlineBlock.Text)

        // 只有 token（无文本）
        val onlyImg = NovelInlineImages.splitIntoBlocks("[IMG:/a/1.png|10|10]", 100f, 100f)
        assertEquals(1, onlyImg.size)
        assertTrue(onlyImg[0] is InlineBlock.Image)

        // 空串
        assertEquals(1, NovelInlineImages.splitIntoBlocks("", 100f, 100f).size)
    }
}
