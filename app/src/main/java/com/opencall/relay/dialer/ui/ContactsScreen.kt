package com.opencall.relay.dialer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.opencall.relay.R
import com.opencall.relay.dialer.data.ContactsRepository
import com.opencall.relay.dialer.data.DialerContact
import com.opencall.relay.dialer.data.OcpAccountRef
import java.util.concurrent.atomic.AtomicInteger

/** PART 3.3: contacts tab — search box + favourites section + full
 *  alphabetical list, same manual-list construction as [CallLogScreen]
 *  (see that file's doc for why, not a RecyclerView). PART 3.2: [ocpMatches]
 *  is a normalized-E.164 → [OcpAccountRef] map, empty by default (no
 *  badges) — the host re-invokes [build] once [com.opencall.relay.dialer.
 *  data.LocalOcpDirectory] resolves asynchronously. PART 5.1: this map is
 *  built entirely from local, on-device knowledge now — nothing about a
 *  contact's numbers is ever sent anywhere to populate it.
 *
 *  PART 0: every [ContactsRepository] query AND every contact-photo decode
 *  below runs on [runOffMainThread] (`Dispatchers.Default`) — this used to
 *  query + eagerly `BitmapFactory.decodeStream` a FULL-resolution bitmap
 *  per row, synchronously, on the main thread, for every contact in the
 *  list, which is both a main-thread I/O violation and — for any contact
 *  list with more than a handful of photographed contacts — a realistic
 *  `OutOfMemoryError` (undownsampled bitmaps held for every row of an
 *  eagerly-built, non-recycling LinearLayout). [decodeThumbnail] now bounds
 *  the decode to roughly the on-screen avatar size via `inSampleSize`, on a
 *  background thread, and the row-building step (the only thing left on
 *  the main thread) never touches ContentResolver or BitmapFactory itself. */
object ContactsScreen {

    private data class ContactRow(val contact: DialerContact, val avatar: Bitmap?)

