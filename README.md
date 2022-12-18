# Hubitat Drivers

This repository contains various Hubitat Elevation drivers.
My main reason to rewrite custom driver, is to understand and trust every line used by them.

## Sinope Thermostat

### Why not using official Hubitat driver?

Official driver doesn't support the following features:

* Fetching power consumption
* Displaying Outdoor temperature
* Activating Lock/Unlock
* Controlling display light with command

## Sinope Plug

### Why not using generic Hubitat driver?

Official driver doesn't support power consumption

### Why not using community Hubitat driver?

Available drivers use many scheduler and do logic that, IMHO, shouldn't done inside a driver.
