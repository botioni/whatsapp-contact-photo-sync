package ro.bara.whatsappcontactphotosync

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ro.bara.whatsappcontactphotosync.databinding.ActivityContactPickBinding

/**
 * Lets the user pick exactly which contacts to sync, instead of relying on
 * the "only missing photo" filter. Shows every phone contact with its
 * current photo (if any) and name, with a checkbox per row.
 */
class ContactPickerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityContactPickBinding
    private lateinit var repo: ContactRepository
    private lateinit var adapter: ContactPickAdapter

    private var allContacts: List<PhoneContact> = emptyList()
    private var filtered: List<PhoneContact> = emptyList()
    private val selectedContactIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactPickBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ContactRepository(this)

        val preselected = intent.getStringArrayListExtra(EXTRA_PRESELECTED_PHONES)?.toSet()

        allContacts = repo.loadContacts().sortedBy { it.name.lowercase() }
        if (preselected != null) {
            val normalizedPreselected = preselected
            allContacts.forEach {
                if (repo.normalize(it.phone) in normalizedPreselected) selectedContactIds += it.contactId
            }
        } else {
            allContacts.forEach { selectedContactIds += it.contactId }
        }
        filtered = allContacts

        adapter = ContactPickAdapter(
            onToggle = { contact ->
                if (!selectedContactIds.add(contact.contactId)) selectedContactIds.remove(contact.contactId)
                updateSelectionCount()
            },
            isSelected = { it.contactId in selectedContactIds }
        )
        binding.contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.contactsRecyclerView.adapter = adapter
        adapter.submitList(filtered)
        updateSelectionCount()

        binding.selectAllButton.setOnClickListener {
            selectedContactIds.clear()
            selectedContactIds += filtered.map { it.contactId }
            adapter.submitList(filtered)
            updateSelectionCount()
        }

        binding.selectNoneButton.setOnClickListener {
            selectedContactIds -= filtered.map { it.contactId }.toSet()
            adapter.submitList(filtered)
            updateSelectionCount()
        }

        binding.filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                filtered = if (query.isEmpty()) allContacts else allContacts.filter {
                    it.name.lowercase().contains(query) || it.phone.contains(query)
                }
                adapter.submitList(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.confirmSelectionButton.setOnClickListener {
            val selectedPhones = allContacts
                .filter { it.contactId in selectedContactIds }
                .map { repo.normalize(it.phone) }
                .distinct()
            val result = Intent().putStringArrayListExtra(EXTRA_SELECTED_PHONES, ArrayList(selectedPhones))
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun updateSelectionCount() {
        binding.selectionCountText.text = "${selectedContactIds.size} selectate"
    }

    companion object {
        const val EXTRA_PRESELECTED_PHONES = "preselected_phones"
        const val EXTRA_SELECTED_PHONES = "selected_phones"
    }
}
