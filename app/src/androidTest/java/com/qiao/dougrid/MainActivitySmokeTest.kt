package com.qiao.dougrid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryWorkspacesAreReachable() {
        closeTutorialIfPresent()
        composeRule.waitUntilAtLeastOneExists(hasText("作品"), timeoutMillis = 10_000)

        composeRule.onNodeWithText("豆格").assertIsDisplayed()
        composeRule.onNodeWithText("导入图片").assertIsDisplayed()
        composeRule.onNodeWithText("拍照").assertIsDisplayed()
        composeRule.onNodeWithText("空白图纸").assertIsDisplayed()

        composeRule.onNodeWithText("开拼").performClick()
        composeRule.onNodeWithText("总进度").assertIsDisplayed()

        composeRule.onNodeWithText("豆仓").performClick()
        composeRule.onNodeWithText("现有").assertIsDisplayed()
        composeRule.onNodeWithText("缺少").assertIsDisplayed()
    }

    @Test
    fun materialSummaryShowsShortagesAndOpensLinkedInventory() {
        closeTutorialIfPresent()
        composeRule.waitUntilAtLeastOneExists(hasText("郁金香杯垫"), timeoutMillis = 10_000)

        composeRule.onNode(hasText("郁金香杯垫") and hasText("待开拼")).performClick()
        composeRule.onNodeWithText("用量").performClick()
        composeRule.onNodeWithText("已识别豆子型号").assertIsDisplayed()
        composeRule.onNodeWithText("去豆仓补货").performClick()

        composeRule.onNodeWithText("搜索色号、名称或色系").assertIsDisplayed()
        composeRule.onNodeWithText("郁金香杯垫").assertIsDisplayed()
    }

    @Test
    fun tutorialCanBeReopenedFromTheTopBar() {
        closeTutorialIfPresent()
        composeRule.waitUntilAtLeastOneExists(hasContentDescription("使用教程"), timeoutMillis = 10_000)
        composeRule.onNodeWithContentDescription("使用教程").performClick()

        composeRule.onNodeWithText("豆格使用教程").assertIsDisplayed()
        composeRule.onNodeWithText("导入一张图片").assertIsDisplayed()
        composeRule.onNodeWithText("下一步").assertIsDisplayed()
    }

    @Test
    fun editorCanvasRedrawsBeforeStrokeEnds() {
        closeTutorialIfPresent()
        composeRule.waitUntilAtLeastOneExists(hasText("郁金香杯垫"), timeoutMillis = 10_000)
        composeRule.onNode(hasText("郁金香杯垫") and hasText("待开拼")).performClick()
        composeRule.waitUntilAtLeastOneExists(hasContentDescription("画笔"), timeoutMillis = 10_000)

        val canvas = composeRule.onNodeWithTag("editor_pattern_canvas")
        composeRule.onNodeWithContentDescription("画笔").performClick()
        canvas.performTouchInput { click(center) }
        val before = canvas.captureToImage()
        val beforePixel = before.toPixelMap()[before.width / 2, before.height / 2].toArgb()

        composeRule.onNodeWithContentDescription("橡皮").performClick()
        canvas.performTouchInput { down(center) }
        canvas.performTouchInput { moveTo(center + Offset(48f, 0f), delayMillis = 200) }
        composeRule.waitForIdle()
        val duringStroke = canvas.captureToImage()
        val duringStrokePixel = duringStroke.toPixelMap()[duringStroke.width / 2, duringStroke.height / 2].toArgb()

        assertNotEquals("Cell pixels should update before the finger is lifted", beforePixel, duringStrokePixel)
        canvas.performTouchInput { up() }
    }

    @Test
    fun editorSelectionToolIsReachable() {
        closeTutorialIfPresent()
        composeRule.waitUntilAtLeastOneExists(hasText("郁金香杯垫"), timeoutMillis = 10_000)
        composeRule.onNode(hasText("郁金香杯垫") and hasText("待开拼")).performClick()
        composeRule.waitUntilAtLeastOneExists(hasContentDescription("框选"), timeoutMillis = 10_000)

        composeRule.onNodeWithContentDescription("框选").performClick()
        composeRule.onNodeWithContentDescription("框选").assertIsDisplayed()
    }

    private fun closeTutorialIfPresent() {
        composeRule.waitUntilAtLeastOneExists(
            hasText("豆格使用教程") or hasContentDescription("使用教程"),
            timeoutMillis = 10_000,
        )
        if (composeRule.onAllNodesWithText("豆格使用教程").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithContentDescription("关闭教程").performClick()
        }
        composeRule.waitUntilAtLeastOneExists(hasContentDescription("使用教程"), timeoutMillis = 10_000)
    }
}
