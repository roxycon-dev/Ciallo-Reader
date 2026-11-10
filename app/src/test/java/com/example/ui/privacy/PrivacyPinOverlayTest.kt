package com.example.ui.privacy

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 第七轮第 6.4 条验收：PIN 输入悬浮窗的交互流（Robolectric 确定性验证，
 * 替代模拟器盲点——设置（输入+二次确认）/ 不一致重试 / 验证错误与成功）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacyPinOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    private fun keypadPin(pin: String) {
        pin.forEach { c ->
            rule.onNodeWithText(c.toString(), useUnmergedTree = true).performClick()
        }
    }

    @Test
    fun setupFlow_firstAndConfirmMatch_callsOnPinSet() {
        var setPin: String? = null
        rule.setContent {
            PrivacyPinOverlay(
                mode = PinEntryMode.SETUP,
                onPinSet = { setPin = it; true },
                onPinVerified = { false },
                onDismiss = { },
            )
        }
        rule.onNodeWithText("设置隐私密码").assertExists()
        keypadPin("135790")
        rule.runOnIdle { }
        rule.waitForIdle()
        rule.onNodeWithText("再次输入以确认").assertExists()
        keypadPin("135790")
        rule.waitForIdle()
        assertEquals("135790", setPin)
    }

    @Test
    fun setupFlow_mismatchResetsToSetup() {
        var setPin: String? = null
        rule.setContent {
            PrivacyPinOverlay(
                mode = PinEntryMode.SETUP,
                onPinSet = { setPin = it; true },
                onPinVerified = { false },
                onDismiss = { },
            )
        }
        keypadPin("135790")
        rule.waitForIdle()
        rule.onNodeWithText("再次输入以确认").assertExists()
        keypadPin("246800") // 与首次不一致
        rule.waitForIdle()
        // 回到设置态并提示不一致；未回调 onPinSet
        rule.onNodeWithText("设置隐私密码").assertExists()
        rule.onNodeWithText("两次输入不一致，请重新设置").assertExists()
        assertEquals(null, setPin)
        // 重新走一遍正确流程仍可成功
        keypadPin("135790")
        rule.waitForIdle()
        keypadPin("135790")
        rule.waitForIdle()
        assertEquals("135790", setPin)
    }

    @Test
    fun verifyFlow_wrongPinShowsErrorRightPinDismisses() {
        var dismissed = false
        rule.setContent {
            PrivacyPinOverlay(
                mode = PinEntryMode.VERIFY,
                onPinSet = { false },
                onPinVerified = { it == "135790" },
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("输入隐私密码").assertExists()
        keypadPin("999999")
        rule.waitForIdle()
        rule.onNodeWithText("密码错误，请重试").assertExists()
        assertFalse(dismissed)
        keypadPin("135790")
        rule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun verifyFlow_seventhDigitIgnored() {
        var dismissed = false
        rule.setContent {
            PrivacyPinOverlay(
                mode = PinEntryMode.VERIFY,
                onPinSet = { false },
                onPinVerified = { it == "135790" },
                onDismiss = { dismissed = true },
            )
        }
        keypadPin("1357901") // 第 7 位应被忽略（输满自动提交）
        rule.waitForIdle()
        assertTrue(dismissed)
    }
}
