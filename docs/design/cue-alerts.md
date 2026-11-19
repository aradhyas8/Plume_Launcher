# Cue alerts: one design on Home and over apps

Status: built in the Cue alerts PR (replaces PR 287).
Owner: you (junior developer). Reviewer: the lead.

## 1. Problem

The PR shows alerts in two ways, and they do not agree.

| | Cue on Home now | Pill over apps now |
|---|---|---|
| Arrival | Cue opens by itself into the big card | Small pill |
| Time | 6 s open, then 2 min in the pill | 2 s (own setting) |
| Buttons | Only the app's buttons (Full privacy) | App's buttons, Open, Dismiss |
| Card look | Glass, 80 dp picture, buttons on an ink disc | Dark tint, 48 dp picture, white buttons |
| Back | Moves focus away | Closes and dismisses |
| Code | `bindCue` in `CueView.kt` | Own views in `CueOverlay.kt` |

Cause: the overlay has its own copy of the UI. Two copies drift apart.

## 2. Goal

One alert is one object. It looks and acts the same on Home and over apps.
Only two things differ, and only because they must:

1. **How you reach it.** On Home: select Cue. Over an app: press Up while the pill shows.
2. **The surface.** On Home: glass over the wallpaper. Over an app: a solid tint, because there is no wallpaper behind it.

## 3. What it looks like

All sizes are dp. Text sizes are sp. The TV screen is 960 × 540 dp.

### 3.1 Pill (arrival, at rest)

```
╭──────────────────────────────────╮   36 high, corner 24 (CUE_ROUND)
│ (◉)  Front door · Someone at the… │   picture 28, round
╰──────────────────────────────────╯   title 13 sp medium, max width 260
```

- Top centre. Same place on Home (in the top bar) and over apps (24 from the top).
- One line only: the title, then the detail, dimmer (the same rule as a song and its artist).
- No hint text, no arrow.

### 3.2 Open card (alert)

```
╭───────────────────────────────────────────╮  width 300–400, corner 24
│  ╭──────╮                                 │  padding 14 / 14 / 16 / 14
│  │  80  │  Front door                     │  title 16 sp medium, 1 line
│  │ round│  Someone is at the door         │  sub 13 sp, ink 70 %, 2 lines
│  ╰──────╯                                 │
│                                           │  6 between text and buttons
│  ( Open )   Dismiss                       │  buttons 36 high, 15 sp medium
╰───────────────────────────────────────────╯
```

- Focused button: a pill of the ink at 18 % (`0x2E`) behind it. Others: ink at 75 % alpha. No ring, no growth. This is the Home rule today (`bindCue`).
- Button gap 14.

### 3.3 Open card with a camera picture (doorbell, camera; Full privacy only)

```
╭───────────────────────────────────────────╮
│ ╭───────────────────────────────────────╮ │  picture 16:9, full card width
│ │            camera snapshot            │ │  corner 12, centre crop
│ ╰───────────────────────────────────────╯ │
│  (◉) Front door                           │  app icon 28 + title 16 sp
│      Someone is at the door               │  sub 13 sp
│  ( Open )   Dismiss                       │
╰───────────────────────────────────────────╯
```

The round 80 dp picture is too small to see who is at the door, so the snapshot takes the full width.

### 3.4 Call card

```
│  (80)  Alex                               │
│        Incoming call                      │
│  ( Answer )   Decline    Open             │  stays while it rings
```

### 3.5 Timer done card (Plume)

```
│  (80 ⏱ on ink disc)  Timer done           │
│                      10 min               │
│  ( ■ )   +1 min                           │  Stop is an icon, like Home now
```

## 4. Hierarchy, type and icons

**Hierarchy, top to bottom:**
1. Who or what (the picture and the title). The eye must find it in under 1 s from 3 m away.
2. Detail (the sub).
3. What you can do (the buttons). The first button is the main action and gets focus.

**Type.** One family: the system sans (`sans-serif`, Roboto on Android TV). No new fonts.

| Role | Size | Weight | Ink |
|---|---|---|---|
| Pill title | 13 sp | medium | ink |
| Pill app name | 13 sp | regular | ink 70 % |
| Card title | 16 sp | medium | ink |
| Card sub | 13 sp | regular | ink 70 % |
| Button | 15 sp | medium | ink (focused 100 %, others 75 %) |
| Changing numbers (timers) | same | same | `tnum` |

