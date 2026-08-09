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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

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

            service.startManualSync()
            binding.status.text = "Sincronizarea a fost pornită manual..."
        }
    }

    private fun hasContactsPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
}
