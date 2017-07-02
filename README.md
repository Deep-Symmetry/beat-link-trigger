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
want to watch, the kind of MIDI message to send when they start and
stop, and when the triggers are enabled:

<image src="doc/assets/TriggerWindow.png" alt="Trigger window" width="793">

Starting with version 0.3.0, there is also an interface for monitoring
the status of each player found on the network, which you can access
by choosing `Show Player Status` in the `Network` menu:

<image src="doc/assets/PlayerStatus.png" alt="Player Status window"
width="538">

### Going Further

Please see the
[user guide](doc/README.adoc#beat-link-trigger-user-guide) for an
introduction in how to configure triggers, and hopefully you will soon
be coming up with interesting integration projects of your own.

## Compatibility

This is in no way a sanctioned implementation of the protocols. It should be clear, but:

> :warning: Use at your own risk! For example, there are reports that
> the XDJ-RX crashes when a related project starts, so don't use this
> with one on your network unless we can figure that out.

While these techniques appear to work for us so far, there are many
gaps in our knowledge, and things could change at any time with new
releases of hardware or even firmware updates from Pioneer.

You should also not expect to be able to run Beat Link Trigger, or any
project like it, on the same machine that you are running rekordbox,
because they will compete over access to network ports.

Beat Link Trigger seems to work great with CDJ-2000 Nexus gear, and
works fairly well (with less information available) with older
CDJ-2000s. It has also been reported to work with XDJ-1000 gear. If
you can try it with anything else, *please* let us know what you learn
in the
[Gitter chat room](https://gitter.im/brunchboy/beat-link-trigger), or
if you have worked out actionable details about something that could
be improved,
[open an Issue](https://github.com/brunchboy/beat-link-trigger/issues)
or submit a pull request so we can all improve our understanding
together.

If something isn't working with your hardware and you don't yet know
the details why, but are willing to learn a little and help figure it
out, look at the
[dysentery project](https://github.com/brunchboy/dysentery#dysentery),
which is where we are organizing the research tools and results which
made programs like Beat Link Trigger possible.

## Startup Issues

If double-clicking doesn&rsquo;t open up the application, make sure you
have a recent
[Java runtime environment](https://java.com/inc/BrowserRedirect1.jsp)
installed, and try running it from the command line:

    java -jar beat-link-trigger.jar

If that does not work, at least you will be able to see a detailed
report of what went wrong, which can help you troubleshoot the issue.

### Font-Related Bugs

If you see a long exception stack trace similar to the one
in
[this discussion](https://github.com/brunchboy/beat-link-trigger/issues/21) and
you have your computer language set to one that uses an alphabet which
is substantially different from English, you may be encountering what
seems to be a bug in the GUI library (or maybe even in Java itself).
Try setting your system language to US English, and see if that at
least lets you run the program.

### Mac Trust Confirmation

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
Copyright © 2016&ndash;2017 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the
[Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php),
the same as Clojure. By using this software in any fashion, you are
agreeing to be bound by the terms of this license. You must not remove
this notice, or any other, from this software.
