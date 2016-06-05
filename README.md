# beat-link-trigger

[![Gitter](https://img.shields.io/gitter/room/brunchboy/beat-link-trigger.svg)](https://gitter.im/brunchboy/beat-link-trigger)

An application to trigger MIDI events when CDJs start playing tracks,
reach particular beats, or whatever else you can think of. Built using
[beat-link](https://github.com/brunchboy/beat-link#beat-link).

[![License](https://img.shields.io/badge/License-Eclipse%20Public%20License%201.0-blue.svg)](#license)

## Usage

Download the latest executable `beat-link-trigger.jar` file from the
[releases](https://github.com/brunchboy/beat-link-trigger/releases)
page and double-click it to run it. If that doesn&rsquo;t work,
[see below](#startup-issues).

> beat-link-trigger requires Java 7 or later. It is compiled and
> tested using the latest release of Java 8, so that is your best bet.

A trigger window will open, in which you can choose the players you
want to watch, and the kind of MIDI message to send when they start
and stop, and when the triggers are enabled:

<image src="doc/assets/TriggerWindow.png" alt="Trigger window" width="840">

### Using Beat Link Trigger

Please see the
[user guide](doc/README.adoc#beat-link-trigger-user-guide) for an
introduction in how to configure triggers, and hopefully you will soon
be coming up with interesting integration projects of your own.

### Startup Issues

If double-clicking doesn&rsquo;t open up the application, make sure you
have a recent
[Java runtime environment](https://java.com/inc/BrowserRedirect1.jsp)
installed, and try running it from the command line:

    java -jar beat-link-trigger.jar

If that does not work, at least you will be able to see a detailed
report of what went wrong, which can help you troubleshoot the issue.

#### Mac Trust Confirmation

If you are on a Mac, the first time you try to launch the downloaded
jar file by double-clicking it you will see an error like this because
it is not a Mac-specific application:

<image src="doc/assets/Unsigned.png" alt="Unsigned jar">

You can fix that by control-clicking on the Jar and choosing
&ldquo;Open&rdquo; at the top of the contextual menu that pops up. You
will be asked to confirm that you really want to run it. Click the
&ldquo;Open&rdquo; button in that confirmation dialog, and from then
on, you will be able to run that copy by just double-clicking it.

<image src="doc/assets/ReallyOpen.png" alt="Confirmation dialog">

> Someday we may release a Mac-specific bundle of the application,
> perhaps even through the Mac App Store, which will avoid the need to
> take this step. But that will be a much larger download because it
> will have to bundle its own copy of the entire Java environment. In
> the mean time, at least you only need to do this once for each new
> release you download.

## License

<a href="http://deepsymmetry.org"><img align="right" alt="Deep Symmetry" src="doc/assets/DS-logo-bw-200-padded-left.png"></a>
Copyright © 2016 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the
[Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php),
the same as Clojure. By using this software in any fashion, you are
agreeing to be bound by the terms of this license. You must not remove
this notice, or any other, from this software.