**Icons.**
- Alert picture: the sending app's icon from the notification (`android.appInfo`), round. With Full privacy, the sender's picture (`largeIcon`) when there is one.
- Plume items: Material Symbols Rounded, filled (`ms_*` drawables), on a soft disc of the ink at 15 %.
- Buttons: words for app actions (Answer, Snooze) and for Open and Dismiss. Icons only where Plume has one today (Stop, Play, Pause).
- No emoji, no letter badges (rule from the Cue spec).

## 5. Themes

| Setting | Home: surface | Home: ink | Over apps: surface | Over apps: ink |
|---|---|---|---|---|
| Light + Clear | Clear glass | `inkBehind` (picks light or dark from the wallpaper) | `0xF2F5F5F7` | `0xFF111214` |
| Light + Frosted | Frosted glass | `inkBehind` | `0xF2F5F5F7` | `0xFF111214` |
| Light + Off | Solid tile | `fg` (`0xFF111214`) | `0xF2F5F5F7` | `0xFF111214` |
| Dark + Clear | Clear glass | `inkBehind` | `0xE61B1C20` | `DARK_TEXT` |
| Dark + Frosted | Frosted glass | `inkBehind` | `0xE61B1C20` | `DARK_TEXT` |
| Dark + Off | Solid tile | `fg` (`DARK_TEXT`) | `0xE61B1C20` | `DARK_TEXT` |

Over apps, the Glass setting does nothing (no wallpaper to put glass on). Light or Dark still applies, so the overlay matches the user's theme. Text gets the top bar's shadow on glass only (`ink()` helper), and no shadow on a tint.

## 6. Behaviour

| | Rule |
|---|---|
| Arrival | Pill only. Cue no longer opens by itself on Home. |
| Time | One setting, **Alert time**: 2, 4, 6 or 10 s, default 2 s. Home and over apps both use it. |
| Calls, timer done | Stay until answered, stopped or ended. |
| Open | Home: select Cue (Up from the rows, as now). Over apps: Up while the pill shows. |
| Buttons | Left and Right move. OK presses. Focus starts on the first button. |
| Back | Closes the card. The alert is gone if its time is over. |
| Dismiss | Removes the alert from Cue. It stays in the sending app. |
| Open | Opens the sending app at that notification, then removes the alert. |
| Idle | An open card over an app with no key for 20 s closes. |
| One at a time | Higher rank wins (`CueKind.rank`): Call, Timer done, the rest. One pill per app in 10 s (exists). |
| Over apps, no Home service | Pill only (Fire OS, or service off). Plume gets no keys there. |

## 7. Alert types

| Type | Detect | Title / sub (Full) | Title / sub (App) | Buttons |
|---|---|---|---|---|
| Call | `category == "call"` | caller / "Incoming call" | app / "Incoming call" | app's (Answer, Decline), Open |
| Camera, doorbell | has `EXTRA_PICTURE` | title / text + wide picture | app / "New notification" | Open, Dismiss |
| Message | `category == "msg"` | sender / text | app / "New message" | Open, app's without typing, Dismiss |
| Alarm, reminder | `category` in `alarm`, `reminder`, `event` | title / text | app / "Reminder" | app's (Snooze), Open, Dismiss |
| Timer done | Plume | "Timer done" / length | same | Stop, +1 min |
| Other | passes `cueAlertPasses` | title / text | app / "New notification" | Open, app's without typing, Dismiss |

Hidden privacy: no pill, except calls ("Incoming call").
Call buttons show in every privacy level, because Answer and Decline show nothing private.

## 8. Code design

Rule: one place builds what an alert says and does. One function draws it. Each screen only gives it a surface and an ink.

### 8.1 Files

| File | Change |
|---|---|
| `res/layout/cue_body.xml` | **New.** The children of `@id/cue` moved out of `activity_main.xml`, inside `<merge>`. |
| `activity_main.xml` | `@id/cue` keeps its own attributes and holds `<include layout="@layout/cue_body"/>`. Same ids, so `CueView.kt` does not change its lookups. |
| `CueViews.kt` | `cueShape` and `cueBind`: the shared drawing code (8.2). No new file. |
| `CueView.kt` | `bindCue` calls `cueBind`, then draws only Home extras (marks, music bar, bars, app logo). `showCue`: `open = focused`, no auto-open. |
| `CueOverlay.kt` | Deletes `pill`, `card`, `button`, `picture`, `words`, `text`, `cueOverlayUntil` and `seen`. Inflates `cue_body`, calls `cueShape` and `cueBind`. |
| `CueAlerts.kt` | Builds every button and the times (8.3). |
| `CueTimers.kt` | Timer done peek uses the alert time. |
| `Cue.kt` | `CueItem` gains `photo: Bitmap?` (wide picture). Shared constants (8.4). |
| `MainActivity.kt` | Access rows, ADB sheet, one "Alert time" row under Alerts. |

