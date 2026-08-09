package ro.bara.whatsappcontactphotosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ro.bara.whatsappcontactphotosync.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), SyncListener {
    private lateinit var binding: ActivityMainBinding
    private val logLines = StringBuilder()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result[Manifest.permission.READ_CONTACTS] == true &&
                result[Manifest.permission.WRITE_CONTACTS] == true
        binding.status.text = if (ok) {
            "Contactele sunt permise. Poți porni sincronizarea."
        } else {
            "Trebuie permise READ_CONTACTS și WRITE_CONTACTS."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.buildInfo.text = "Build ${BuildConfig.VERSION_NAME}"

        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.syncButton.setOnClickListener {
            if (!hasContactsPermission()) {
                permissions.launch(arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                ))
                return@setOnClickListener
            }

            val service = WhatsAppAccessibilityService.instance
            if (service == null) {
                Toast.makeText(
                    this,
                    "Activează serviciul din Accesibilitate, apoi apasă din nou.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val limit = binding.limitInput.text?.toString()?.trim()?.toIntOrNull()

            logLines.clear()
            binding.results.text = ""
            service.startManualSync(limit)
            binding.status.text = "Sincronizarea a fost pornită manual..."
        }

        binding.stopButton.setOnClickListener {
            val service = WhatsAppAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "Serviciul de accesibilitate nu rulează.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            service.stopSync()
        }
    }

    override fun onResume() {
        super.onResume()
        // Listener is kept registered across onPause/onResume (only cleared
        // in onDestroy) because the sync flow spends nearly all its time
        // with WhatsApp in the foreground and this activity backgrounded —
        // unregistering on pause meant the log went silent the instant
        // WhatsApp opened for the first contact.
        val service = WhatsAppAccessibilityService.instance
        service?.listener = this
        binding.status.text = if (service != null) {
            "Accesibilitatea este activă. Poți porni sincronizarea."
        } else {
            "Serviciul de accesibilitate nu este activ. Apasă butonul 1."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val service = WhatsAppAccessibilityService.instance
        if (service?.listener === this) service.listener = null
    }

    override fun onProgress(processed: Int, total: Int, updated: Int, skipped: Int) {
        runOnUiThread {
            binding.status.text = "Sincronizare: $processed/$total · Actualizate: $updated · Omise: $skipped"
        }
    }

    override fun onFinished(updated: Int, skipped: Int, total: Int) {
        runOnUiThread {
            binding.status.text = if (total > 0) {
                "Terminat. Actualizate: $updated · Omise: $skipped · Total: $total"
            } else {
                "Nu există contacte cu număr de telefon valid."
            }
        }
    }

    override fun onLog(message: String) {
        runOnUiThread {
            if (logLines.isNotEmpty()) logLines.append('\n')
            logLines.append(message)
            binding.results.text = logLines.toString()
            binding.resultsScroll.post {
                binding.resultsScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun hasContactsPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
}
