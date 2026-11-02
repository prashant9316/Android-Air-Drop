# CareMate: a voice assistant to look after Mum

CareMate is a native Android app (Kotlin + Jetpack Compose) that works like Google
Assistant but is built around **Claude** and around caring for an older person. You
talk to it (or Mum does), and it actually operates the phone: calls, WhatsApp, SMS,
reminders, YouTube, settings, and any other app it can see on screen. It keeps an
organised long-term memory of the people, medicines, routines and preferences in her life.

## What it can do

| Area | Examples |
|---|---|
| Calls & messages | "Call Rahul", "Send a WhatsApp to Priya saying I reached home", "Who called me?", "Read my new messages" |
| Any app (screen control) | "Play old Lata Mangeshkar songs on YouTube", "Open the photo Priya sent on WhatsApp", "Turn on Wi-Fi". It reads the screen, taps, types, scrolls and checks the result. |
| Medicines & reminders | "Remind me every day at 9 am to take my BP tablet": the phone rings and **reads the reminder aloud**, even when locked. Survives reboots. |
| Memory | Remembers facts as you talk ("My doctor is Dr Mehta, 98xxxxxx") in categories: People, Health, Routines, Preferences, Places, Events, How-tos, Notes. You can view and edit it in the app. |
| Phone settings | Volume, silent/vibrate, torch, brightness, any settings page, battery and network status |
| Out and about | "Where am I?", "Directions to the temple", calendar appointments |
| Questions | Weather, news, general questions (Claude with web search) |
| Safety | Big **SOS** button (hold): texts every family contact her **location** and calls the first one. Saying "help, I fell" does the same, and if she can't answer the confirmation it goes ahead anyway. |
| Scam protection | Will never read out or send OTPs, PINs or bank details, warns about "bank/police/urgent money" messages, makes no payments. |
| Remote check-in | You text her phone `CAREMATE <PIN> WHERE` and get a map link back. Also `STATUS`, `RING` (loud alarm to find the phone), `NOTE <text>` (adds to her assistant's memory), `REMIND 18:30 <text>`. Only your numbers + PIN work. |
| Offline basics | With no internet: call people, time/date, battery, torch, open apps, SOS still work. |

Ways to start talking: **say the wake word** ("Hey Jarvis", "Alexa", "Hey Mycroft", or
your own phrase), from anywhere, even with the screen off or locked; the big button in
the app; **long-press home / swipe from a bottom corner** once CareMate is the default
assistant (works on the lock screen too);
the on-screen **accessibility button**; the **Quick Settings tile**; long-press the app
icon for **Talk / SOS** shortcuts.

## Install on Mum's phone

1. **Get the APK.** Either download it from this repo's GitHub Actions
   (Actions > *CareMate APK* > latest run > Artifacts > `CareMate-apk`), or build it:
   `cd caremate && ./gradlew assembleRelease` → `app/build/outputs/apk/release/`.
   Use `app-arm64-v8a-release.apk` (almost every phone from 2017 on). Only very old or
   budget phones need `app-armeabi-v7a-release.apk`; if the first one says "app not
   compatible", use that one.
2. Copy it to the phone and open it. Allow "install unknown apps" when asked.
3. Get a Claude API key at [console.anthropic.com](https://console.anthropic.com)
   (Settings > API keys). Add some credit; see *Cost* below.
4. Open CareMate. The **setup checklist** walks through every permission, with a reason
   for each. Then open **Settings** and fill in:
   - the API key (stored encrypted with the phone's Android Keystore),
   - what to call her ("Mum", "Amma"…) and her language (Hindi, Tamil, Bengali… are listed),
   - **About the user**: a few lines that help a lot ("72, lives alone, diabetic,
     hard of hearing, loves old film songs, speaks Marathi and some English"),
   - **Family & emergency contacts**, one per line: `Rahul (son), +91 98xxxxxxxx`,
   - optionally **Remote check-in by SMS**: your number(s) and a secret PIN.
5. Turn on **Screen control** (Settings > Accessibility > CareMate screen helper).
   On Android 13+ this is greyed out for sideloaded apps: open
   *Settings > Apps > CareMate > ⋮ (top right) > Allow restricted settings*, then try again.
   Do the same if "Notification access" is greyed out.
6. Make it the **default assistant**: Settings > Apps > Default apps > Digital assistant app > CareMate.
7. Turn on the **wake word**: Settings > Wake word > *Listen for a wake word*, pick the
   phrase, and set the sensitivity. Say it from across the room to test; if it misses
   her voice, raise the sensitivity. If it wakes up by itself, lower it.
8. Add a few memories to start (Memory screen > *What I know*): medicines and timings,
   doctor, key people, home address.

## Wake word

Wake-word detection runs **entirely on the phone** with
[openWakeWord](https://github.com/dscripka/openWakeWord) models on ONNX Runtime. A
small foreground service (shown as a "Say Hey Jarvis to talk" notification) listens in
80 ms chunks; no audio is stored or sent anywhere until the phrase is heard. Then
it beeps, buzzes, wakes the screen, and CareMate starts listening normally. It
lets go of the microphone during phone calls and while CareMate itself is listening or
speaking.

- **Phrases included:** "Hey Jarvis" (the default), "Alexa" (Amazon speakers nearby may
  answer too), "Hey Mycroft". These models are English-trained but work for most accents.
- **Your own phrase** (e.g. "Hey CareMate", or a name Mum likes): train one for free with
  openWakeWord's [automatic training notebook](https://github.com/dscripka/openWakeWord#training-new-models)
  in Google Colab (about an hour, no audio recording needed), copy the resulting
  `.onnx` file to the phone, then Settings > Wake word > *Import custom wake phrase*.
- **Battery:** listening all the time costs some battery (how much depends on the phone;
  check Settings > Battery after a day). Switch it off from the notification's *Turn off* button.
- **After a restart** on Android 14+, Android won't let an app open the microphone until
  it's been opened once; CareMate shows a "Tap to switch on" notification.
- The included models are licensed CC BY-NC-SA 4.0 (non-commercial), which is fine for
  personal use.

The detector is a line-for-line port of the reference Python pipeline. Its tests check
it against the reference scores, chunk by chunk, and against real speech for each
phrase (`./gradlew :wakeword:test`).

## Cost

Each request goes to the Claude API, billed to your key. Default model is
**Claude Opus 5.5** with *low* thinking effort, which keeps replies quick. For a lot of
daily use you can switch to Claude Sonnet 5.5 or Claude Haiku 5.5 (much cheaper) in
Settings. The instructions and tool list are prompt-cached, so repeat requests cost less.
Screen-control tasks take several steps and cost more than a simple question.

## Privacy: what leaves the phone

Sent to Anthropic's API when someone talks to the assistant: the transcribed request,
the saved memories and reminders (as context), and, during screen-control tasks, the
text on the screen and screenshots. Speech-to-text uses the phone's own recogniser
(usually Google's). Memories, reminders and the activity log are stored only on the
phone, in the app's private storage, and are not backed up. Nothing is sent anywhere
else. The **Activity** tab in Memory shows what was asked, what was answered, and every
SOS and caregiver SMS, so you can check in on how she's using it.

## Limits worth knowing

- The wake word uses its own detector, because Android reserves its built-in low-power
  hotword hardware for preinstalled apps like Google's. That's why it uses a bit more
  battery than "Hey Google".
- Screen control is as good as what apps expose to accessibility. WhatsApp, YouTube and
  Settings work well; some games and banking apps hide their screens (on purpose).
- Banking/UPI apps: by design CareMate will not make payments.
- It uses permissions Google Play restricts (SMS, call log, accessibility for automation),
  so it's for personal sideloading, not for publishing on the Play Store.
- Not tested on a physical device yet: the request/response logic has unit tests, but
  please try the main flows (calls, a reminder, a WhatsApp, SOS with your own number)
  before relying on it.

## How it's built

```
caremate/app/src/main/java/com/caremate/assistant/
├─ AssistantEngine.kt      listen → think → act → speak loop, yes/no confirmations
├─ brain/Agent.kt          Claude Messages API tool-use loop (Anthropic Java SDK)
├─ brain/ToolBox.kt        39 phone tools Claude can call (plus web search), and their implementations
├─ brain/SystemPrompt.kt   persona, safety rules, memories + reminders as context
├─ brain/LocalCommands.kt  offline fallback
├─ device/ScreenAgentService.kt  accessibility: read screen, tap, type, scroll, screenshot
├─ device/PhoneActions.kt  calls, SMS, WhatsApp, apps, alarms, volume, calendar…
├─ device/NotificationCatcher.kt  reads incoming notifications
├─ memory/MemoryStore.kt   SQLite: memories, reminders, activity log
├─ reminders/              exact alarms + spoken reminders + reboot rescheduling
├─ care/Care.kt            SOS, find-my-phone, caregiver SMS commands
├─ voice/                  speech in/out, default-assistant services
├─ wake/WakeWordService.kt always-on microphone service for the wake word
└─ ui/                     Compose screens: home, setup checklist, settings, memory

caremate/wakeword/          on-device wake-word detector (pure Kotlin + ONNX Runtime)
```

Run the tests with `./gradlew testDebugUnitTest :wakeword:test`.
