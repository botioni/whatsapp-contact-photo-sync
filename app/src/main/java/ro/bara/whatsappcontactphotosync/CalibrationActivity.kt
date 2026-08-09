package ro.bara.whatsappcontactphotosync

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ro.bara.whatsappcontactphotosync.databinding.ActivityCalibrationBinding

class CalibrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCalibrationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bitmap = WhatsAppAccessibilityService.lastCalibrationBitmap
        if (bitmap == null || bitmap.isRecycled) {
            Toast.makeText(this, "Nu am nicio captură disponibilă. Apasă din nou Calibrează.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.calibrationImage.setImageBitmap(bitmap)
        binding.cropOverlay.imageWidth = bitmap.width
        binding.cropOverlay.imageHeight = bitmap.height

        CalibrationStore.load(this)?.let { crop ->
            binding.cropOverlay.cropLeft = crop.left
            binding.cropOverlay.cropTop = crop.top
            binding.cropOverlay.cropRight = crop.right
            binding.cropOverlay.cropBottom = crop.bottom
        }

        binding.cancelCalibration.setOnClickListener { finish() }

        binding.saveCalibration.setOnClickListener {
            val overlay = binding.cropOverlay
            CalibrationStore.save(
                this,
                CalibrationStore.Crop(overlay.cropLeft, overlay.cropTop, overlay.cropRight, overlay.cropBottom)
            )
            Toast.makeText(this, "Calibrare salvată.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
