package jp.co.crossmap

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSError
import platform.Foundation.NSHomeDirectory
import platform.darwin.NSObject
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
private class IosLocationProvider : NSObject(), CLLocationManagerDelegateProtocol {
    private val manager = CLLocationManager().also { it.delegate = this }
    private var pending: CancellableContinuation<GeoPoint?>? = null

    suspend fun currentLocation(): GeoPoint? = suspendCancellableCoroutine { continuation ->
        pending?.resume(null)
        pending = continuation
        continuation.invokeOnCancellation { pending = null }
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> manager.requestLocation()
            kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> finish(null)
            else -> manager.requestWhenInUseAuthorization()
        }
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> manager.requestLocation()
            kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> finish(null)
            else -> Unit
        }
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation
        finish(location?.coordinate?.useContents { GeoPoint(latitude, longitude) })
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = finish(null)

    private fun finish(location: GeoPoint?) {
        pending?.let {
            pending = null
            it.resume(location)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun MainViewController() = ComposeUIViewController {
    val locationProvider = IosLocationProvider()
    App(
        appDataPath = "${NSHomeDirectory()}/Documents/crossmap",
        locationProvider = locationProvider::currentLocation,
    )
}
