# WiFi TV Receiver

Turns an Android phone into a Wi-Fi screen-cast receiver. A laptop (Chrome/Edge) is the sender.

    Laptop browser (sender.js)  <--WebRTC media (P2P, same Wi-Fi)-->  Phone app (Kotlin)
            \__ WebSocket signaling via server.py (SDP/ICE text only) __/

## STATUS: what is and is not verified
| Item | Status |
|---|---|
| Python server: pairing, relay, LAN-only filter, origin check, brute-force limit | Automated tests pass (`python3 server/test_server.py`, 8 tests) |
| sender.js | Syntax-checked only. NOT run in a browser against a phone |
| Android app | Source written, **never compiled** (build environment had no Android SDK / Maven access). Expect to fix small compile errors on first build |
| APK | **Not built yet.** Build it with one of the methods below |
| Real streaming, audio, reconnection, fullscreen | **Not tested on any device** |

## 1. Get the APK
**A. No install needed: GitHub Actions.** Push this folder to a GitHub repo, open Actions > "Build APK" > Run workflow, download `WiFi-TV-Receiver-apk`. (Free for public repos.)

**B. Android Studio.** Open the `android/` folder, let it sync, then Build > Build APK(s). Or run `./build-apk.sh` (needs `ANDROID_HOME`, JDK 17, Gradle 8.7).
Output: `output/WiFi-TV-Receiver.apk` (debug-signed, installable).

**C. Release build.** Put `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` in `~/.gradle/gradle.properties`, then `gradle assembleRelease`.

## 2. Install on the phone
Copy the APK to the phone, open it, allow "Install unknown apps" for your file manager/browser when asked.

## 3. Use it
1. Phone and laptop on the **same Wi-Fi** (not a guest/"client isolation" network).
2. Laptop: install Python 3.8+, run `python server/server.py` (Windows: double-click `start-server.bat`). It prints the laptop address, e.g. `192.168.1.20:8765`, and opens the sender page.
3. Phone: open WiFi TV Receiver, type the laptop address, tap **Start Receiving**. It shows a 6-digit code.
4. Laptop sender page: enter the code, click **Start Casting**, pick a screen / window / tab.
Phone shows the picture full-screen; tap it for volume, mute, fit/fill, disconnect.

Windows firewall: allow Python on **Private networks** when prompted (port 8765 TCP; WebRTC media uses ephemeral UDP).

## Audio
Tab share: tick "Also share tab audio". Entire screen: "Also share system audio" works on **Windows only**. Window shares have no audio. macOS/Linux cannot capture system audio from the browser.

## Security / privacy
Server accepts only private/loopback/link-local clients (override `--allow-public`, not recommended). Pairing needs the 6-digit code; 5 wrong codes per minute per IP are blocked. Cross-origin WebSocket requests are refused. No recording, no cloud, no analytics. Android permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE (show IP, detect loss), WAKE_LOCK (keep screen on), MODIFY_AUDIO_SETTINGS (volume/speaker). Cleartext `ws://` is allowed because the laptop server has no TLS cert; it carries only SDP/ICE, and media is DTLS-SRTP encrypted.

## Known limitations
- Phone connects out to the laptop, so you type the laptop address once (no auto-discovery, no QR code yet).
- One sender at a time. Windows sender only officially; Chrome/Edge on other OSes should work.
- Chrome hides its LAN IP behind mDNS names; connection should still form because the phone's real IP is offered. If it does not, disable `chrome://flags/#enable-webrtc-hide-local-ips-with-mdns`.
- Quality is adaptive; the phone's quality choice is a cap, not a guarantee.

## Troubleshooting
- "Cannot connect": wrong laptop address, firewall, or different network. Open `http://<laptop-ip>:8765/api/info` in the phone's browser to test.
- "No receiver found": tap Start Receiving on the phone first; check the code.
- Black picture / stuck negotiating: try 480p, disable VPNs, avoid AP/client isolation.
- No audio: see Audio section; also raise volume in the phone's controls.
