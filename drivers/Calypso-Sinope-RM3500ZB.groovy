/* groovylint-disable DuplicateMapLiteral, DuplicateNumberLiteral */
/* groovylint-disable UnnecessaryGetter */
/**
 *  Sinope Zigbee Plug (RM3500ZB) Device Driver for Hubitat
 *
 *  1.0 (2023-05-07): Initial release
 *  Author: fblackburn
 *  Inspired by:
 *    - Sinope => https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/sinope-technologies
 *    - sacua => https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/RM3500ZB_Sinope_Hubitat.groovy
 */

import hubitat.device.HubMultiAction

metadata
{
    definition(
        name: 'Sinope Zigbee Calypso (RM3500ZB)',
        namespace: 'fblackburn',
        author: 'fblackburn',
    ) {
        capability 'Switch'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Outlet'
        capability 'PowerMeter'
        capability 'EnergyMeter'
        capability 'TemperatureMeasurement'
        capability 'CurrentMeter'
        capability 'VoltageMeasurement'
    }
    preferences {
        input(
            name: 'powerChange',
            type: 'number',
            title: 'Power Change',
            description: '''
                |Difference of watts to trigger power report (1..10000).
                |Power below this value will be considered as 0
            |'''.stripMargin(),
            range: '1..10000',
            defaultValue: getDefaultPowerChange()
        )
        input(
            name: 'trace',
            type: 'bool',
            title: 'Enable debug logging',
            defaultValue: false
        )
    }
}

void installed() {
    if (settings.trace) {
        log.trace 'RM3500ZB >> installed()'
    }
    configure()
    refresh()
}

void updated() {
    if (settings.trace) {
        log.trace 'RM3500ZB >> updated()'
    }
    configure()
    refresh()
}

void uninstalled() {
    if (settings.trace) {
        log.trace 'RM3500ZB >> uninstalled()'
    }
    unschedule()
}

void configure() {
    if (settings.trace) {
        log.trace 'RM3500ZB >> configure()'
    }
    try {
        unschedule()
    } catch (ignored) { }

    List cmds = []
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 3600, null)            // State

    Integer temperatureChange = 50 // 0.5 celcius
    cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 30, 580, temperatureChange) // Temperature sensor

    Integer powerChange = settings.powerChange == null ? getDefaultPowerChange() : settings.powerChange
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 0, 600, powerChange)        // Power

    // Logic made by device to trigger an event:
    // if energyChange <= energyValue; then trigger event
    // Since energyValue will only increase with time, we can only use minReportTime (300)
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 300, 1800, 0)              // Energy
    sendCommands(cmds)
}

List<Map> parse(String description) {
    if (!description?.startsWith('read attr -')) {
        if (description?.startsWith('zone')) {
            log.trace "RM3500ZB >> parse(description) Ingoring zone event: ${description}"
        } else if (!description?.startsWith('catchall:')) {
            log.warn "RM3500ZB >> parse(description) ==> Unhandled event: ${description}"
        }
        return []
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    Map event = extractEvent(descMap)
    List<Map> events = [event]
    if (descMap.additionalAttrs) {
        // When many events from same cluster must be sent at the same time,
        // device send other events in additionalAttrs instead of sending several
        if (settings.trace) {
            log.trace "TH112XZB >> Found additionalAttrs: ${descMap}"
        }
        descMap.additionalAttrs.each { Map attribute ->
            attribute.cluster = descMap.cluster
            events.add(extractEvent(attribute))
        }
    }
    return events
}

void refresh() {
    if (settings.trace) {
        log.trace 'RM3500ZB >> refresh()'
    }
    if (state.updatedLastRanAt && now() < state.updatedLastRanAt + 2000) {
        if (settings.trace) {
            log.trace 'RM3500ZB >> refresh() ==> Ran within last 2 seconds so aborting'
        }
        return
    }
    state.updatedLastRanAt = now()

    List cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x0000) // State
    cmds += zigbee.readAttribute(0x0B04, 0x050B) // Active power
    cmds += zigbee.readAttribute(0x0B04, 0x0505) // Voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508) // Amperage
    cmds += zigbee.readAttribute(0x0702, 0x0000) // Energy delivered
    cmds += zigbee.readAttribute(0x0402, 0x0000) // Temperature sensor
    sendCommands(cmds)
}

