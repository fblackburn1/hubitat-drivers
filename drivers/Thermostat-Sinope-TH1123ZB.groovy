/* groovylint-disable DuplicateListLiteral */
/* groovylint-disable DuplicateMapLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable EmptyMethod */
/* groovylint-disable UnnecessaryGetter */
/* groovylint-disable UnnecessarySetter */
/**
 *  Thermostat Sinopé TH1123ZB-TH1124ZB Driver
 *
 *  Version: 0.3
 *  0.1   (2019-12-20) => First release
 *  0.2   (2019-12-21) => Added Lock / Unlock setting / HealthCheck
 *  0.3   (2019-12-22) => Fixed thermostat mode reporting, added thermostat mode setting, added power reporting (?)
 *  Author(0.1-0.3): scoulombe
 *  Date: 2019-12-22
 *
 *  0.4   (2021-12-12) => Added changes from SmartThings driver v1.2.0
 *  0.5   (2021-12-15) => Added possibility to set outdoor temperature from command and fixed power reporting event
 *  0.6   (2022-01-01) => Fixed duplicate events and added event descriptionText attribute
 *  0.7   (2022-01-09) => Added setClockTime command and updated time on "configure" command
 *  0.8   (2022-03-12) => Added enableBacklight and disableBacklight commands
 *  Author(0.4+): fblackburn
 */

// Sources:
/* groovylint-disable-next-line LineLength */
// * Sinopé => https://github.com/SmartThingsCommunity/SmartThingsPublic/blob/master/devicetypes/sinope-technologies/th1123zb-th1124zb-sinope-thermostat.src/th1123zb-th1124zb-sinope-thermostat.groovy
// * scoulombe => https://github.com/scoulombe79/HubitatDrivers/blob/master/Thermostat-Sinope-TH1123ZB.groovy

import hubitat.device.HubMultiAction

metadata
{
    definition(
        name: 'TH1123ZB-TH1124ZB Sinope Thermostat',
        namespace: 'fblackburn',
        author: 'fblackburn',
    ) {
        capability 'Configuration'
        capability 'Thermostat'
        capability 'Refresh'
        capability 'TemperatureMeasurement'
        capability 'ThermostatHeatingSetpoint'
        capability 'ThermostatMode'
        capability 'Lock'
        capability 'PowerMeter'

        command(
            'setThermostatMode',
            [[
                name: 'Thermostat Mode',
                type: 'ENUM',
                description: 'Thermostat Mode',
                constraints: ['off', 'heat']
            ]]
        )
        command(
            'setOutdoorTemperature',
            [[
                name: 'Outdoor Temperature',
                type: 'NUMBER',
                description: "set 'off' to display temperature heating set point",
            ]]
        )
        command(
            'displayTemperature',
            [[
                name: 'Display Temperature',
                type: 'ENUM',
                description: 'Temperature to display',
                constraints: ['Outdoor', 'Setpoint'],
            ]]
        )
        command('setClockTime')
        command('enableBacklight')
        command('disableBacklight')

        command 'emergencyHeat', [[name: 'Not Supported']]
        command 'auto', [[name: 'Not Supported']]
        command 'cool', [[name: 'Not Supported']]
        command 'fanCirculate', [[name: 'Not Supported']]
        command 'fanOn', [[name: 'Not Supported']]
        command 'fanAuto', [[name: 'Not Supported']]
        command 'setThermostatFanMode', [[name: 'Not Supported']]
        command 'setCoolingSetpoint', [[name: 'Not Supported']]

        fingerprint(
            manufacturer: 'Sinope Technologies',
            model: 'TH1123ZB',
            deviceJoinName: 'Sinope TH1123ZB-TH1124ZB Thermostat',
            inClusters: '0000,0003,0004,0005,0201,0204,0402,0B04,0B05,FF01',
            outClusters: '0019,FF01',
        )
    }

    preferences
    {
        input(
            name: 'backlightAutoDimParam',
            type: 'enum',
            title:'Display backlight',
            options: ['On Demand', 'Always On (Default)'],
            multiple: false,
            required: false,
        )
        input(
            name: 'timeFormatParam',
            type: 'enum',
            title:'Clock display format',
            options:['12 Hour', '24 Hour (Default)'],
            multiple: false,
            required: false,
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
        log.trace 'TH112XZB >> installed()'
    }
    refresh_misc()
    refresh()
}

void updated() {
    if (settings.trace) {
        log.trace 'TH112XZB >> updated()'
    }

    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 1000) {
        state.updatedLastRanAt = now()
        try {
            unschedule()
        }
        catch (ignored) { }

        refresh_misc()
    }
}

