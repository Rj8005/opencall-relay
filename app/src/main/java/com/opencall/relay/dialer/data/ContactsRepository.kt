package com.opencall.relay.dialer.data

import android.content.Context
import android.provider.ContactsContract
import com.opencall.relay.dialer.role.DialerPermissions

/** A contact as this pillar needs it — deliberately NOT the full
 *  ContactsContract row shape, just what the keypad/T9 search, contacts
 *  list, and call-log name lookups actually use. */
data class DialerContact(
    val contactId: Long,
    val displayName: String,
    val phoneNumbers: List<String>,
    val starred: Boolean,
    val photoUri: String?
)

/**
 * PART 3.3: contacts from [ContactsContract]. Every query here is
 * permission-gated and returns an empty result rather than throwing when
 * READ_CONTACTS isn't granted — callers (the contacts screen, T9 search,
 * call-log name resolution) never need their own try/catch around this.
 */
object ContactsRepository {

    fun isPermissionGranted(context: Context): Boolean =
        DialerPermissions.isGranted(context, DialerPermissions.READ_CONTACTS)

    /** PART 3.3: all contacts with at least one phone number, one row per
     *  contact (numbers merged), optionally filtered by [query] against the
     *  display name (case-insensitive substring — the T9 numeric match is a
     *  SEPARATE, purpose-built matcher, see [com.opencall.relay.dialer.ui.T9Matcher];
     *  this [query] param is for the contacts tab's own plain-text search
     *  box). Empty list if READ_CONTACTS isn't granted — never throws. */
    fun queryContacts(context: Context, query: String? = null): List<DialerContact> {
        if (!isPermissionGranted(context)) return emptyList()
        val byContact = LinkedHashMap<Long, MutableList<String>>()
        val names = HashMap<Long, String>()
        val starred = HashMap<Long, Boolean>()
        val photos = HashMap<Long, String?>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )
        val (selection, args) = if (!query.isNullOrBlank()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" to arrayOf("%$query%")
        } else {
            null to null
        }
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, selection, args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val starredCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
            val photoCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val number = cursor.getString(numberCol) ?: continue
                byContact.getOrPut(id) { mutableListOf() }.add(number)
                names[id] = cursor.getString(nameCol) ?: names[id] ?: "Unknown"
                starred[id] = cursor.getInt(starredCol) != 0
                photos[id] = cursor.getString(photoCol)
            }
        }
        return byContact.map { (id, numbers) ->
            DialerContact(
                contactId = id,
                displayName = names[id] ?: "Unknown",
                phoneNumbers = numbers.distinct(),
                starred = starred[id] == true,
                photoUri = photos[id]
            )
        }
    }

    fun favourites(context: Context): List<DialerContact> =
        queryContacts(context).filter { it.starred }

    /** Best-effort reverse lookup — the call-log screen and in-call UI both
     *  need "what name goes with this number." Null (never an exception)
     *  when unmatched, unpermitted, or the platform lookup fails. */
    fun lookupNameForNumber(context: Context, number: String): String? {
        if (!isPermissionGranted(context) || number.isBlank()) return null
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
        )
        return try {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
