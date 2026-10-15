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
    "com.codespace.ide.chat.AttachmentSecretsTest",
    "com.codespace.ide.chat.AttachmentSheetNoticesTest",
    "com.codespace.ide.chat.ModelCatalogTest",
    "com.codespace.ide.chat.StatusStripLabelTest",
    "com.codespace.ide.data.SessionStateStoreTest",
    "com.codespace.ide.debug.DAPClientTest",
    "com.codespace.ide.debug.NodeDAPAdapterTest",
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
]

counts = collections.Counter()
failed = collections.Counter()
for path in glob.glob("app/build/test-results/testProdDebugUnitTest/*.xml"):
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
