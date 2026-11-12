package com.codespace.ide.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8.B: a credential only travels to the host it was issued for. Every case here is a way the key
 * could otherwise leave the device, so the assertions are about refusal, not convenience.
 */
class CredentialHostGuardTest {

    private val supabase = "https://abcdefgh.supabase.co"

    private fun refused(service: String, base: String, target: String): Boolean =
        CredentialHostGuard.check(service, base, target) is CredentialHostGuard.Decision.Refused

    @Test fun theConfiguredHostAndItsPathsAreAllowed() {
        assertTrue(!refused("supabase", supabase, "$supabase/rest/v1/items?select=*"))
        assertTrue(!refused("supabase", supabase, "https://abcdefgh.supabase.co"))
    }

    @Test fun anAbsoluteUrlToAnotherHostIsRefused() {
        assertTrue(refused("supabase", supabase, "https://attacker.example/collect"))
        assertTrue(refused("n8n", "https://n8n.example.com", "https://evil.example.com/hook"))
    }

    @Test fun aLookalikeHostIsRefused() {
        assertTrue("a suffix match must be on label boundaries", refused("supabase", supabase, "https://abcdefgh.supabase.co.evil.tld/x"))
        assertTrue(refused("firebase", "https://proj.firebaseio.com", "https://googleapis.com.evil.tld/x"))
    }

    @Test fun plainHttpIsRefusedBecauseTheKeyWouldBeInTheClear() {
        assertTrue(refused("supabase", supabase, "http://abcdefgh.supabase.co/rest/v1/x"))
    }

    @Test fun firebaseMayReachGooglesApiSurfaceButNothingElse() {
        val base = "https://proj-1234.firebaseio.com"
        assertTrue(!refused("firebase", base, "https://identitytoolkit.googleapis.com/v1/accounts:signIn"))
        assertTrue(!refused("firebase", base, "https://proj-1234.firebaseio.com/users.json"))
        assertTrue(refused("firebase", base, "https://example.com/x"))
    }

    @Test fun anotherServiceDoesNotInheritFirebasesAllowance() {
        assertTrue(refused("supabase", supabase, "https://identitytoolkit.googleapis.com/v1/x"))
    }

    @Test fun aMalformedTargetIsRefusedRatherThanGuessed() {
        assertTrue(refused("supabase", supabase, "not a url"))
        assertTrue(refused("supabase", supabase, "https:///nohost"))
        assertTrue(refused("supabase", "not a base", "$supabase/x"))
    }

    @Test fun userInfoAndPortsCannotBeUsedToSmuggleAHost() {
        assertTrue(refused("supabase", supabase, "https://abcdefgh.supabase.co@attacker.example/x"))
        assertTrue(!refused("supabase", supabase, "https://abcdefgh.supabase.co:443/rest/v1/x"))
    }
}
