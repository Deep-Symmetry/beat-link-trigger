# beat-link-trigger

An application to trigger MIDI events when CDJs start playing tracks,
as an example of how to work with
[beat-link](https://github.com/brunchboy/beat-link#beat-link).

## Usage

Download the latest executable `beat-link-trigger.jar` file from the
[releases](https://github.com/brunchboy/beat-link-trigger/releases)
page and double-click it to run it. If that doesn&rsquo;t work, make
sure you have a recent
[Java runtime environment](https://java.com/inc/BrowserRedirect1.jsp)
installed, and try running it from the command line:

    java -jar beat-link-trigger.jar

> beat-link-trigger requires Java 7 or later. It is compiled and
> tested using the latest release of Java 8, so that is your best bet.

A trigger window will open, in which you can choose the players you
want to watch, and the kind of MIDI message to send when they start
and stop, and when the triggers are enabled:

<image src="doc/assets/TriggerWindow.png" alt="Trigger window" width="800">

### More to Come

A great deal of the power of Beat Link Trigger is in what you can do
with custom expressions in the Enabled filters, and that is under
rapid development right now, and I haven&rsquo;t even started
documenting it. I was not expecting people to find and start trying
to use this so quickly! :calendar: So please bear with me for a few
more days while I get this part of the interface into really good
shape, then write up how to use it.

> :warning: If you are playing with this already, please be aware it
> is not finished, and you are getting a sneak preview with no
> schedule or guarantees!

## License

<img align="right" alt="Deep Symmetry" src="https://github.com/brunchboy/beat-link/blob/master/assets/DS-logo-bw-200-padded-left.png">
Copyright © 2016 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the
[Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php),
the same as Clojure. By using this software in any fashion, you are
agreeing to be bound by the terms of this license. You must not remove
this notice, or any other, from this software.
