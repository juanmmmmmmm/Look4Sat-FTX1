# Look4Sat-FTX1

Experimental version of Look4Sat with direct USB OTG support for the
Yaesu FTX-1.

## FTX-1 support

This version is intended for portable satellite operation using:

**Android + USB OTG + Yaesu FTX-1**

without requiring a PC or rigctld.

## Current features

- Direct CAT communication with the Yaesu FTX-1 over USB OTG
- MAIN used for RX / downlink
- SUB used for TX / uplink
- Independent and continuous Doppler correction for RX and TX
- Independent MAIN and SUB frequency control
- Independent mode control
- Automatic SUB selection for transmission
- CTCSS configuration on SUB for FM satellites
- No software PTT control
- Physical microphone PTT remains under operator control

## Project status

⚠️ Experimental / testing version.

The FTX-1 integration is currently being tested during real satellite
passes.

Doppler behavior, USB stability and radio control are still being evaluated.

## Portable operation goal

The main objective is to operate satellites in the field using only:

**Android phone + USB OTG cable + Yaesu FTX-1**

without needing to carry a laptop or PC.

## Original project

This project is based on **Look4Sat** by **rt-bishop**:

https://github.com/rt-bishop/Look4Sat

Many thanks to the original author and contributors for developing and
maintaining Look4Sat.

## FTX-1 modifications

FTX-1 integration, modifications and testing:

**EA7KWF**

September 2026

## License

This project remains licensed under the **GNU General Public License v3.0**,
in accordance with the original Look4Sat project.

See the `LICENSE` file for details.
