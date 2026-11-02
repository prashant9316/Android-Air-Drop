package com.caremate.assistant.device

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val fresh = if (Build.VERSION.SDK_INT >= 30) withTimeoutOrNull(12_000) {
            val p = providers.firstOrNull { it == LocationManager.FUSED_PROVIDER } ?: providers.firstOrNull() ?: return@withTimeoutOrNull null
            suspendCancellableCoroutine<Location?> { cont ->
                val cs = CancellationSignal()
                cont.invokeOnCancellation { cs.cancel() }
                lm.getCurrentLocation(p, cs, context.mainExecutor) { cont.resume(it) }
            }
        } else null
        fresh ?: providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    @Suppress("DEPRECATION")
    suspend fun address(context: Context, loc: Location): String? = withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.getAddressLine(0)
        }.getOrNull()
    }

    fun mapsLink(loc: Location) = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
}