void configure() {
    if (settings.trace) {
        log.trace 'TH112XZB >> configure()'
    }

    // Configure reporting
    List cmds = []
    cmds += zigbee.configureReporting(0x0201, 0x0000, DataType.INT16, 19, 301, 50)      // local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, DataType.UINT8, 4, 300, 10)       // heating demand
    cmds += zigbee.configureReporting(0x0201, 0x0012, DataType.INT16, 15, 302, 40)      // occupied heating setpoint
    // FIXME: Doesn't seem to work
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 30, 599, 0x64)    // active power

    sendCommands(cmds)
    refresh_misc()
}

void uninstalled() {
    if (settings.trace) {
        log.trace 'TH112XZB >> uninstalled()'
    }
    try {
        unschedule()
    }
    catch (ignored) { }
}

Map parse(String description) {
    if (!description?.startsWith('read attr -')) {
        if (!description?.startsWith('catchall:')) {
            log.warn "SP2600ZB >> parse(description) ==> Unhandled event: ${description}"
        }
        return [:]
    }

    Map event = [:]
    String scale = getTemperatureScale()
    Map descMap = zigbee.parseDescriptionAsMap(description)
    state.scale = scale

    if (descMap.cluster == '0201' && descMap.attrId == '0000') {
        event.name = 'temperature'
        event.value = getTemperatureValue(descMap.value)
        event.unit = "°${scale}"
    } else if (descMap.cluster == '0201' && descMap.attrId == '0008') {
        event.name = 'heatingDemand'
        event.value = getHeatingDemand(descMap.value)
        event.unit = '%'
        String operatingState = (event.value.toInteger() < 10) ? 'idle' : 'heating'
        Map subEvent = ['name': 'thermostatOperatingState', 'value': operatingState]
        subEvent.descriptionText = "${device.getLabel()} ${subEvent.name} is ${subEvent.value}"
        sendEvent(subEvent)
        runIn(1, requestPower)
    } else if (descMap.cluster == '0B04' && descMap.attrId == '050B') {
        event.name = 'power'
        event.value = getActivePower(descMap.value)
        event.unit = 'W'
    } else if (descMap.cluster == '0201' && descMap.attrId == '0012') {
        event.name = 'heatingSetpoint'
        event.value = getTemperatureValue(descMap.value, true)
        event.unit = "°${scale}"
    } else if (descMap.cluster == '0201' && descMap.attrId == '0014') {
        event.name = 'heatingSetpoint'
        event.value = getTemperatureValue(descMap.value, true)
        event.unit = "°${scale}"
    } else if (descMap.cluster == '0201' && descMap.attrId == '001C') {
        event.name = 'thermostatMode'
        event.value = getModeMap()[descMap.value]
    } else if (descMap.cluster == '0204' && descMap.attrId == '0001') {
        event.name = 'lock'
        event.value = getLockMap()[descMap.value]
    } else {
        log.warn "TH112XZB >> parse(descMap) ==> Unhandled attribute: ${descMap}"
    }

    if (event.name && event.value) {
        event.descriptionText = "${device.getLabel()} ${event.name} is ${event.value}"
        if (event.unit) {
            event.descriptionText = "${event.descriptionText}${event.unit}"
        }
    }

    if (settings.trace) {
        log.trace "TH112XZB >> parse(description) ==> ${event.name}: ${event.value}"
    }
    return event
}

void unlock() {
    if (settings.trace) {
        log.trace 'TH112XZB >> unlock()'
    }
    Map event = ['name': 'lock', 'value': 'unlocked']
    event.descriptionText = "${device.getLabel()} ${event.name} is ${event.value}"
    sendEvent(event)

    List cmds = zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x00)
    sendCommands(cmds)
}

