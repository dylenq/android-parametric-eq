package com.dylenq.eq.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreNoticeTextTest {
    @Test
    fun no_notice_has_no_text() {
        assertNull(storeNoticeText(null))
    }

    @Test
    fun the_two_set_aside_texts_are_unchanged() {
        assertEquals(
            "Saved settings were made by a newer version and were set aside",
            storeNoticeText(StoreNotice.NewerVersion(2u)),
        )
        assertEquals("Saved settings were damaged and were set aside", storeNoticeText(StoreNotice.Damaged))
    }

    @Test
    fun a_repair_with_a_backup_says_a_backup_was_kept() {
        val text = "Some saved settings were damaged and were removed. A backup was kept."
        assertEquals(text, storeNoticeText(StoreNotice.Repaired(1u)))
        assertEquals(text, storeNoticeText(StoreNotice.Repaired(7u, backedUp = true)))
    }

    @Test
    fun a_repair_without_a_backup_does_not_claim_one() {
        assertEquals(
            "Some saved settings were damaged and were removed.",
            storeNoticeText(StoreNotice.Repaired(1u, backedUp = false)),
        )
    }

    @Test
    fun the_repair_notice_carries_its_count_and_defaults_to_backed_up() {
        assertEquals(StoreNotice.Repaired(3u, true), StoreNotice.Repaired(3u))
    }
}