    fun build(
        activity: AppCompatActivity,
        ocpMatches: Map<String, OcpAccountRef> = emptyMap(),
        onCall: (String) -> Unit
    ): View {
        val density = activity.resources.displayMetrics.density
        val listBody = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        // PART 0: guards against a fast typist's earlier, slower query
        // landing AFTER a later one and clobbering fresher results.
        val requestGeneration = AtomicInteger(0)

        val searchBox = EditText(activity).apply {
            hint = "Search contacts"
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_muted))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.bg_field))
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }

        fun renderEmpty() {
            listBody.removeAllViews()
            listBody.addView(TextView(activity).apply {
                text = "No matches"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
                setPadding(0, (32 * density).toInt(), 0, 0)
            })
        }

        fun refresh(query: String?) {
            if (!ContactsRepository.isPermissionGranted(activity)) {
                listBody.removeAllViews()
                return
            }
            val myGeneration = requestGeneration.incrementAndGet()
            activity.runOffMainThread(
                work = {
                    if (query.isNullOrBlank()) {
                        val favourites = ContactsRepository.favourites(activity).map { ContactRow(it, decodeThumbnail(activity, it, density)) }
                        val all = ContactsRepository.queryContacts(activity).map { ContactRow(it, decodeThumbnail(activity, it, density)) }
                        favourites to all
                    } else {
                        val results = ContactsRepository.queryContacts(activity, query).map { ContactRow(it, decodeThumbnail(activity, it, density)) }
                        emptyList<ContactRow>() to results
                    }
                },
                onResult = { (favourites, all) ->
                    if (requestGeneration.get() != myGeneration) return@runOffMainThread // superseded by a newer query
                    listBody.removeAllViews()
                    if (query.isNullOrBlank()) {
                        if (favourites.isNotEmpty()) {
                            listBody.addView(sectionHeader(activity, "Favourites"))
                            favourites.forEach { listBody.addView(contactRow(activity, it, ocpMatches, onCall)) }
                            listBody.addView(sectionHeader(activity, "All contacts"))
                        }
                        all.forEach { listBody.addView(contactRow(activity, it, ocpMatches, onCall)) }
                    } else if (all.isEmpty()) {
                        renderEmpty()
                    } else {
                        all.forEach { listBody.addView(contactRow(activity, it, ocpMatches, onCall)) }
                    }
                }
            )
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh(s?.toString())
        })

        refresh(null)

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchBox)
            addView(listBody)
        }
        return ScrollView(activity).apply { addView(column) }
    }

    private fun sectionHeader(activity: AppCompatActivity, label: String): View {
        val density = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
        }
    }

    /** PART 3.2: light E.164-ish normalization for OCP-badge matching only
     *  — strips spaces/dashes/parens, does not attempt full libphonenumber
     *  parsing (that lives in Tab 1, see [com.opencall.relay.international.
     *  InternationalCallScreen]) since this is a same-device best-effort
     *  match against a server-provided E.164 list, not user-facing display
     *  formatting. */
    internal fun normalizeForMatch(number: String): String {
        var n = number.trim().replace(Regex("[\\s\\-()]"), "")
        if (n.isNotEmpty() && !n.startsWith("+")) n = "+$n"
        return n
    }

    /** PART 3.2/TESTS: "a contact with no matching OCP account shows only
     *  SIM routing" — the pure predicate behind that, Android-free so it's
     *  directly unit-testable (see ContactsScreenTest). An empty
     *  [ocpMatches] (no local knowledge recorded for this contact yet, per
     *  [com.opencall.relay.dialer.data.LocalOcpDirectory]'s own doc) always
     *  yields false for every contact — SIM-only, never a fabricated
     *  badge. */
    internal fun hasOcpRoute(phoneNumbers: List<String>, ocpMatches: Map<String, OcpAccountRef>): Boolean =
        phoneNumbers.any { ocpMatches.containsKey(normalizeForMatch(it)) }

    private fun contactRow(
        activity: AppCompatActivity,
        row: ContactRow,
        ocpMatches: Map<String, OcpAccountRef>,
        onCall: (String) -> Unit
    ): View {
        val contact = row.contact
        val density = activity.resources.displayMetrics.density
        val primaryNumber = contact.phoneNumbers.firstOrNull()
        val hasOcpRoute = hasOcpRoute(contact.phoneNumbers, ocpMatches)
        val rowView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        rowView.addView(buildAvatar(activity, row, density))
        val nameColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
        }
        val nameRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(activity).apply {
            text = (if (contact.starred) "★ " else "") + contact.displayName
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        if (hasOcpRoute) {
            nameRow.addView(TextView(activity).apply {
                text = "OCP"
                textSize = 9f
                setTextColor(ContextCompat.getColor(activity, R.color.accent_green))
                setBackgroundColor(ContextCompat.getColor(activity, R.color.accent_green_dim))
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (8 * density).toInt()
                }
            })
        }
        nameColumn.addView(nameRow)
        if (contact.phoneNumbers.size > 1) {
            nameColumn.addView(TextView(activity).apply {
                text = "${contact.phoneNumbers.size} numbers"
                textSize = 12f
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            })
        }
        rowView.addView(nameColumn)
        rowView.addView(TextView(activity).apply {
            text = if (hasOcpRoute) "🌐" else "📞"
            textSize = 18f
            setPadding((16 * density).toInt(), 0, 0, 0)
            setOnClickListener { primaryNumber?.let(onCall) }
        })
        rowView.setOnClickListener { primaryNumber?.let(onCall) }
        return rowView
    }

    /** PART 3.2: "name, photo, numbers" — the already-decoded (background
     *  thread, bounded size) [ContactRow.avatar], or a plain neutral
     *  placeholder square when there's no photo or it failed to decode. */
    private fun buildAvatar(activity: AppCompatActivity, row: ContactRow, density: Float): View {
        val sizePx = (40 * density).toInt()
        return ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (row.avatar != null) {
                setImageBitmap(row.avatar)
            } else {
                setImageDrawable(null)
                setBackgroundColor(ContextCompat.getColor(activity, R.color.bg_field))
            }
            contentDescription = row.contact.displayName
        }
    }

    /** PART 0: runs off the main thread (called only from within
     *  [runOffMainThread]'s `work` lambda). Bounds the decode to roughly the
     *  on-screen avatar size via `inSampleSize` — the fix for the
     *  `OutOfMemoryError` this screen could previously hit decoding a full-
     *  resolution photo per row for every contact in the list. [OutOfMemoryError]
     *  is caught explicitly, not just [Exception] — the platform allocator can
     *  throw it directly on decode, and one bad photo must never take the
     *  whole list down. */
    private fun decodeThumbnail(activity: AppCompatActivity, contact: DialerContact, density: Float): Bitmap? {
        val uriString = contact.photoUri ?: return null
        val targetPx = (40 * density).toInt()
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            activity.contentResolver.openInputStream(Uri.parse(uriString))?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val (w, h) = bounds.outWidth to bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sampleSize = 1
            while (w / (sampleSize * 2) >= targetPx && h / (sampleSize * 2) >= targetPx) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            activity.contentResolver.openInputStream(Uri.parse(uriString))?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
