package com.ingti.mixer
import ai.onnxruntime.OrtEnvironment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.jna.NativeLibrary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
/** Run on ARM64: ./gradlew connectedDebugAndroidTest. Does not start microphone capture. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun launchesPlayer() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { assertFalse(it.isFinishing) };s.recreate();s.onActivity { assertFalse(it.isFinishing) }
        }
    }
    @Test fun bothNativeBindingsLoad() {
        assertTrue(OrtEnvironment.getEnvironment().version.isNotBlank())
        assertNotNull(NativeLibrary.getInstance("sherpa-onnx-c-api").getFunction("SherpaOnnxCreateOfflineSourceSeparation"))
    }
    @Test fun manifestsAreReadable() {
        val ctx=ApplicationProvider.getApplicationContext<android.content.Context>()
        for(i in 0..1) { val m=Models(ctx,i);assertEquals(64,m.entry.getString("sha256").length);assertTrue(m.entry.getString("url").startsWith("https://")) }
    }
}
