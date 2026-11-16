package com.gndec.timetable.net

import com.gndec.timetable.domain.LostFoundRelativeTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure, offline parts of the Lost & Found stack: path/URL derivation for
 * the thumbnail variant, row decoding from PostgREST JSON (including the
 * contact info of the delete-and-contact update), the delete-PIN rules, the
 * contact sanitizer, and the relative age rendering shown under each post.
 * (LostFoundManager itself needs android.content.Context, so its
 * relativeTime() algorithm is exercised via the LostFoundRelativeTime
 * object it delegates to.)
 */
class LostFoundClientTest {

    @Test
    fun thumbPathDerivation() {
        assertEquals("found/abc_t.jpg", LostFoundClient.thumbPath("found/abc.jpg"))
        assertEquals("lost/x_y_t.jpeg", LostFoundClient.thumbPath("lost/x_y.jpeg"))
        assertEquals("found/1_t.jpg", LostFoundClient.thumbPath("found/1.jpg"))
        // No extension: a plain _t suffix is appended.
        assertEquals("found/img_t", LostFoundClient.thumbPath("found/img"))
    }

    @Test
    fun imageUrlsPointAtThePublicBucket() {
        assertEquals(
            "https://kitemjxgqisxpefsqjlc.supabase.co/storage/v1/object/public/lostfound/lost/a.jpg",
            LostFoundClient.imageUrl("lost/a.jpg")
        )
        assertEquals(
            "https://kitemjxgqisxpefsqjlc.supabase.co/storage/v1/object/public/lostfound/lost/a_t.jpg",
            LostFoundClient.thumbUrl("lost/a.jpg")
        )
    }

    @Test
    fun postgrestRowsDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val body = """
            [
              {
                "id": "0e7d1f4a-6f13-4f6f-9a52-1b1e8ef41c5c",
                "kind": "lost",
                "title": "Black Titan watch",
                "description": "Lost near the workshop block, has a scratch on the clasp.",
                "image_path": "lost/0e7d.jpg",
                "created_at": "2026-11-13T10:15:30+00:00",
                "contact_info": "98765 43210",
                "owner_phone": "9876543210"
              },
              {
                "id": "1c8e2b5a-0000-4f6f-9a52-1b1e8ef41c5c",
                "kind": "found",
                "title": "Set of house keys with a red fob",
                "description": "",
                "image_path": null,
                "created_at": "2026-11-14T09:00:00+00:00"
              }
            ]
        """.trimIndent()
        val rows = json.decodeFromString<List<LostFoundItem>>(body)
        assertEquals(2, rows.size)
        assertEquals("lost", rows[0].kind)
        assertEquals("Black Titan watch", rows[0].title)
        assertEquals("lost/0e7d.jpg", rows[0].image_path)
        assertEquals("98765 43210", rows[0].contact_info)
        assertEquals(null, rows[1].image_path)
        assertEquals("", rows[1].description)
        // Pre-update rows (and the demo seeds) simply decode with blank contact.
        assertEquals("", rows[1].contact_info)
    }

    @Test
    fun createRpcRowDecodes() {
        // The create_lost_found_post RPC returns the created row as a single
        // JSON object — the same shape the insert() call decodes.
        val json = Json { ignoreUnknownKeys = true }
        val body = """
            {
              "id": "9b1f2c00-1111-4f6f-9a52-1b1e8ef41c5c",
              "kind": "found",
              "title": "Found: blue Nike water bottle",
              "description": "Left near the CSE lab on Thursday.",
              "image_path": "found/9b1f.jpg",
              "created_at": "2026-11-14T09:30:00+00:00",
              "contact_info": "@sameer_on_insta"
            }
        """.trimIndent()
        val row = json.decodeFromString<LostFoundItem>(body)
        assertEquals("found", row.kind)
        assertEquals("@sameer_on_insta", row.contact_info)
    }

    @Test
    fun deletePinRules() {
        // 4-8 digits pass …
        assertTrue(LostFoundClient.isValidDeletePin("1357"))
        assertTrue(LostFoundClient.isValidDeletePin("135790"))
        assertTrue(LostFoundClient.isValidDeletePin("12345678"))
        // … anything else does not.
        assertTrue(!LostFoundClient.isValidDeletePin(""))
        assertTrue(!LostFoundClient.isValidDeletePin("123"))
        assertTrue(!LostFoundClient.isValidDeletePin("123456789"))
        assertTrue(!LostFoundClient.isValidDeletePin("12a4"))
        assertTrue(!LostFoundClient.isValidDeletePin("12 4"))
        assertTrue(!LostFoundClient.isValidDeletePin("12.4"))
    }

    @Test
    fun contactSanitizer() {
        // Pasted numbers with stray runs of whitespace collapse to one line.
        assertEquals("98765 43210", LostFoundClient.sanitizeContact("  98765   43210 "))
        assertEquals("sameer@gndec.ac.in", LostFoundClient.sanitizeContact("sameer@gndec.ac.in"))
        // Hard cap at the server's 120-char column limit.
        assertEquals(120, LostFoundClient.sanitizeContact("a".repeat(400)).length)
        // Tabs and newlines count as whitespace runs too.
        assertEquals("@handle 98765 43210", LostFoundClient.sanitizeContact("@handle\n\t 98765  43210"))
    }

    @Test
    fun relativeTimeRendering() {
        val base = java.time.OffsetDateTime.parse("2026-11-14T12:00:00+00:00")
        val now = base.toInstant().toEpochMilli()
        fun iso(minutesAgo: Long): String = base.minusMinutes(minutesAgo).toString()
        assertEquals("just now", LostFoundRelativeTime.relativeTime(iso(0), now))
        assertEquals("5 min ago", LostFoundRelativeTime.relativeTime(iso(5), now))
        assertEquals("1 hour ago", LostFoundRelativeTime.relativeTime(iso(60), now))
        assertEquals("3 hours ago", LostFoundRelativeTime.relativeTime(iso(180), now))
        assertEquals("yesterday", LostFoundRelativeTime.relativeTime(iso(60 * 24), now))
        assertEquals("3 days ago", LostFoundRelativeTime.relativeTime(iso(60 * 24 * 3), now))
        assertEquals("2 weeks ago", LostFoundRelativeTime.relativeTime(iso(60 * 24 * 14), now))
        // Unparseable input degrades quietly.
        assertTrue(LostFoundRelativeTime.relativeTime("not-a-date", now).isEmpty())
    }
}
