# Lazy Reader — Play Store listing copy

Paste-ready. Character limits noted; counts verified against Play's caps.

---

## App title (max 30)

```
Lazy Reader
```

## Short description (max 80)

```
Hands-free PDF & EPUB reader. Turn pages with your voice. 100% offline.
```
*(71 characters)*

---

## Full description (max 4000)

```
Lazy Reader is a document reader for people who read lying down.

Turn pages with your voice — no tapping, no swiping, no reaching for your phone.


VOICE CONTROL

Say "go" to move forward. Say "backward" to go back. Say "stop" to switch
listening off and lock the screen against accidental taps.

Recognition runs entirely on your phone, using a small speech model built into
the app.


TRULY OFFLINE — AND YOU CAN CHECK

Lazy Reader does not request internet permission. Android itself prevents the
app from opening any network connection, so this is not a promise buried in a
policy document — it is enforced by your operating system, and you can verify
it yourself in the app's system settings.

Your books, your reading progress, and your voice never leave your device.


ABOUT THE MICROPHONE

Audio is analysed in memory the moment it is heard, then immediately discarded.
Nothing is recorded, stored, or uploaded — the app has no ability to upload
anything at all. The microphone is active only while voice control is switched
on, and saying "stop" turns it off completely.


READING

• PDF and EPUB support
• Clean, properly paginated text
• Chapter navigation from the book's own table of contents
• Page slider for jumping through PDFs
• Reading progress remembered for every book
• Screen stays awake while you read
• Controls fade away so nothing covers the page


NO NONSENSE

No ads. No analytics. No crash reporting. No sign-in. No accounts. No data
collection of any kind.

Free, and built to stay out of your way.
```

---

## Data safety form answers

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | N/A — no data collected or transmitted |
| Do you provide a way for users to request that their data is deleted? | N/A — no data collected |

**Microphone / audio:** Play's form asks about *collected* data. Audio here is
processed transiently on-device and never stored or transmitted, which does not
count as collection under Play's definition. Declare **no data collected**.
Be ready to explain in review notes: the app holds no `INTERNET` permission, so
transmission is impossible.

**Privacy policy URL:** https://jha11aditya.github.io/lazy_reader/

---

## Permissions justification (for review notes)

```
The app requests RECORD_AUDIO to recognise three spoken navigation commands
("go", "backward", "stop") so users can turn pages hands-free while reading.

Audio is processed on-device by a bundled TensorFlow Lite model via MediaPipe
Audio Classifier. It is held briefly in an in-memory buffer for classification
and then discarded. No audio is written to storage or transmitted.

The app does not hold the INTERNET permission, so transmission of any data is
blocked by the operating system.
```

---

## Assets checklist

| Asset | Requirement | Status |
|---|---|---|
| App icon | 512×512 PNG, 32-bit | ⬜ export from adaptive vector |
| Feature graphic | 1024×500 | ✅ `feature_graphic.png` |
| Phone screenshots | 2–8, ratio ≤ 2:1 | ✅ `01`–`05` at 800×1400 |
| Privacy policy URL | public | ✅ live |
| Signed `.aab` | — | ✅ `app/build/outputs/bundle/release/` |