void lock() {
    if (settings.trace) {
        log.trace 'TH112XZB >> lock()'
    }
    Map event = ['name': 'lock', 'value': 'locked']
    event.descriptionText = "${device.getLabel()} ${event.name} is ${event.value}"
    sendEvent(event)

    List cmds = zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x01)
    sendCommands(cmds)
}

void refresh() {
    if (settings.trace) {
        log.trace 'TH112XZB >> refresh()'
    }

    if (state.updatedLastRanAt && now() < state.updatedLastRanAt + 5000) {
        if (settings.trace) {
            log.trace 'TH112XZB >> refresh() --- Ran within last 5 seconds so aborting'
        }
        return
    }
    state.updatedLastRanAt = now()

    List cmds = []
    cmds += zigbee.readAttribute(0x0201, 0x0000)  // Rd thermostat Local temperature
    cmds += zigbee.readAttribute(0x0201, 0x0012)  // Rd thermostat Occupied heating setpoint
    cmds += zigbee.readAttribute(0x0201, 0x0008)  // Rd thermostat PI heating demand
    cmds += zigbee.readAttribute(0x0201, 0x001C)  // Rd thermostat System Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001)  // Rd thermostat Keypad lock
    cmds += zigbee.readAttribute(0x0B04, 0x050B)  // Rd thermostat Active power
    sendCommands(cmds)
}

void setOutdoorTemperature(Double outdoorTemp) {
    if (settings.trace) {
        log.trace "TH112XZB >> setOutdoorTemperature(${outdoorTemp})"
    }
    state.outdoorTemperature = outdoorTemp
    if (state.displayTemperature == 'Setpoint') {
        return
    }
    sendOutdoorTemperature(outdoorTemp)
}

void displayTemperature(String choice) {
    if (settings.trace) {
        log.trace "TH112XZB >> displayTemperature(${choice})"
    }
    if (state.outdoorTemperature && choice == 'Outdoor') {
        sendOutdoorTemperature(state.outdoorTemperature)
    } else {
        sendSetpointTemperature()
    }
    state.displayTemperature = choice
}

void enableBacklight() {
    if (settings.trace) {
        log.trace 'TH112XZB >> enableBacklight()'
    }
    List cmds = zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0001)
    sendCommands(cmds)
}

void disableBacklight() {
    if (settings.trace) {
        log.trace 'TH112XZB >> disableBacklight()'
    }
    List cmds = zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0000)
    sendCommands(cmds)
}

void setClockTime() {
    if (settings.trace) {
        log.trace 'TH112XZB >> setClockTime()'
    }
    List cmds = []
    // Time Format
    if (settings.timeFormatParam == '12 Hour') {
        // 12 Hour
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, DataType.ENUM8, 0x0001)
    } else {
        // 24 Hour
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, DataType.ENUM8, 0x0000)
    }

    // Time
    /* groovylint-disable-next-line NoJavaUtilDate */
    Date thermostatDate = new Date()
    Integer thermostatTimeSec = thermostatDate.getTime() / 1000
    Integer thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60
    Integer currentTime = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800)

    cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, currentTime, [mfgCode: '0x119C'])

    sendCommands(cmds)
}

void setHeatingSetpoint(Double degrees) {
    if (settings.trace) {
        log.trace "TH112XZB >> setHeatingSetpoint(${degrees})"
    }
    String scale = getTemperatureScale()
    Double degreesScoped = checkTemperature(degrees)
    Double celsius = (scale == 'C') ? degreesScoped : fahrenheitToCelsius(degreesScoped).round(1)
    Integer celsiusRound = Math.round(celsius * 100)

    List cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x0012, DataType.INT16, celsiusRound)
    cmds += zigbee.readAttribute(0x0201, 0x0012)
    sendCommands(cmds)
}

void off() {
    if (settings.trace) {
        log.trace 'TH112XZB >> off()'
    }
    setThermostatMode('off')
}

void heat() {
    if (settings.trace) {
        log.trace 'TH112XZB >> heat()'
    }
    setThermostatMode('heat')
}

void cool() { }

void auto() { }

void emergencyHeat() { }

void setCoolingSetpoint() { }

void fanCirculate() { }

