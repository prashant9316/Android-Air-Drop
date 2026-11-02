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

Ways to start talking: the big button in the app; **long-press home / swipe from a
bottom corner** once CareMate is the default assistant (works on the lock screen too);
the on-screen **accessibility button**; the **Quick Settings tile**; long-press the app
icon for **Talk / SOS** shortcuts.

## Install on Mum's phone

1. **Get the APK.** Either download it from this repo's GitHub Actions
   (Actions > *CareMate APK* > latest run > Artifacts > `CareMate-apk`), or build it:
   `cd caremate && ./gradlew assembleRelease` →
   `app/build/outputs/apk/release/app-release.apk`.
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
7. Add a few memories to start (Memory screen > *What I know*): medicines and timings,
   doctor, key people, home address.

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

- **No always-listening "Hey…" wake word.** Android only gives hotword access to
  system apps like Google's. Use long-press home, the accessibility button, or the tile.
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
└─ ui/                     Compose screens: home, setup checklist, settings, memory
```

Run the tests with `./gradlew testDebugUnitTest`.
