# Security policy

Handoff lets one of your devices ask another to disconnect your headphones, so security problems matter even when they look small. Thank you for reporting them responsibly.

## Supported versions

Only the latest release gets security fixes. Handoff updates itself (GitHub edition and Windows) or through Google Play, so please check you're on the [latest release](https://github.com/FancyCorey/handoff/releases/latest) before reporting.

## Reporting a vulnerability

**Please don't open a public issue for security problems.**

Report it privately on GitHub: go to the repository's **Security** tab and choose **Report a vulnerability** ([direct link](https://github.com/FancyCorey/handoff/security/advisories/new)). Only the maintainer can see it.

Please include:

- what an attacker could do, and what they need (same Wi-Fi, a linked device, physical access…);
- steps to reproduce, and the Handoff version and platform;
- a diagnostics report if it helps (it never contains keys).

## What happens next

| When | What |
|---|---|
| Within 7 days | We confirm we received the report. |
| Within 30 days | We share our assessment and a plan, or explain why it isn't a vulnerability. |
| Within 90 days | A fix is released, and the report is published as a GitHub security advisory crediting you (unless you'd rather not be named). |

If a fix needs longer, we'll tell you why and agree on a new date. Please don't disclose the problem publicly before the fix is out or the 90 days have passed.

## Scope

In scope: the Android and Windows apps, the peer protocol and linking, the update mechanism, and the release process.

Out of scope: problems that need a device that is already compromised or rooted, or physical access to an unlocked device; denial of service by someone who controls your Wi-Fi network (they can already block it); and the hidden Android Bluetooth methods Handoff depends on (see [Bluetooth compatibility](docs/BLUETOOTH_COMPATIBILITY.md)).

How Handoff is secured is described in the [security design](docs/SECURITY.md).
