package com.v2ray.ang.ui

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.ui.compose.ThemeManager
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArtworkRenderDeviceTest {
    @Test fun captureIllustratedThemeInBothModes() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val palette=ThemeManager.palette.value.settingId
        val mode=ThemeManager.themeMode.value
        val dynamic=ThemeManager.dynamicColorEnabled.value
        try {
            ThemeManager.setPalette("kyoto")
            for (theme in listOf("1","2")) {
                ThemeManager.setThemeMode(theme)
                ActivityScenario.launch(com.v2ray.ang.ui.main.MainActivity::class.java).use {
                    Thread.sleep(1800)
                    val screenshot=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        File(instrumentation.targetContext.getExternalFilesDir(null),"artwork-153-$theme.png").outputStream().use { output ->
                            check(screenshot.compress(Bitmap.CompressFormat.PNG,100,output))
                        }
                    } finally { screenshot.recycle() }
                }
            }
        } finally { ThemeManager.setPalette(palette);ThemeManager.setThemeMode(mode);ThemeManager.setDynamicColorEnabled(dynamic) }
    }
}
