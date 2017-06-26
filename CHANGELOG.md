# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

- Nothing so far.

## [0.3.0] - 2017-06-25

### Added

- Some major new features taking advantage of the incredible advances
  in the Beat Link library over the past month. The most visible are
  found in the new Player Status window, available in the Network
  menu, providing a detailed view of what the player on the network
  are currently doing.
- This also provides the foundation for upcoming work to generate
  timecode, now that we can keep track of detailed playback location
  when a player is playing normally.
- There is a whole new infrastructure for creating, attaching, and
  using metadata cache files for performance settings with four
  players all linked to a single media library, which makes requesting
  metadata difficult.

### Changed

- Now that metadata has become so fundamental to so many features in
  Beat Link Trigger, the user guide assumes that you will be working
  with it turned on. You can also make use of the extensive new
  metadata cache file mechanism to cope with performance environments
  in which requesting the metadata from a player is difficult.

### Fixed

- File management is much improved, adding standard file extensions
  for configuration and trigger export files, filtering on those
  extensions to make it easier to see the right files, asking for
  confirmation before overwriting an existing file, and still letting
  you load a file with the wrong extension (such as one you saved
  before this release) by choosing "All files" in the Open dialog.

## [0.2.1] - 2017-03-18

### Added

- You can now request track metadata from the CDJs, display it in the
  trigger windows, and use it in your trigger logic.
- You can take Beat Link Trigger online after starting it in offline
  mode, or go offline when operating in online mode, at will using a
  new Network menu.
- A sample integration with
  [The Lighting Controller](http://thelightingcontroller.com), also
  known as ShowXpress, QuickDMX, and SweetLight, a widely-used DMX
  lighting control package.
- An example of how to automatically create a playlist logging all
  tracks played.

### Fixed

- The `BeatListener` was not being started, so Beat Expressions would
  never be run.
- Previously an extra closing parenthesis in an Expression would cause
  anything after it to be silently ignored. Now it will properly
  cause a parse failure due to an unmatched delimiter.
- Parse errors are also now identified with the trigger number and
  expression type, as well as proper line and column numbers.
- Runtime exceptions within triggers are now also logged with the
  trigger number and type description, with better formatting, to help
  track them down.

### Changed

- The Carabiner connection is now configured through the new Network
  menu.

## [0.1.4] - 2016-11-20

### Added

- You can now tie an [Ableton Link](https://www.ableton.com/en/link/)
  session's timeline to the beat grid being received on a trigger
  (setting its BPM and aligning either at the level of individual
  beats full bars), with the help of
  [carabiner](https://github.com/brunchboy/carabiner).

## [0.1.3] - 2016-10-02

### Added

- Thanks to new beat-link features, expressions can now determine the
  rekordbox ID number and source (player number and slot) of a track
  being played.
- In describing tracks, triggers show the rekordbox ID when available
  in preference to the simple playlist position, as well as the source
  player and slot.
- User expressions can override how a track is described by storing a
  custom description string under the key `:track-description` in the
  trigger locals map.
- A Media Locations window to help keep track of media library
  locations so that triggers can identify tracks by their rekordbox ID
  regardless of where the DJ ends up needing to insert media during
  show setup.
- A new, separate Tracked Update expression to use for relaying status
  updates about the tracked player to other systems, simplifying the
  purpose and use of the Enabled Filter expression
- An example of how to control Pangolin BEYOND laser shows.
- Takes advantage of beat-link's new slf4j integration to redirect any
  log output that beat-link produces into the beat-link-trigger log
  file.

### Fixed

- An exception which occurred trying, and failing, to clean out any
  previous local bindings when editing a trigger's Setup Expression.
- Triggers were not being properly cleaned up (including leaving open
  now-orphaned expression editor windows) when replacing the entire
  trigger window, for example by opening a different trigger file.
- Creating a trigger expression using the expression editor window
  that was automatically opened when you chose Custom as a Message or
  Enabled option for the trigger was not updating the state of the
  gear button.
- Spurious exceptions could appear in the log file when triggers had
  Tracked Update expressions because they were being called during
  window creation without an actual status update. These expressions
  are now only called when a status update has been received.

## [0.1.2] - 2016-06-05

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
  application, including a large variety of convenience variables to
  simplify the task, and built-in help about the expressions and
  variables.
- Support for sending MIDI Beat Clock.
- A visible button to let people know about the trigger contextual
  menu even if they don't think of trying to control or right-click on
  it.
- Errors and other important events are now logged to a log file,
  which is available for inspection via the File menu.
- A user guide.

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

[unreleased]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/brunchboy/beat-link-trigger/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.4...v0.2.1
[0.1.4]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.0...v0.1.1
