# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Added

- Support for running under Java 9, 10 and 11.
- Beat Link Trigger can now become Tempo Master on the Pioneer DJ Link
  network, and can synchronize the tempo and beat grid of CDJs to
  Ableton Link in both directions.
- The Carabiner window now lets you control which device is the Tempo
  Master and which devices are synced to it.
- When Ableton Link is the tempo master, the Carabiner window also
  lets you nudge the Link tempo up or down, or type in an exact tempo
  value you want to set it to.
- Triggers configured to control Ableton Link can now use Link v3
  Start/Stop Sync (transport control) to start Link playback when the
  trigger activates, and stop it when the trigger deactivates. (Of
  course this works only when Carabiner's Sync Mode is set to
  `Triggers`. In other modes, your expressions can call the
  `start-transport` and `stop-transport` functions directly, as long
  as they first check that Carabiner is active using either `active?`
  or `sync-enabled?` if they care about being synced.)
- There is a new interface for picking a track from a player's media
  slot, and telling it (or another player) to load it. Players can
  also be stopped using this interface, and (as long as they are
  stopped at the cue point) started. This is useful for playing tracks
  during a pre-show from Front of House before there is a DJ on stage.
- There is a new Playlist Writer window for ease of use by radio
  stations and others wanting to be careful about royalties.
- Can now display metadata for non-rekordbox tracks, including audio
  and data CDs, thanks to dysentery and Beat Link updates.
- Player Status window shows a distinction between players with no
  track loaded, and with no metadata at all (which is now rare).
- The `Online?` option in the `Triggers` menu now shows the player
  number that Beat Link Trigger is using once it is online, to help
  people understand how it is operating.
- Icons are now displayed when no album art is available for a track,
  providing information about where the track was loaded from, or an
  indication that there is no track loaded.
- A new `Help` menu with options to open the User Guide, project page,
  Gitter chat, and to compose emails to report issues with
  pre-populated version details, or simply say "hello" as a new user.
- An embedded copy of the User Guide will be served by an embedded web
  server when you access it from the `Help` menu, so you can read it
  even if you do not have an Internet connection.
- The `About` window now shows Java version information, and all
  version information can be selected and copied, if useful in
  discussing issues.
- The log files now include Java and operating system version
  information at the top.

### Fixed

- Can now get metadata from Windows rekordbox; previously we were
  running into a rekordbox bug when sending dbserver messages split
  across more than one network packet. The Beat Link library  now
  takes pains to prevent them from being split.
- The SD slots were showing up as mounted in the Player Status window
  even when they were emtpy.
- If we had trouble communicating with a player's database server when
  first trying to get metadata from it, we would not ever try again,
  even if taken offline and back online. Now when we go offline, we
  shut down the `ConnectionManager`, clearing out that state so the
  next time we go online we can try again.
- Make the Player Status window show up in the right size to not
  require scroll bars.
- The Player Status window display when no players were found was huge
  and lacked suitable borders. It looks much better now.
- The error dialog that was displayed when we did not hear the right
  response from a Carabiner daemon after connecting was not being
  displayed on the correct thread, and so was completely unreadable.
- If multiple messages were sent rapidly to or from Carabiner they
  might get grouped into a single network packet, and the later ones
  would be ignored. This release, along with a newer Carabiner
  release, process even later messages grouped in the same packet.
  This version will warn you if you need to upgrade Carabiner.
- Protect against race conditions reading and writing preferences from
  different threads, now that they are split across multiple nodes.
- Deep-linking to headings in the new User Guide will scroll the
  browser to the correct place (the problem was the browser not
  knowing the image sizes during layout, so its guess about where to
  scroll would get knocked off as they filled in).

### Changed

- On Windows, the `About` window is now accessed through the new
  `Help` menu (which is more consistent with Windows applicaton
  standards), instead of the `File` menu.
- The `Open Logs Folder` option has moved from the `File` menu to the
  new `Help` menu so it is right next to the options where you might
  need it.

## [0.3.8] - 2018-06-17

### Fixed

