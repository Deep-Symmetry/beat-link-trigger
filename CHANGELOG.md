# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Added

- A comment field for keeping notes about each trigger.
- In addition to the former yes/no values (now renamed Always and
  Never), the Enabled setting of a trigger can be set to On-Air
  (enabled when its player reports being on the air), and Custom,
  which allows you to evaluate a Clojure expression on the player's
  status report and decide however you want.
- A graphical indicator to summarize the trigger's enabled and tripped
  state.
- A general framework for editing Clojure expressions to customize the
  application, including a large variety of convenienve variable to
  simplify the task, and built-in help about the expressions and
  variables.
- A visible button to let people know about the trigger contextual
  menu even if they don't think of trying to control or right-click on
  it.
- Errors and other important events are now logged to a log file,
  which is available for inspection via the File menu.

### Fixed

- The About box and the Searching for Devices box would appear in the
  top left corner of the screen momentarily before jumping to where
  they belonged.
- Newly created trigger rows had a blank choice at the bottom of the
  MIDI Output menu.

## [0.1.1] - 2016-05-20

### Added

- A status summary of the selected player is shown after the menu.
- The trigger can be enabled or disabled with a checkbox.
- You can specify the channel on which the MIDI message should be sent.
- You can now open multiple trigger windows using the new Window menu,
  and have each watching a different player and sending a different
  MIDI message. Closing the last window quits.
- Support for offline operation when no DJ Link device can be found.
- The list of triggers is saved when the application exits and
  restored when it starts.
- You can save or load the configuration to a text file of your choice.
- Triggers can be deleted by right-clicking on them as long as there
  is more than one in the list.
- A first-stage loading process which checks the Java version and
  presents an error dialog if it is too old to successfully load the
  rest of the application, offering to open the download page.
- The beginnings of an informative and attractive About box.

### Changed

- The MIDI Output menu now reformats names so you don't need to see
  the CoreMIDI4J prefix even when it is in use.
- The Player menu stores its choices in a format that is more
  efficient for comparing with the incoming player packets.
- The Player menu always lists Players 1 through 4 as choices, and
  reports when you have chosen one that is not currently visible.
- Switched to a dark UI theme to fit in better with the kind of
  software this will be used with, and the dark environments in which
  it will be used.

### Fixed

- Identified a source of potential and unpredictable latency in the
  upstream beat-link library, and fixed it.
- Moved all interaction with UI objects to the AWT Event Dispatch
  thread; we were trying to get the current output menu selection on
  the MIDI event thread, which was causing a
  ConcurrentModificationException within AWT.
- Keep track of MIDI outputs as we open them, so we can reuse the same
  instance rather than creating new outputs every time. Clean them up
  when they disappear from the MIDI environment, like Afterglow does.

## 0.1.0 - 2016-05-13

### Added

- Set up initial project structure.
- Selector to choose MIDI output as trigger destination.

[unreleased]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.0...v0.1.1
