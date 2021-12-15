/* groovylint-disable DuplicateListLiteral */
/* groovylint-disable DuplicateMapLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable EmptyMethod */
/* groovylint-disable UnnecessaryGetter */
/* groovylint-disable UnnecessarySetter */
/**
 *  Thermostat Sinopé TH1123ZB Driver
 *
 *  Version: 0.3
 *  0.1   (2019-12-20) => First release
 *  0.2   (2019-12-21) => Added Lock / Unlock setting / HealthCheck
 *  0.3   (2019-12-22) => Fixed thermostat mode reporting, added thermostat mode setting, added power reporting (?)
 *  0.4   (2021-12-12) => Add changes from SmartThings driver v1.2.0
 *
 *  Author(0.1-0.3): scoulombe
 *  Date: 2019-12-22
 *
 *  Author(0.4+): fblackburn
 *  Date: 2021-12-12
 */

/* groovylint-disable-next-line LineLength */
// Sources: Sinopé => https://github.com/SmartThingsCommunity/SmartThingsPublic/blob/master/devicetypes/sinope-technologies/th1123zb-th1124zb-sinope-thermostat.src/th1123zb-th1124zb-sinope-thermostat.groovy

preferences
{
    input(
        'backlightAutoDimParam',
        'enum',
        title:'Display backlight',
        options: ['On Demand', 'Always On (Default)'],
        multiple: false,
        required: false,
    )
    input(
        'timeFormatParam',
        'enum',
        title:'Clock display format',
        options:['12 Hour', '24 Hour (Default)'],
        multiple: false,
        required: false,
    )
    input(
        'trace',
        'bool',
        title: 'Enable debug logging',
    )
}

metadata
{
    definition(
        name: 'TH1123ZB Sinope Thermostat',
        namespace: 'fblackburn',
        author: 'fblackburn',
        ocfDeviceType: 'oic.d.thermostat'
    ) {
        capability 'Configuration'
        capability 'Thermostat'
        capability 'Refresh'
        capability 'TemperatureMeasurement'
        capability 'ThermostatHeatingSetpoint'
        capability 'ThermostatMode'
        capability 'Lock'
        capability 'HealthCheck'
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
            deviceJoinName: 'Sinope TH1123ZB Thermostat',
            inClusters: '0000,0003,0004,0005,0201,0204,0402,0B04,0B05,FF01',
            outClusters: '0019,FF01',
        )
    }
}

void installed() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> installed()'
    }

    initialize()
}

void updated() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> updated()'
    }

    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 1000) {
        state.updatedLastRanAt = now()

        if (settings.trace) {
            log.trace 'TH1123ZB >> updated() => Device is now updated'
        }

        try {
            unschedule()
        }
        catch (ignored) { }

        refresh_misc()
    }
}

void configure() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> configure()'
    }

    // Configure reporting ...
    List cmds = []
    cmds += zigbee.configureReporting(0x0201, 0x0000, DataType.INT16, 19, 301, 50)      // local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, DataType.UINT8, 4, 300, 10)       // heating demand
    cmds += zigbee.configureReporting(0x0201, 0x0012, DataType.INT16, 15, 302, 40)      // occupied heating setpoint
    // FIXME: Doesn't seem to work
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 30, 599, 0x64)    // active power

    sendCommands(cmds)

    // Allow 5 min without receiving temperature report
    sendEvent(
        name: 'checkInterval',
        value: 300,
        displayed: false,
        data: [protocol: 'zigbee', hubHardwareId: device.hub.hardwareID],
    )
}

void initialize() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> initialize()'
    }

    refresh_misc()
    refresh()
}

void ping() {
    runIn(1, requestPower)
    //List cmds = []
    //cmds += zigbee.readAttribute(0x0201, 0x0000) // temperature
    //sendCommands(cmds)
}

void uninstalled() {
    try {
      unschedule()
    }
    catch (ignored) { }
}

List parse(String description) {
    List result = []
    String scale = getTemperatureScale()
    state?.scale = scale

    if (description?.startsWith('read attr -') || description?.startsWith('write attr -')) {
        Map descMap = zigbee.parseDescriptionAsMap(description)

        result += convertCustomMap(descMap)

        if (descMap.additionalAttrs) {
            List mapAdditionnalAttrs = descMap.additionalAttrs

            mapAdditionnalAttrs.each { add ->
                add.cluster = descMap.cluster
                result += convertCustomMap(add)
            }
        }
    }
    else if (!description?.startsWith('catchall:')) {
        log.trace 'TH1123ZB >> parse(description) ==> ' + description
    }

    return result
}

