package com.lm.inventoryvoice

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnOverlay      = findViewById(R.id.btnOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnToggle       = findViewById(R.id.btnToggle)
        tvStatus        = findViewById(R.id.tvStatus)

        btnOverlay.setOnClickListener {
            // Opens Android's "Display over other apps" settings page for this app
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnAccessibility.setOnClickListener {
            // Opens Accessibility settings so user can enable the service
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnToggle.setOnClickListener {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                updateUI()
            } else {
                if (!hasOverlayPermission()) {
                    tvStatus.text = getString(R.string.status_overlay_needed)
                    return@setOnClickListener
                }
                if (!isAccessibilityEnabled()) {
                    tvStatus.text = getString(R.string.status_accessibility_needed)
                    return@setOnClickListener
                }
                startForegroundService(Intent(this, OverlayService::class.java))
                updateUI()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val overlayOk       = hasOverlayPermission()
        val accessibilityOk = isAccessibilityEnabled()

        btnOverlay.isEnabled      = !overlayOk
        btnOverlay.alpha          = if (overlayOk) 0.4f else 1f
        btnAccessibility.isEnabled = !accessibilityOk
        btnAccessibility.alpha    = if (accessibilityOk) 0.4f else 1f

        if (OverlayService.isRunning) {
            btnToggle.text = getString(R.string.btn_stop)
            tvStatus.text  = getString(R.string.status_ready)
        } else {
            btnToggle.text = getString(R.string.btn_start)
            tvStatus.text  = when {
                !overlayOk       -> getString(R.string.status_overlay_needed)
                !accessibilityOk -> getString(R.string.status_accessibility_needed)
                else             -> "Ready — tap Start"
            }
        }
    }

    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(this)

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }
}