- An infinite loop reporting that Carabiner could not be found if you
  tried to connect to it when it was not running.
- The Carabiner Connection window could show a connected state even
  after Carabiner had terminated.

### Added

- For very special situations, you can configure MIDI Clock and
  Carabiner tempo sync to ignore the actual track BPM, and sync a
  fixed tempo value adjusted by the player pitch.
- Provided a safe entry point for expression code to make sure the
  Player Status window is showing.

## [0.3.7] - 2018-03-26

### Fixed

- Setup expressions are now always compiled before other expressions,
  so the context they set up is guaranteed to be available even at
  compile time when Beat Link Trigger is initially launched.
- The Beat Expression is now fired only when beats are received from
  the player being tracked by the trigger (which is controlled by
  Watch Menu, as well as the Enabled Filter if _Any Player_ is
  chosen). This makes the Beat Expression far more useful, and an
  example of synchronizing light shows driven by the Ableton Live
  timeline locked to actual track positions became practical. I plan
  to add that example to the Wiki and/or the User Guide shortly after
  this release.

### Added

- You can now access track album metadata using the `track-album`
  convenience variable within expressions that work with CDJ status
  updates (the omission of this was an oversight, prompted by an
  oversight in the Beat Link library itself).
- If you would like the Player Status Window to be always on top of
  other windows, you can arrange for that by creating a global
  variable entry (using the Global Setup Expression) with the key
  `:player-status-always-on-top` and the value `true` before showing
  the window.
- The Beat Link `MetadataFinder` is now imported into the namespace
  used by the expression compiler, so it is easier to use from your
  expressions.

## [0.3.6] - 2017-11-30

### Fixed

- When looping a track that has audio data that extends well past the
  final beat in the beat grid, players sometimes report playing a beat
  that does not exist in the beat grid. This previously caused an
  exception in the log, and the reported playback position would keep
  growing without bound as long as the loop continued. The Beat Link
  library has been updated to handle this better by interpolating
  missing beats at the end of the beat grid, so there is no exception
  and the looping of the player is properly reflected in the **Player
  Status** window.
- In Windows, the MIDI environment sometimes throws exceptions trying
  to find or open devices which do not get thrown on the Mac. These
  were interfering with trigger display and event delivery, now they
  are more gracefully treated as a missing device, although they still
  will get stack traces in the log.

### Added

- Now displays the build date in the **About** box, to make it easier
  for people who are kindly testing pre-release versions keep track of
  which one they are running.
- Also shows the build date and version number at the start of the
  log to help remote troubleshooting.

## [0.3.5] - 2017-10-08

### Fixed

