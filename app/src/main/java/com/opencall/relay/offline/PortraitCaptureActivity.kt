package com.opencall.relay.offline

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * FIX 4: zxing-android-embedded's own CaptureActivity is declared
 * android:screenOrientation="sensorLandscape" in the library's OWN AAR
 * manifest (com.journeyapps:zxing-android-embedded:4.3.0) — that is why the
 * QR scanner opened in landscape regardless of every OTHER Activity in this
 * app being portrait-locked in AndroidManifest.xml; that per-Activity
 * attribute has no effect on a separate Activity the library launches.
 *
 * This subclass adds no behavior of its own — its only purpose is to give
 * the manifest a class name distinct from the library's CaptureActivity so
 * a portrait-locked <activity> entry can be declared for THIS class without
 * touching (or needing to match) the library's own declaration. Paired with
 * IntentIntegrator.setCaptureActivity(PortraitCaptureActivity::class.java)
 * and setOrientationLocked(true) in showScanQrDialog().
 */
class PortraitCaptureActivity : CaptureActivity()
