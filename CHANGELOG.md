# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Fixed

- Move all interaction with UI objects to the AWT Event Dispatch
  thread; we were trying to get the current output menu selection on
  the MIDI event thread, which was causing a
  ConcurrentModificationException within AWT.
- Keep track of MIDI outputs as we open them, so we can reuse the same
  instance rather than creating new outputs every time. Clean them up
  when they disappear from the MIDI environment, like Afterglow does.

### Added

- A first-stage loading process which checks the Java version and
  presents an error dialog if it is too old to successfully load the
  rest of the application, offering to open the download page.

## 0.1.0 - 2016-05-13

### Added

- Set up initial project structure.
- Selector to choose MIDI output as trigger destination.

[unreleased]: https://github.com/brunchboy/beat-link/compare/v0.1.0...HEAD
