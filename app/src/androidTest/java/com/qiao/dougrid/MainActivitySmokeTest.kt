package com.qiao.dougrid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        composeRule.waitUntilAtLeastOneExists(hasText("作品"), timeoutMillis = 10_000)

        composeRule.onNodeWithText("豆格").assertIsDisplayed()
        composeRule.onNodeWithText("相册").assertIsDisplayed()
        composeRule.onNodeWithText("拍照").assertIsDisplayed()
        composeRule.onNodeWithText("空白").assertIsDisplayed()

        composeRule.onNodeWithText("开拼").performClick()
        composeRule.onNodeWithText("总进度").assertIsDisplayed()

        composeRule.onNodeWithText("豆仓").performClick()
        composeRule.onNodeWithText("现有").assertIsDisplayed()
        composeRule.onNodeWithText("缺少").assertIsDisplayed()
    }

    @Test
    fun materialSummaryShowsShortagesAndOpensLinkedInventory() {
        composeRule.waitUntilAtLeastOneExists(hasText("郁金香杯垫"), timeoutMillis = 10_000)

        composeRule.onNode(hasText("郁金香杯垫") and hasText("待开拼")).performClick()
        composeRule.onNodeWithText("用量").performClick()
        composeRule.onNodeWithText("已识别豆子型号").assertIsDisplayed()
        composeRule.onNodeWithText("去豆仓补货").performClick()

        composeRule.onNodeWithText("搜索色号").assertIsDisplayed()
        composeRule.onNodeWithText("郁金香杯垫").assertIsDisplayed()
    }
}