void fanOn() { }

void fanAuto() { }

void setThermostatFanMode() { }

List<String> getSupportedThermostatModes() {
    return ['heat', 'off']
}

void setThermostatMode(String mode) {
    if (settings.trace) {
        log.trace "TH112XZB >> setThermostatMode(${mode})"
    }

    String modeLower = mode?.toLowerCase()
    List<String> supportedThermostatModes = getSupportedThermostatModes()

    if (mode in supportedThermostatModes) {
        "mode_$modeLower"()
    }
}

void mode_off() {
    if (settings.trace) {
        log.trace 'TH112XZB >> mode_off()'
    }

    List cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, DataType.ENUM8, 0)
    cmds += zigbee.readAttribute(0x0201, 0x001C)
    sendCommands(cmds)
}

void mode_heat() {
    if (settings.trace) {
        log.trace 'TH112XZB >> mode_heat()'
    }

    List cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, DataType.ENUM8, 4)
    cmds += zigbee.readAttribute(0x0201, 0x001C)
    sendCommands(cmds)
}

// Cannot be private to be executed by runIn
void requestPower() {

}

private void refresh_misc() {
    List cmds = []

    // °C or °F
    if (state?.scale == 'C') {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 0)  // °C on thermostat display
    } else {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 1)  // °F on thermostat display
    }

    sendCommands(cmds)
    setClockTime()

    // Backlight
    if (settings.backlightAutoDimParam == 'On Demand') {
        disableBacklight()
    } else {
        enableBacklight()
    }
}

private void sendOutdoorTemperature(Double outdoorTemp) {
    List cmds = []
    Integer timeout = 3 * 60 * 60 // 3 hours
    Integer outdoorTempInt = Math.round(outdoorTemp * 100)
    cmds += zigbee.writeAttribute(0xFF01, 0x0011, DataType.UINT16, timeout, [:], 1000)
    cmds += zigbee.writeAttribute(0xFF01, 0x0010, DataType.INT16, outdoorTempInt, [mfgCode: '0x119C'], 1000)
    sendCommands(cmds)
}

private void sendSetpointTemperature() {
    List cmds = zigbee.writeAttribute(0xFF01, 0x0010, DataType.INT16, 0x8000)
    sendCommands(cmds)
}

private Double checkTemperature(Double temperature) {
    String scale = getTemperatureScale()
    Double number = temperature
    Integer maxCelcius = 25

    if (scale == 'F') {
        if (number < 41) {
            number = 41
        } else if (number > 86) {
            number = 86
        }
    } else { //scale == 'C'
        if (number < 5) {
            number = 5
        } else if (number > maxCelcius) {
            number = maxCelcius
        }
    }
    return number
}

private Double getTemperatureValue(String value, Boolean doRounding = false) {
    if (value == null) {
        return
    }

    Double celsius = (Integer.parseInt(value, 16) / 100).toDouble()
    String scale = state?.scale
    if (scale == 'C') {
        if (doRounding) {
            String tempValueString = String.format('%2.1f', celsius)

            if (tempValueString.matches('.*([.,][456])')) {
                tempValueString = String.format('%2d.5', celsius.intValue())
            } else if (tempValueString.matches('.*([.,][789])')) {
                celsius = celsius.intValue() + 1
                tempValueString = String.format('%2d.0', celsius.intValue())
            } else {
                tempValueString = String.format('%2d.0', celsius.intValue())
            }

            return tempValueString.toDouble().round(1)
        }
        return celsius.round(1)
    }
    return Math.round(celsiusToFahrenheit(celsius))
}

private String getHeatingDemand(String value) {
    if (value == null) {
        return
    }
    Integer demand = Integer.parseInt(value, 16)
    return demand.toString()
}

private Integer getActivePower(String value) {
    if (value == null) {
        return
    }
    return Integer.parseInt(value, 16)
}

private Map getModeMap() {
    return [
        '00': 'off',
        '04': 'heat'
    ]
}

private Map getLockMap() {
    return [
        '00': 'unlocked ',
        '01': 'locked ',
    ]
}

private void sendCommands(List<Map> commands) {
    HubMultiAction actions = new HubMultiAction(commands, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(actions)
}
