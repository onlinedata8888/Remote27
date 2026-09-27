# remote_r — Google TV / Android TV remote (real protocol)

Aapki `remote_r.apk` ka UI **bilkul same** hai (HTML markup + CSS byte-for-byte unchanged). Sirf peeche ka connection ab
asli **Android TV Remote Protocol v2** hai (wahi jo Google TV app use karti hai).

## Kya real hai
- TV discovery (3 tarike ek saath): Android NSD mDNS + apna raw mDNS query + Wi-Fi subnet scan (port 6466)
- **TV na mile to**: dropdown kholo -> upar ki "Searching for TVs…" line par tap -> TV ka IP daalo (10 sec me kuch na mile to ye dialog apne aap aata hai)
- Pairing: TLS port **6467**, 6-character code, SHA-256 secret (self-signed RSA-2048 client certificate)
- Remote: TLS port **6466**, protobuf messages, ping/keepalive, auto-reconnect
- D-pad / OK / Back / Home / Recent / Power / Mute / Volume / Channel / numbers / colour keys / media keys
- Touchpad (tap = OK, drag = arrows), scroll wheels = arrows, volume wheel + slider = volume keys
- On-screen keyboard (and phone keyboard) types into the TV (IME text; key-code fallback)
- App row launches apps by link (`APP_LINKS` in `app/assets/remote.html`)
- Speed: persistent TLS connection, TCP_NODELAY, key **down on touch / up on release**, batched volume steps

## Pehli baar use
1. Purani `remote_r` app **uninstall** karo (signing key alag hai), phir `dist/remote_r_googletv.apk` install karo.
2. Phone aur TV **same Wi-Fi** par. App khulte hi TV list me aa jayega (dropdown me select karo).
3. TV par 6-character code aayega -> app ka **keyboard** khulega -> code type karke **Done**.
4. Status dot green = connected. Dot par tap = reconnect / dobara pair.

## Jo nahi ho sakta (protocol limit)
- `Cast`, `Mirror` buttons: is protocol me nahi hain (toast dikhta hai).
- `P.Mode`, `S.Mode`, `Wi-Fi`, `Bluetooth`: TV ki **Settings** kholte hain (vendor-specific keys nahi hote).
- Mic button: TV par Search kholta hai; phone se voice streaming implement nahi hai.
- TV deep standby me ho to power-on ke liye TV ka network-standby/Wake-on-LAN on hona chahiye.

## Build
```
./build_apk.sh      # Linux/macOS/WSL, Java 17+, python3
```
Output: `dist/remote_r_googletv.apk`

### GitHub Actions (auto-build)
Push this repo to GitHub and `.github/workflows/build.yml` builds the APK for you on every push
(and on demand via the "Run workflow" button under the Actions tab). The finished
`remote_r_googletv.apk` shows up as a downloadable artifact on the workflow run — no local
Android setup needed. Push a tag like `v1.1` and it's also attached to a GitHub Release automatically.

## Recent fixes (this build)
- **Mic / voice command capturing nothing**: recording used to start (`AudioRecord.startRecording()`)
  *before* the app had even heard back from the TV. While waiting up to ~2.5 s for the TV's reply,
  the phone's mic buffer (only ~2 s deep) filled up and overflowed with silence/handshake noise —
  and that stale audio is what got sent first once streaming actually began, so the TV opened its
  mic UI but never heard real speech. Fixed: the mic now only starts capturing *after* the TV has
  confirmed it is listening, so the first byte sent is genuinely live audio from when you start
  talking. The "TV ne voice session nahi kholi" timeout was also extended (2.5s → 4s) since some
  TVs are slower to respond over Wi-Fi than the fake test TV.
- **Quick Settings (the 3-line button, top-left)**: already wired to long-press Home (which is how
  Google TV opens the settings panel that slides in from the right edge), but the hold was only
  650ms — extended to 900ms, comfortably above the ~500ms long-press threshold most TVs use, so it
  registers reliably instead of sometimes being read as a normal Home tap.

## Structure
- `app/java/.../TvBridge.java` — JS<->native bridge, connection state machine, pairing flow
- `PairingSession.java`, `RemoteSession.java`, `Msgs.java`, `Pb.java`, `CertStore.java`, `Tls.java`, `Discovery.java`
- `app/assets/remote.html` — aapka UI (sirf `<script>` me TVNative wiring; `patch/patch_html.py` diff banata hai)
- `patch/patch_apk.py` — MainActivity hook + Wi-Fi permissions
- `tests/` — `faketv.py` (reference library ke protobuf schema par bana fake TV) + `TestMain.java`

## Testing status (honest)
Real Google TV par abhi test **nahi** hua. Protocol ko fake TV ke against verify kiya gaya
(reference `androidtvremote2` ke schema/algorithm se): pairing hash, handshake, ping, key down/up, volume, IME text, app launch.
Real TV par kuch button galat behave kare to `K` / `PAGE_KEYS` / `APP_LINKS` (remote.html ke top par) badalna kaafi hai.
