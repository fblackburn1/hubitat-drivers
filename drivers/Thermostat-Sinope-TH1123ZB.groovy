/* groovylint-disable DuplicateListLiteral, DuplicateMapLiteral, DuplicateNumberLiteral */
/* groovylint-disable UnnecessaryGetter, UnnecessarySetter */
/**
 *  Thermostat Sinopé TH1123ZB-TH1124ZB Driver
 *
 *  1.0 (2022-12-31): initial release
 *  1.1 (2022-01-04): Handled short circuit and rmsVoltage/rmsCurrent
 *  Author: fblackburn
 *  Inspired by:
 *    - Sinope => https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/sinope-technologies
 *    - scoulombe => https://github.com/scoulombe79/HubitatDrivers/blob/master/Thermostat-Sinope-TH1123ZB.groovy
 *    - sacua => https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/SP2600ZB_Sinope_Hubitat.groovy
 */

import hubitat.device.HubMultiAction

metadata
{
    definition(
        name: 'Sinope Zigbee Thermostat (TH1123ZB-TH1124ZB)',
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
        capability 'EnergyMeter'

        attribute 'maxPower', 'number'
        attribute 'rmsVoltage', 'number'
        attribute 'rmsCurrent', 'number'

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

        List notSupported = [[name: 'Not Supported']]
        command('emergencyHeat', notSupported)
        command('auto', notSupported)
        command('cool', notSupported)
        command('fanCirculate', notSupported)
        command('fanOn', notSupported)
        command('fanAuto', notSupported)
        command('setThermostatFanMode', notSupported)
        command('setCoolingSetpoint', notSupported)

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
            options: ['On Demand', 'Always On'],
            defaultValue: getDefaultBacklight(),
            multiple: false,
            required: false,
        )
        input(
            name: 'timeFormatParam',
            type: 'enum',
            title:'Clock display format',
            options:['12 Hour', '24 Hour'],
            defaultValue: getDefaultTimeFormat(),
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

    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 2000) {
        state.updatedLastRanAt = now()
        refresh_misc()
    }
}

void configure() {
    if (settings.trace) {
        log.trace 'TH112XZB >> configure()'
    }

    try {
        unschedule()
    }
    catch (ignored) { }

    List cmds = []
    cmds += zigbee.configureReporting(0x0201, 0x0000, DataType.INT16, 19, 301, 50)    // Temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, DataType.UINT8, 4, 300, 10)     // Heating (%)
    cmds += zigbee.configureReporting(0x0201, 0x0012, DataType.INT16, 15, 302, 40)    // Heating Setpoint (°)
    // Since energyValue will only increase with time, we can only use minReportTime (300)
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 300, 1800, 50) // Energy (Wh)

    runEvery3Hours('handlePowerOutage')

    sendCommands(cmds)
    refresh_misc()
}

void uninstalled() {
    if (settings.trace) {
        log.trace 'TH112XZB >> uninstalled()'
    }
    unschedule()
}