### 8.2 `cueShape` and `cueBind`: the shared component

```kotlin
/** Sizes and padding for the open tile or the pill. [root] holds cue_body. */
internal fun cueShape(root: ViewGroup, open: Boolean)

/** Text, pictures and buttons. [picked] = selected button, -1 for none; it sits on a pill of [pickInk]. */
internal fun cueBind(root: ViewGroup, item: CueItem, open: Boolean, ink: Int, inked: (TextView) -> Unit,
    picture: Bitmap?, picture2: Bitmap?, picked: Int, pickInk: Int)
```

- Two functions, not a class: nothing to hold, so nothing to allocate.
- Home: `bindCue` computes the ink (`inkBehind` or `fg`), the pictures and the pick, calls `cueBind`, then draws only Home extras (marks, music bar, bars, app logo). `reshapeCue` calls `cueShape`.
- Over apps: `CueOverlay.draw` calls the same two with the theme ink from 5 and no shadow.

### 8.3 `CueAlerts`: one source of truth

- `posted()` builds `CueItem.actions` with every button: the app's own (no typing; a call's in every privacy), then Open (`contentIntent`), then Dismiss. Each one removes the alert after it runs. Home and the overlay draw this one list.
- `peekUntil = post + Alert time`, and `until = peekUntil` (decision A: gone from Cue). A call: both `Long.MAX_VALUE`.
- `CueAlerts.held`: the key of the alert someone has open (Cue selected on Home, or the card over an app). `visible()` keeps it past its time while held.
- The App privacy label follows the category: "New message", "Reminder", else "New notification".
- `photo` = the notification's big picture, Full privacy only.

### 8.4 Constants (`Cue.kt`)

```kotlin
internal const val CUE_ROUND = 24          // exists
internal const val CUE_ALERT_MS = 2_000L   // Alert time default
internal val CUE_ALERT_TIMES = listOf(2_000L, 4_000L, 6_000L, 10_000L)
```

Sizes live once, in `cueShape` and `cueBind`. A constant only where two files read the value.

### 8.5 Keys over apps

| Where | Key | Who handles it | Result |
|---|---|---|---|
| Pill shows | Up | `HomeOverride.onKeyEvent` → `CueOverlay.key` | Card opens with focus |
| Pill shows | any other | the app | Unchanged |
| Card open | Left / Right | card `OnKeyListener` | `picked` moves, redraw |
| Card open | OK | card | Runs `item.actions[picked]` |
| Card open | Back | card | Card closes |

The card uses the same "picked" index as Home (`cueCol`), not Android focus on each button. This keeps one drawing path for the pick.

### 8.6 Data flow

```
Notification ─▶ MediaListener ─▶ CueAlerts.posted ─▶ CueItem (text, picture, photo, actions, peekUntil)
                                                          │
                        Cue.changed() ────────────────────┤
                         │                                │
                 Home: showCue ─▶ bindCue ─▶ cueBind ◀─ CueOverlay.update ─▶ over apps
```

## 9. Decision

After the alert time ends, a normal alert is gone from Cue (A). The sending app still has it. An alert someone has open stays until they close it.

## 10. Tests

**Unit (JVM):**
- `CachedAlert.visible`: an open (held) alert stays past its time, then goes.
- `cueAlertText`, `cueAlertPasses`: kept.
- `cueOverlayPick`: kept.

**TV (Hisense), one scripted pass:**
- Each alert type on Home and over Netflix: pill, open, Left/Right, OK, Back, Dismiss, Open.
- Light and Dark × Clear, Frosted and Off: screenshots of the open card on Home. Over apps: Light and Dark.
- Use `hidkey.sh` for Up, because `input keyevent` does not reach the Home service.

**Not testable here:** Fire OS, Android 13/14.

## 11. Size

Release APK: 536,636 B on main, 547,408 B with this change (+10.8 KB). Most of it is the access flow (on-TV ADB, the sheet, the rows) and the strings. The overlay itself adds no layout and no drawing code of its own: it inflates `cue_body` and calls `cueBind`.
