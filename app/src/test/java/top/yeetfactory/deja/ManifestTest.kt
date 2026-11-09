package top.yeetfactory.deja

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reads the merged manifest, so a permission added by a library counts too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `the app can never reach the internet`() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertTrue("The merged manifest wasn't read", Manifest.permission.BLUETOOTH_SCAN in requested)
        assertFalse("Déjà promises that nothing leaves the phone", Manifest.permission.INTERNET in requested)
    }
}
