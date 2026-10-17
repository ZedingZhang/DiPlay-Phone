# Selected upstream updates for phone receivers

This integration starts from the phone fork at `3c3e991ba2dbaf626e69ebb30ffb92cc67262394`.
It ports the five groups below from upstream changes after `e6e7cc0`, using upstream
`9509960e96ade3b9184e2f41d3aae3a123a73920` as the reference. It does not merge upstream
main or change the current published APK. The minimum Android version stays at 9/API 28.

## Included changes

| Group | Behavior | Upstream sources |
| --- | --- | --- |
| Hotspot networking | Wait for a usable hotspot interface; prefer IPv4 with scoped IPv6 fallback. The manual hotspot and Same LAN publish and listen on both usable families; P2P and LocalOnlyHotspot retain a single selected address. | [#229](https://github.com/shihabal3amri/DiPlay/pull/229), [#283](https://github.com/shihabal3amri/DiPlay/pull/283), [#394](https://github.com/shihabal3amri/DiPlay/pull/394) |
| Music playback | Account for retained PCM when recovering the prebuffer; bound decoder backpressure; compensate for clock drift using decoded and queued audio; recover sustained stale music without dropping short catch-up bursts; recover focus when the iPhone resumes. | [#401](https://github.com/shihabal3amri/DiPlay/pull/401), audio portions of [#644](https://github.com/shihabal3amri/DiPlay/pull/644), [#788](https://github.com/shihabal3amri/DiPlay/pull/788), [#814](https://github.com/shihabal3amri/DiPlay/pull/814), [#804](https://github.com/shihabal3amri/DiPlay/pull/804) |
| Phone calls and Siri | Add the per-call loudspeaker choice, fall back from an earpiece route, restore the previous route/mode, use the negotiated microphone RTP clock and recover bounded recording failures. | [#704](https://github.com/shihabal3amri/DiPlay/pull/704), [#797](https://github.com/shihabal3amri/DiPlay/pull/797), [#295](https://github.com/shihabal3amri/DiPlay/pull/295), microphone portions of [#644](https://github.com/shihabal3amri/DiPlay/pull/644) |
| Diagnostics and startup | Include a bounded, redacted last-crash record in exported diagnostics; show missing permissions and typed startup causes; stop after five automatic startup retries and offer a manual retry. | [#700](https://github.com/shihabal3amri/DiPlay/pull/700), crash-record refinement from [#827](https://github.com/shihabal3amri/DiPlay/pull/827), [#824](https://github.com/shihabal3amri/DiPlay/pull/824), [#795](https://github.com/shihabal3amri/DiPlay/pull/795), retry prerequisites from #229 |
| Existing Wi-Fi / Same LAN | Use a selected external Wi-Fi network without creating a hotspot or changing the default route; retain separate router credentials and restart after network/address changes. | [#223](https://github.com/shihabal3amri/DiPlay/pull/223), lazy NSD lookup `32550b2a`, Android 9–11 request compatibility `d729cd89` |

The music-clock correction includes #814, which fixes backlog introduced by #788 after
the upstream 0.2.20 release. Phone-specific adaptations remain in place: the unified
swipe settings page, compact landscape layout, freely rotating settings with locked
CarPlay direction, effective size presets and hidden BYD return tile on non-BYD devices.
The upstream settings redesign, square-canvas rotation, BYD features and built-in APK
updater are outside this integration.

## New settings

**Settings → Audio routing → Calls on the loudspeaker** is off by default. On an Android
phone or tablet used as the receiver, it requests the loudspeaker for the next CarPlay
call. The choice is read for each call, including after returning from Settings while
a session remains connected. Ending the call, microphone failure or disconnection
releases the call route and restores the prior audio mode. Actual vendor routing and
microphone behavior require a device test.

For **Connection setup → Existing Wi-Fi / Same LAN**, first connect the Android receiver
and iPhone to the same third-party router in their system settings. Enter that router's
exact SSID and WPA2 password in DiPlay, save, and connect as usual with Bluetooth enabled.
An open network uses an empty password. A WPA2/WPA3 mixed router must also allow WPA2; this combination still needs a phone test. WPA3-only,
enterprise networks and captive portals are outside this mode's scope. The router must
allow communication and mDNS between clients. These credentials do not overwrite the
Android phone's saved hotspot details. If Android hides the live SSID, check location
access and the connection in system settings.

## Validation and device acceptance

Automated regressions cover the two network families, listener port-collision cleanup,
network callback ownership, hotspot readiness, startup timeout/retry ownership, missing
permissions, audio clock/backlog behavior, focus grant/refusal, per-call routing and
microphone failure cleanup. The existing phone layout, size, OEM icon and orientation
tests must continue to pass alongside them.

Before distributing a standalone test APK, use the build and identity checks in
[BUILD.md](BUILD.md). A source-only APK cannot establish an iPhone connection. On the
Mi 10 / Android 11 and the iPhone 16 Pro Max / iOS 18.7.8, verify:

1. Keep the existing Android-hotspot setup. Reconnect repeatedly, run music for at least
   30 minutes, and check track changes, navigation ducking, interruption and resumption.
2. With the speaker option off and then on, make and end calls, use Siri, open Settings
   between calls, and verify the next call uses the saved option. Disconnect during a
   call and confirm normal Android playback and routing return.
3. On a separate WPA2 router, save Same LAN details, cancel an edit, connect, then leave
   and rejoin the network. Confirm phone-hotspot credentials and normal connection mode
   are retained when switching back.
4. Refuse wireless permissions or disable the selected network. Check that the screen
   explains the cause, stops automatic startup retries and offers a manual retry.
5. Repeat the phone size, touch, Chinese landscape, swipe-settings and orientation-lock
   checks in [TESTING.md](TESTING.md). Export diagnostics immediately after failures.

These changes do not establish that the unresolved Mi 10 Wi-Fi Direct connection issue
is fixed. No new physical-phone acceptance is claimed by this source integration.