List<Map> parse(String description) {
    if (!description?.startsWith('read attr -')) {
        if (!description?.startsWith('catchall:')) {
            log.warn "TH112XZB >> parse(description) ==> Unhandled event: ${description}"
        }
        return []
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    Map event = extractEvent(descMap)
    List<Map> events = [event]
    if (event.name == 'heatingDemand') {
        String operatingState = (event.value.toInteger() < 10) ? 'idle' : 'heating'
        Map opEvent = ['name': 'thermostatOperatingState', 'value': operatingState]
        opEvent.descriptionText = generateDescription(opEvent)
        if (settings.trace) {
            log.trace "TH112XZB >> parse(description)[generated] ==> ${opEvent.name}: ${opEvent.value}"
        }
        events.add(opEvent)

        Integer maxPower = device.currentValue('maxPower')
        if (maxPower != null) {
            Integer power = Math.round(maxPower * event.value / 100)
            Map powerEvent = [name: 'power', value: power, unit: 'W']
            powerEvent.descriptionText = generateDescription(powerEvent)
            if (settings.trace) {
                log.trace "TH112XZB >> parse(description)[generated] ==> ${powerEvent.name}: ${powerEvent.value}"
            }
            events.add(powerEvent)
        }
    }
    if (descMap.additionalAttrs) {
        // From test, only (cluster: 0B04 / attrId: 0505) has additionalAttrs
        descMap.additionalAttrs.each { Map attribute ->
            attribute.cluster = attribute.cluster ? attribute.cluster : descMap.cluster
            events.add(extractEvent(attribute))
        }
    }
    return events
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
            log.trace 'TH112XZB >> refresh() ==> Ran within last 5 seconds so aborting'
        }
        return
    }
    state.updatedLastRanAt = now()

    List cmds = []
    cmds += zigbee.readAttribute(0x0201, 0x0000) // Local temperature
    cmds += zigbee.readAttribute(0x0201, 0x0012) // Occupied heating setpoint
    cmds += zigbee.readAttribute(0x0201, 0x0008) // PI heating demand
    cmds += zigbee.readAttribute(0x0201, 0x001C) // System Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001) // Keypad lock
    cmds += zigbee.readAttribute(0x0B04, 0x0505) // RMS Voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508) // RMS Current
    cmds += zigbee.readAttribute(0x0B04, 0x050B) // Active power
    cmds += zigbee.readAttribute(0x0B04, 0x050D) // Maximum power available
    cmds += zigbee.readAttribute(0x0702, 0x0000) // Total Energy
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

    /* groovylint-disable-next-line NoJavaUtilDate */
    Date now = new Date()
    Long currentTimeSec = now.getTime() / 1000
    Integer currentTimezoneOffsetSec = now.getTimezoneOffset() * 60
    Integer referenceTimeSec = 946684800 // 2000-01-01T00:00:00+00:00
    Integer currentTimeTSFormat = currentTimeSec - currentTimezoneOffsetSec - referenceTimeSec
    List cmds = zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, currentTimeTSFormat, [mfgCode: '0x119C'])
    sendCommands(cmds)
}