void off() {
    if (settings.trace) {
        log.trace 'RM3500ZB >> off()'
    }
    List cmds = zigbee.command(0x0006, 0x00) // Off
    sendCommands(cmds)
}

void on() {
    if (settings.trace) {
        log.trace 'RM3500ZB >> on()'
    }
    List cmds = zigbee.command(0x0006, 0x01) // On
    sendCommands(cmds)
}

private Map extractEvent(Map descMap) {
    Map event = [:]
    if (descMap.cluster == '0006' && descMap.attrId == '0000') {
        event.name = 'switch'
        event.value = getSwitchMap()[descMap.value]
    } else if (descMap.cluster == '0B04' && descMap.attrId == '050B') {
        Double power = getPower(descMap.value)
        Double oldPower = device.currentValue('power')
        if (power != 0.0 && power < oldPower) {  // check if power decrease
            Integer powerChange = settings.powerChange == null ? getDefaultPowerChange() : settings.powerChange
            if (power < powerChange) {
                // No other even will be sent if power is lower than powerChange
                // So we will prioritze to send 0 value instead of the "real" value
                if (settings.trace) {
                    log.trace "RM3500ZB >> power(${power}) hardcoded to 0"
                }
                power = 0.0
            }
        }
        event.name = 'power'
        event.value = power
        event.unit = 'W'
    } else if (descMap.cluster == '0B04' && descMap.attrId == '0505') {
        // This event seems to be triggered at same frequency than Power
        event.name = 'voltage'
        event.value = getVoltage(descMap.value)
        event.unit = 'V'
    } else if (descMap.cluster == '0B04' && descMap.attrId == '0508') {
        // This event is sent with voltage event
        event.name = 'amperage'
        event.value = getAmperage(descMap.value)
        event.unit = 'A'
    } else if (descMap.cluster == '0702' && descMap.attrId == '0000') {
        BigInteger newEnergyValue = getEnergy(descMap.value)
        if (newEnergyValue == 0) {
            log.info 'RM3500ZB >> Ignoring energy event (Caused: power outage or new pairing device)'
        } else {
            event.name = 'energy'
            event.value = newEnergyValue / 1000
            event.unit = 'kWh'
        }
    } else if (descMap.cluster == '0402' && descMap.attrId == '0000') {
        String scale = getTemperatureScale()
        event.name = 'temperature'
        event.value = getTemperatureValue(descMap.value, scale)
        event.unit = "°${scale}"
    } else {
        log.warn "RM3500ZB >> parse(descMap) ==> Unhandled attribute: ${descMap}"
    }

    if (event.name != null && event.value != null) {
        event.descriptionText = "${device.getLabel()} ${event.name} is ${event.value}"
        if (event.unit) {
            event.descriptionText = "${event.descriptionText}${event.unit}"
        }
    }

    if (settings.trace) {
        log.trace "RM3500ZB >> parse(description) ==> ${event.name}: ${event.value}"
    }
    return event
}

private void sendCommands(List<Map> commands) {
    HubMultiAction actions = new HubMultiAction(commands, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(actions)
}

private Map getSwitchMap() {
    return [
        '00': 'off',
        '01': 'on',
    ]
}

private Double getPower(String value) {
    if (value == null) {
        return
    }
    return Integer.parseInt(value, 16)
}

private BigInteger getEnergy(String value) {
    if (value == null) {
        return 0
    }
    return new BigInteger(value, 16)
}

private Integer getVoltage(String value) {
    if (value == null) {
        return 0
    }
    return Integer.parseInt(value, 16)
}

private Double getAmperage(String value) {
    if (value == null) {
        return 0
    }
    return Integer.parseInt(value, 16) / 1000
}

private Double getTemperatureValue(String value, String scale) {
    // 8000 is the value when sensor is not plugged
    if (value == '8000' || value == null) {
        return
    }

    Double celsius = (Integer.parseInt(value, 16) / 100).toDouble()
    if (scale == 'C') {
        return celsius.round(1)
    }
    return Math.round(celsiusToFahrenheit(celsius))
}

private Integer getDefaultPowerChange() {
    return 10
}
