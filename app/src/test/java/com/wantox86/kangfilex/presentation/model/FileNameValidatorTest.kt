package com.wantox86.kangfilex.presentation.model

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameValidatorTest {

    @Test
    fun emptyAndBlankNamesAreRejected() {
        assertNotNull(FileNameValidator.validate(""))
        assertNotNull(FileNameValidator.validate("   "))
        assertNotNull(FileNameValidator.validate("\t\n "))
    }

    @Test
    fun slashInNameIsRejected() {
        assertNotNull(FileNameValidator.validate("/"))
        assertNotNull(FileNameValidator.validate("a/b"))
        assertNotNull(FileNameValidator.validate("folder/"))
    }

    @Test
    fun dotAndDoubleDotAreRejected() {
        assertNotNull(FileNameValidator.validate("."))
        assertNotNull(FileNameValidator.validate(".."))
        assertNotNull(FileNameValidator.validate(" . "))
        assertNotNull(FileNameValidator.validate(" .. "))
    }

    @Test
    fun nulCharacterIsRejectedWithIndonesianMessage() {
        val nulOnly = FileNameValidator.validate("\u0000")
        assertNotNull(nulOnly)
        assertTrue(nulOnly!!.contains("NUL"))

        val embeddedNul = FileNameValidator.validate("file\u0000.txt")
        assertNotNull(embeddedNul)
        assertTrue(embeddedNul!!.contains("NUL"))

        val trailingNul = FileNameValidator.validate("notes\u0000")
        assertNotNull(trailingNul)
        assertTrue(trailingNul!!.contains("NUL"))
    }

    @Test
    fun validNamesAndNamesWithOuterSpacesPass() {
        assertNull(FileNameValidator.validate("notes.txt"))
        assertNull(FileNameValidator.validate("folder_1"))
        assertNull(FileNameValidator.validate(".hidden"))
        assertNull(FileNameValidator.validate("a..b"))
        assertNull(FileNameValidator.validate("  notes.txt  "))
        assertNull(FileNameValidator.validate("\tmy folder \n"))
    }
}