Double getTemperatureValue(String value, Boolean doRounding = false) {
    String scale = state?.scale

    if (value != null) {
        Double celsius = (Integer.parseInt(value, 16) / 100).toDouble()

        if (scale == 'C') {
            if (doRounding) {
                String tempValueString = String.format('%2.1f', celsius)

                if (tempValueString.matches('.*([.,][456])')) {
                    tempValueString = String.format('%2d.5', celsius.intValue())
                }

                else if (tempValueString.matches('.*([.,][789])')) {
                    celsius = celsius.intValue() + 1
                    tempValueString = String.format('%2d.0', celsius.intValue())
                }
                else {
                    tempValueString = String.format('%2d.0', celsius.intValue())
                }

                return tempValueString.toDouble().round(1)
            }
            return celsius.round(1)
        }
        return Math.round(celsiusToFahrenheit(celsius))
    }
}

String getHeatingDemand(String value) {
    if (value == null) {
        return
    }
    Integer demand = Integer.parseInt(value, 16)
    return demand.toString()
}

Integer getActivePower(String value) {
    if (value == null) {
        return
    }
    Integer activePower = Integer.parseInt(value, 16)
    return activePower
}

Map getModeMap() {
    return [
        '00': 'off',
        '04': 'heat'
    ]
}

Map getLockMap() {
    return [
        '00': 'unlocked ',
        '01': 'locked ',
    ]
}

void unlock() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> unlock()'
    }

    sendEvent(name: 'lock', value: 'unlocked')

    List cmds = []
    cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x00)
    sendCommands(cmds)
}

void lock() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> lock()'
    }

    sendEvent(name: 'lock', value: 'locked')

    List cmds = []
    cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x01)
    sendCommands(cmds)
}

void refresh() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> refresh()'
    }

    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
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
    if (settings.trace) {
        log.trace 'TH1123ZB >> refresh() --- Ran within last 5 seconds so aborting'
    }
}

void setOutdoorTemperature(Double outdoorTemp) {
    state.outdoorTemperature = outdoorTemp
    if (state.displayTemperature == 'Setpoint') {
        return
    }
    sendOutdoorTemperature(outdoorTemp)
}

void displayTemperature(String choice) {
    if (state.outdoorTemperature && choice == 'Outdoor') {
        sendOutdoorTemperature(state.outdoorTemperature)
    }
    else {
        sendSetpointTemperature()
    }
    state.displayTemperature = choice
}

void refresh_misc() {
    List cmds = []

    // Backlight
    if (backlightAutoDimParam == 'On Demand') {
        cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0000)
    }
    else {
        cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0001)
    }

    // TimeFormat
    if (timeFormatParam == '12 Hour') {
        // 12 Hour
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0001)
    }
    else {
        // 24 Hour
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0000)
    }

    // Time
    /* groovylint-disable-next-line NoJavaUtilDate */
    Date thermostatDate = new Date()
    Integer thermostatTimeSec = thermostatDate.getTime() / 1000
    Integer thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60
    Integer currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800)

    Integer currentTimeToSend = zigbee.convertHexToInt(hex(currentTimeToDisplay))
    cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, currentTimeToSend, [mfgCode: '0x119C'])

    // °C or °F
    if (state?.scale == 'C') {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 0)  // °C on thermostat display
    }
    else {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 1)  // °F on thermostat display
    }

    sendCommands(cmds)
}

void setHeatingSetpoint(Double degrees) {
    String scale = getTemperatureScale()
    Double degreesScoped = checkTemperature(degrees)
    Double degreesDouble = degreesScoped as Double
    String tempValueString

    if (scale == 'C') {
        tempValueString = String.format('%2.1f', degreesDouble)
    }
    else {
        tempValueString = String.format('%2d', degreesDouble.intValue())
    }

    sendEvent(name: 'heatingSetpoint', value: tempValueString, unit: scale)

    Double celsius = (scale == 'C') ? degreesDouble : (fahrenheitToCelsius(degreesDouble) as Double).round(1)

    List cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x0012, DataType.INT16,  zigbee.convertHexToInt(hex(celsius * 100)))
    cmds += zigbee.readAttribute(0x0201, 0x0012)
    sendCommands(cmds)
}

void off() {
    setThermostatMode('off')
}

void heat() {
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
        log.trace "TH1123ZB >> setThermostatMode(${mode})"
    }

    String modeLower = mode?.toLowerCase()
    List<String> supportedThermostatModes = getSupportedThermostatModes()

    if (mode in supportedThermostatModes) {
        "mode_$modeLower"()
    }
}

