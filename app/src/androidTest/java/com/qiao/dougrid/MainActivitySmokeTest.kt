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
}
