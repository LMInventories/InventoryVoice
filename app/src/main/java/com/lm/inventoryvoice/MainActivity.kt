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

    private lateinit var btnAccessibility: Button
    private lateinit var btnOpenApp: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOpenApp       = findViewById(R.id.btnOpenApp)
        tvStatus         = findViewById(R.id.tvStatus)

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOpenApp.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage("com.radweb.ib")
            if (intent != null) {
                startActivity(intent)
            } else {
                // Fallback: open Play Store listing
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.radweb.ib")))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val enabled = isAccessibilityEnabled()
        btnAccessibility.isEnabled = !enabled
        btnAccessibility.alpha     = if (enabled) 0.4f else 1f
        tvStatus.text = if (enabled)
            getString(R.string.status_enabled)
        else
            getString(R.string.status_not_enabled)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
