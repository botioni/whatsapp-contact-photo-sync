package ro.bara.whatsappcontactphotosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
        if (ok) {
            startActivity(Intent(this, WebSyncActivity::class.java))
        } else {
            binding.status.text = "Trebuie permise READ_CONTACTS și WRITE_CONTACTS."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.buildInfo.text = "Build ${BuildConfig.VERSION_NAME}"

        binding.webSyncButton.setOnClickListener {
            if (hasContactsPermission()) {
                startActivity(Intent(this, WebSyncActivity::class.java))
            } else {
                permissions.launch(arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                ))
            }
        }
    }

    private fun hasContactsPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
}