void setHeatingSetpoint(Double degrees) {
    if (settings.trace) {
        log.trace "TH112XZB >> setHeatingSetpoint(${degrees})"
    }
    String scale = getTemperatureScale()
    Double degreesScoped = checkTemperature(degrees, scale)
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

void cool() { return }

void auto() { return }

void emergencyHeat() { return }

void setCoolingSetpoint() { return }

void fanCirculate() { return }

void fanOn() { return }

void fanAuto() { return }

void setThermostatFanMode() { return }

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
void handlePowerOutage() {
    // Maximum power can change with time (i.e. after a power outage)
    List cmds = zigbee.readAttribute(0x0B04, 0x050D) // Maximum power available
    sendCommands(cmds)
    // Clock can be desynced with time (i.e. after a power outage or summer time)
    setClockTime()
}

private Map extractEvent(Map descMap) {
    Map event = [:]
    if (descMap.cluster == '0201' && descMap.attrId == '0000') {
        String scale = getTemperatureScale()
        event.name = 'temperature'
        event.value = getTemperatureValue(descMap.value, scale)
        event.unit = "°${scale}"
    } else if (descMap.cluster == '0201' && descMap.attrId == '0008') {
        event.name = 'heatingDemand'
        event.value = getHeatingDemand(descMap.value)
        event.unit = '%'
    } else if (descMap.cluster == '0702' && descMap.attrId == '0000') {
        BigInteger energy = getEnergy(descMap.value)
        Double previousEnergy = device.currentValue('energy')
        if (energy < previousEnergy) {
            // When a baseboard heater is too hot, a short circuit is created for few seconds until the unit cools down.
            // This kind of power outage, reset to an old "random" value
            // Note: For some unknown reason, power outage from electrical board doesn't reset value ...
            // If you have this warning you should verify that nothing prevents the release of heat from your heater
            // (ex: curtains, bedding, reverse installation, etc)
            /* groovylint-disable-next-line LineLength */
            log.warn "TH112XZB >> Energy[${energy}] is lower than previous one[${previousEnergy}] (Caused: short circuit from heater)"
        }
        event.name = 'energy'
        event.value = energy / 1000
        event.unit = 'kWh'
    } else if (descMap.cluster == '0B04' && descMap.attrId == '050B') {
        event.name = 'power'
        event.value = getPower(descMap.value)
        event.unit = 'W'
    } else if (descMap.cluster == '0B04' && descMap.attrId == '050D') {
        event.name = 'maxPower'
        event.value = getPower(descMap.value)
        event.unit = 'W'
    } else if (descMap.cluster == '0201' && descMap.attrId == '0012') {
        String scale = getTemperatureScale()
        event.name = 'heatingSetpoint'
        event.value = getTemperatureValue(descMap.value, scale, true)
        event.unit = "°${scale}"
    } else if (descMap.cluster == '0201' && descMap.attrId == '0014') {
        String scale = getTemperatureScale()
        event.name = 'heatingSetpoint'
        event.value = getTemperatureValue(descMap.value, scale, true)
        event.unit = "°${scale}"
    } else if (descMap.cluster == '0201' && descMap.attrId == '001C') {
        event.name = 'thermostatMode'
        event.value = getModeMap()[descMap.value]
    } else if (descMap.cluster == '0204' && descMap.attrId == '0001') {
        event.name = 'lock'
        event.value = getLockMap()[descMap.value]
    } else if (descMap.cluster == '0B04' && descMap.attrId == '0505') {
        // This event seems to be triggered automatically after each 18 hours
        event.name = 'rmsVoltage'
        event.value = getVoltage(descMap.value)
        event.unit = 'V'
    } else if (descMap.cluster == '0B04' && descMap.attrId == '0508') {
        event.name = 'rmsCurrent'
        event.value = getCurrent(descMap.value)
        event.unit = 'A'
    } else if (descMap.cluster == '0B04' && descMap.attrId == '0551') {
        BigInteger energy = getEnergy(descMap.value)
        log.trace "TH112XZB >> Skipping duplicate event[0551] energy': ${energy}"
        return [:]
    } else {
        log.warn "TH112XZB >> parse(descMap) ==> Unhandled attribute: ${descMap}"
        return [:]
    }
    event.descriptionText = generateDescription(event)

    if (settings.trace) {
        log.trace "TH112XZB >> parse(description) ==> ${event.name}: ${event.value}"
    }
    return event
}

private String generateDescription(Map event) {
    String description = null
    if (event.name && event.value) {
        description = "${device.getLabel()} ${event.name} is ${event.value}"
        if (event.unit) {
            description = "${description}${event.unit}"
        }
    }
    return description
}

private void refresh_misc() {
    List cmds = []

    // °C or °F
    String scale = getTemperatureScale()
    if (scale == 'C') {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 0)  // °C
    } else {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 1)  // °F
    }

    String timeFormat = settings.timeFormatParam == null ? getDefaultTimeFormat() : settings.timeFormatParam
    if (timeFormat == '12 Hour') {
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, DataType.ENUM8, 0x0001) // 12 Hour
    } else {
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, DataType.ENUM8, 0x0000) // 24 Hour
    }

    sendCommands(cmds)
    setClockTime()

    String backlight = settings.backlightAutoDimParam == null ? getDefaultBacklight() : settings.backlightAutoDimParam
    if (backlight == 'On Demand') {
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

private Double checkTemperature(Double temperature, String scale) {
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

private Double getTemperatureValue(String value, String scale, Boolean doRounding = false) {
    if (value == null) {
        return
    }

    Double celsius = (Integer.parseInt(value, 16) / 100).toDouble()
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

private Integer getHeatingDemand(String value) {
    if (value == null) {
        return
    }
    return Integer.parseInt(value, 16)
}

private Integer getPower(String value) {
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

private Double getVoltage(String value) {
    if (value == null) {
        return 0
    }
    return Integer.parseInt(value, 16) / 10
}

private Double getCurrent(String value) {
    if (value == null) {
        return 0
    }
    return Integer.parseInt(value, 16) / 1000
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

private String getDefaultBacklight() {
    return 'Always On'
}

private String getDefaultTimeFormat() {
    return '24 Hour'
}
private void sendCommands(List<Map> commands) {
    HubMultiAction actions = new HubMultiAction(commands, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(actions)
}
