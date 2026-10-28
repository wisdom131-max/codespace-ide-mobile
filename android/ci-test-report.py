#!/usr/bin/env python3
"""Advisor follow-up (2026-10-15, CI hygiene): run after the FULL unit-test
task. Prints per-suite executed-test counts and FAILS the build when a required
suite is missing (or executed zero tests) — a green gradle badge alone used to
hide suites that were only compiled, never executed."""
import collections
import glob
import sys
import xml.etree.ElementTree as ET

REQUIRED = [
    # REMOVAL 4/7: chat suites for removed classes were deleted from this
    # guard list with their test files (AttachmentSheetNotices, ModelCatalog,
    # StatusStripLabel + earlier rot removals).
    "com.codespace.ide.chat.AttachmentSecretsTest",
    "com.codespace.ide.data.SessionStateStoreTest",
    "com.codespace.ide.debug.DAPClientTest",
    "com.codespace.ide.debug.NodeDAPAdapterTest",
    "com.codespace.ide.terminal.TimestampedBackupNameTest",
    "com.codespace.ide.diagnostics.CrumbLogTest",
    "com.codespace.ide.terminal.FileLinkReproTest",
    "com.codespace.ide.debug.LegacyDebugAdapterTest",
    "com.codespace.ide.debug.UniversalDebugManagerTest",
    "com.codespace.ide.environment.GuestCwdHostCheckTest",
    "com.codespace.ide.resolver.HostResolutionTest",
    "com.codespace.ide.resolver.PathDialectsTest",
    "com.codespace.ide.terminal.BackupTarTest",
    "com.codespace.ide.terminal.ContainerSafetyTest",
    "com.codespace.ide.terminal.IdeTerminalBridgeSpanTest",
    "com.codespace.ide.terminal.InstallChoiceMachineTest",
    "com.codespace.ide.terminal.ProotLaunchArgsCwdTest",
    "com.codespace.ide.terminal.RealAssetBackupTest",
    "com.codespace.ide.terminal.TabInstallDecisionTest",
    "com.codespace.ide.terminal.TerminalSessionStoreF3Test",
    "com.codespace.ide.terminal.shellintegration.ShellIntegrationParserTest",
    "com.codespace.ide.terminal.shellintegration.ShellIntegrationStateTest",
    # Pure-JVM ChatEngine suites (rebuild order step 3)
    "com.codespace.ide.engine.SseParserTest",
    "com.codespace.ide.engine.ErrorTaxonomyTest",
    "com.codespace.ide.engine.BudgetGuardTest",
    "com.codespace.ide.engine.HistoryBudgetTest",
    "com.codespace.ide.engine.EngineContractTest",
    # U1 (build 657 findings): the suites that guard slot naming, default selection
    # and the real stored shapes. Guarded so a silently-skipped run fails the build.
    "com.codespace.ide.chat.keys.StoredCredentialsMigrationTest",
    "com.codespace.ide.chat.panel.SelectionResolverTest",
    "com.codespace.ide.chat.panel.ConfiguredProvidersTest",
    "com.codespace.ide.chat.EndpointRoundTripTest",
]

counts = collections.Counter()
failed = collections.Counter()
for path in glob.glob("app/build/test-results/testProdDebugUnitTest/*.xml") + \
               glob.glob("engine/build/test-results/test/*.xml"):
    for tc in ET.parse(path).getroot().iter("testcase"):
        counts[tc.get("classname")] += 1
        if tc.find("failure") is not None or tc.find("error") is not None:
            failed[tc.get("classname")] += 1

print("=== JVM unit-test execution report ===")
for name in sorted(counts):
    print(f"{counts[name]:4d}  {name}")
print(f"TOTAL: {sum(counts.values())} tests across {len(counts)} suites")

ok = True
if not counts:
    print("FATAL: no test results found at all")
    ok = False
missing = [s for s in REQUIRED if counts.get(s, 0) == 0]
if missing:
    print("FATAL: required suites with ZERO executed tests:")
    for s in missing:
        print(f"  {s}")
    ok = False
if failed:
    print("FATAL: failing tests:")
    for name in sorted(failed):
        print(f"  {failed[name]} in {name}")
    ok = False
sys.exit(0 if ok else 1)
