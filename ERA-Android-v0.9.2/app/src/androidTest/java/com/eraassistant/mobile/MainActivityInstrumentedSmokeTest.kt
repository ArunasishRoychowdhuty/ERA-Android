package com.eraassistant.mobile

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.eraassistant.mobile.accessibility.EraAccessibilityService
import com.eraassistant.mobile.productivity.EraDatabase
import com.eraassistant.mobile.productivity.SecurePayloadCipher
import com.eraassistant.mobile.productivity.ReminderRepository
import com.eraassistant.mobile.productivity.ManagedMemoryRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Real Android instrumentation smoke tests. These need a running emulator or a connected
 * Android device and must not be counted as passing when only JVM tests have run.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedSmokeTest {
    private val timeoutMs = 8_000L

    @Test
    fun mainActivityLaunchesAndShowsHomeHeading() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
                assertNotNull(activity.window.decorView.rootView)
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.hasObject(By.text("COMMAND CENTER")), timeoutMs))
        }
    }

    @Test
    fun allFiveNavigationDestinationsCanBeOpened() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val destinations = listOf(
                "Agents" to "TOOLS & WORKFLOWS",
                "Screen" to "SCREEN INTELLIGENCE",
                "Settings" to "DEVICE CONFIGURATION",
                "Personal" to "Reminders & private memory",
                "Home" to "COMMAND CENTER"
            )
            for ((tab, heading) in destinations) {
                val navItem = device.wait(Until.findObject(By.text(tab)), timeoutMs)
                assertNotNull("Missing bottom navigation item: $tab", navItem)
                navItem.click()
                assertTrue("Destination did not render: $heading", device.wait(Until.hasObject(By.text(heading)), timeoutMs))
            }
        }
    }

    @Test
    fun activityRecreationRestoresTheSelectedDestination() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val settingsTab = device.wait(Until.findObject(By.text("Settings")), timeoutMs)
            assertNotNull(settingsTab)
            settingsTab.click()
            assertTrue(device.wait(Until.hasObject(By.text("DEVICE CONFIGURATION")), timeoutMs))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("DEVICE CONFIGURATION")), timeoutMs))
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
        }
    }

    @Test
    fun keystoreCipherAndRoomRepositoriesPersistEncryptedValues() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = EraDatabase.get(context)
        val cipher = SecurePayloadCipher()
        val secretTestValue = "ERA instrumentation sample reminder"

        val encrypted = cipher.encrypt(secretTestValue)
        assertFalse(encrypted.contains(secretTestValue))
        assertEquals(secretTestValue, cipher.decrypt(encrypted))
        assertFalse(encrypted == cipher.encrypt(secretTestValue))

        val reminderRepository = ReminderRepository(database.reminders(), cipher)
        val reminder = reminderRepository.create(secretTestValue, 5L)
        val storedReminder = database.reminders().getById(reminder.id)
        assertNotNull(storedReminder)
        assertFalse(storedReminder!!.encryptedTitle.contains(secretTestValue))
        assertEquals(secretTestValue, reminderRepository.titleFor(storedReminder))
        reminderRepository.delete(reminder.id)

        val memoryRepository = ManagedMemoryRepository(database.managedMemory(), cipher)
        val savedMemory = memoryRepository.add("PROJECT_NOTE", "Test title", "A private project note.")
        val storedMemory = database.managedMemory().getById(savedMemory.id)
        assertNotNull(storedMemory)
        assertFalse(storedMemory!!.encryptedTitle.contains("Test title"))
        assertFalse(storedMemory.encryptedContent.contains("A private project note."))
        memoryRepository.delete(savedMemory.id)
    }

    @Test
    fun liveAccessibilityOverlayCapturesAnActualForegroundScreenWhenEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context, EraAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        assumeTrue("Enable ERA Screen Inspector in Android Accessibility settings to run this device test.",
            enabledServices.split(':').any { it.equals(component, ignoreCase = true) })
        val consent = context.getSharedPreferences("era_preferences", android.content.Context.MODE_PRIVATE)
            .getBoolean("accessibility_consent_accepted", false)
        assumeTrue("Screen-inspection consent must already be enabled by the device owner.", consent)

        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val device = UiDevice.getInstance(instrumentation)
        val overlay = device.wait(Until.findObject(By.text("ERA · Capture UI")), timeoutMs)
        assumeTrue("ERA Accessibility overlay was not visible on the foreground Settings screen.", overlay != null)
        overlay!!.click()

        val prefs = context.getSharedPreferences("era_ui_snapshot", android.content.Context.MODE_PRIVATE)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var encoded: String? = null
        while (SystemClock.uptimeMillis() < deadline && encoded.isNullOrBlank()) {
            encoded = prefs.getString("latest_json", null)
            if (encoded.isNullOrBlank()) SystemClock.sleep(150)
        }
        assertNotNull("Accessibility service did not persist a fresh screen tree.", encoded)
        val snapshot = JSONObject(encoded!!)
        assertEquals("era.ui-tree.v1", snapshot.getString("schemaVersion"))
        assertTrue(snapshot.getInt("nodeCount") > 0)
        assertTrue(snapshot.has("root"))
    }
}
