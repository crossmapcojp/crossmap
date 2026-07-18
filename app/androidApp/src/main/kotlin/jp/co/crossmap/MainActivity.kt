package jp.co.crossmap

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.location.LocationManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val requestLocationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val preferences = getSharedPreferences("crossmap-ui", MODE_PRIVATE)
            App(
                appDataPath = filesDir.resolve("crossmap").absolutePath,
                locationProvider = { lastKnownLocation() },
                openUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                osLocaleCode = Locale.getDefault().toLanguageTag(),
                languagePreferences = object : UiLanguagePreferences {
                    override fun readLanguageCode(): String? = preferences.getString("language", null)
                    override fun writeLanguageCode(languageCode: String) {
                        preferences.edit().putString("language", languageCode).apply()
                    }
                },
            )
        }
    }

    private fun lastKnownLocation(): GeoPoint? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            return null
        }
        val manager = getSystemService(LocationManager::class.java)
        val location = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        return location?.let { GeoPoint(it.latitude, it.longitude) }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
