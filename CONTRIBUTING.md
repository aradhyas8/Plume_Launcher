# Contributing to Plume

Thanks for wanting to help. Plume is a small project with one maintainer, so this page explains what fits, what doesn't, and how to send a change that can be merged quickly.

## What Plume is

Plume is a minimal home screen for Android TV and Google TV. Every change is weighed against these principles:

- **Small and light.** A 0.4 MB APK, about 50–70 MB of RAM, no third-party libraries, no extra background services.
- **Simple home screen.** One calm screen with your apps and what you watch. Nothing competes for attention.
- **Yours, not ours.** No ads, no sponsored content, no analytics, no account.
- **Quiet on the network.** Plume only goes online for the things listed under [Privacy](README.md#privacy). Anything new that connects to the internet must be optional and off until the user turns it on.

## What's out of scope

These will be declined, however well they're built:

- Ads, analytics, crash reporting services, or accounts
- New third-party libraries or SDKs (AndroidX included)
- Services, workers or alarms that run while Plume isn't on screen
- Online metadata services that need an API key, such as TMDB
- Rows, widgets or sections that copy the scrolling layouts of other launchers

More of your own content on the home screen, such as Up next or more of Continue Watching, is welcome as a design first. Open a feature request that shows how it keeps the screen calm. A clear description is enough; sketches and mockups help.

Not sure if an idea fits? Open a feature request first and ask. That's much cheaper than finding out after the code is written.

## Before you start

- **Bug fixes:** go ahead and open a pull request. Link the issue if there is one.
- **Features:** open a [feature request](../../issues/new/choose) first and wait for a reply. Pull requests for features that weren't discussed may be closed.
- **Translations:** see [Translations](#translations) below.

## Pull requests

- **One change per pull request.** A translation, a bug fix and a new menu are three pull requests. Mixed pull requests are hard to review and will be asked to split.
- **Keep the diff small.** Match the style of the code around your change. Don't reformat or reorganise code you aren't changing.
- **Test on a real TV** (or an Android TV emulator if you have no TV) and say which device and Android version you used.
- **Mention size and memory changes.** If your change adds resources or keeps things in memory, give the APK size before and after.
- **No new permissions** without discussing them in an issue first.

By sending a pull request you agree that your contribution is licensed under the project's [GPL-3.0 licence](LICENSE).

## Translations

Translations are welcome. Shipping every language costs very little: each language adds a few KB to the APK and no memory, because Android only loads the strings for the language the TV is set to.

- A translation pull request adds or edits **only** `app/src/main/res/values-<code>/strings.xml`, for example `values-it/strings.xml`. No code changes, no new features.
- Translate every string in `app/src/main/res/values/strings.xml`. Keep placeholders such as `%1$s` and `%d` exactly as they are.
- Keep text short. TV screens are read from across the room, and long labels get cut off.
- Run `python3 tools/lang.py check` before you send it: it lists the keys your language lacks, the keys it has that English does not, and the texts whose `%s` or `%d` markers differ from English. If your pull request adds a string to `values/strings.xml`, run it too and say in the description which languages were not translated.
- If you add a language, please say whether you're happy to be asked about new strings later. Languages that fall far behind may be removed.

Some of Plume's text still lives in the Kotlin code instead of `strings.xml`. Please don't move strings into `strings.xml` inside a translation pull request; open an issue instead, so the move happens once for every language.

## Reporting bugs

Use the [bug report form](../../issues/new/choose). The TV model, Android version and Plume version matter most, because many problems only happen on one manufacturer's TVs. A photo or video of the screen helps a lot.

## Building

See [Building from source](README.md#building-from-source) in the README.
