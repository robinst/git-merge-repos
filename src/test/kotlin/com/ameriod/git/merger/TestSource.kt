package com.ameriod.git.merger

import kotlin.test.assertEquals
import org.junit.Test
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TestSource {
    @Test fun test() {
        assertEquals(2, 1 + 1)
    }

    @Test fun indexOutOfBoundsReturnsNull() {
        assertNull(getArgAtIndex(arrayOf("1", "2"), 3), "there is no value at index 3 and the return is null")
    }

    @Test fun indexInBoundsReturnsValue() {
        assertEquals("2", getArgAtIndex(listOf("1", "2").toTypedArray(), 1))
    }

    @Test fun passwordAndUsernameBothAreRequired() {
        assertNotNull(getCredentialsProvider(arrayOf("", "", "username", "password")))
    }

    @Test fun passwordWithNoUsernameCrashes() {
        assertFails { getCredentialsProvider(arrayOf("", "", "", "password")) }
    }

    @Test fun noPasswordWithUsernameCrashes() {
        assertFails { getCredentialsProvider(arrayOf("", "", "username", "")) }
    }

    @Test fun noPasswordNoUsernameNull() {
        assertNull(getCredentialsProvider(arrayOf("", "")), "when username and password are not provided then there is no credentials provider")
    }

    @Test fun fileToJsonWorks() {
        assertEquals(4, getSubtreeConfigs(arrayOf("test.json")).size)
    }
}
