package com.diapersstore.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val settings by lazy { LocationServices.getSettingsClient(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) checkLocationSettings() else sendError(1, "Location permission was not granted")
    }

    private val resolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) getCurrentLocation()
        else sendError(2, "Please turn on Location to continue")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val uri = Uri.parse(url)
                if (uri.host == "diapers-online-store.vercel.app") return false
                if (uri.scheme == "https" || uri.scheme == "tel" || uri.scheme == "mailto") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
                return false
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installNativeGeolocationBridge()
            }
        }
        web.addJavascriptInterface(NativeLocationBridge(), "AndroidLocation")
        web.loadUrl("https://diapers-online-store.vercel.app/")
    }

    private fun installNativeGeolocationBridge() {
        val script = """
            (function() {
              if (window.__nativeGeoInstalled) return;
              window.__nativeGeoInstalled = true;
              if (!navigator.geolocation) return;
              navigator.geolocation.getCurrentPosition = function(success, error, options) {
                window.__nativeGeoSuccess = success;
                window.__nativeGeoError = error;
                window.AndroidLocation.requestLocation();
              };
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    inner class NativeLocationBridge {
        @JavascriptInterface
        fun requestLocation() {
            runOnUiThread {
                val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!fine && !coarse) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                } else checkLocationSettings()
            }
        }
    }

    private fun checkLocationSettings() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        val settingsRequest = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true).build()
        settings.checkLocationSettings(settingsRequest)
            .addOnSuccessListener { getCurrentLocation() }
            .addOnFailureListener { exception ->
                if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                    try {
                        resolutionLauncher.launch(IntentSenderRequest.Builder(exception.resolution).build())
                    } catch (_: Exception) {
                        sendError(2, "Could not open Location settings")
                    }
                } else sendError(2, "Device Location is unavailable")
            }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation() {
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location == null) sendError(2, "Could not determine your current location. Try again.")
                else {
                    val js = "window.__nativeGeoSuccess && window.__nativeGeoSuccess({coords:{latitude:${location.latitude},longitude:${location.longitude},accuracy:${location.accuracy}},timestamp:${System.currentTimeMillis()}});"
                    web.post { web.evaluateJavascript(js, null) }
                }
            }
            .addOnFailureListener { sendError(2, "Could not get your location") }
    }

    private fun sendError(code: Int, message: String) {
        val safe = message.replace("\\", "\\\\").replace("'", "\\'")
        val js = "window.__nativeGeoError && window.__nativeGeoError({code:$code,message:'$safe'});"
        web.post { web.evaluateJavascript(js, null) }
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