void mode_off() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> mode_off()'
    }

    List cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
    cmds += zigbee.readAttribute(0x0201, 0x001C)
    sendCommands(cmds)
}

void mode_heat() {
    if (settings.trace) {
        log.trace 'TH1123ZB >> mode_heat()'
    }

    List cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 4)
    cmds += zigbee.readAttribute(0x0201, 0x001C)
    sendCommands(cmds)
}

// Cannot be private to be executed by runIn
void requestPower() {
    List cmds = []
    cmds += zigbee.readAttribute(0x0B04, 0x050B)
    sendCommands(cmds)
}

private Map convertCustomMap(Map descMap) {
    Map map = [:]
    String scale = getTemperatureScale()

    if (descMap.cluster == '0201' && descMap.attrId == '0000') {
        map.name = 'temperature'
        map.value = getTemperatureValue(descMap.value)
        sendEvent(name: map.name, value: map.value, unit: scale)
        //allow 5 min without receiving temperature report
        Map data = [protocol: 'zigbee', hubHardwareId: device.hub.hardwareID]
        sendEvent(name: 'checkInterval', value: 300, displayed: false, data: data)
    }
    else if (descMap.cluster == '0201' && descMap.attrId == '0008') {
        map.name = 'heatingDemand'
        map.value = getHeatingDemand(descMap.value)
        sendEvent(name: map.name, value: map.value, unit: '%')
        String operatingState = (map.value.toInteger() < 10) ? 'idle' : 'heating'
        sendEvent(name: 'thermostatOperatingState', value: operatingState)
        runIn(1, requestPower)
    }
    else if (descMap.cluster == '0B04' && descMap.attrId == '050B') {
        map.name = 'power'
        map.value = getActivePower(descMap.value)
        sendEvent(name: map.name, value: map.value, unit: 'W')
    }
    else if (descMap.cluster == '0201' && descMap.attrId == '0012') {
        map.name = 'heatingSetpoint'
        map.value = getTemperatureValue(descMap.value, true)
        sendEvent(name: map.name, value: map.value, unit: scale)
    }
    else if (descMap.cluster == '0201' && descMap.attrId == '0014') {
        map.name = 'heatingSetpoint'
        map.value = getTemperatureValue(descMap.value, true)
        sendEvent(name: map.name, value: map.value, unit: scale)
    }
    else if (descMap.cluster == '0201' && descMap.attrId == '001C') {
        map.name = 'thermostatMode'
        map.value = getModeMap()[descMap.value]
        sendEvent(name: map.name, value: map.value)
    }
    else if (descMap.cluster == '0204' && descMap.attrId == '0001') {
        map.name = 'lock'
        map.value = getLockMap()[descMap.value]
        sendEvent(name: map.name, value: map.value)
    }
    else {
        log.trace 'TH1123ZB >> convertCustomMap(descMap) ==> ' + descMap
    }

    return map
}

private void sendOutdoorTemperature(Double outdoorTemp) {
    List cmds = []
    Integer timeout = 3 * 60 * 60 // 3 hours
    Integer outdoorTempHex = zigbee.convertHexToInt(hex(outdoorTemp * 100))
    cmds += zigbee.writeAttribute(0xFF01, 0x0011, DataType.UINT16, timeout, [:], 1000)
    cmds += zigbee.writeAttribute(0xFF01, 0x0010, DataType.INT16, outdoorTempHex, [mfgCode: '0x119C'], 1000)
    sendCommands(cmds)
}

private void sendSetpointTemperature() {
    List cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0010, DataType.INT16, 0x8000)
    sendCommands(cmds)
}

private Double checkTemperature(Double temperature) {
    String scale = getTemperatureScale()
    Double number = temperature

    if (scale == 'F') {
        if (number < 41) {
            number = 41
        }
        else if (number > 86) {
            number = 86
        }
    }
    else { //scale == 'C'
        if (number < 5) {
            number = 5
        }
        else if (number > 30) {
            number = 3
        }
    }

    return number
}

private void sendCommands(List commands) {
    if (commands != null && commands.size() > 0) {
        if (settings.trace) {
            log.trace('Executing commands:' + commands)
        }

        for (String value : commands) {
            sendHubCommand([value].collect {
                command -> new hubitat.device.HubAction(command, hubitat.device.Protocol.ZIGBEE)
            })
        }
    }
}

private String hex(Double value) {
    return new BigInteger(Math.round(value).toString()).toString(16)
}
