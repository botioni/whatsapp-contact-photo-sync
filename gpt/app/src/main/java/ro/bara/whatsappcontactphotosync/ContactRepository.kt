package ro.bara.whatsappcontactphotosync

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import java.io.ByteArrayOutputStream

data class PhoneContact(
    val contactId: Long,
    val name: String,
    val phone: String
)

class ContactRepository(private val context: Context) {

    fun loadContacts(): List<PhoneContact> {
        val out = mutableListOf<PhoneContact>()
        val uri = Phone.CONTENT_URI
        val projection = arrayOf(
            Phone.CONTACT_ID,
            Phone.DISPLAY_NAME,
            Phone.NUMBER
        )

        context.contentResolver.query(
            uri, projection, null, null, Phone.DISPLAY_NAME + " COLLATE LOCALIZED"
        )?.use { c ->
            val idIx = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameIx = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
            val numIx = c.getColumnIndexOrThrow(Phone.NUMBER)

            while (c.moveToNext()) {
                val id = c.getLong(idIx)
                val name = c.getString(nameIx) ?: continue
                val number = c.getString(numIx) ?: continue
                if (number.isNotBlank()) out += PhoneContact(id, name, number)
            }
        }
        return out.distinctBy { it.contactId to normalize(it.phone) }
    }

    fun setPhoto(contactId: Long, jpeg: ByteArray) {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newDelete(
            ContactsContract.Data.CONTENT_URI
        ).withSelection(
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId.toString(), Photo.CONTENT_ITEM_TYPE)
        ).build()

        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId(contactId))
            .withValue(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
            .withValue(Photo.PHOTO, jpeg)
            .build()

        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun rawContactId(contactId: Long): Long {
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        error("Raw contact not found: $contactId")
    }

    fun normalize(value: String): String {
        var s = value.filter { it.isDigit() || it == '+' }
        if (s.startsWith("00")) s = "+" + s.drop(2)
        if (s.startsWith("0") && !s.startsWith("00")) s = "+40" + s.drop(1)
        return s.replace("+", "").trimStart('0')
    }
}