- The embedded CoreMidi4J library which is used to communicate with
  MIDI devices on the Mac has been upgraded to improve stablity and to
  better handle working with multiple devices of the same type.

  > :warning: Unfortunately, this causes most devices to show up with
  > different names than they used to, so when you upgrade to this
  > version you are going to need to go through all of your triggers
  > that are configured to talk to MIDI devices, and reconnect them to
  > the new device name if it has changed. See the [CoreMidi4J
  > project](https://github.com/DerekCook/CoreMidi4J#device-names) for
  > details.
  >
  > If your device name has changed (and, again, this only affects the
  > Macintosh platform), instead of seeing the Enabled section at the
  > bottom right end of the trigger, you will see &ldquo;Not
  > found.&rdquo; in red, just as you would see if the device was
  > unplugged:
  >
  > <image src="doc/assets/MissingDevice.png" alt="Missing Device" width="800">

- The embedded Beat Link library which communicates with the Pioneer
  network has been updated to handle creating metadata caches from
  playlists that contain more than one copy of the same track. (This
  would previously fail with an exception when trying to create a
  duplicate entry in the ZIP file that holds the metadata cache; now
  extra copies of a track are simply skipped.)

### Added

- Taking advantage of the new `TimeFinder` class (which supports the
  **Time** and **Remain** fields in the **Player Status** window),
  expressions that run in response to player status updates can use a
  new convenience variable, `track-time-reached`, which will contain
  how far into the playing track has been reached, in milliseconds.
  (This value will only be available if the `TimeFinder` is running,
  otherwise `track-time-reached` will have the value `nil`. The
  easiest way to make sure the `TimeFinder` is running is to open the
  Player Status window.)

## [0.3.4] - 2017-09-05

### Fixed

- If you created too many triggers, or your triggers got too large
  because of complex expressions, they would fail to save because of
  exceeding the size limit for an entry in the Java Preferences. Even
  worse, this would happen silently (unless you happened to look in
  the log file). Now they will be split across multiple entries if
  needed, and if anything does fail, an error dialog will be
  displayed.

### Added

- A menu item which lets you view the contents of a metadata cache
  file, so you can work on triggers when you don't have a player or
  the actual media handy.

## [0.3.3] - 2017-08-08

### Added

- Log more details of the process of going online, to better support
  people who are reporting issues with metadata.

### Fixed

- Provide more specific guidance when turning on metadata requests,
  based on the actual number of physical players detected on the
  network, and stop offering the unreliable metadata option if there
  is only one real player, since it cannot be used in that situation.
- Stop logging stack traces on each beat if we are offline but
  configured to align the master player with Ableton Link.
- Found one more (albeit extremely unlikely) path where trying to
  create a metadata cache could fail silently, and added an error
  message there.

## [0.3.2] - 2017-08-08

This is a small release to fix some issues found by the much wider
audience that has been introduced to Beat Link Trigger through
the
[DJ TechTools article](http://djtechtools.com/2017/07/19/decoding-pioneer-pro-link-connect-cdjs-ableton-link/).

### Fixed

- If there was a problem retrieving metadata when the user asked to
  create a cache file (for example, if they did not have Request Track
  Metadata turned on, and there was only one player on the network),
  it would silently fail with a somewhat cryptic entry in the log
  file. It now gives a nice error dialog explaining how to fix the
  situation. It also gives a general error dialog if something else
  unexpected blows up the process.
- The explanation of the timestamp value inside trigger expressions
  incorrectly stated they were millisecond values. In order to be
  compatible with Ableton Link, Beat Link switched to using
  seconds in its packet timestamps.
- Added information about the `status` value available in the
  documentation for all triggers where it is present (some were
  previously missing this important detail).
- Improved wording and variable name in grandMA2 example, thanks to
  suggestions from Alex Hughes.

## [0.3.1] - 2017-07-22

This is a small release primarily to make it easier for people who
discover Beat Link Trigger through
the
[DJ TechTools article](http://djtechtools.com/2017/07/19/decoding-pioneer-pro-link-connect-cdjs-ableton-link/) to
find the correct version, with on-air indicators and SMPTE integration
support.

### Added

- On-Air indicators for players in the Player Status window. If you
  have the players configured to track and report this, it will be
  reflected right above the beat phase display.
- A new version of Beat Link which adds hooks needed to allow triggers
  to generate SMPTE timecode synced to the track position (with the
  help of an external daemon).
- Beat Expressions can now check whether the player sending the beat
  was on the air by simply looking at the value of `on-air?` as was
  already possible in Enabled and Tracked Update expressions. (Even
  though the information is not part of the beat packet itself, Beat
  Link Trigger will look it up from the last status packet received
  from the same player.)
- It is now possible to reconfigure the maximum log file size and the
  number of backlog files by calling, for example:

      (beat-link-trigger.logs/install-appenders 1000000 4)

  This would allow the log files to grow to a million bytes each, with
  five backlog files kept. The default maximum size has been doubled
  to 200,000 bytes, and the default backlog file count remains 5.

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

[unreleased]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.8...HEAD
[0.3.8]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.7...v0.3.8
[0.3.7]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.6...v0.3.7
[0.3.6]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.1...v0.3.2
[0.3.0]: https://github.com/brunchboy/beat-link-trigger/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/brunchboy/beat-link-trigger/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.4...v0.2.1
[0.1.4]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/beat-link-trigger/compare/v0.1.0...v0.1.1
